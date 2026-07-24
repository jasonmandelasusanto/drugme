package com.drugme.app.data.sync

import com.drugme.app.data.local.entity.DoseEntity
import com.drugme.app.data.local.entity.MedicationEntity
import com.drugme.app.data.local.entity.ScheduleEntity
import com.drugme.app.domain.model.DoseStatus
import com.drugme.app.domain.model.DoseUnit
import com.drugme.app.domain.model.FoodRelation
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
    // Defaulted so a payload written by v1 still decodes: an older device's records must
    // not fail to sync onto a newer one.
    val foodRelation: String = "ANY",
    val stockAmount: Double? = null,
    val stockUnit: String? = null,
    val stockPerDose: Double? = null,
    val refillReminderDays: Int = 7,
    val diseaseId: String? = null,
    val diseaseName: String? = null,
    val notes: String? = null,
    val isActive: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
    val schedules: List<SchedulePayload> = emptyList(),
    val doses: List<DosePayload> = emptyList(),
) {
    companion object {
        /**
         * 3: added doses (taken/skipped/missed/snoozed history).
         * 2: added foodRelation, stockAmount, refillReminderDays.
         *
         * Backward compatible in both directions — every new field has a default, so an older
         * payload decodes here, and a newer payload decodes on an older app (which ignores
         * unknown keys). The number exists so a future breaking change can be detected rather
         * than guessed at.
         */
        const val SCHEMA_VERSION = 4
    }
}

/**
 * One dose's recorded state, carried inside its medication's blob.
 *
 * Only doses that hold real history are worth sending — see the acted-on filter in the DAO.
 * The scheduleId and scheduledAt are the natural key a restore reconciles against, so a
 * regenerated pending dose is replaced by its true taken/skipped state rather than duplicated.
 */
@Serializable
data class DosePayload(
    val id: String,
    val scheduleId: String,
    val scheduledAt: Long,
    val localDate: String,
    val doseAmount: Double? = null,
    val doseUnit: String? = null,
    val status: String,
    val takenAt: Long? = null,
    val snoozedUntil: Long? = null,
    val note: String? = null,
)

@Serializable
data class SchedulePayload(
    val id: String,
    val type: String,
    /** "08:00,20:00" — wall-clock, so it stays correct across zones and DST. */
    val timesOfDay: String,
    val doseAmount: Double? = null,
    val doseUnit: String? = null,
    val weekdayBits: Int,
    val intervalDays: Int,
    val startDate: String,
    val endDate: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * refillNotifiedAt is deliberately not in the payload: whether *this device* has already
 * shown a low-stock warning is local UI state, not something the user's other phone needs
 * — and syncing it would suppress the warning on a device that never showed it.
 */

/**
 * Dose history rides *inside* the medication blob, not in its own collection.
 *
 * Adherence history (what was taken, skipped, missed, and when) is real user data that a
 * reinstall must not lose — regenerating pending doses is not enough. Bundling it into the
 * existing per-medication record keeps that promise while adding no new documents, so the
 * server's metadata surface (document count and timestamps) is unchanged. The cost is a
 * larger blob; only acted-on doses are sent, which bounds it to actual history.
 */

fun MedicationEntity.toPayload(
    schedules: List<ScheduleEntity>,
    doses: List<DoseEntity> = emptyList(),
) = MedicationPayload(
    id = id,
    name = name,
    rxcui = rxcui,
    doseAmount = doseAmount,
    doseUnit = doseUnit.name,
    foodRelation = foodRelation.name,
    stockAmount = stockAmount,
    stockUnit = stockUnit?.name,
    stockPerDose = stockPerDose,
    refillReminderDays = refillReminderDays,
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
            doseAmount = s.doseAmount,
            doseUnit = s.doseUnit?.name,
            weekdayBits = s.weekdays.bits,
            intervalDays = s.intervalDays,
            startDate = s.startDate.toString(),
            endDate = s.endDate?.toString(),
            createdAt = s.createdAt.toEpochMilli(),
            updatedAt = s.updatedAt.toEpochMilli(),
        )
    },
    doses = doses.map { it.toDosePayload() },
)

fun DoseEntity.toDosePayload() = DosePayload(
    id = id,
    scheduleId = scheduleId,
    scheduledAt = scheduledAt.toEpochMilli(),
    localDate = localDate.toString(),
    doseAmount = doseAmount,
    doseUnit = doseUnit?.name,
    status = status.name,
    takenAt = takenAt?.toEpochMilli(),
    snoozedUntil = snoozedUntil?.toEpochMilli(),
    note = note,
)

fun MedicationPayload.toEntity() = MedicationEntity(
    id = id,
    name = name,
    rxcui = rxcui,
    doseAmount = doseAmount,
    // valueOf, so an unknown unit from a newer app version throws loudly here rather than
    // silently defaulting to milligrams on someone's dose.
    doseUnit = DoseUnit.valueOf(doseUnit),
    // Unknown food relations fall back to ANY rather than throwing: unlike a dose unit,
    // getting this wrong is an inconvenience, not a dosing error.
    foodRelation = runCatching { FoodRelation.valueOf(foodRelation) }.getOrDefault(FoodRelation.ANY),
    stockAmount = stockAmount,
    stockUnit = stockUnit?.let { runCatching { DoseUnit.valueOf(it) }.getOrNull() },
    stockPerDose = stockPerDose,
    refillReminderDays = refillReminderDays,
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
    doseAmount = doseAmount,
    doseUnit = doseUnit?.let { runCatching { DoseUnit.valueOf(it) }.getOrNull() },
    weekdays = WeekdayMask(weekdayBits),
    intervalDays = intervalDays,
    startDate = LocalDate.parse(startDate),
    endDate = endDate?.let(LocalDate::parse),
    createdAt = Instant.ofEpochMilli(createdAt),
    updatedAt = Instant.ofEpochMilli(updatedAt),
)

fun DosePayload.toEntity(medicationId: String) = DoseEntity(
    id = id,
    medicationId = medicationId,
    scheduleId = scheduleId,
    scheduledAt = Instant.ofEpochMilli(scheduledAt),
    localDate = LocalDate.parse(localDate),
    doseAmount = doseAmount,
    doseUnit = doseUnit?.let { DoseUnit.valueOf(it) },
    // valueOf, not a lenient fallback: an unrecognised status is a bug we want to see, not a
    // dose silently resurrected to PENDING and re-alarmed.
    status = DoseStatus.valueOf(status),
    takenAt = takenAt?.let(Instant::ofEpochMilli),
    snoozedUntil = snoozedUntil?.let(Instant::ofEpochMilli),
    note = note,
)
