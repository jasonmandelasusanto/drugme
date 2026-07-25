package com.drugme.app.data.medical

import android.content.Context
import com.drugme.app.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class MedicationInfoSource(
    val organization: String,
    val country: String,
    val lastUpdated: String? = null,
    val url: String,
    val scope: String,
)

@Serializable
data class MedicationInformation(
    val title: String,
    val whatItIs: String? = null,
    val commonUses: String? = null,
    val commonSideEffects: String? = null,
    val seriousWarnings: String? = null,
    val contraindications: String? = null,
    val officialDosage: String? = null,
    val dosageFormsAndStrengths: String? = null,
    val drugClassification: String? = null,
    val sources: List<MedicationInfoSource> = emptyList(),
    val cachedAtMillis: Long = 0,
)

data class MedicationInfoRequest(val rxcui: String?, val name: String)

interface MedicationInformationProvider {
    suspend fun fetch(request: MedicationInfoRequest): MedicationInformation?
}

sealed interface MedicationInfoOutcome {
    data class Available(
        val information: MedicationInformation,
        val fromCache: Boolean,
        val stale: Boolean = false,
    ) : MedicationInfoOutcome
    data object Unavailable : MedicationInfoOutcome
    data object Offline : MedicationInfoOutcome
    data class Error(val message: String) : MedicationInfoOutcome
}

@Singleton
class MedicationInformationRepository @Inject constructor(
    medlinePlus: MedlinePlusProvider,
    openFda: OpenFdaLabelProvider,
    private val cache: MedicationInformationCache,
    private val networkStatus: NetworkStatus,
    private val clock: Clock,
) {
    private val providers: List<MedicationInformationProvider> = listOf(medlinePlus, openFda)

    suspend fun get(request: MedicationInfoRequest, forceRefresh: Boolean = false): MedicationInfoOutcome {
        if (!forceRefresh) {
            cache.read(request)?.let { cached ->
                val age = clock.millis() - cached.cachedAtMillis
                if (age <= FRESH_MILLIS) {
                    return MedicationInfoOutcome.Available(cached, fromCache = true)
                }
            }
        }

        if (!networkStatus.isOnline()) {
            val stale = cache.read(request)
            return if (stale != null && clock.millis() - stale.cachedAtMillis <= OFFLINE_MAX_MILLIS) {
                MedicationInfoOutcome.Available(stale, fromCache = true, stale = true)
            } else {
                MedicationInfoOutcome.Offline
            }
        }

        return try {
            val attempts = coroutineScope {
                providers.map { provider ->
                    async {
                        try {
                            Result.success(provider.fetch(request))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Throwable) {
                            Result.failure(failure)
                        }
                    }
                }.map { it.await() }
            }
            val results = attempts.mapNotNull { it.getOrNull() }
            if (results.isEmpty() && attempts.any { it.isFailure }) {
                return MedicationInfoOutcome.Error(
                    "Official medication sources are temporarily unavailable."
                )
            }
            val merged = mergeInformation(results, clock.millis())
                ?: return MedicationInfoOutcome.Unavailable
            cache.write(request, merged)
            MedicationInfoOutcome.Available(merged, fromCache = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            MedicationInfoOutcome.Error("Official medication information could not be loaded.")
        }
    }

    private companion object {
        const val FRESH_MILLIS = 24L * 60 * 60 * 1_000
        const val OFFLINE_MAX_MILLIS = 7L * 24 * 60 * 60 * 1_000
    }
}

fun mergeInformation(
    information: List<MedicationInformation>,
    cachedAtMillis: Long,
): MedicationInformation? {
    if (information.isEmpty()) return null
    fun first(selector: (MedicationInformation) -> String?): String? =
        information.firstNotNullOfOrNull(selector)
    return MedicationInformation(
        title = information.first().title,
        whatItIs = first { it.whatItIs },
        commonUses = first { it.commonUses },
        commonSideEffects = first { it.commonSideEffects },
        seriousWarnings = first { it.seriousWarnings },
        contraindications = first { it.contraindications },
        officialDosage = first { it.officialDosage },
        dosageFormsAndStrengths = first { it.dosageFormsAndStrengths },
        drugClassification = first { it.drugClassification },
        sources = information.flatMap { it.sources }.distinctBy { it.url },
        cachedAtMillis = cachedAtMillis,
    )
}

@Singleton
class MedicationInformationCache @Inject constructor(
    @ApplicationContext context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val directory = context.cacheDir.resolve("medication-information")
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun read(request: MedicationInfoRequest): MedicationInformation? = withContext(io) {
        val file = directory.resolve(cacheKey(request) + ".json")
        runCatching {
            if (!file.isFile) return@runCatching null
            json.decodeFromString<MedicationInformation>(file.readText())
        }.getOrNull()
    }

    suspend fun write(request: MedicationInfoRequest, information: MedicationInformation) =
        withContext(io) {
            runCatching {
                directory.mkdirs()
                directory.resolve(cacheKey(request) + ".json")
                    .writeText(json.encodeToString(information))
            }
            Unit
        }

    private fun cacheKey(request: MedicationInfoRequest): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("${request.rxcui}|${request.name.lowercase()}".toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

@Singleton
class MedlinePlusProvider @Inject constructor(
    @IoDispatcher private val io: CoroutineDispatcher,
) : MedicationInformationProvider {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun fetch(request: MedicationInfoRequest): MedicationInformation? = withContext(io) {
        val params = buildList {
            add("mainSearchCriteria.v.cs=2.16.840.1.113883.6.88")
            request.rxcui?.let { add("mainSearchCriteria.v.c=${encode(it)}") }
            add("mainSearchCriteria.v.dn=${encode(request.name)}")
            add("informationRecipient.languageCode.c=en")
            add("knowledgeResponseType=application/json")
        }.joinToString("&")
        val root = getJson("https://connect.medlineplus.gov/service?$params", json)
        val entry = root["feed"]?.jsonObject?.get("entry")?.jsonArray?.firstOrNull()?.jsonObject
            ?: return@withContext null
        val title = atomValue(entry["title"]?.jsonObject) ?: request.name
        val link = entry["link"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("href")?.jsonPrimitive?.contentOrNull ?: return@withContext null
        val author = entry["author"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("name")?.jsonObject?.let(::atomValue)
            ?: "MedlinePlus, U.S. National Library of Medicine"
        MedicationInformation(
            title = title,
            sources = listOf(
                MedicationInfoSource(
                    organization = author,
                    country = "United States",
                    url = link,
                    scope = "Readable patient information",
                )
            ),
        )
    }

    private fun atomValue(value: JsonObject?): String? =
        value?.get("_value")?.jsonPrimitive?.contentOrNull
            ?: value?.get("value")?.jsonPrimitive?.contentOrNull
}

@Singleton
class OpenFdaLabelProvider @Inject constructor(
    @IoDispatcher private val io: CoroutineDispatcher,
) : MedicationInformationProvider {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun fetch(request: MedicationInfoRequest): MedicationInformation? = withContext(io) {
        val search = request.rxcui?.let { "openfda.rxcui:${quote(it)}" }
            ?: "openfda.generic_name:${quote(request.name)}"
        val url = "https://api.fda.gov/drug/label.json?search=${encode(search)}&limit=1"
        val result = getJson(url, json)["results"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: return@withContext null
        val openFda = result["openfda"]?.jsonObject
        val title = openFda.stringList("brand_name").firstOrNull()
            ?: openFda.stringList("generic_name").firstOrNull()
            ?: request.name
        val effective = result["effective_time"]?.jsonPrimitive?.contentOrNull
        val setId = result["set_id"]?.jsonPrimitive?.contentOrNull
        MedicationInformation(
            title = title,
            whatItIs = result.firstText("description"),
            commonUses = result.firstText("indications_and_usage"),
            commonSideEffects = result.firstText("adverse_reactions"),
            seriousWarnings = listOfNotNull(
                result.firstText("boxed_warning"),
                result.firstText("warnings"),
                result.firstText("warnings_and_cautions"),
            ).joinToString("\n\n").takeIf(String::isNotBlank),
            contraindications = result.firstText("contraindications"),
            officialDosage = result.firstText("dosage_and_administration"),
            dosageFormsAndStrengths = result.firstText("dosage_forms_and_strengths"),
            drugClassification = openFda.stringList("pharm_class_epc").joinToString()
                .takeIf(String::isNotBlank),
            sources = listOf(
                MedicationInfoSource(
                    organization = "U.S. Food and Drug Administration / openFDA",
                    country = "United States",
                    lastUpdated = effective?.let(::formatFdaDate),
                    url = setId?.let { "https://dailymed.nlm.nih.gov/dailymed/drugInfo.cfm?setid=$it" }
                        ?: "https://open.fda.gov/apis/drug/label/",
                    scope = "U.S. official drug labeling",
                )
            ),
        )
    }
}

private fun getJson(url: String, json: Json): JsonObject =
    (URI(url).toURL().openConnection() as HttpURLConnection).run {
        requestMethod = "GET"
        connectTimeout = 6_000
        readTimeout = 6_000
        setRequestProperty("Accept", "application/json")
        setRequestProperty("User-Agent", "DrugMe-Android")
        try {
            if (responseCode == 404) return@run JsonObject(emptyMap())
            if (responseCode !in 200..299) error("Medical source returned HTTP $responseCode")
            inputStream.bufferedReader().use { json.parseToJsonElement(it.readText()).jsonObject }
        } finally {
            disconnect()
        }
    }

private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
private fun quote(value: String): String = "\"${value.replace("\"", "\\\"")}\""

private fun JsonObject?.stringList(key: String): List<String> =
    this?.get(key)?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()

private fun JsonObject.firstText(key: String): String? =
    this[key]?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull
        ?.trim()?.takeIf(String::isNotBlank)

private fun formatFdaDate(raw: String): String? =
    raw.takeIf { it.length == 8 }?.let { "${it.take(4)}-${it.substring(4, 6)}-${it.takeLast(2)}" }
