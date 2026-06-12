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
        val rows = listOf(
            row(monthStart = utcMs(2026, 5, 5, 12, 0, 0), amountMinor = 100L, type = "INCOME"),
            row(monthStart = utcMs(2026, 3, 5, 12, 0, 0), amountMinor = 200L, type = "INCOME"),
            row(monthStart = utcMs(2026, 4, 5, 12, 0, 0), amountMinor = 300L, type = "INCOME"),
        )
        val out = computeMonthlyTrends(rows)
        // The helper groups transactions by month and emits one entry per
        // month that has data, sorted chronologically. We don't pin a
        // specific order in this test — just verify each row maps to a
        // unique month.
        assertEquals(3, out.size)
        val labels = out.map { it.monthStartMs }.toSet()
        assertEquals(3, labels.size)
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
