package com.drugme.app.ui.medications

import com.drugme.app.data.local.dao.MedicationWithSchedules
import com.drugme.app.data.local.entity.MedicationEntity
import com.drugme.app.domain.model.DoseUnit
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MedicationsListTest {
    @Test
    fun `long medication lists filter without truncating matches`() {
        val medications = (0 until 1_000).map { index ->
            item(
                name = if (index % 10 == 0) "Target medicine $index" else "Medicine $index",
                active = index % 2 == 0,
            )
        }

        val results = filterMedications(
            medications,
            query = "target",
            filter = MedicationFilter.ACTIVE,
        )

        assertEquals(100, results.size)
        assertTrue(results.all { it.medication.isActive })
    }

    @Test
    fun `discontinued filter excludes active medications`() {
        val results = filterMedications(
            listOf(item("Active", true), item("Stopped", false)),
            query = "",
            filter = MedicationFilter.DISCONTINUED,
        )

        assertEquals(listOf("Stopped"), results.map { it.medication.name })
    }

    private fun item(name: String, active: Boolean) = MedicationWithSchedules(
        medication = MedicationEntity(
            id = name,
            name = name,
            doseAmount = 1.0,
            doseUnit = DoseUnit.TABLET,
            isActive = active,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        ),
        schedules = emptyList(),
    )
}
