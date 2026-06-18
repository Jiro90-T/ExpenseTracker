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

    @Test
    fun monthLabel_june() {
        val ms = LocalDate.of(2026, 6, 17).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals("June 2026", StatisticsCalculator.monthLabel(ms))
    }

    @Test
    fun monthLabel_january() {
        val ms = LocalDate.of(2026, 1, 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals("January 2026", StatisticsCalculator.monthLabel(ms))
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
}
