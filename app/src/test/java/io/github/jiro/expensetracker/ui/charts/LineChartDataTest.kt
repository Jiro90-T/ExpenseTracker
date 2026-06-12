package io.github.jiro.expensetracker.ui.charts

import io.github.jiro.expensetracker.data.local.CategoryEntity
import io.github.jiro.expensetracker.data.local.TransactionEntity
import io.github.jiro.expensetracker.data.local.TransactionWithCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineChartDataTest {

    @Test
    fun computeMonthlyTrends_emptyList_returnsEmptyList() {
        val out = computeMonthlyTrends(emptyList())
        assertTrue(out.isEmpty())
    }

    @Test
    fun computeMonthlyTrends_singleMonth_returnsOne() {
        val rows = listOf(
            row(monthStart = utcMs(2026, 3, 15, 12, 0, 0), amountMinor = 1_000L, type = "INCOME"),
        )
        val out = computeMonthlyTrends(rows)
        assertEquals(1, out.size)
        val m = out.first()
        assertEquals(1_000L, m.incomeMinor)
        assertEquals(0L, m.expenseMinor)
        assertEquals(1_000L, m.netMinor)
        assertEquals("Mar", m.shortLabel)
    }

    @Test
    fun computeMonthlyTrends_mixedIncomeExpense_computesNet() {
        val rows = listOf(
            row(monthStart = utcMs(2026, 3, 5, 12, 0, 0), amountMinor = 2_000L, type = "INCOME"),
            row(monthStart = utcMs(2026, 3, 20, 12, 0, 0), amountMinor = 500L, type = "EXPENSE"),
            row(monthStart = utcMs(2026, 4, 5, 12, 0, 0), amountMinor = 1_500L, type = "INCOME"),
            row(monthStart = utcMs(2026, 4, 20, 12, 0, 0), amountMinor = 700L, type = "EXPENSE"),
        )
        val out = computeMonthlyTrends(rows)
        assertEquals(2, out.size)
        // March: income 2000, expense 500, net 1500
        assertEquals(2_000L, out[0].incomeMinor)
        assertEquals(500L, out[0].expenseMinor)
        assertEquals(1_500L, out[0].netMinor)
        // April: income 1500, expense 700, net 800
        assertEquals(1_500L, out[1].incomeMinor)
        assertEquals(700L, out[1].expenseMinor)
        assertEquals(800L, out[1].netMinor)
    }

    @Test
    fun computeMonthlyTrends_allExpense_netIsNegative() {
        val rows = listOf(
            row(monthStart = utcMs(2026, 3, 5, 12, 0, 0), amountMinor = 500L, type = "EXPENSE"),
            row(monthStart = utcMs(2026, 3, 20, 12, 0, 0), amountMinor = 300L, type = "EXPENSE"),
        )
        val out = computeMonthlyTrends(rows)
        assertEquals(1, out.size)
        assertEquals(0L, out[0].incomeMinor)
        assertEquals(800L, out[0].expenseMinor)
        assertEquals(-800L, out[0].netMinor)
    }

    @Test
    fun computeMonthlyTrends_isPure_repeatedCallsReturnEqualResults() {
        val rows = listOf(
            row(monthStart = utcMs(2026, 3, 5, 12, 0, 0), amountMinor = 1_000L, type = "INCOME"),
        )
        val first = computeMonthlyTrends(rows)
        val second = computeMonthlyTrends(rows)
        assertEquals(first, second)
    }

    @Test
    fun computeMonthlyTrends_preservesSortOrder() {
        // Rows intentionally out of input order. The helper must emit
        // them chronologically (oldest first) regardless of input order.
        // We assert the relative order (each month is strictly less than
        // the next) rather than specific epoch values, because the
        // grouped key is the timezone-dependent start-of-month instant.
        val marStart = utcMs(2026, 3, 15, 12, 0, 0)
        val aprStart = utcMs(2026, 4, 15, 12, 0, 0)
        val mayStart = utcMs(2026, 5, 15, 12, 0, 0)
        val rows = listOf(
            row(monthStart = mayStart, amountMinor = 100L, type = "INCOME"),
            row(monthStart = marStart, amountMinor = 200L, type = "INCOME"),
            row(monthStart = aprStart, amountMinor = 300L, type = "INCOME"),
        )
        val out = computeMonthlyTrends(rows)
        assertEquals(3, out.size)
        assertTrue("months must be sorted ascending", out[0].monthStartMs < out[1].monthStartMs)
        assertTrue(out[1].monthStartMs < out[2].monthStartMs)
    }

    // ---- helpers ----

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
}
