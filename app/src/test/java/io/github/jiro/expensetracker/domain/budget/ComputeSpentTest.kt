package io.github.jiro.expensetracker.domain.budget

import io.github.jiro.expensetracker.data.local.CategoryEntity
import io.github.jiro.expensetracker.data.local.TransactionEntity
import io.github.jiro.expensetracker.data.local.TransactionWithCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputeSpentTest {

    @Test
    fun emptyList_returnsEmptySummary() {
        val s = computeSpentByCategory(emptyList(), monthBoundsUtcJune2026(), "USD", emptyMap())
        assertTrue(s.byCategoryMinor.isEmpty())
        assertEquals(0, s.missingRateCount)
    }

    @Test
    fun incomeRows_areIgnored() {
        val rows = listOf(
            tx(amountMinor = 1_000_000, type = "INCOME", categoryId = 1, categoryName = "Salary"),
            tx(amountMinor = 500, type = "EXPENSE", categoryId = 2, categoryName = "Food"),
        )
        val s = computeSpentByCategory(rows, monthBoundsUtcJune2026(), "USD", emptyMap())
        assertEquals(1, s.byCategoryMinor.size)
        assertEquals(500L, s.byCategoryMinor[2])
        assertEquals(0, s.missingRateCount)
    }

    @Test
    fun multipleExpenses_sameCategory_areSummed() {
        val rows = listOf(
            tx(amountMinor = 100, type = "EXPENSE", categoryId = 1, categoryName = "Food"),
            tx(amountMinor = 250, type = "EXPENSE", categoryId = 1, categoryName = "Food"),
            tx(amountMinor = 50, type = "EXPENSE", categoryId = 2, categoryName = "Transport"),
        )
        val s = computeSpentByCategory(rows, monthBoundsUtcJune2026(), "USD", emptyMap())
        assertEquals(350L, s.byCategoryMinor[1])
        assertEquals(50L, s.byCategoryMinor[2])
        assertEquals(0, s.missingRateCount)
    }

    @Test
    fun transactionsOutsideRange_areIgnored() {
        val june = monthBoundsUtcJune2026()
        val rows = listOf(
            // May — should be ignored
            tx(amountMinor = 999, type = "EXPENSE", categoryId = 1, categoryName = "Food",
                occurredAt = utcMs(2026, 5, 31, 23, 59, 59)),
            // July — should be ignored
            tx(amountMinor = 888, type = "EXPENSE", categoryId = 1, categoryName = "Food",
                occurredAt = utcMs(2026, 7, 1, 0, 0, 0)),
            // June — counted
            tx(amountMinor = 200, type = "EXPENSE", categoryId = 1, categoryName = "Food",
                occurredAt = utcMs(2026, 6, 15, 12, 0, 0)),
        )
        val s = computeSpentByCategory(rows, june, "USD", emptyMap())
        assertEquals(200L, s.byCategoryMinor[1])
    }

    @Test
    fun foreignCurrency_isConvertedUsingProvidedRate() {
        val rows = listOf(
            // EUR 1000 minor = €10.00; rate EUR→USD = 1.10 → $11.00 = 1100 minor
            tx(amountMinor = 1_000, type = "EXPENSE", categoryId = 1, categoryName = "Food",
                currency = "EUR"),
        )
        val rates = mapOf("EUR_to_USD" to 1.10)
        val s = computeSpentByCategory(rows, monthBoundsUtcJune2026(), "USD", rates)
        // 1000 * 1.10 = 1100 (Math.round)
        assertEquals(1_100L, s.byCategoryMinor[1])
        assertEquals(0, s.missingRateCount)
    }

    @Test
    fun missingRate_fallsBackToOneToOneAndIncrementsCounter() {
        val rows = listOf(
            tx(amountMinor = 1_234, type = "EXPENSE", categoryId = 1, categoryName = "Food",
                currency = "XYZ"),
        )
        val s = computeSpentByCategory(rows, monthBoundsUtcJune2026(), "USD", emptyMap())
        assertEquals(1_234L, s.byCategoryMinor[1])
        assertEquals(1, s.missingRateCount)
    }

    @Test
    fun sameCurrencyAsHome_isNotConverted() {
        val rows = listOf(
            tx(amountMinor = 5_000, type = "EXPENSE", categoryId = 1, categoryName = "Food",
                currency = "USD"),
        )
        val s = computeSpentByCategory(rows, monthBoundsUtcJune2026(), "USD", emptyMap())
        assertEquals(5_000L, s.byCategoryMinor[1])
        assertEquals(0, s.missingRateCount)
    }

    // ---- helpers ----

    private fun monthBoundsUtcJune2026(): LongRange {
        val start = utcMs(2026, 6, 1, 0, 0, 0)
        val end = utcMs(2026, 7, 1, 0, 0, 0)
        return start until end
    }

    private fun utcMs(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(year, month - 1, day, hour, minute, second)
        return cal.timeInMillis
    }

    private fun tx(
        amountMinor: Long,
        type: String,
        categoryId: Long,
        categoryName: String,
        currency: String = "USD",
        occurredAt: Long = utcMs(2026, 6, 15, 12, 0, 0),
    ): TransactionWithCategory {
        val txn = TransactionEntity(
            id = 0L,
            title = "t",
            amountMinor = amountMinor,
            currencyCode = currency,
            type = type,
            categoryId = categoryId,
            occurredAtEpochMillis = occurredAt,
            note = null,
            createdAtEpochMillis = occurredAt,
        )
        val cat = CategoryEntity(
            id = categoryId,
            name = categoryName,
            type = type,
            sortOrder = 0,
            isBuiltIn = true,
        )
        return TransactionWithCategory(txn, cat)
    }
}
