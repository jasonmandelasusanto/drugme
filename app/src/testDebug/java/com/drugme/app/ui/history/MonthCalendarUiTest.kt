package com.drugme.app.ui.history

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MonthCalendarUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `calendar announces medication count and selects a date`() {
        val month = YearMonth.of(2026, 7)
        val target = LocalDate.of(2026, 7, 26)
        var selected: LocalDate? = null
        val counts = mapOf(target to 2)

        compose.setContent {
            MaterialTheme {
                MonthCalendar(
                    state = HistoryState(
                        displayedMonth = month,
                        selectedDate = month.atDay(1),
                        today = target,
                        calendar = monthGridDates(month).map { date ->
                            CalendarDay(
                                date = date,
                                inDisplayedMonth = YearMonth.from(date) == month,
                                medicationCount = counts[date] ?: 0,
                            )
                        },
                    ),
                    onPreviousMonth = {},
                    onNextMonth = {},
                    onToday = {},
                    onSelectDate = { selected = it },
                )
            }
        }

        compose
            .onNodeWithContentDescription("Sunday, 26 July, 2 medications scheduled")
            .performClick()

        assertEquals(target, selected)
    }
}
