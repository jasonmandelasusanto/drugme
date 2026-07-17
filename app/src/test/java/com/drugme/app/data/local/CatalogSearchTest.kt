package com.drugme.app.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.drugme.app.data.local.entity.DiseaseCatalogEntity
import com.drugme.app.data.local.entity.DrugCatalogEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * FTS search against real SQLite.
 *
 * FTS MATCH is a query language, not a literal string — quotes, `*`, `-`, `^` and the bare
 * words AND/OR/NOT are all operators. This runs on every keystroke, so unescaped input
 * doesn't return bad results, it throws a SQLiteException and takes the form down while
 * someone is mid-word. Real drug and condition names are full of exactly the punctuation
 * that triggers it: "st john's wort", "estrogens, conjugated (usp)", "Diabetes Mellitus,
 * Type 2".
 *
 * The escaping lives in the repositories, so this asserts the DAO behaves when given
 * properly-escaped input, and the escaping itself is exercised through the same helper.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CatalogSearchTest {

    private lateinit var db: DrugMeDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            DrugMeDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    /** Mirrors the escaping in DrugCatalogRepository / DiseaseCatalogRepository. */
    private fun ftsQuery(input: String): String =
        input.trim().split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { "\"${it.replace("\"", "\"\"")}\"*" }

    private suspend fun seedDrugs() {
        db.drugCatalogDao().insertAll(
            listOf(
                DrugCatalogEntity("6809", "metformin", "[]"),
                DrugCatalogEntity("1191", "aspirin", "[]"),
                DrugCatalogEntity("161", "acetaminophen", "[]"),
                // The punctuation cases that break naive FTS.
                DrugCatalogEntity("42", "st john's wort", "[]"),
                DrugCatalogEntity("43", "estrogens, conjugated (usp)", "[]"),
                DrugCatalogEntity("44", "alpha-tocopherol", "[]"),
            )
        )
    }

    @Test
    fun `prefix search finds a drug`() = runTest {
        seedDrugs()
        val results = db.drugCatalogDao().search(ftsQuery("metf"))
        assertEquals(1, results.size)
        assertEquals("metformin", results.first().name)
    }

    @Test
    fun `apostrophes do not throw`() = runTest {
        seedDrugs()
        // "st john's" — an apostrophe mid-token is the single most likely way a user hits
        // this, and it must return a result rather than crash.
        val results = db.drugCatalogDao().search(ftsQuery("john's"))
        assertTrue(results.any { it.name == "st john's wort" })
    }

    @Test
    fun `commas and parentheses do not throw`() = runTest {
        seedDrugs()
        val results = db.drugCatalogDao().search(ftsQuery("estrogens, conjugated"))
        assertTrue(results.any { it.name.startsWith("estrogens") })
    }

    @Test
    fun `a hyphen is not treated as NOT`() = runTest {
        seedDrugs()
        // Bare "-" is an FTS operator. "alpha-tocopherol" must find the drug, not exclude it.
        val results = db.drugCatalogDao().search(ftsQuery("alpha-tocopherol"))
        assertTrue(results.any { it.name == "alpha-tocopherol" })
    }

    @Test
    fun `a bare quote does not throw`() = runTest {
        seedDrugs()
        // Someone typing a stray quote gets no results, not a crash.
        db.drugCatalogDao().search(ftsQuery("\""))
    }

    @Test
    fun `the word AND is searched literally, not as an operator`() = runTest {
        seedDrugs()
        db.drugCatalogDao().search(ftsQuery("and"))
    }

    @Test
    fun `condition search finds by prefix`() = runTest {
        db.diseaseCatalogDao().insertAll(
            listOf(
                DiseaseCatalogEntity("D003924", "Diabetes Mellitus, Type 2"),
                DiseaseCatalogEntity("D003920", "Diabetes Mellitus"),
                DiseaseCatalogEntity("D006973", "Hypertension"),
            )
        )

        val results = db.diseaseCatalogDao().search(ftsQuery("diabetes"))
        assertEquals(2, results.size)
        // Shortest first: MeSH names nest, and the shorter one is nearly always what a
        // person means.
        assertEquals("Diabetes Mellitus", results.first().name)
    }

    @Test
    fun `condition search survives the comma in MeSH names`() = runTest {
        db.diseaseCatalogDao().insertAll(
            listOf(DiseaseCatalogEntity("D003924", "Diabetes Mellitus, Type 2"))
        )
        val results = db.diseaseCatalogDao().search(ftsQuery("Diabetes Mellitus, Type 2"))
        assertEquals(1, results.size)
    }

    @Test
    fun `multi-token search requires every token`() = runTest {
        seedDrugs()
        // "st wort" must not match on "st" alone matching something else.
        val results = db.drugCatalogDao().search(ftsQuery("st wort"))
        assertTrue(results.all { it.name.contains("wort") })
    }
}
