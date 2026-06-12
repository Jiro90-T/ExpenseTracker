package io.github.jiro.expensetracker.ui.charts

import io.github.jiro.expensetracker.data.local.CategoryEntity
import io.github.jiro.expensetracker.data.local.TransactionEntity
import io.github.jiro.expensetracker.data.local.TransactionWithCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TrendsPeriodTest {

    @Test
    fun computePeriodTrends_emptyRows_returnsEmpty() {
        val out = computePeriodTrends(
            rows = emptyList(),
            period = TrendsPeriod.SixMonths,
            nowMs = utcMs(2026, 6, 15, 12, 0, 0),
        )
        assertTrue(out.current.isEmpty())
        assertTrue(out.prior?.isEmpty() == true)
        assertNull(out.delta)
        assertNull(out.currentMonthMs)
    }

    @Test
    fun computePeriodTrends_allPeriod_priorIsNull() {
        val rows = listOf(
            row(utcMs(2026, 3, 15, 12, 0, 0), 1_000L, "INCOME"),
            row(utcMs(2026, 4, 15, 12, 0, 0), 2_000L, "INCOME"),
        )
        val out = computePeriodTrends(
            rows = rows,
            period = TrendsPeriod.All,
            nowMs = utcMs(2026, 6, 15, 12, 0, 0),
        )
        assertEquals(2, out.current.size)
        assertNull(out.prior)
        assertNull(out.delta)
    }

    @Test
    fun computePeriodTrends_sixMonths_windowOnly() {
        // nowMs = June 15, 2026 → 6M window is Jan–Jun 2026
        // Out-of-window rows: Nov 2025, Dec 2025 (excluded)
        val rows = listOf(
            row(utcMs(2025, 11, 15, 12, 0, 0), 999L, "INCOME"),
            row(utcMs(2025, 12, 15, 12, 0, 0), 999L, "INCOME"),
            row(utcMs(2026, 1, 15, 12, 0, 0), 100L, "INCOME"),
            row(utcMs(2026, 3, 15, 12, 0, 0), 200L, "INCOME"),
            row(utcMs(2026, 6, 15, 12, 0, 0), 300L, "INCOME"),
        )
        val out = computePeriodTrends(
            rows = rows,
            period = TrendsPeriod.SixMonths,
            nowMs = utcMs(2026, 6, 15, 12, 0, 0),
        )
        assertEquals(3, out.current.size)  // Jan, Mar, Jun
        assertEquals(600L, out.current.sumOf { it.incomeMinor })
    }

    @Test
    fun computePeriodTrends_sixMonths_priorIsPrecedingSix() {
        // nowMs = June 15, 2026 → current = Jan–Jun 2026, prior = Jul–Dec 2025
        val rows = listOf(
            // current window
            row(utcMs(2026, 1, 15, 12, 0, 0), 100L, "INCOME"),
            row(utcMs(2026, 6, 15, 12, 0, 0), 200L, "INCOME"),
            // prior window
            row(utcMs(2025, 7, 15, 12, 0, 0), 50L, "INCOME"),
            row(utcMs(2025, 12, 15, 12, 0, 0), 50L, "INCOME"),
            // outside both windows
            row(utcMs(2025, 6, 15, 12, 0, 0), 999L, "INCOME"),
        )
        val out = computePeriodTrends(
            rows = rows,
            period = TrendsPeriod.SixMonths,
            nowMs = utcMs(2026, 6, 15, 12, 0, 0),
        )
        val prior = out.prior
        assertNotNull(prior)
        // Only the two prior-window months are present
        assertEquals(2, prior!!.size)
        assertEquals(100L, prior.sumOf { it.incomeMinor })  // 50 + 50
    }

    @Test
    fun computePeriodTrends_ytdPriorIsSameRangeLastYear() {
        // nowMs = June 15, 2026 → current = Jan–Jun 2026, prior = Jan–Jun 2025
        val rows = listOf(
            row(utcMs(2026, 1, 15, 12, 0, 0), 100L, "INCOME"),
            row(utcMs(2026, 6, 15, 12, 0, 0), 200L, "INCOME"),
            row(utcMs(2025, 1, 15, 12, 0, 0), 50L, "INCOME"),
            row(utcMs(2025, 6, 15, 12, 0, 0), 50L, "INCOME"),
            row(utcMs(2024, 1, 15, 12, 0, 0), 999L, "INCOME"),  // outside both
        )
        val out = computePeriodTrends(
            rows = rows,
            period = TrendsPeriod.Ytd,
            nowMs = utcMs(2026, 6, 15, 12, 0, 0),
        )
        assertEquals(2, out.current.size)
        assertEquals(2, out.prior!!.size)
        assertEquals(300L, out.current.sumOf { it.incomeMinor })
        assertEquals(100L, out.prior!!.sumOf { it.incomeMinor })
    }

    @Test
    fun computePeriodTrends_ytdJanEdge() {
        // nowMs = Jan 15, 2026 → current = just Jan 2026, prior = just Jan 2025
        val rows = listOf(
            row(utcMs(2026, 1, 15, 12, 0, 0), 100L, "INCOME"),
            row(utcMs(2025, 1, 15, 12, 0, 0), 50L, "INCOME"),
        )
        val out = computePeriodTrends(
            rows = rows,
            period = TrendsPeriod.Ytd,
            nowMs = utcMs(2026, 1, 15, 12, 0, 0),
        )
        assertEquals(1, out.current.size)
        assertEquals(1, out.prior!!.size)
    }

    @Test
    fun computePeriodTrends_deltaCalculations() {
        // current: income 150, expense 50, net 100
        // prior:   income 100, expense 50, net 50
        // expected: income +50%, expense 0%, net +100%
        val rows = listOf(
            // current
            row(utcMs(2026, 1, 15, 12, 0, 0), 100L, "INCOME"),
            row(utcMs(2026, 6, 15, 12, 0, 0), 50L, "INCOME"),
            row(utcMs(2026, 1, 20, 12, 0, 0), 30L, "EXPENSE"),
            row(utcMs(2026, 6, 20, 12, 0, 0), 20L, "EXPENSE"),
            // prior
            row(utcMs(2025, 7, 15, 12, 0, 0), 60L, "INCOME"),
            row(utcMs(2025, 12, 15, 12, 0, 0), 40L, "INCOME"),
            row(utcMs(2025, 7, 20, 12, 0, 0), 30L, "EXPENSE"),
            row(utcMs(2025, 12, 20, 12, 0, 0), 20L, "EXPENSE"),
        )
        val out = computePeriodTrends(
            rows = rows,
            period = TrendsPeriod.SixMonths,
            nowMs = utcMs(2026, 6, 15, 12, 0, 0),
        )
        val delta = out.delta
        assertNotNull(delta)
        assertEquals(50.0, delta!!.incomePct!!, 0.001)
        assertEquals(0.0, delta.expensePct!!, 0.001)
        assertEquals(100.0, delta.netPct!!, 0.001)
    }

    @Test
    fun computePeriodTrends_priorZero_pctIsNull() {
        // current: income 100, expense 50, net 50
        // prior:   income 0, expense 50, net -50
        val rows = listOf(
            // current
            row(utcMs(2026, 1, 15, 12, 0, 0), 100L, "INCOME"),
            row(utcMs(2026, 1, 20, 12, 0, 0), 50L, "EXPENSE"),
            // prior (no income, only expense)
            row(utcMs(2025, 7, 15, 12, 0, 0), 30L, "EXPENSE"),
            row(utcMs(2025, 12, 15, 12, 0, 0), 20L, "EXPENSE"),
        )
        val out = computePeriodTrends(
            rows = rows,
            period = TrendsPeriod.SixMonths,
            nowMs = utcMs(2026, 6, 15, 12, 0, 0),
        )
        val delta = out.delta
        assertNotNull(delta)
        assertNull(delta!!.incomePct)   // prior income = 0
        assertNotNull(delta.expensePct) // prior expense != 0
        assertNotNull(delta.netPct)     // prior net != 0
    }

    @Test
    fun computePeriodTrends_currentAndPriorEqual_pctIsZero() {
        val rows = listOf(
            row(utcMs(2026, 1, 15, 12, 0, 0), 100L, "INCOME"),
            row(utcMs(2026, 6, 15, 12, 0, 0), 50L, "INCOME"),
            row(utcMs(2025, 7, 15, 12, 0, 0), 100L, "INCOME"),
            row(utcMs(2025, 12, 15, 12, 0, 0), 50L, "INCOME"),
        )
        val out = computePeriodTrends(
            rows = rows,
            period = TrendsPeriod.SixMonths,
            nowMs = utcMs(2026, 6, 15, 12, 0, 0),
        )
        val delta = out.delta!!
        assertEquals(0.0, delta.incomePct!!, 0.001)
    }

    @Test
    fun computePeriodTrends_purityRepeatedCalls() {
        val rows = listOf(
            row(utcMs(2026, 1, 15, 12, 0, 0), 100L, "INCOME"),
        )
        val a = computePeriodTrends(rows, TrendsPeriod.SixMonths, utcMs(2026, 6, 15, 12, 0, 0))
        val b = computePeriodTrends(rows, TrendsPeriod.SixMonths, utcMs(2026, 6, 15, 12, 0, 0))
        assertEquals(a, b)
    }

    @Test
    fun computePeriodTrends_currentMonthIsInWindow() {
        val rows = listOf(
            row(utcMs(2026, 6, 15, 12, 0, 0), 100L, "INCOME"),
        )
        val out = computePeriodTrends(
            rows = rows,
            period = TrendsPeriod.SixMonths,
            nowMs = utcMs(2026, 6, 15, 12, 0, 0),
        )
        // June's start-of-month in the production code is local-tz dependent;
        // assert that currentMonthMs is non-null and that it equals the start
        // of the nowMs's month in local time.
        val expectedStart = startOfMonth(utcMs(2026, 6, 15, 12, 0, 0))
        assertEquals(expectedStart, out.currentMonthMs)
    }

    @Test
    fun computePeriodTrends_currentMonthIsOutsideWindow() {
        // nowMs is in 2024. 6M window = Jan–Jun 2024. No data for that window,
        // so current is empty → currentMonthMs must be null (nothing to mark).
        val rows = listOf(
            row(utcMs(2025, 1, 15, 12, 0, 0), 100L, "INCOME"),
        )
        val out = computePeriodTrends(
            rows = rows,
            period = TrendsPeriod.SixMonths,
            nowMs = utcMs(2024, 6, 15, 12, 0, 0),
        )
        assertNull(out.currentMonthMs)
    }

    @Test
    fun computePeriodTrends_priorMatchesCurrentLength() {
        val rows = listOf(
            // current 3M = Apr, May, Jun 2026
            row(utcMs(2026, 4, 15, 12, 0, 0), 100L, "INCOME"),
            row(utcMs(2026, 5, 15, 12, 0, 0), 100L, "INCOME"),
            row(utcMs(2026, 6, 15, 12, 0, 0), 100L, "INCOME"),
            // prior 3M = Jan, Feb, Mar 2026
            row(utcMs(2026, 1, 15, 12, 0, 0), 50L, "INCOME"),
            row(utcMs(2026, 2, 15, 12, 0, 0), 50L, "INCOME"),
            row(utcMs(2026, 3, 15, 12, 0, 0), 50L, "INCOME"),
        )
        val out = computePeriodTrends(
            rows = rows,
            period = TrendsPeriod.ThreeMonths,
            nowMs = utcMs(2026, 6, 15, 12, 0, 0),
        )
        assertEquals(out.current.size, out.prior!!.size)
    }

    // ---- helpers (mirror LineChartDataTest's row/utcMs pattern) ----

    private fun row(
        monthStart: Long,
        amountMinor: Long,
        type: String,
    ): TransactionWithCategory {
        val txn = TransactionEntity(
            id = 0L,
            title = "t",
            amountMinor = amountMinor,
            currencyCode = "USD",
            type = type,
            categoryId = 0L,
            occurredAtEpochMillis = monthStart + 24L * 3600_000L,  // mid-month
            note = null,
            createdAtEpochMillis = monthStart,
        )
        val cat = CategoryEntity(
            id = 0L,
            name = "Any",
            type = type,
            sortOrder = 0,
            isBuiltIn = true,
        )
        return TransactionWithCategory(txn, cat)
    }

    private fun utcMs(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(year, month - 1, day, hour, minute, second)
        return cal.timeInMillis
    }

    private fun startOfMonth(epochMs: Long): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = epochMs
        cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
