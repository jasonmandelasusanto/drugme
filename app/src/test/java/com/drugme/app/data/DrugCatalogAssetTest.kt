package com.drugme.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@Serializable
private data class Drug(val rxcui: String, val name: String, val diseases: List<Disease>)

@Serializable
private data class Disease(val id: String, val name: String)

/**
 * Guards the shipped catalog asset.
 *
 * The generator is careful, but the asset is a committed binary artifact that someone can
 * regenerate, hand-edit, or replace years from now without reading tools/build-drug-db.mjs.
 * These assertions are what make the safety property survive that.
 */
class DrugCatalogAssetTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val drugs: List<Drug> by lazy {
        val f = File("src/main/assets/drugs.json")
        assertTrue("drugs.json asset missing — run tools/build-drug-db.mjs", f.exists())
        json.decodeFromString<List<Drug>>(f.readText())
    }

    private val synonyms: Map<String, String> by lazy {
        val f = File("src/main/assets/synonyms.json")
        assertTrue("synonyms.json asset missing", f.exists())
        // "_comment" holds an array of documentation lines, so a direct decode into
        // Map<String, String> throws before any filter runs. Underscore keys are docs.
        json.parseToJsonElement(f.readText())
            .jsonObject
            .filterKeys { !it.startsWith("_") }
            .mapValues { (_, v) -> v.jsonPrimitive.content }
    }

    @Test
    fun `catalog is populated`() {
        assertTrue("implausibly small catalog: ${drugs.size}", drugs.size > 1000)
    }

    /**
     * THE safety assertion.
     *
     * RxClass returns `ci_with` (contraindicated-with) relations from the same endpoint as
     * `may_treat`. For metformin those include acidosis and liver disease — conditions
     * where the drug is dangerous. If a future regeneration dropped the relation filter,
     * the app would tell users metformin treats liver disease. This is the check that
     * fails loudly instead.
     */
    @Test
    fun `metformin does not list its contraindications as indications`() {
        val metformin = drugs.firstOrNull { it.name == "metformin" }
        assertNotNull("metformin missing from catalog", metformin)

        val contraindications = setOf(
            "Acidosis",
            "Liver Diseases",
            "Diabetic Ketoacidosis",
            "Drug Hypersensitivity",
            "Renal Insufficiency",
        )
        val leaked = metformin!!.diseases.map { it.name }.filter { it in contraindications }
        assertEquals("ci_with relations leaked into indications: $leaked", emptyList<String>(), leaked)

        assertTrue(
            "metformin should still list its real indication",
            metformin.diseases.any { it.name == "Diabetes Mellitus, Type 2" },
        )
    }

    /** MeSH taxonomy scaffolding is not an indication and must not reach the picker. */
    @Test
    fun `structural mesh nodes are excluded`() {
        val structural = setOf(
            "Disease",
            "Diseases, Life Phases, Behavior Mechanisms and Physiologic States",
            "Pathological Conditions, Signs and Symptoms",
            "Pathologic Processes",
        )
        val offenders = drugs.filter { d -> d.diseases.any { it.name in structural } }
        assertEquals(
            "structural nodes present on ${offenders.size} drugs, e.g. ${offenders.take(3).map { it.name }}",
            emptyList<Drug>(),
            offenders,
        )
    }

    /** Every drug earns its place by having at least one indication. */
    @Test
    fun `no drug has an empty disease list`() {
        val empty = drugs.filter { it.diseases.isEmpty() }
        assertEquals("drugs with no indications: ${empty.take(3).map { it.name }}", emptyList<Drug>(), empty)
    }

    @Test
    fun `most specific indication is listed first`() {
        val metformin = drugs.first { it.name == "metformin" }
        assertEquals("Diabetes Mellitus, Type 2", metformin.diseases.first().name)
    }

    @Test
    fun `rxcuis are unique`() {
        assertEquals(drugs.size, drugs.map { it.rxcui }.distinct().size)
    }

    // --- synonyms ---------------------------------------------------------

    /** A broken alias is dead weight; the user types a real name and still sees nothing. */
    @Test
    fun `every synonym target exists in the catalog`() {
        val names = drugs.map { it.name }.toSet()
        val broken = synonyms.filterValues { it !in names }
        assertEquals("aliases pointing nowhere: $broken", emptyMap<String, String>(), broken)
    }

    /**
     * An alias must be a naming difference, never a substitution to a different drug.
     * "gliclazide" -> "glipizide" was rejected during development for this reason: both
     * are sulfonylureas, but they are not the same medication.
     */
    @Test
    fun `synonyms do not silently substitute a different drug`() {
        assertFalse("gliclazide is a different drug from glipizide", synonyms.containsKey("gliclazide"))
        assertEquals("acetaminophen", synonyms["paracetamol"])
    }

    @Test
    fun `paracetamol resolves - the reason the alias layer exists`() {
        val target = synonyms["paracetamol"]
        assertNotNull("paracetamol alias missing; non-US users cannot find their drug", target)
        assertTrue(drugs.any { it.name == target })
    }
}
