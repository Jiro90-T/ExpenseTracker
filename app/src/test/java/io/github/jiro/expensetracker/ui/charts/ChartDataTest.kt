package io.github.jiro.expensetracker.ui.charts

import io.github.jiro.expensetracker.data.local.CategoryEntity
import io.github.jiro.expensetracker.data.local.TransactionEntity
import io.github.jiro.expensetracker.data.local.TransactionWithCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ChartDataTest {

    @Test
    fun emptyRows_returnsZeroFilledMonths() {
        val today = noonUtc(2026, 6, 15)
        val months = computeMonthlyTotals(emptyList(), monthsBack = 6, todayMs = today)
        assertEquals(6, months.size)
        // Every month should have zero income and zero expense.
        assertTrue(months.all { it.incomeMinor == 0L && it.expenseMinor == 0L })
        // All monthStartMs are at the 1st of the month (midnight local).
        assertTrue(months.all { isFirstOfMonth(it.monthStartMs) })
    }

    @Test
    fun resultIsInChronologicalOrder_oldestFirst() {
        val today = noonUtc(2026, 6, 15)
        val months = computeMonthlyTotals(emptyList(), monthsBack = 6, todayMs = today)
        // Each subsequent monthStartMs should be later than the previous.
        for (i in 1 until months.size) {
            assertTrue(
                "Months should be in ascending order at index $i",
                months[i].monthStartMs > months[i - 1].monthStartMs,
            )
        }
    }

    @Test
    fun transactionsAggregateIntoTheirMonth() {
        val today = noonUtc(2026, 6, 15)
        val rows = listOf(
            tx(occurredAt = noonUtc(2026, 5, 10), amountMinor = 1_000, type = "INCOME"),
            tx(occurredAt = noonUtc(2026, 5, 25), amountMinor = 200, type = "EXPENSE"),
            tx(occurredAt = noonUtc(2026, 4, 5), amountMinor = 3_000, type = "INCOME"),
            tx(occurredAt = noonUtc(2026, 4, 20), amountMinor = 500, type = "EXPENSE"),
        )
        val months = computeMonthlyTotals(rows, monthsBack = 3, todayMs = today)
        assertEquals(3, months.size)
        // Oldest first: April, May, June
        val april = months[0]
        val may = months[1]
        val june = months[2]
        assertEquals(3_000L, april.incomeMinor)
        assertEquals(500L, april.expenseMinor)
        assertEquals(1_000L, may.incomeMinor)
        assertEquals(200L, may.expenseMinor)
        assertEquals(0L, june.incomeMinor)
        assertEquals(0L, june.expenseMinor)
    }

    @Test
    fun transactionsOutsideTheWindow_areIgnored() {
        val today = noonUtc(2026, 6, 15)
        // Transaction from a year ago, well outside the 3-month window.
        val rows = listOf(
            tx(occurredAt = noonUtc(2025, 1, 15), amountMinor = 999_999, type = "INCOME"),
            tx(occurredAt = noonUtc(2026, 5, 15), amountMinor = 100, type = "INCOME"),
        )
        val months = computeMonthlyTotals(rows, monthsBack = 3, todayMs = today)
        // Only the May transaction should show.
        val may = months[1]
        assertEquals(100L, may.incomeMinor)
        // No 999_999 anywhere.
        assertTrue(months.all { it.incomeMinor < 1_000_000L })
    }

    @Test
    fun resultSizeIsAlwaysMonthsBack_evenIfEmpty() {
        val today = noonUtc(2026, 6, 15)
        assertEquals(1, computeMonthlyTotals(emptyList(), monthsBack = 1, todayMs = today).size)
        assertEquals(3, computeMonthlyTotals(emptyList(), monthsBack = 3, todayMs = today).size)
        assertEquals(12, computeMonthlyTotals(emptyList(), monthsBack = 12, todayMs = today).size)
    }

    // ---- helpers ----

    private fun noonUtc(year: Int, month: Int, day: Int): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(year, month - 1, day, 12, 0, 0)  // noon UTC, no DST edge case
        return cal.timeInMillis
    }

    private fun isFirstOfMonth(epochMs: Long): Boolean {
        val cal = Calendar.getInstance().apply { timeInMillis = epochMs }
        return cal.get(Calendar.DAY_OF_MONTH) == 1 &&
            cal.get(Calendar.HOUR_OF_DAY) == 0 &&
            cal.get(Calendar.MINUTE) == 0 &&
            cal.get(Calendar.SECOND) == 0 &&
            cal.get(Calendar.MILLISECOND) == 0
    }

    private fun tx(occurredAt: Long, amountMinor: Long, type: String): TransactionWithCategory {
        val txn = TransactionEntity(
            id = 0L,
            title = "t",
            amountMinor = amountMinor,
            currencyCode = "USD",
            type = type,
            categoryId = 0L,
            occurredAtEpochMillis = occurredAt,
            note = null,
            createdAtEpochMillis = occurredAt,
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
}
