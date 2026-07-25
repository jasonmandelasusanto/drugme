package com.drugme.app.data.repo

import android.content.Context
import android.util.Log
import com.drugme.app.data.local.dao.DiseaseCatalogDao
import com.drugme.app.data.local.entity.DiseaseCatalogEntity
import com.drugme.app.data.prefs.SettingsRepository
import com.drugme.app.di.IoDispatcher
import com.drugme.app.domain.model.DiseaseRef
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The conditions a user can say they have.
 *
 * Independent of what they take. The condition is the user's own statement — they know
 * their diagnosis before their prescription, drugs get used off-label, and vitamins or
 * contraceptives have no listed indication at all.
 */
@Singleton
class DiseaseCatalogRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: DiseaseCatalogDao,
    private val settings: SettingsRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Imports the bundled list on first launch. ~6k rows, so gated on a flag and a count. */
    suspend fun ensureLoaded() = withContext(io) {
        if (settings.diseaseCatalogLoaded.first() && dao.count() > 0) return@withContext

        runCatching {
            val entries = context.assets.open(ASSET).use { stream ->
                json.decodeFromString<List<DiseaseRef>>(stream.readBytes().decodeToString())
            }
            dao.clear()
            dao.insertAll(entries.map { DiseaseCatalogEntity(id = it.id, name = it.name) })
            settings.setDiseaseCatalogLoaded(true)
            Log.i(TAG, "Imported ${entries.size} conditions")
        }.onFailure {
            // Never fatal. The condition field is optional and free of consequence for
            // reminders; losing suggestions must not stop someone adding a medication.
            Log.e(TAG, "Condition catalog import failed; suggestions unavailable", it)
        }
    }

    /**
     * Type-ahead over condition names.
     *
     * Returns empty on any failure — this runs per keystroke, and a search that can throw
     * would take the whole form down with it.
     */
    suspend fun search(query: String, limit: Int = 20): List<DiseaseRef> = withContext(io) {
        val q = query.trim()
        if (q.length < 2) return@withContext emptyList()

        runCatching {
            val synonym = CONDITION_SYNONYMS[q.lowercase()]
            val direct = dao.search(toFtsPrefixQuery(synonym ?: q), limit)
                .map { DiseaseRef(it.id, it.name) }
            if (direct.isNotEmpty() || q.length < 4) return@runCatching direct

            // A small typo should not make a common diagnosis disappear. Fuzzy matching
            // runs only after indexed search misses, so normal typing stays on the fast path.
            dao.getAll()
                .asSequence()
                .map { it to fuzzyDiseaseDistance(q, it.name) }
                .filter { (_, distance) -> distance <= maxOf(1, q.length / 4) }
                .sortedWith(compareBy({ it.second }, { it.first.name.length }))
                .take(limit)
                .map { (entity, _) -> DiseaseRef(entity.id, entity.name) }
                .toList()
        }.getOrElse {
            Log.w(TAG, "Condition search failed for '$q'", it)
            emptyList()
        }
    }

    suspend fun getById(id: String): DiseaseRef? = withContext(io) {
        dao.getById(id)?.let { DiseaseRef(it.id, it.name) }
    }

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
        const val TAG = "DiseaseCatalogRepo"
        const val ASSET = "diseases.json"
        val CONDITION_SYNONYMS = mapOf(
            "high blood pressure" to "Hypertension",
            "heart attack" to "Myocardial Infarction",
            "type 2 diabetes" to "Diabetes Mellitus, Type 2",
            "acid reflux" to "Gastroesophageal Reflux",
        )
    }
}

internal fun fuzzyDiseaseDistance(query: String, candidate: String): Int {
    val normalizedQuery = query.lowercase().trim()
    val normalizedCandidate = candidate.lowercase()
    val variants = buildList {
        add(normalizedCandidate)
        add(normalizedCandidate.take(normalizedQuery.length))
        addAll(normalizedCandidate.split(Regex("[^\\p{L}\\p{N}]+")).filter(String::isNotBlank))
    }
    return variants.minOf { editDistance(normalizedQuery, it) }
}

private fun editDistance(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    var previous = IntArray(b.length + 1) { it }
    a.forEachIndexed { row, ca ->
        val current = IntArray(b.length + 1)
        current[0] = row + 1
        b.forEachIndexed { column, cb ->
            current[column + 1] = minOf(
                current[column] + 1,
                previous[column + 1] + 1,
                previous[column] + if (ca == cb) 0 else 1,
            )
        }
        previous = current
    }
    return previous[b.length]
}
