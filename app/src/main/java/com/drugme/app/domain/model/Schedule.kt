package com.drugme.app.domain.model

import java.time.DayOfWeek

/** How a medication's dose times are derived. Stored by [name], never by ordinal. */
enum class ScheduleType {
    /** N times every day, at explicit clock times. */
    TIMES_PER_DAY,

    /** Only on selected weekdays, at explicit clock times. */
    DAYS_OF_WEEK,

    /** Every N days from the start date, at explicit clock times. */
    INTERVAL_DAYS,
}

/** Lifecycle of a single scheduled dose. Stored by [name], never by ordinal. */
enum class DoseStatus {
    /** Due, or not yet acted on and still inside the grace window. */
    PENDING,
    TAKEN,

    /** User explicitly declined this dose. Distinct from MISSED — intent matters for adherence. */
    SKIPPED,

    /** Grace window elapsed with no action. Assigned by the system, never by the user. */
    MISSED,
}

/**
 * Weekday selection as a 7-bit mask. Bit 0 = Monday .. bit 6 = Sunday, matching
 * [DayOfWeek.getValue] minus one.
 *
 * A mask (rather than a joined table) keeps a schedule a single row, which matters
 * because the sync layer encrypts and ships whole records as opaque blobs.
 */
@JvmInline
value class WeekdayMask(val bits: Int) {

    operator fun contains(day: DayOfWeek): Boolean = bits and (1 shl (day.value - 1)) != 0

    fun with(day: DayOfWeek, enabled: Boolean): WeekdayMask {
        val bit = 1 shl (day.value - 1)
        return WeekdayMask(if (enabled) bits or bit else bits and bit.inv())
    }

    val isEmpty: Boolean get() = bits and ALL_BITS == 0

    val days: List<DayOfWeek> get() = DayOfWeek.entries.filter { it in this }

    companion object {
        private const val ALL_BITS = 0b1111111
        val NONE = WeekdayMask(0)
        val EVERY_DAY = WeekdayMask(ALL_BITS)
        fun of(vararg days: DayOfWeek): WeekdayMask =
            WeekdayMask(days.fold(0) { acc, d -> acc or (1 shl (d.value - 1)) })
    }
}
