package com.drugme.app.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The analytics maths, tested where it's easy to be plausibly wrong.
 *
 * These numbers are shown to someone about their own medical behaviour. A percentage that
 * looks reasonable but is computed wrongly is worse than no percentage at all — it gets
 * believed.
 */
class AnalyticsTest {

    // --- Adherence ---------------------------------------------------------

    @Test
    fun `adherence counts only decided doses`() {
        // 8 taken, 1 missed, 1 skipped -> 80%. Upcoming doses must not be in the denominator,
        // or the number would start at 0% every morning and climb through the day.
        val a = Adherence(taken = 8, missed = 1, skipped = 1)
        assertEquals(10, a.decided)
        assertEquals(80, a.takenPercent)
    }

    @Test
    fun `adherence with nothing due is null, not zero`() {
        // "No data" and "you failed" must not render identically.
        assertNull(Adherence().takenPercent)
    }

    @Test
    fun `skipped counts against adherence but is reported separately`() {
        val a = Adherence(taken = 5, missed = 0, skipped = 5)
        assertEquals(50, a.takenPercent)
        // The split is what lets the UI say "you chose to skip 5" rather than implying
        // 5 failures.
        assertEquals(5, a.skipped)
        assertEquals(0, a.missed)
    }

    // --- Punctuality -------------------------------------------------------

    @Test
    fun `punctuality with nothing taken is null, not zero`() {
        assertNull(Punctuality().onTimePercent)
    }

    @Test
    fun `punctuality percentage counts on-time against everything taken`() {
        val p = Punctuality(onTimeCount = 15, lateCount = 4, earlyCount = 1)
        assertEquals(20, p.total)
        assertEquals(75, p.onTimePercent)
    }

    @Test
    fun `early and late are distinct, not folded into one`() {
        // Averaging absolute delays would let "two hours early, two hours late" report as
        // punctual. Both are real behaviours and neither is on time.
        val p = Punctuality(onTimeCount = 0, lateCount = 5, earlyCount = 5)
        assertEquals(0, p.onTimePercent)
        assertEquals(10, p.total)
    }
}
