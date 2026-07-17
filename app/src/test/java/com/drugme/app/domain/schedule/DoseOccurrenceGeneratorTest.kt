package com.drugme.app.domain.schedule

import com.drugme.app.data.local.entity.ScheduleEntity
import com.drugme.app.domain.model.ScheduleType
import com.drugme.app.domain.model.WeekdayMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class DoseOccurrenceGeneratorTest {

    private val gen = DoseOccurrenceGenerator()
    private val utc = ZoneId.of("UTC")
    private val nyc = ZoneId.of("America/New_York")
    private val now = Instant.parse("2026-01-01T00:00:00Z")

    private fun schedule(
        type: ScheduleType = ScheduleType.TIMES_PER_DAY,
        times: List<LocalTime> = listOf(LocalTime.of(8, 0)),
        weekdays: WeekdayMask = WeekdayMask.EVERY_DAY,
        intervalDays: Int = 1,
        start: LocalDate = LocalDate.of(2026, 3, 1),
        end: LocalDate? = null,
    ) = ScheduleEntity(
        id = "s1",
        medicationId = "m1",
        type = type,
        timesOfDay = times,
        weekdays = weekdays,
        intervalDays = intervalDays,
        startDate = start,
        endDate = end,
        createdAt = now,
        updatedAt = now,
    )

    @Test
    fun `three times a day yields three doses per day in order`() {
        val s = schedule(times = listOf(LocalTime.of(8, 0), LocalTime.of(14, 0), LocalTime.of(20, 0)))
        val out = gen.generate(s, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 2), utc)

        assertEquals(6, out.size)
        assertEquals(
            listOf("08:00", "14:00", "20:00", "08:00", "14:00", "20:00"),
            out.map { it.time.toString() },
        )
        assertTrue(out.zipWithNext().all { (a, b) -> a.scheduledAt <= b.scheduledAt })
    }

    @Test
    fun `endDate is inclusive`() {
        val s = schedule(start = LocalDate.of(2026, 3, 1), end = LocalDate.of(2026, 3, 3))
        val out = gen.generate(s, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 10), utc)

        assertEquals(3, out.size)
        assertEquals(LocalDate.of(2026, 3, 3), out.last().localDate)
    }

    @Test
    fun `no occurrences after endDate has passed`() {
        val s = schedule(start = LocalDate.of(2026, 3, 1), end = LocalDate.of(2026, 3, 3))
        val out = gen.generate(s, LocalDate.of(2026, 3, 4), LocalDate.of(2026, 3, 10), utc)
        assertTrue(out.isEmpty())
    }

    @Test
    fun `window is clamped to startDate`() {
        val s = schedule(start = LocalDate.of(2026, 3, 5))
        val out = gen.generate(s, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 6), utc)

        assertEquals(2, out.size)
        assertEquals(LocalDate.of(2026, 3, 5), out.first().localDate)
    }

    @Test
    fun `days of week only fires on selected days`() {
        val s = schedule(
            type = ScheduleType.DAYS_OF_WEEK,
            weekdays = WeekdayMask.of(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
            start = LocalDate.of(2026, 3, 2), // a Monday
        )
        val out = gen.generate(s, LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 8), utc)

        assertEquals(listOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), out.map { it.localDate.dayOfWeek })
    }

    @Test
    fun `empty weekday mask yields nothing rather than every day`() {
        val s = schedule(type = ScheduleType.DAYS_OF_WEEK, weekdays = WeekdayMask.NONE)
        val out = gen.generate(s, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 30), utc)
        assertTrue(out.isEmpty())
    }

    @Test
    fun `interval anchors to startDate not to the window`() {
        val s = schedule(
            type = ScheduleType.INTERVAL_DAYS,
            intervalDays = 3,
            start = LocalDate.of(2026, 3, 1),
        )
        // Window opens mid-cycle: the 5th is not on the every-3-days grid, the 7th is.
        val out = gen.generate(s, LocalDate.of(2026, 3, 5), LocalDate.of(2026, 3, 12), utc)

        assertEquals(
            listOf(LocalDate.of(2026, 3, 7), LocalDate.of(2026, 3, 10)),
            out.map { it.localDate },
        )
    }

    @Test
    fun `interval of zero yields nothing instead of dividing by zero`() {
        val s = schedule(type = ScheduleType.INTERVAL_DAYS, intervalDays = 0)
        val out = gen.generate(s, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 10), utc)
        assertTrue(out.isEmpty())
    }

    @Test
    fun `no times of day yields nothing`() {
        val out = gen.generate(schedule(times = emptyList()), LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 5), utc)
        assertTrue(out.isEmpty())
    }

    @Test
    fun `duplicate times are collapsed`() {
        val s = schedule(times = listOf(LocalTime.of(8, 0), LocalTime.of(8, 0)))
        val out = gen.generate(s, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 1), utc)
        assertEquals(1, out.size)
    }

    // --- DST ---------------------------------------------------------------
    // US DST springs forward 2026-03-08 02:00 -> 03:00 and falls back 2026-11-01.

    @Test
    fun `8am stays 8am local across spring forward`() {
        val s = schedule(times = listOf(LocalTime.of(8, 0)), start = LocalDate.of(2026, 3, 7))
        val out = gen.generate(s, LocalDate.of(2026, 3, 7), LocalDate.of(2026, 3, 9), nyc)

        // The wall-clock hour is what the user cares about; the UTC instant shifts by an
        // hour across the transition, and that is correct rather than a bug.
        assertTrue(out.all { it.scheduledAt.atZone(nyc).hour == 8 })
        val gaps = out.map { it.scheduledAt }.zipWithNext { a, b -> java.time.Duration.between(a, b).toHours() }
        assertEquals(listOf(23L, 24L), gaps)
    }

    @Test
    fun `8am stays 8am local across fall back`() {
        val s = schedule(times = listOf(LocalTime.of(8, 0)), start = LocalDate.of(2026, 10, 31))
        val out = gen.generate(s, LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 2), nyc)

        assertTrue(out.all { it.scheduledAt.atZone(nyc).hour == 8 })
        val gaps = out.map { it.scheduledAt }.zipWithNext { a, b -> java.time.Duration.between(a, b).toHours() }
        assertEquals(listOf(25L, 24L), gaps)
    }

    @Test
    fun `dose inside the spring-forward gap is shifted forward rather than dropped`() {
        // 02:30 does not exist on 2026-03-08 in New York. A dropped dose would be a
        // silently missed medication, so it must resolve to a real instant.
        val s = schedule(times = listOf(LocalTime.of(2, 30)), start = LocalDate.of(2026, 3, 8))
        val out = gen.generate(s, LocalDate.of(2026, 3, 8), LocalDate.of(2026, 3, 8), nyc)

        assertEquals(1, out.size)
        assertEquals(3, out.first().scheduledAt.atZone(nyc).hour)
    }
}
