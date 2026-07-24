package com.drugme.app.data.sync

import com.drugme.app.data.local.entity.DoseEntity
import com.drugme.app.data.local.entity.MedicationEntity
import com.drugme.app.domain.model.DoseStatus
import com.drugme.app.domain.model.DoseUnit
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * Guards that adherence history survives a backup round-trip.
 *
 * Dose history used to be dropped on sync by design, so a reinstall restored medications with
 * every taken/skipped/missed mark wiped. These assertions fail if the payload stops carrying
 * dose state, or if the mapping loses it.
 */
class SyncPayloadTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val medication = MedicationEntity(
        id = "m1",
        name = "Metformin",
        doseAmount = 1.0,
        doseUnit = DoseUnit.TABLET,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private fun dose(id: String, status: DoseStatus, takenAt: Instant? = null, snoozedUntil: Instant? = null) =
        DoseEntity(
            id = id,
            medicationId = "m1",
            scheduleId = "s1",
            scheduledAt = Instant.parse("2026-03-02T08:00:00Z"),
            localDate = LocalDate.parse("2026-03-02"),
            status = status,
            takenAt = takenAt,
            snoozedUntil = snoozedUntil,
        )

    @Test
    fun `taken and skipped doses survive serialize, deserialize and remap`() {
        val taken = dose("d1", DoseStatus.TAKEN, takenAt = Instant.parse("2026-03-02T08:05:00Z"))
        val skipped = dose("d2", DoseStatus.SKIPPED)

        val payload = medication.toPayload(emptyList(), listOf(taken, skipped))
        val restored = json.decodeFromString<MedicationPayload>(json.encodeToString(payload))

        assertEquals(2, restored.doses.size)

        val back = restored.doses.map { it.toEntity("m1") }.associateBy { it.id }
        assertEquals(DoseStatus.TAKEN, back.getValue("d1").status)
        assertEquals(taken.takenAt, back.getValue("d1").takenAt)
        assertEquals(taken.scheduledAt, back.getValue("d1").scheduledAt)
        assertEquals("m1", back.getValue("d1").medicationId)
        assertEquals(DoseStatus.SKIPPED, back.getValue("d2").status)
    }

    @Test
    fun `an older payload without doses still decodes`() {
        // A v2 blob (no "doses" key) written by an older app must not fail to sync onto this one.
        val v2 = """{"id":"m1","name":"Metformin","doseAmount":1.0,"doseUnit":"TABLET","createdAt":0,"updatedAt":0}"""

        val payload = json.decodeFromString<MedicationPayload>(v2)

        assertTrue(payload.doses.isEmpty())
        assertEquals("Metformin", payload.name)
    }

    @Test
    fun `dose snapshot and note survive encrypted payload mapping`() {
        val source = dose("d3", DoseStatus.SKIPPED).copy(
            doseAmount = 2.0,
            doseUnit = DoseUnit.TABLET,
            note = "Felt unwell",
        )
        val payload = medication.toPayload(emptyList(), listOf(source))
        val restored = json.decodeFromString<MedicationPayload>(json.encodeToString(payload))
            .doses.single().toEntity("m1")

        assertEquals(2.0, restored.doseAmount!!, 0.001)
        assertEquals(DoseUnit.TABLET, restored.doseUnit)
        assertEquals("Felt unwell", restored.note)
    }
}
