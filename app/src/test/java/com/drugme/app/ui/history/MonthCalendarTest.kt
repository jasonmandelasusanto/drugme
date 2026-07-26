package com.drugme.app.ui.history

import com.drugme.app.data.repo.DoseRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MonthCalendarTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `month grid is six complete Monday-first weeks`() {
        val dates = monthGridDates(YearMonth.of(2026, 2))

        assertEquals(42, dates.size)
        assertEquals(LocalDate.of(2026, 1, 26), dates.first())
        assertEquals(LocalDate.of(2026, 3, 8), dates.last())
        assertEquals(DayOfWeek.MONDAY, dates.first().dayOfWeek)
        assertEquals(DayOfWeek.SUNDAY, dates.last().dayOfWeek)
        assertTrue((1..28).all { day -> LocalDate.of(2026, 2, day) in dates })
    }

    @Test
    fun `leap day is included in its month grid`() {
        assertTrue(LocalDate.of(2024, 2, 29) in monthGridDates(YearMonth.of(2024, 2)))
    }

    @Test
    fun `dot count uses distinct medications rather than individual doses`() {
        val july26 = LocalDate.of(2026, 7, 26)
        val july27 = july26.plusDays(1)

        val counts = medicationCountsByDate(
            listOf(
                july26 to "metformin",
                july26 to "metformin",
                july26 to "atorvastatin",
                july27 to "metformin",
            )
        )

        assertEquals(2, counts[july26])
        assertEquals(1, counts[july27])
    }

    @Test
    fun `moving to next month selects its first day and extends dose generation`() =
        runTest(dispatcher) {
            val repository = mockk<DoseRepository>(relaxed = true)
            every { repository.observeHistory(any(), any()) } returns MutableStateFlow(emptyList())
            coEvery { repository.materializeWindow(any()) } returns 0
            val clock = Clock.fixed(
                Instant.parse("2026-07-26T08:00:00Z"),
                ZoneOffset.UTC,
            )
            val viewModel = HistoryViewModel(repository, clock)
            val collection = backgroundScope.launch { viewModel.state.collect {} }

            advanceUntilIdle()
            viewModel.showNextMonth()
            advanceUntilIdle()

            assertEquals(YearMonth.of(2026, 8), viewModel.state.value.displayedMonth)
            assertEquals(LocalDate.of(2026, 8, 1), viewModel.state.value.selectedDate)
            assertEquals(42, viewModel.state.value.calendar.size)
            coVerify { repository.materializeWindow(42) }
            collection.cancel()
        }
}
