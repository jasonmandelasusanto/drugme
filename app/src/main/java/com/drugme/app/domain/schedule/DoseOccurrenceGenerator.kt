package com.drugme.app.domain.schedule

import com.drugme.app.data.local.entity.ScheduleEntity
import com.drugme.app.domain.model.ScheduleType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/** One concrete dose time produced by a schedule. */
data class Occurrence(
    val scheduledAt: Instant,
    val localDate: LocalDate,
    val time: LocalTime,
)

/**
 * Expands a [ScheduleEntity] into concrete dose times over a date window.
 *
 * Deliberately pure — no database, no clock, no Android — so every awkward case (interval
 * arithmetic, the "until" boundary, DST) is exercisable in plain unit tests. This is the
 * component whose bugs are least visible in practice: a wrong dose time doesn't crash, it
 * just quietly reminds someone at the wrong hour.
 */
@Singleton
class DoseOccurrenceGenerator @Inject constructor() {

    fun generate(
        schedule: ScheduleEntity,
        from: LocalDate,
        to: LocalDate,
        zone: ZoneId,
    ): List<Occurrence> {
        if (schedule.timesOfDay.isEmpty()) return emptyList()

        // Clamp the requested window to the schedule's own bounds. endDate is inclusive:
        // "until the 30th" means a dose on the 30th is still due.
        val start = maxOf(from, schedule.startDate)
        val end = schedule.endDate?.let { minOf(to, it) } ?: to
        if (start > end) return emptyList()

        if (schedule.type == ScheduleType.INTERVAL_DAYS && schedule.intervalDays < 1) {
            return emptyList()
        }
        if (schedule.type == ScheduleType.DAYS_OF_WEEK && schedule.weekdays.isEmpty) {
            return emptyList()
        }

        val times = schedule.timesOfDay.distinct().sorted()
        val out = ArrayList<Occurrence>()
        var date = start
        while (date <= end) {
            if (schedule.matches(date)) {
                for (t in times) {
                    out += Occurrence(
                        // Resolving through ZonedDateTime rather than a fixed offset is what
                        // makes DST correct. On a spring-forward gap the time is shifted to
                        // the next valid instant; on a fall-back overlap the earlier offset
                        // wins. Both beat a dose that silently vanishes or fires twice.
                        scheduledAt = date.atTime(t).atZone(zone).toInstant(),
                        localDate = date,
                        time = t,
                    )
                }
            }
            date = date.plusDays(1)
        }
        return out
    }

    private fun ScheduleEntity.matches(date: LocalDate): Boolean = when (type) {
        ScheduleType.TIMES_PER_DAY -> true
        ScheduleType.DAYS_OF_WEEK -> date.dayOfWeek in weekdays
        // Counted from startDate, so "every 3 days" stays anchored to the day the user
        // began — not to an arbitrary epoch or to whenever generation happens to run.
        ScheduleType.INTERVAL_DAYS ->
            ChronoUnit.DAYS.between(startDate, date) % intervalDays == 0L
    }
}
