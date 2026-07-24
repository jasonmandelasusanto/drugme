package com.drugme.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.drugme.app.domain.model.ScheduleType
import com.drugme.app.domain.model.WeekdayMask
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * The rule that generates dose times for a medication.
 *
 * Times are stored as wall-clock [LocalTime], not instants: "08:00" means 8am wherever
 * the user is. Concrete firing instants are resolved per-day at generation time, which
 * is what keeps the schedule correct across DST shifts and travel.
 */
@Entity(
    tableName = "schedules",
    foreignKeys = [
        ForeignKey(
            entity = MedicationEntity::class,
            parentColumns = ["id"],
            childColumns = ["medicationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("medicationId")],
)
data class ScheduleEntity(
    @PrimaryKey val id: String,

    val medicationId: String,

    val type: ScheduleType,

    /** Wall-clock times to dose at. Applies to every schedule type. */
    val timesOfDay: List<LocalTime>,

    /** Optional amount override for this schedule; null uses the medication's base dose. */
    val doseAmount: Double? = null,

    /** Unit paired with [doseAmount]. Null uses the medication's base unit. */
    val doseUnit: com.drugme.app.domain.model.DoseUnit? = null,

    /** Used only when [type] is [ScheduleType.DAYS_OF_WEEK]. */
    val weekdays: WeekdayMask = WeekdayMask.EVERY_DAY,

    /** Used only when [type] is [ScheduleType.INTERVAL_DAYS]. 2 = every other day. */
    val intervalDays: Int = 1,

    val startDate: LocalDate,

    /** The "until" date, inclusive. Null means open-ended. */
    val endDate: LocalDate? = null,

    val createdAt: Instant,
    val updatedAt: Instant,
)
