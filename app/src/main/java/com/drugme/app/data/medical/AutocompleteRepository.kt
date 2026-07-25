package com.drugme.app.data.medical

import com.drugme.app.data.repo.DiseaseCatalogRepository
import com.drugme.app.data.repo.DrugCatalogRepository
import com.drugme.app.data.repo.DrugSuggestion
import com.drugme.app.domain.model.DiseaseRef
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed interface AutocompleteOutcome<out T> {
    data class Results<T>(val items: List<T>, val remoteUnavailable: Boolean = false) :
        AutocompleteOutcome<T>
    data object Offline : AutocompleteOutcome<Nothing>
    data class Error(val message: String) : AutocompleteOutcome<Nothing>
}

interface MedicationAutocompleteSource {
    suspend fun popular(limit: Int = 6): List<DrugSuggestion>
    suspend fun search(query: String, limit: Int = 12): AutocompleteOutcome<DrugSuggestion>
}

interface DiseaseAutocompleteSource {
    suspend fun popular(limit: Int = 6): List<DiseaseRef>
    suspend fun search(query: String, limit: Int = 12): AutocompleteOutcome<DiseaseRef>
}

@Singleton
class MedicationAutocompleteRepository @Inject constructor(
    private val local: DrugCatalogRepository,
    private val rxNorm: RxNormAutocompleteProvider,
    private val networkStatus: NetworkStatus,
) : MedicationAutocompleteSource {
    override suspend fun popular(limit: Int): List<DrugSuggestion> =
        POPULAR_NAMES.mapNotNull { name -> local.search(name, 1).firstOrNull() }.take(limit)

    override suspend fun search(
        query: String,
        limit: Int,
    ): AutocompleteOutcome<DrugSuggestion> {
        val localResults = runCatching { local.search(query, limit) }
            .getOrElse { return AutocompleteOutcome.Error("Local medication search is unavailable.") }
        if (query.trim().length < 3) return AutocompleteOutcome.Results(localResults)
        if (!networkStatus.isOnline()) {
            return if (localResults.isNotEmpty()) {
                AutocompleteOutcome.Results(localResults, remoteUnavailable = true)
            } else {
                AutocompleteOutcome.Offline
            }
        }

        return try {
            val remote = rxNorm.search(query, limit)
            AutocompleteOutcome.Results(
                mergeMedicationSuggestions(localResults, remote).take(limit),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            if (localResults.isNotEmpty()) {
                AutocompleteOutcome.Results(localResults, remoteUnavailable = true)
            } else {
                AutocompleteOutcome.Error("Medication suggestions could not be loaded.")
            }
        }
    }

    private companion object {
        val POPULAR_NAMES = listOf(
            "acetaminophen",
            "ibuprofen",
            "amoxicillin",
            "metformin",
            "atorvastatin",
            "omeprazole",
        )
    }
}

@Singleton
class DiseaseAutocompleteRepository @Inject constructor(
    private val local: DiseaseCatalogRepository,
) : DiseaseAutocompleteSource {
    override suspend fun popular(limit: Int): List<DiseaseRef> =
        POPULAR_NAMES.mapNotNull { local.search(it, 1).firstOrNull() }.take(limit)

    override suspend fun search(query: String, limit: Int): AutocompleteOutcome<DiseaseRef> =
        runCatching { local.search(query, limit) }
            .fold(
                onSuccess = { AutocompleteOutcome.Results(it.distinctBy { item -> item.id }) },
                onFailure = { AutocompleteOutcome.Error("Condition suggestions could not be loaded.") },
            )

    private companion object {
        val POPULAR_NAMES = listOf(
            "Hypertension",
            "Diabetes Mellitus, Type 2",
            "Asthma",
            "Migraine Disorders",
        )
    }
}

@Singleton
class RxNormAutocompleteProvider @Inject constructor() {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun search(query: String, limit: Int): List<DrugSuggestion> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        val root = getJson(
            "https://rxnav.nlm.nih.gov/REST/approximateTerm.json" +
                "?term=$encoded&maxEntries=${limit.coerceAtMost(8)}&option=1"
        )
        val candidates = root["approximateGroup"]?.jsonObject
            ?.get("candidate")?.jsonArray.orEmpty()
            .mapNotNull { it.jsonObject["rxcui"]?.jsonPrimitive?.contentOrNull }
            .distinct()
            .take(limit.coerceAtMost(8))

        coroutineScope {
            candidates.map { rxcui ->
                async {
                    ensureActive()
                    val properties = getJson(
                        "https://rxnav.nlm.nih.gov/REST/rxcui/$rxcui/properties.json"
                    )["properties"]?.jsonObject ?: return@async null
                    val name = properties["name"]?.jsonPrimitive?.contentOrNull ?: return@async null
                    parseRxNormSuggestion(rxcui, name)
                }
            }.awaitAll().filterNotNull()
        }
    }

    private fun getJson(url: String) =
        (URI(url).toURL().openConnection() as HttpURLConnection).run {
            requestMethod = "GET"
            connectTimeout = 4_000
            readTimeout = 4_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "DrugMe-Android")
            try {
                if (responseCode !in 200..299) error("RxNorm returned HTTP $responseCode")
                inputStream.bufferedReader().use { json.parseToJsonElement(it.readText()).jsonObject }
            } finally {
                disconnect()
            }
        }
}

fun mergeMedicationSuggestions(
    local: List<DrugSuggestion>,
    remote: List<DrugSuggestion>,
): List<DrugSuggestion> =
    (local + remote).distinctBy {
        listOf(it.activeIngredient, it.strength, it.dosageForm, it.brandName)
            .joinToString("|") { part -> part.orEmpty().lowercase().replace(Regex("\\s+"), " ").trim() }
    }

fun parseRxNormSuggestion(rxcui: String, rawName: String): DrugSuggestion {
    val brand = Regex("""\[([^\]]+)\]""").find(rawName)?.groupValues?.get(1)
    val withoutBrand = rawName.replace(Regex("""\s*\[[^\]]+\]"""), "").trim()
    val strengthRegex = Regex(
        "\\b\\d+(?:\\.\\d+)?\\s*(?:mcg|mg|g|kg|ml|l|unit|units|%)" +
            "(?:\\s*/\\s*\\d+(?:\\.\\d+)?\\s*(?:mcg|mg|g|ml|l))?",
        RegexOption.IGNORE_CASE,
    )
    val strength = strengthRegex.find(withoutBrand)?.value
    val forms = listOf(
        "extended release oral tablet",
        "delayed release oral tablet",
        "oral tablet",
        "oral capsule",
        "oral solution",
        "oral suspension",
        "injection",
        "topical cream",
        "topical ointment",
        "transdermal patch",
        "inhalation powder",
        "inhalation solution",
        "suppository",
    )
    val form = forms.firstOrNull { withoutBrand.contains(it, ignoreCase = true) }
    val ingredientEnd = listOfNotNull(
        strength?.let { withoutBrand.indexOf(it, ignoreCase = true).takeIf { index -> index >= 0 } },
        form?.let { withoutBrand.indexOf(it, ignoreCase = true).takeIf { index -> index >= 0 } },
    ).minOrNull() ?: withoutBrand.length
    val ingredient = withoutBrand.substring(0, ingredientEnd).trim()
        .ifBlank { withoutBrand }
    return DrugSuggestion(
        rxcui = rxcui,
        name = rawName,
        diseases = emptyList(),
        genericName = ingredient,
        brandName = brand,
        activeIngredient = ingredient,
        strength = strength,
        dosageForm = form?.replaceFirstChar(Char::uppercase),
    )
}
