package com.drugme.app.domain.schedule

import com.drugme.app.data.local.dao.MedicationWithSchedules
import com.drugme.app.data.local.entity.MedicationEntity
import com.drugme.app.data.local.entity.ScheduleEntity
import com.drugme.app.domain.model.DoseUnit
import com.drugme.app.domain.model.ScheduleType
import com.drugme.app.domain.model.WeekdayMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The forecast fails silently in both directions, which is why it's worth testing.
 *
 * Too pessimistic and the user is told to refill constantly until they ignore the warning;
 * too optimistic and they run out of a medication they were relying on the app to track.
 * Neither shows up as a crash.
 */
class StockForecastTest {

    private val forecaster = StockForecast(DoseOccurrenceGenerator())
    private val utc: ZoneId = ZoneId.of("UTC")
    private val today: LocalDate = LocalDate.of(2026, 3, 2) // a Monday
    private val now: Instant = Instant.parse("2026-03-02T00:00:00Z")

    private fun med(
        doseAmount: Double = 1.0,
        stock: Double? = 10.0,
        refillDays: Int = 7,
    ) = MedicationEntity(
        id = "m1",
        name = "Test",
        doseAmount = doseAmount,
        doseUnit = DoseUnit.TABLET,
        stockAmount = stock,
        refillReminderDays = refillDays,
        createdAt = now,
        updatedAt = now,
    )

    private fun schedule(
        type: ScheduleType = ScheduleType.TIMES_PER_DAY,
        times: List<LocalTime> = listOf(LocalTime.of(8, 0)),
        weekdays: WeekdayMask = WeekdayMask.EVERY_DAY,
        intervalDays: Int = 1,
        start: LocalDate = today,
    ) = ScheduleEntity(
        id = "s1",
        medicationId = "m1",
        type = type,
        timesOfDay = times,
        weekdays = weekdays,
        intervalDays = intervalDays,
        startDate = start,
        endDate = null,
        createdAt = now,
        updatedAt = now,
    )

    private fun item(m: MedicationEntity = med(), vararg s: ScheduleEntity) =
        MedicationWithSchedules(m, s.toList().ifEmpty { listOf(schedule()) })

    @Test
    fun `one a day with ten left runs out in ten days`() {
        val f = forecaster.forecast(item(), today, utc)!!

        assertEquals(10, f.dosesRemaining)
        assertEquals(LocalDate.of(2026, 3, 12), f.runOutDate)
        assertEquals(10, f.daysRemaining)
    }

    @Test
    fun `twice a day with ten left runs out in five days`() {
        val f = forecaster.forecast(
            item(med(), schedule(times = listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)))),
            today, utc,
        )!!

        assertEquals(10, f.dosesRemaining)
        assertEquals(5, f.daysRemaining)
    }

    @Test
    fun `dose size is respected, not just dose count`() {
        // 500mg twice a day out of 10000mg = 10 days, not 10 doses.
        val f = forecaster.forecast(
            item(
                med(doseAmount = 500.0, stock = 10_000.0),
                schedule(times = listOf(LocalTime.of(8, 0), LocalTime.of(20, 0))),
            ),
            today, utc,
        )!!

        assertEquals(20, f.dosesRemaining)
        assertEquals(10, f.daysRemaining)
    }

    @Test
    fun `every other day lasts twice as long as a naive average would say`() {
        // The whole reason this simulates the schedule instead of dividing by a daily
        // average: an average would call this 10 days and warn a week early, every time.
        val f = forecaster.forecast(
            item(med(), schedule(type = ScheduleType.INTERVAL_DAYS, intervalDays = 2)),
            today, utc,
        )!!

        // 10 doses land on Mar 2, 4, 6 ... 20; the 11th would be Mar 22, which is when the
        // stock is actually short. A daily average would have said Mar 12.
        assertEquals(10, f.dosesRemaining)
        assertEquals(20, f.daysRemaining)
        assertEquals(LocalDate.of(2026, 3, 22), f.runOutDate)
    }

    @Test
    fun `weekday-only schedule skips weekends`() {
        // Mon/Wed/Fri, 10 tablets -> 3 per week, so the 10th falls in week four.
        val f = forecaster.forecast(
            item(
                med(),
                schedule(
                    type = ScheduleType.DAYS_OF_WEEK,
                    weekdays = WeekdayMask.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
                ),
            ),
            today, utc,
        )!!

        assertEquals(10, f.dosesRemaining)
        assertTrue("expected well beyond 10 days, got ${f.daysRemaining}", f.daysRemaining!! > 20)
    }

    @Test
    fun `warns when the run-out falls inside the threshold`() {
        val f = forecaster.forecast(item(med(stock = 5.0, refillDays = 7)), today, utc)!!
        assertTrue(f.needsRefillWarning)
    }

    @Test
    fun `does not warn when stock outlasts the threshold`() {
        val f = forecaster.forecast(item(med(stock = 30.0, refillDays = 7)), today, utc)!!
        assertFalse(f.needsRefillWarning)
    }

    @Test
    fun `zero stock warns immediately and reports no doses`() {
        val f = forecaster.forecast(item(med(stock = 0.0)), today, utc)!!

        assertEquals(0, f.dosesRemaining)
        assertEquals(0, f.daysRemaining)
        assertTrue(f.needsRefillWarning)
    }

    @Test
    fun `null stock means not tracking, not empty`() {
        // The distinction matters: defaulting untracked medications to zero would fire a
        // refill warning at every user the moment they added anything.
        assertNull(forecaster.forecast(item(med(stock = null)), today, utc))
    }

    @Test
    fun `no schedule means stock is never consumed`() {
        val f = forecaster.forecast(
            MedicationWithSchedules(med(), emptyList()),
            today, utc,
        )!!

        assertNull(f.runOutDate)
        assertNull(f.daysRemaining)
        assertFalse(f.needsRefillWarning)
    }

    @Test
    fun `stock beyond the horizon reports no run-out rather than a false date`() {
        val f = forecaster.forecast(item(med(stock = 100_000.0)), today, utc)!!

        assertNull("should not invent a run-out date past the horizon", f.runOutDate)
        assertFalse(f.needsRefillWarning)
    }

    @Test
    fun `zero dose amount is not divided by`() {
        assertNull(forecaster.forecast(item(med(doseAmount = 0.0)), today, utc))
    }

    @Test
    fun `partial stock cannot cover a full dose`() {
        // 2.5 tablets, one a day: three days are covered (1, 1, 0.5 is NOT a dose), so the
        // third dose is the one that can't be taken.
        val f = forecaster.forecast(item(med(stock = 2.5)), today, utc)!!

        assertEquals(2, f.dosesRemaining)
        assertEquals(LocalDate.of(2026, 3, 4), f.runOutDate)
    }

    @Test
    fun `multiple schedules for one medication are combined`() {
        // A morning schedule and an evening schedule both draw from the same stock.
        val f = forecaster.forecast(
            item(
                med(stock = 10.0),
                schedule(times = listOf(LocalTime.of(8, 0))),
                schedule(times = listOf(LocalTime.of(20, 0))).copy(id = "s2"),
            ),
            today, utc,
        )!!

        assertEquals(10, f.dosesRemaining)
        assertEquals(5, f.daysRemaining)
    }

    @Test
    fun `milligram prescription can consume tablets from inventory`() {
        val medication = med(doseAmount = 500.0, stock = 10.0).copy(
            doseUnit = DoseUnit.MG,
            stockUnit = DoseUnit.TABLET,
            stockPerDose = 1.0,
        )
        val f = forecaster.forecast(item(medication), today, utc)!!

        assertEquals(10, f.dosesRemaining)
        assertEquals(10, f.daysRemaining)
    }

    @Test
    fun `larger scheduled dose scales inventory consumption`() {
        val medication = med(doseAmount = 500.0, stock = 10.0).copy(
            doseUnit = DoseUnit.MG,
            stockUnit = DoseUnit.TABLET,
            stockPerDose = 1.0,
        )
        val doubleDose = schedule().copy(doseAmount = 1000.0, doseUnit = DoseUnit.MG)
        val f = forecaster.forecast(item(medication, doubleDose), today, utc)!!

        assertEquals(5, f.dosesRemaining)
        assertEquals(5, f.daysRemaining)
    }
}
