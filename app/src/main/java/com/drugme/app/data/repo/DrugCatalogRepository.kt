package com.drugme.app.data.repo

import android.content.Context
import com.drugme.app.data.local.dao.DrugCatalogDao
import com.drugme.app.data.local.entity.DrugCatalogEntity
import com.drugme.app.data.prefs.SettingsRepository
import com.drugme.app.di.IoDispatcher
import com.drugme.app.domain.model.DiseaseRef
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/** A catalog hit, with its indications resolved. */
data class DrugSuggestion(
    val rxcui: String,
    val name: String,
    val diseases: List<DiseaseRef>,
    /** Set when the user's query matched via an alias, e.g. typing "paracetamol". */
    val matchedAlias: String? = null,
)

@Singleton
class DrugCatalogRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: DrugCatalogDao,
    private val settings: SettingsRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** alias -> canonical RxNorm name, loaded lazily from the bundled asset. */
    private var aliases: Map<String, String>? = null

    /**
     * Loads the bundled catalog into Room on first launch.
     *
     * ~4.5k rows, so this is done once and gated on a flag rather than on every start.
     */
    suspend fun ensureLoaded() = withContext(io) {
        if (settings.catalogLoaded.first() && dao.count() > 0) return@withContext

        val entries = context.assets.open(ASSET_DRUGS).use { stream ->
            json.decodeFromString<List<CatalogJson>>(stream.readBytes().decodeToString())
        }
        dao.clear()
        dao.insertAll(
            entries.map {
                DrugCatalogEntity(
                    rxcui = it.rxcui,
                    name = it.name,
                    diseasesJson = json.encodeToString(it.diseases),
                )
            }
        )
        settings.setCatalogLoaded(true)
    }

    private suspend fun aliasMap(): Map<String, String> = aliases ?: withContext(io) {
        context.assets.open(ASSET_SYNONYMS).use { stream ->
            // Parsed as a JsonObject rather than straight into Map<String, String>: the
            // file carries a "_comment" key whose value is an array of documentation
            // lines, and a direct decode throws on it before any filter could run.
            // Underscore-prefixed keys are documentation, not aliases.
            json.parseToJsonElement(stream.readBytes().decodeToString())
                .jsonObject
                .filterKeys { !it.startsWith("_") }
                .mapValues { (_, v) -> v.jsonPrimitive.content }
        }.also { aliases = it }
    }

    /**
     * Type-ahead over drug names, including international aliases.
     *
     * Aliases matter because RxNorm is a US vocabulary: someone typing "paracetamol" —
     * the name used almost everywhere outside the US — would otherwise get an empty list
     * and conclude the app doesn't know their medication.
     */
    suspend fun search(query: String, limit: Int = 25): List<DrugSuggestion> = withContext(io) {
        val q = query.trim().lowercase()
        if (q.length < 2) return@withContext emptyList()

        val direct = dao.search(toFtsPrefixQuery(q), limit).map { it.toSuggestion() }

        // Alias hits are appended, not merged into the FTS query, so the canonical name is
        // still what gets stored on the medication.
        val aliasHits = aliasMap()
            .filterKeys { it.startsWith(q) }
            .values
            .distinct()
            .filter { canonical -> direct.none { it.name == canonical } }
            .mapNotNull { canonical ->
                dao.search(toFtsPrefixQuery(canonical), 1).firstOrNull()?.toSuggestion()
                    ?.copy(matchedAlias = aliasMap().entries.first { it.value == canonical && it.key.startsWith(q) }.key)
            }

        (direct + aliasHits).take(limit)
    }

    suspend fun getByRxcui(rxcui: String): DrugSuggestion? = withContext(io) {
        dao.getByRxcui(rxcui)?.toSuggestion()
    }

    private fun DrugCatalogEntity.toSuggestion() = DrugSuggestion(
        rxcui = rxcui,
        name = name,
        diseases = json.decodeFromString<List<DiseaseRef>>(diseasesJson),
    )

    /**
     * Turns raw user input into an FTS4 prefix query.
     *
     * MUST NOT quote the tokens. FTS4 treats `"metf"` as an exact *phrase*, and a trailing
     * star on a quoted phrase is inert — `"metf"*` matches nothing at all, so type-ahead
     * only ever fired once you typed the whole word. Verified: `metf*` -> metformin,
     * `"metf"*` -> no rows.
     *
     * Punctuation is stripped rather than escaped because the default tokenizer already
     * treats it as a separator: "st john's wort" indexes as [st, john, s, wort]. Stripping
     * it here means the query can never contain FTS operators (`"`, `*`, `-`, `^`, AND/OR/
     * NOT), which is what made escaping necessary in the first place — a stray quote used
     * to throw a SQLiteException mid-keystroke.
     */
    private fun toFtsPrefixQuery(input: String): String =
        input.lowercase()
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { "$it*" }

    private companion object {
        const val ASSET_DRUGS = "drugs.json"
        const val ASSET_SYNONYMS = "synonyms.json"
    }
}

@kotlinx.serialization.Serializable
private data class CatalogJson(
    val rxcui: String,
    val name: String,
    val diseases: List<DiseaseRef>,
)
