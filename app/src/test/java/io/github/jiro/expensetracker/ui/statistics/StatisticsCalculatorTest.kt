package io.github.jiro.expensetracker.ui.statistics

import io.github.jiro.expensetracker.data.local.CategoryEntity
import io.github.jiro.expensetracker.data.local.TransactionEntity
import io.github.jiro.expensetracker.data.local.TransactionWithCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class StatisticsCalculatorTest {

    @Test
    fun monthBounds_january() {
        val (start, end) = StatisticsCalculator.monthBounds(2026, 1)
        val zone = ZoneId.systemDefault()
        assertEquals(LocalDate.of(2026, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli(), start)
        assertEquals(LocalDate.of(2026, 2, 1).atStartOfDay(zone).toInstant().toEpochMilli(), end)
    }

    @Test
    fun monthBounds_decemberYearRollover() {
        val (start, end) = StatisticsCalculator.monthBounds(2026, 12)
        val zone = ZoneId.systemDefault()
        assertEquals(LocalDate.of(2026, 12, 1).atStartOfDay(zone).toInstant().toEpochMilli(), start)
        assertEquals(LocalDate.of(2027, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli(), end)
    }

    @Test(expected = IllegalArgumentException::class)
    fun monthBounds_invalidMonth_throws() {
        StatisticsCalculator.monthBounds(2026, 13)
    }

    // ---- helpers ----

    private fun date(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun txn(
        id: Long, title: String, amountMinor: Long, currency: String,
        type: String, categoryId: Long, occurredAt: Long,
    ): TransactionWithCategory {
        val t = TransactionEntity(
            id = id, title = title, amountMinor = amountMinor,
            currencyCode = currency, type = type, categoryId = categoryId,
            occurredAtEpochMillis = occurredAt, createdAtEpochMillis = occurredAt,
        )
        val c = CategoryEntity(id = categoryId, name = "Cat-$categoryId", type = "EXPENSE")
        return TransactionWithCategory(t, c)
    }

    private fun income(
        id: Long, amountMinor: Long, occurredAt: Long,
    ): TransactionWithCategory = TransactionWithCategory(
        TransactionEntity(
            id = id, title = "Salary", amountMinor = amountMinor,
            currencyCode = "USD", type = "INCOME", categoryId = 99L,
            occurredAtEpochMillis = occurredAt, createdAtEpochMillis = occurredAt,
        ),
        CategoryEntity(id = 99L, name = "Salary", type = "INCOME"),
    )

    private val nowMs = LocalDate.of(2026, 6, 17).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    // ---- topCategories ----

    @Test
    fun topCategories_groupsByCategoryAndSortsDesc() {
        val txns = listOf(
            txn(1L, "Coffee", 500L, "USD", "EXPENSE", 1L, date(2026, 6, 5)),
            txn(2L, "Lunch", 1200L, "USD", "EXPENSE", 2L, date(2026, 6, 6)),
            txn(3L, "Bus", 300L, "USD", "EXPENSE", 1L, date(2026, 6, 7)),
        )
        val cats = listOf(
            CategoryEntity(1L, "Food", "EXPENSE"),
            CategoryEntity(2L, "Transit", "EXPENSE"),
        )
        val out = StatisticsCalculator.topCategories(txns, cats, "USD", emptyMap(), nowMs)
        assertEquals(2, out.slices.size)
        assertEquals(2L, out.slices[0].categoryId)
        assertEquals(1200L, out.slices[0].amountMinor)
        assertEquals(1L, out.slices[1].categoryId)
        assertEquals(800L, out.slices[1].amountMinor)
        assertEquals(0, out.missingRateCount)
    }

    @Test
    fun topCategories_topFivePlusOther() {
        val txns = (1L..8L).map { i ->
            txn(i, "X$i", (i * 100L), "USD", "EXPENSE", i, date(2026, 6, 5))
        }
        val cats = (1L..8L).map { CategoryEntity(it, "Cat$it", "EXPENSE") }
        val out = StatisticsCalculator.topCategories(txns, cats, "USD", emptyMap(), nowMs)
        assertEquals(6, out.slices.size)
        // Top 5: 800, 700, 600, 500, 400
        assertEquals(8L, out.slices[0].categoryId)
        assertEquals(800L, out.slices[0].amountMinor)
        assertEquals(4L, out.slices[4].categoryId)
        assertEquals(400L, out.slices[4].amountMinor)
        // Other: 300 + 200 + 100 = 600
        assertEquals(-1L, out.slices[5].categoryId)
        assertEquals("Other", out.slices[5].categoryName)
        assertEquals(600L, out.slices[5].amountMinor)
    }

    @Test
    fun topCategories_topFiveOnlyWhenFewerCategories() {
        val txns = listOf(
            txn(1L, "A", 1000L, "USD", "EXPENSE", 1L, date(2026, 6, 5)),
            txn(2L, "B", 500L, "USD", "EXPENSE", 2L, date(2026, 6, 6)),
        )
        val cats = listOf(
            CategoryEntity(1L, "Food", "EXPENSE"),
            CategoryEntity(2L, "Transit", "EXPENSE"),
        )
        val out = StatisticsCalculator.topCategories(txns, cats, "USD", emptyMap(), nowMs)
        assertEquals(2, out.slices.size)
        assertEquals(1L, out.slices[0].categoryId)
        assertEquals(2L, out.slices[1].categoryId)
    }

    @Test
    fun topCategories_excludesIncome() {
        val txns = listOf(
            income(1L, 5000L, date(2026, 6, 1)),
            txn(2L, "Coffee", 500L, "USD", "EXPENSE", 1L, date(2026, 6, 5)),
        )
        val cats = listOf(CategoryEntity(1L, "Food", "EXPENSE"))
        val out = StatisticsCalculator.topCategories(txns, cats, "USD", emptyMap(), nowMs)
        assertEquals(1, out.slices.size)
        assertEquals(500L, out.slices[0].amountMinor)
    }

    @Test
    fun topCategories_fxConversionToHomeCurrency() {
        val txns = listOf(
            txn(1L, "Coffee", 100_00L, "EUR", "EXPENSE", 1L, date(2026, 6, 5)),
            txn(2L, "Book", 50_00L, "USD", "EXPENSE", 1L, date(2026, 6, 6)),
        )
        val cats = listOf(CategoryEntity(1L, "Food", "EXPENSE"))
        val rates = mapOf("EUR_to_USD" to 1.10)
        val out = StatisticsCalculator.topCategories(txns, cats, "USD", rates, nowMs)
        assertEquals(1, out.slices.size)
        // 10000 * 1.10 = 11000 minor; + 5000 = 16000
        assertEquals(16000L, out.slices[0].amountMinor)
        assertEquals(0, out.missingRateCount)
    }

    @Test
    fun topCategories_missingRateCount() {
        val txns = listOf(
            txn(1L, "Coffee", 100_00L, "EUR", "EXPENSE", 1L, date(2026, 6, 5)),
            txn(2L, "Book", 50_00L, "JPY", "EXPENSE", 1L, date(2026, 6, 6)),
        )
        val cats = listOf(CategoryEntity(1L, "Food", "EXPENSE"))
        val rates = mapOf("EUR_to_USD" to 1.10) // JPY rate missing
        val out = StatisticsCalculator.topCategories(txns, cats, "USD", rates, nowMs)
        assertEquals(1, out.missingRateCount)
    }

    @Test
    fun topCategories_emptyTxns() {
        val cats = listOf(CategoryEntity(1L, "Food", "EXPENSE"))
        val out = StatisticsCalculator.topCategories(emptyList(), cats, "USD", emptyMap(), nowMs)
        assertTrue(out.slices.isEmpty())
        assertEquals(0, out.missingRateCount)
    }

    @Test
    fun topCategories_excludesOutsideCurrentMonth() {
        val txns = listOf(
            txn(1L, "Old", 999L, "USD", "EXPENSE", 1L, date(2026, 5, 31)),
            txn(2L, "New", 500L, "USD", "EXPENSE", 1L, date(2026, 6, 1)),
        )
        val cats = listOf(CategoryEntity(1L, "Food", "EXPENSE"))
        val out = StatisticsCalculator.topCategories(txns, cats, "USD", emptyMap(), nowMs)
        assertEquals(1, out.slices.size)
        assertEquals(500L, out.slices[0].amountMinor)
    }

    // ---- savingsAndAverage ----

    @Test
    fun savingsAndAverage_basicIncomeAndExpense() {
        val txns = listOf(
            income(1L, 500_000L, date(2026, 6, 1)),
            txn(2L, "Coffee", 1_000L, "USD", "EXPENSE", 1L, date(2026, 6, 5)),
            txn(3L, "Lunch", 4_000L, "USD", "EXPENSE", 2L, date(2026, 6, 6)),
        )
        val out = StatisticsCalculator.savingsAndAverage(txns, "USD", emptyMap(), nowMs)
        assertEquals(500_000L, out.incomeMinor)
        assertEquals(5_000L, out.expenseMinor)
        assertEquals(495_000L, out.netMinor)
        // (500000 - 5000) / 500000 = 0.99
        assertEquals(0.99f, out.savingsRate, 0.001f)
        assertEquals(4_000L, out.topTransactionMinor)
    }

    @Test
    fun savingsAndAverage_zeroIncome_returnsZeroRate() {
        val txns = listOf(
            txn(1L, "Coffee", 1_000L, "USD", "EXPENSE", 1L, date(2026, 6, 5)),
        )
        val out = StatisticsCalculator.savingsAndAverage(txns, "USD", emptyMap(), nowMs)
        assertEquals(0f, out.savingsRate, 0.0001f)
    }

    @Test
    fun savingsAndAverage_expenseExceedsIncome_clampsToZero() {
        val txns = listOf(
            income(1L, 100L, date(2026, 6, 1)),
            txn(2L, "Big", 5_000L, "USD", "EXPENSE", 1L, date(2026, 6, 5)),
        )
        val out = StatisticsCalculator.savingsAndAverage(txns, "USD", emptyMap(), nowMs)
        assertEquals(0f, out.savingsRate, 0.0001f)
    }

    @Test
    fun savingsAndAverage_averageOverSixCompletedMonths() {
        // nowMs = Jun 17 2026. Six prior completed months: Dec, Jan, Feb, Mar, Apr, May.
        val priorMonthExpenses = mapOf(
            2025 to listOf(12 to 1000L),                  // Dec 2025
            2026 to listOf(1 to 2000L, 2 to 3000L,        // Jan, Feb, Mar, Apr, May 2026
                            3 to 4000L, 4 to 5000L, 5 to 6000L),
        )
        val txns = mutableListOf<TransactionWithCategory>()
        for ((year, months) in priorMonthExpenses) {
            for ((month, amt) in months) {
                txns += txn(txns.size + 1L, "X", amt, "USD", "EXPENSE", 1L, date(year, month, 15))
            }
        }
        // Plus an unrelated row in current month (Jun 2026) — must be excluded.
        txns += txn(99L, "Jun", 7_777L, "USD", "EXPENSE", 1L, date(2026, 6, 5))
        val out = StatisticsCalculator.savingsAndAverage(txns, "USD", emptyMap(), nowMs)
        // sum = 1000+2000+3000+4000+5000+6000 = 21000; avg = 21000/6 = 3500
        assertEquals(3_500L, out.averageMonthlyExpenseMinor)
        assertEquals(6, out.averageMonthlySampleMonths)
        // Top transaction is the Jun one (current month), not the May one.
        assertEquals(7_777L, out.topTransactionMinor)
    }

    @Test
    fun savingsAndAverage_averageReturnsZeroWhenLessThanThreeMonths() {
        // Only 2 prior completed months have data.
        val txns = listOf(
            txn(1L, "X", 1000L, "USD", "EXPENSE", 1L, date(2026, 4, 15)),
            txn(2L, "X", 2000L, "USD", "EXPENSE", 1L, date(2026, 5, 15)),
        )
        val out = StatisticsCalculator.savingsAndAverage(txns, "USD", emptyMap(), nowMs)
        assertEquals(0L, out.averageMonthlyExpenseMinor)
        assertEquals(2, out.averageMonthlySampleMonths)
    }

    @Test
    fun savingsAndAverage_topTransaction() {
        val txns = listOf(
            txn(1L, "A", 100L, "USD", "EXPENSE", 1L, date(2026, 6, 1)),
            txn(2L, "B", 9_999L, "USD", "EXPENSE", 2L, date(2026, 6, 5)),
            txn(3L, "C", 50L, "USD", "EXPENSE", 3L, date(2026, 6, 6)),
        )
        val out = StatisticsCalculator.savingsAndAverage(txns, "USD", emptyMap(), nowMs)
        assertEquals(9_999L, out.topTransactionMinor)
    }

    @Test
    fun savingsAndAverage_emptyTxns() {
        val out = StatisticsCalculator.savingsAndAverage(emptyList(), "USD", emptyMap(), nowMs)
        assertEquals(0L, out.incomeMinor)
        assertEquals(0L, out.expenseMinor)
        assertEquals(0L, out.netMinor)
        assertEquals(0f, out.savingsRate, 0.0001f)
        assertEquals(0L, out.averageMonthlyExpenseMinor)
        assertEquals(0L, out.topTransactionMinor)
        assertEquals(0, out.averageMonthlySampleMonths)
    }

    // ---- dayOfWeek ----

    @Test
    fun dayOfWeek_alwaysReturnsSevenBuckets() {
        val out = StatisticsCalculator.dayOfWeekPattern(emptyList(), "USD", emptyMap(), nowMs)
        assertEquals(7, out.size)
        // Ordered Mon..Sun
        assertEquals(1, out[0].isoDayOfWeek)
        assertEquals(7, out[6].isoDayOfWeek)
        assertTrue(out.all { it.amountMinor == 0L })
    }

    @Test
    fun dayOfWeek_sumsAcrossMultipleWeeks() {
        // nowMs = Jun 17 2026 (Wednesday). Add expenses on 3 Mondays within 90 days.
        // Mondays before Jun 17 2026 within 90d: Jun 8, Jun 1, May 25.
        val txns = listOf(
            txn(1L, "A", 100L, "USD", "EXPENSE", 1L, date(2026, 6, 8)),   // Mon
            txn(2L, "B", 200L, "USD", "EXPENSE", 1L, date(2026, 6, 1)),   // Mon
            txn(3L, "C", 50L, "USD", "EXPENSE", 2L, date(2026, 6, 3)),    // Wed
        )
        val out = StatisticsCalculator.dayOfWeekPattern(txns, "USD", emptyMap(), nowMs)
        val monday = out.first { it.isoDayOfWeek == 1 }
        val wednesday = out.first { it.isoDayOfWeek == 3 }
        assertEquals(300L, monday.amountMinor)
        assertEquals(50L, wednesday.amountMinor)
    }

    @Test
    fun dayOfWeek_excludesIncome() {
        val txns = listOf(
            income(1L, 5_000L, date(2026, 6, 8)),
        )
        val out = StatisticsCalculator.dayOfWeekPattern(txns, "USD", emptyMap(), nowMs)
        assertTrue(out.all { it.amountMinor == 0L })
    }

    @Test
    fun dayOfWeek_usesHomeCurrency() {
        val txns = listOf(
            txn(1L, "A", 100_00L, "EUR", "EXPENSE", 1L, date(2026, 6, 8)),
        )
        val rates = mapOf("EUR_to_USD" to 1.10)
        val out = StatisticsCalculator.dayOfWeekPattern(txns, "USD", rates, nowMs)
        val monday = out.first { it.isoDayOfWeek == 1 }
        assertEquals(11000L, monday.amountMinor)
    }

    @Test
    fun dayOfWeek_respects90DayWindow() {
        val txns = listOf(
            txn(1L, "Old", 999L, "USD", "EXPENSE", 1L, date(2026, 2, 1)),   // > 90 days before Jun 17
            txn(2L, "New", 500L, "USD", "EXPENSE", 1L, date(2026, 6, 1)),
        )
        val out = StatisticsCalculator.dayOfWeekPattern(txns, "USD", emptyMap(), nowMs)
        assertTrue(out.all { it.amountMinor == 500L || it.amountMinor == 0L })
    }

    // ---- yearOverYear ----

    @Test
    fun yearOverYear_basicPercentChange() {
        val txns = listOf(
            txn(1L, "A", 800L, "USD", "EXPENSE", 1L, date(2025, 6, 10)),
            txn(2L, "B", 1_000L, "USD", "EXPENSE", 2L, date(2026, 6, 10)),
        )
        val out = StatisticsCalculator.yearOverYear(txns, "USD", emptyMap(), nowMs)
        assertEquals("June 2026", out.currentMonthLabel)
        assertEquals("June 2025", out.previousMonthLabel)
        assertEquals(1_000L, out.currentExpenseMinor)
        assertEquals(800L, out.previousExpenseMinor)
        assertEquals(0.25f, out.percentChange, 0.001f)  // (1000-800)/800 = 0.25
        assertEquals(false, out.isNewSpending)
    }

    @Test
    fun yearOverYear_previousIsZero_marksNewSpending() {
        val txns = listOf(
            txn(1L, "A", 500L, "USD", "EXPENSE", 1L, date(2026, 6, 10)),
        )
        val out = StatisticsCalculator.yearOverYear(txns, "USD", emptyMap(), nowMs)
        assertEquals(500L, out.currentExpenseMinor)
        assertEquals(0L, out.previousExpenseMinor)
        assertEquals(0f, out.percentChange, 0.0001f)
        assertEquals(true, out.isNewSpending)
    }

    @Test
    fun yearOverYear_bothZero() {
        val out = StatisticsCalculator.yearOverYear(emptyList(), "USD", emptyMap(), nowMs)
        assertEquals(0L, out.currentExpenseMinor)
        assertEquals(0L, out.previousExpenseMinor)
        assertEquals(0f, out.percentChange, 0.0001f)
        assertEquals(false, out.isNewSpending)
    }

    @Test
    fun yearOverYear_calendarBoundary() {
        // nowMs = Jun 17 2026; previous = Jun 1 - Jun 30 2025.
        val txns = listOf(
            txn(1L, "May", 999L, "USD", "EXPENSE", 1L, date(2025, 5, 31)),     // boundary: May, not Jun
            txn(2L, "Jul", 999L, "USD", "EXPENSE", 1L, date(2025, 7, 1)),      // boundary: Jul, not Jun
            txn(3L, "Jun",  500L, "USD", "EXPENSE", 1L, date(2025, 6, 15)),
            txn(4L, "Jun", 1_000L, "USD", "EXPENSE", 1L, date(2026, 6, 10)),
        )
        val out = StatisticsCalculator.yearOverYear(txns, "USD", emptyMap(), nowMs)
        assertEquals(500L, out.previousExpenseMinor)
        assertEquals(1_000L, out.currentExpenseMinor)
    }

    @Test
    fun yearOverYear_excludesIncome() {
        val txns = listOf(
            income(1L, 5_000L, date(2025, 6, 10)),
            income(2L, 5_000L, date(2026, 6, 10)),
        )
        val out = StatisticsCalculator.yearOverYear(txns, "USD", emptyMap(), nowMs)
        assertEquals(0L, out.currentExpenseMinor)
        assertEquals(0L, out.previousExpenseMinor)
    }

    @Test
    fun yearOverYear_usesHomeCurrency() {
        val txns = listOf(
            txn(1L, "A", 800_00L, "EUR", "EXPENSE", 1L, date(2025, 6, 10)),
            txn(2L, "B", 1_000_00L, "EUR", "EXPENSE", 2L, date(2026, 6, 10)),
        )
        val rates = mapOf("EUR_to_USD" to 1.10)
        val out = StatisticsCalculator.yearOverYear(txns, "USD", rates, nowMs)
        // 80000 * 1.10 = 88000; 100000 * 1.10 = 110000
        assertEquals(110_000L, out.currentExpenseMinor)
        assertEquals(88_000L, out.previousExpenseMinor)
        // (110000 - 88000) / 88000 = 0.25
        assertEquals(0.25f, out.percentChange, 0.001f)
    }

    // ---- rangeLabel ----

    @Test
    fun rangeLabel_sameMonth_returnsMonthYear() {
        val start = date(2026, 6, 1)
        val end = date(2026, 7, 1)
        assertEquals("June 2026", StatisticsCalculator.rangeLabel(start, end))
    }

    @Test
    fun rangeLabel_crossMonthSameYear_returnsStartEndYear() {
        // endMs is exclusive — pass the day after the last calendar day in the window.
        val start = date(2026, 1, 15)
        val end = date(2026, 2, 15)
        // "Jan 15 – Feb 14, 2026"  (en dash U+2013, not hyphen)
        assertEquals("Jan 15 – Feb 14, 2026", StatisticsCalculator.rangeLabel(start, end))
    }

    @Test
    fun rangeLabel_crossYear_returnsFullDates() {
        val start = date(2025, 12, 28)
        val end = date(2026, 1, 5)
        assertEquals("Dec 28, 2025 – Jan 4, 2026", StatisticsCalculator.rangeLabel(start, end))
    }
}
