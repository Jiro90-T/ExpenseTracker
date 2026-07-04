package io.github.jiro.expensetracker.ui.statistics

import io.github.jiro.expensetracker.data.local.CategoryEntity
import io.github.jiro.expensetracker.data.local.TransactionEntity
import io.github.jiro.expensetracker.data.local.TransactionWithCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class InsightsCalculatorTest {

    // ---- helpers (mirror StatisticsCalculatorTest) ----

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
        val c = CategoryEntity(id = categoryId, name = "Cat-$categoryId", type = type)
        return TransactionWithCategory(t, c)
    }

    private val nowMs: Long = date(2026, 6, 17)
    private val currentStart = StatisticsCalculator.monthBounds(2026, 6).first
    private val currentEnd = StatisticsCalculator.monthBounds(2026, 6).second
    private val priorStart = StatisticsCalculator.monthBounds(2026, 5).first
    private val priorEnd = StatisticsCalculator.monthBounds(2026, 5).second

    // ---- compute() ----

    @Test
    fun compute_returnsFourInsights_forTypicalUserWithTwoMonths() {
        val cats = listOf(
            CategoryEntity(1L, "Food", "EXPENSE"),
            CategoryEntity(2L, "Transit", "EXPENSE"),
        )
        val txns = listOf(
            // Current month — Food higher than last month
            txn(1, "Groceries", 1500L, "USD", "EXPENSE", 1L, date(2026, 6, 3)),
            txn(2, "Coffee", 500L, "USD", "EXPENSE", 1L, date(2026, 6, 5)),
            txn(3, "Bus", 300L, "USD", "EXPENSE", 2L, date(2026, 6, 7)),
            txn(4, "Salary", 5000L, "USD", "INCOME", 99L, date(2026, 6, 1)),
            // Prior month — Food lower, Transit higher
            txn(5, "Groceries", 1000L, "USD", "EXPENSE", 1L, date(2026, 5, 3)),
            txn(6, "Bus", 500L, "USD", "EXPENSE", 2L, date(2026, 5, 7)),
            txn(7, "Salary", 5000L, "USD", "INCOME", 99L, date(2026, 5, 1)),
        )
        val insights = InsightsCalculator.compute(txns, cats, "USD", emptyMap(), nowMs)
        // All four insight types present, ordered by priority ascending.
        assertEquals(4, insights.size)
        assertTrue(insights[0] is Insight.CategoryDelta)
        assertTrue(insights[1] is Insight.WeekendVsWeekday)
        assertTrue(insights[2] is Insight.SavingsTrend)
        assertTrue(insights[3] is Insight.TopExpenseSpotlight)
    }

    @Test
    fun compute_categoryDelta_picksLargestAbsoluteDelta() {
        val cats = listOf(
            CategoryEntity(1L, "Food", "EXPENSE"),
            CategoryEntity(2L, "Transit", "EXPENSE"),
            CategoryEntity(3L, "Coffee", "EXPENSE"),
        )
        val txns = listOf(
            // Current month
            txn(1, "Food", 2000L, "USD", "EXPENSE", 1L, date(2026, 6, 3)),
            txn(2, "Transit", 1500L, "USD", "EXPENSE", 2L, date(2026, 6, 7)),
            txn(3, "Coffee", 600L, "USD", "EXPENSE", 3L, date(2026, 6, 8)),
            // Prior month — biggest delta is Food (+1000) vs Transit (+200) vs Coffee (+500)
            txn(4, "Food", 1000L, "USD", "EXPENSE", 1L, date(2026, 5, 3)),
            txn(5, "Transit", 1300L, "USD", "EXPENSE", 2L, date(2026, 5, 7)),
            txn(6, "Coffee", 100L, "USD", "EXPENSE", 3L, date(2026, 5, 8)),
        )
        val insights = InsightsCalculator.compute(txns, cats, "USD", emptyMap(), nowMs)
        val cd = insights.filterIsInstance<Insight.CategoryDelta>().single()
        assertEquals("Food", cd.categoryName)
        assertEquals(Insight.Direction.UP, cd.direction)
        assertEquals(2000L, cd.currentMinor)
        assertEquals(1000L, cd.previousMinor)
        // 100% increase (2000 - 1000) / 1000 = 1.0
        assertEquals(1.0f, cd.percentChange, 0.001f)
    }

    @Test
    fun compute_categoryDelta_picksDirectionUpDownNew() {
        val cats = listOf(
            CategoryEntity(1L, "Up", "EXPENSE"),
            CategoryEntity(2L, "Down", "EXPENSE"),
            CategoryEntity(3L, "New", "EXPENSE"),
        )
        val txns = listOf(
            txn(1, "Up-now", 2000L, "USD", "EXPENSE", 1L, date(2026, 6, 3)),
            txn(2, "Up-then", 1000L, "USD", "EXPENSE", 1L, date(2026, 5, 3)),
            txn(3, "Down-now", 500L, "USD", "EXPENSE", 2L, date(2026, 6, 3)),
            txn(4, "Down-then", 1500L, "USD", "EXPENSE", 2L, date(2026, 5, 3)),
            txn(5, "New-now", 800L, "USD", "EXPENSE", 3L, date(2026, 6, 3)),
            // No prior-month row for category 3 → NEW
        )
        val insights = InsightsCalculator.compute(txns, cats, "USD", emptyMap(), nowMs)
        val cd = insights.filterIsInstance<Insight.CategoryDelta>().single()
        // The largest |delta| is Down at 1000 (1500→500) vs Up at 1000 (1000→2000). Tie-break by largest current → Up wins.
        assertEquals("Up", cd.categoryName)
        assertEquals(Insight.Direction.UP, cd.direction)
    }

    @Test
    fun compute_categoryDelta_marksNewWhenPriorIsZero() {
        val cats = listOf(CategoryEntity(1L, "New", "EXPENSE"))
        val txns = listOf(
            txn(1, "Only-this-month", 500L, "USD", "EXPENSE", 1L, date(2026, 6, 3)),
        )
        val insights = InsightsCalculator.compute(txns, cats, "USD", emptyMap(), nowMs)
        val cd = insights.filterIsInstance<Insight.CategoryDelta>().single()
        assertEquals(Insight.Direction.NEW, cd.direction)
        assertEquals(0f, cd.percentChange, 0.001f)
        assertEquals(500L, cd.currentMinor)
        assertEquals(0L, cd.previousMinor)
    }

    @Test
    fun compute_categoryDelta_skipsWhenOnlyOneMonthOfData() {
        val cats = listOf(CategoryEntity(1L, "Food", "EXPENSE"))
        // Only current-month data
        val txns = listOf(
            txn(1, "Food", 500L, "USD", "EXPENSE", 1L, date(2026, 6, 3)),
        )
        val insights = InsightsCalculator.compute(txns, cats, "USD", emptyMap(), nowMs)
        assertTrue(insights.filterIsInstance<Insight.CategoryDelta>().isEmpty())
    }

    @Test
    fun compute_categoryDelta_excludesIncome() {
        val cats = listOf(
            CategoryEntity(1L, "Food", "EXPENSE"),
            CategoryEntity(99L, "Salary", "INCOME"),
        )
        val txns = listOf(
            txn(1, "Salary-now", 5000L, "USD", "INCOME", 99L, date(2026, 6, 1)),
            txn(2, "Salary-then", 1000L, "USD", "INCOME", 99L, date(2026, 5, 1)),
            // Food: same in both months, no delta
            txn(3, "Food-now", 500L, "USD", "EXPENSE", 1L, date(2026, 6, 3)),
            txn(4, "Food-then", 500L, "USD", "EXPENSE", 1L, date(2026, 5, 3)),
        )
        val insights = InsightsCalculator.compute(txns, cats, "USD", emptyMap(), nowMs)
        // Salary delta should NOT appear (income is excluded from CategoryDelta).
        val cd = insights.filterIsInstance<Insight.CategoryDelta>()
        assertTrue(cd.isEmpty() || cd.single().categoryName == "Food")
    }

    @Test
    fun compute_weekendVsWeekday_uses90DayRollingWindow() {
        val cats = listOf(CategoryEntity(1L, "Food", "EXPENSE"))
        // nowMs = 2026-06-17 (Wednesday). 90 days back = 2026-03-19 (Thursday).
        // 2026-04-04 (Saturday) is inside the window. 2026-01-10 is outside.
        val txns = listOf(
            txn(1, "Weekend", 2000L, "USD", "EXPENSE", 1L, date(2026, 4, 4)), // Saturday
            txn(2, "Weekend-2", 500L, "USD", "EXPENSE", 1L, date(2026, 4, 5)), // Sunday
            txn(3, "Weekday", 1000L, "USD", "EXPENSE", 1L, date(2026, 4, 6)), // Monday
            txn(4, "Old", 9999L, "USD", "EXPENSE", 1L, date(2026, 1, 10)),    // outside window
        )
        val insights = InsightsCalculator.compute(txns, cats, "USD", emptyMap(), nowMs)
        val wv = insights.filterIsInstance<Insight.WeekendVsWeekday>().single()
        assertEquals(2500L, wv.weekendMinor)    // 2000 + 500
        assertEquals(1000L, wv.weekdayMinor)
        // 2500 / 3500 = 0.7143
        assertEquals(0.7143f, wv.weekendPercent, 0.001f)
    }

    @Test
    fun compute_weekendVsWeekday_returnsNullWhenAllZero() {
        val cats = listOf(CategoryEntity(1L, "Food", "EXPENSE"))
        // No expenses at all in the 90-day window
        val insights = InsightsCalculator.compute(emptyList(), cats, "USD", emptyMap(), nowMs)
        assertTrue(insights.filterIsInstance<Insight.WeekendVsWeekday>().isEmpty())
    }

    @Test
    fun compute_savingsTrend_undefinedWhenBothMonthsHaveZeroIncome() {
        val cats = listOf(CategoryEntity(1L, "Food", "EXPENSE"))
        // No income, only expenses — savings rate undefined for both months.
        val txns = listOf(
            txn(1, "Food", 500L, "USD", "EXPENSE", 1L, date(2026, 6, 3)),
            txn(2, "Food", 500L, "USD", "EXPENSE", 1L, date(2026, 5, 3)),
        )
        val insights = InsightsCalculator.compute(txns, cats, "USD", emptyMap(), nowMs)
        assertTrue(insights.filterIsInstance<Insight.SavingsTrend>().isEmpty())
    }

    @Test
    fun compute_savingsTrend_marksUnchangedWhenWithinEpsilon() {
        val cats = listOf(CategoryEntity(1L, "Food", "EXPENSE"))
        // Both months: income 1000, expense 500 → rate 0.5f
        val txns = listOf(
            txn(1, "Salary", 1000L, "USD", "INCOME", 99L, date(2026, 6, 1)),
            txn(2, "Food", 500L, "USD", "EXPENSE", 1L, date(2026, 6, 3)),
            txn(3, "Salary", 1000L, "USD", "INCOME", 99L, date(2026, 5, 1)),
            txn(4, "Food", 500L, "USD", "EXPENSE", 1L, date(2026, 5, 3)),
        )
        val insights = InsightsCalculator.compute(txns, cats, "USD", emptyMap(), nowMs)
        val st = insights.filterIsInstance<Insight.SavingsTrend>().single()
        assertEquals(0.5f, st.currentRate, 0.001f)
        assertEquals(0.5f, st.previousRate, 0.001f)
        assertEquals(Insight.Direction.UNCHANGED, st.direction)
    }

    @Test
    fun compute_topExpenseSpotlight_picksLargestExpense() {
        val cats = listOf(CategoryEntity(1L, "Food", "EXPENSE"))
        val txns = listOf(
            txn(1, "Groceries", 500L, "USD", "EXPENSE", 1L, date(2026, 6, 3)),
            txn(2, "Dinner-out", 2500L, "USD", "EXPENSE", 1L, date(2026, 6, 10)),
            txn(3, "Coffee", 300L, "USD", "EXPENSE", 1L, date(2026, 6, 11)),
        )
        val insights = InsightsCalculator.compute(txns, cats, "USD", emptyMap(), nowMs)
        val te = insights.filterIsInstance<Insight.TopExpenseSpotlight>().single()
        assertEquals(2500L, te.amountMinor)
        assertEquals("Dinner-out", te.title)
        assertEquals("Jun 10", te.dateLabel)
    }

    @Test
    fun compute_topExpenseSpotlight_usesNativeCurrencyNotHome() {
        val cats = listOf(CategoryEntity(1L, "Food", "EXPENSE"))
        // MYR expense, USD home — no FX rate. The amount is shown as-is in MYR
        // (native currency), NOT converted to USD.
        val txns = listOf(
            txn(1, "Lunch-MY", 4500L, "MYR", "EXPENSE", 1L, date(2026, 6, 5)),
        )
        val insights = InsightsCalculator.compute(txns, cats, "USD", emptyMap(), nowMs)
        val te = insights.filterIsInstance<Insight.TopExpenseSpotlight>().single()
        assertEquals("MYR", te.currencyCode)
        assertEquals(4500L, te.amountMinor)
    }

    @Test
    fun compute_topExpenseSpotlight_returnsNullWhenNoExpenses() {
        val cats = listOf(CategoryEntity(99L, "Salary", "INCOME"))
        val txns = listOf(
            txn(1, "Salary", 5000L, "USD", "INCOME", 99L, date(2026, 6, 1)),
        )
        val insights = InsightsCalculator.compute(txns, cats, "USD", emptyMap(), nowMs)
        assertTrue(insights.filterIsInstance<Insight.TopExpenseSpotlight>().isEmpty())
    }

    @Test
    fun compute_fallsBackToRawAmountWhenFxRateMissing() {
        val cats = listOf(CategoryEntity(1L, "Food", "EXPENSE"))
        // MYR expense, USD home, no rate. CategoryDelta should still pick it
        // up using the raw amountMinor (no crash, no missingRateCount tracking
        // for insights — just fallback per Phase 2.13 calculator policy).
        val txns = listOf(
            txn(1, "Now-MY", 4500L, "MYR", "EXPENSE", 1L, date(2026, 6, 3)),
            txn(2, "Then-MY", 1500L, "MYR", "EXPENSE", 1L, date(2026, 5, 3)),
        )
        val insights = InsightsCalculator.compute(txns, cats, "USD", emptyMap(), nowMs)
        val cd = insights.filterIsInstance<Insight.CategoryDelta>().single()
        assertEquals(4500L, cd.currentMinor)
        assertEquals(1500L, cd.previousMinor)
    }

    @Test
    fun compute_returnsEmptyListWhenNoTransactionsAtAll() {
        val cats = listOf(CategoryEntity(1L, "Food", "EXPENSE"))
        val insights = InsightsCalculator.compute(emptyList(), cats, "USD", emptyMap(), nowMs)
        assertEquals(emptyList<Insight>(), insights)
    }
}
