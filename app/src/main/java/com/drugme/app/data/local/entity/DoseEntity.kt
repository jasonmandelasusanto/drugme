package com.drugme.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.drugme.app.domain.model.DoseStatus
import java.time.Instant
import java.time.LocalDate

/**
 * One concrete, materialised dose occurrence.
 *
 * Occurrences are rows rather than values computed on demand. That choice is what makes
 * taken-tracking, snooze, adherence history and "reschedule after edit" ordinary queries
 * instead of special cases — each needs per-occurrence state that has nowhere to live in
 * a purely computed model.
 *
 * The unique index on (scheduleId, scheduledAt) is load-bearing. The WorkManager healer
 * re-materialises the rolling window on every run, so generation must be idempotent;
 * without this constraint a heal would duplicate every upcoming dose and the user would
 * be reminded twice for one pill.
 */
@Entity(
    tableName = "doses",
    foreignKeys = [
        ForeignKey(
            entity = MedicationEntity::class,
            parentColumns = ["id"],
            childColumns = ["medicationId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ScheduleEntity::class,
            parentColumns = ["id"],
            childColumns = ["scheduleId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["scheduleId", "scheduledAt"], unique = true),
        Index("scheduledAt"),
        Index("status"),
        Index("medicationId"),
        Index("localDate"),
    ],
)
data class DoseEntity(
    @PrimaryKey val id: String,

    val medicationId: String,
    val scheduleId: String,

    /** The exact instant this dose is due; what the alarm is set to. */
    val scheduledAt: Instant,

    /**
     * The calendar day [scheduledAt] belongs to, resolved in the user's zone at
     * generation time. Stored so "today's doses" is an index lookup rather than a
     * scan that re-derives local dates for every row.
     */
    val localDate: LocalDate,

    val status: DoseStatus = DoseStatus.PENDING,

    val takenAt: Instant? = null,

    /** Set when snoozed; the alarm re-fires at this instant instead of [scheduledAt]. */
    val snoozedUntil: Instant? = null,
) {
    /** When this dose should actually alert — the snooze target if one is set. */
    val effectiveAt: Instant get() = snoozedUntil ?: scheduledAt
}
