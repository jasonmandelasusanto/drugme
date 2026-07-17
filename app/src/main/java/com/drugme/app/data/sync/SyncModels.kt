package com.drugme.app.data.sync

import com.drugme.app.data.local.entity.MedicationEntity
import com.drugme.app.data.local.entity.ScheduleEntity
import com.drugme.app.domain.model.DoseUnit
import com.drugme.app.domain.model.ScheduleType
import com.drugme.app.domain.model.WeekdayMask
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * The plaintext payload that gets encrypted, one per medication.
 *
 * A medication and its schedules travel as a single record because they are meaningless
 * apart: a schedule referencing a medication that failed to sync would generate reminders
 * for a drug the app can't name.
 *
 * [schemaVersion] lives *inside* the encrypted payload rather than beside it. Putting it
 * outside would tell the server how old each user's app is — small, but it costs nothing
 * to not leak.
 */
@Serializable
data class MedicationPayload(
    val schemaVersion: Int = SCHEMA_VERSION,
    val id: String,
    val name: String,
    val rxcui: String? = null,
    val doseAmount: Double,
    val doseUnit: String,
    val diseaseId: String? = null,
    val diseaseName: String? = null,
    val notes: String? = null,
    val isActive: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
    val schedules: List<SchedulePayload> = emptyList(),
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

@Serializable
data class SchedulePayload(
    val id: String,
    val type: String,
    /** "08:00,20:00" — wall-clock, so it stays correct across zones and DST. */
    val timesOfDay: String,
    val weekdayBits: Int,
    val intervalDays: Int,
    val startDate: String,
    val endDate: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * Dose history is deliberately NOT synced in v1.
 *
 * Doses are derivable from a medication's schedule, so syncing them would multiply record
 * count (and therefore metadata leakage) by roughly the number of days tracked, in
 * exchange for preserving taken/skipped marks across devices — a poor trade while there is
 * one phone per user. Regenerating locally after a restore is correct and cheap.
 */

fun MedicationEntity.toPayload(schedules: List<ScheduleEntity>) = MedicationPayload(
    id = id,
    name = name,
    rxcui = rxcui,
    doseAmount = doseAmount,
    doseUnit = doseUnit.name,
    diseaseId = diseaseId,
    diseaseName = diseaseName,
    notes = notes,
    isActive = isActive,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli(),
    schedules = schedules.map { s ->
        SchedulePayload(
            id = s.id,
            type = s.type.name,
            timesOfDay = s.timesOfDay.joinToString(",") { it.toString() },
            weekdayBits = s.weekdays.bits,
            intervalDays = s.intervalDays,
            startDate = s.startDate.toString(),
            endDate = s.endDate?.toString(),
            createdAt = s.createdAt.toEpochMilli(),
            updatedAt = s.updatedAt.toEpochMilli(),
        )
    },
)

fun MedicationPayload.toEntity() = MedicationEntity(
    id = id,
    name = name,
    rxcui = rxcui,
    doseAmount = doseAmount,
    // valueOf, so an unknown unit from a newer app version throws loudly here rather than
    // silently defaulting to milligrams on someone's dose.
    doseUnit = DoseUnit.valueOf(doseUnit),
    diseaseId = diseaseId,
    diseaseName = diseaseName,
    notes = notes,
    isActive = isActive,
    createdAt = Instant.ofEpochMilli(createdAt),
    updatedAt = Instant.ofEpochMilli(updatedAt),
)

fun SchedulePayload.toEntity(medicationId: String) = ScheduleEntity(
    id = id,
    medicationId = medicationId,
    type = ScheduleType.valueOf(type),
    timesOfDay = timesOfDay.split(",").filter { it.isNotBlank() }.map { LocalTime.parse(it.trim()) },
    weekdays = WeekdayMask(weekdayBits),
    intervalDays = intervalDays,
    startDate = LocalDate.parse(startDate),
    endDate = endDate?.let(LocalDate::parse),
    createdAt = Instant.ofEpochMilli(createdAt),
    updatedAt = Instant.ofEpochMilli(updatedAt),
)
