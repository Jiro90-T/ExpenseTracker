package io.github.jiro.expensetracker.ui.home

import io.github.jiro.expensetracker.data.local.BudgetEntity
import io.github.jiro.expensetracker.data.local.CategoryEntity
import io.github.jiro.expensetracker.data.local.MoneyFormat
import io.github.jiro.expensetracker.data.local.TransactionEntity
import io.github.jiro.expensetracker.data.local.TransactionWithCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class BudgetAlertsTest {

    @Test
    fun computeBudgetAlerts_emptyBudgets_returnsEmpty() {
        val out = computeBudgetAlerts(
            budgets = emptyList(),
            spentByCategory = emptyMap(),
            homeCurrency = "USD",
            fxRates = emptyMap(),
            nowMs = monthStart(2026, 6),
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun computeBudgetAlerts_spentUnderBudget_noAlert() {
        val out = computeBudgetAlerts(
            budgets = listOf(budget(categoryId = 1L, monthStart = monthStart(2026, 6), amount = 10_000L)),
            spentByCategory = mapOf(1L to 8_000L),
            homeCurrency = "USD",
            fxRates = emptyMap(),
            nowMs = monthStart(2026, 6),
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun computeBudgetAlerts_spentEqualToBudget_noAlert() {
        val out = computeBudgetAlerts(
            budgets = listOf(budget(categoryId = 1L, monthStart = monthStart(2026, 6), amount = 10_000L)),
            spentByCategory = mapOf(1L to 10_000L),
            homeCurrency = "USD",
            fxRates = emptyMap(),
            nowMs = monthStart(2026, 6),
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun computeBudgetAlerts_spentOverBudget_oneAlert() {
        val out = computeBudgetAlerts(
            budgets = listOf(budget(categoryId = 1L, monthStart = monthStart(2026, 6), amount = 10_000L)),
            spentByCategory = mapOf(1L to 15_000L),
            homeCurrency = "USD",
            fxRates = emptyMap(),
            nowMs = monthStart(2026, 6),
        )
        assertEquals(1, out.size)
        val alert = out.first()
        assertEquals(1L, alert.categoryId)
        assertEquals(10_000L, alert.budgetMinor)
        assertEquals(15_000L, alert.spentMinor)
        assertEquals(5_000L, alert.overageMinor)
    }

    @Test
    fun computeBudgetAlerts_multipleOverspent_sortedByOverageDesc() {
        val out = computeBudgetAlerts(
            budgets = listOf(
                budget(categoryId = 1L, monthStart = monthStart(2026, 6), amount = 10_000L),  // over by 1,000
                budget(categoryId = 2L, monthStart = monthStart(2026, 6), amount = 10_000L),  // over by 5,000
            ),
            spentByCategory = mapOf(1L to 11_000L, 2L to 15_000L),
            homeCurrency = "USD",
            fxRates = emptyMap(),
            nowMs = monthStart(2026, 6),
        )
        assertEquals(2, out.size)
        // The one with the larger overage (2: 5000) is first.
        assertEquals(2L, out[0].categoryId)
        assertEquals(1L, out[1].categoryId)
    }

    @Test
    fun computeBudgetAlerts_overageAmountIsSpentMinusBudget() {
        val out = computeBudgetAlerts(
            budgets = listOf(budget(categoryId = 1L, monthStart = monthStart(2026, 6), amount = 7_500L)),
            spentByCategory = mapOf(1L to 12_345L),
            homeCurrency = "USD",
            fxRates = emptyMap(),
            nowMs = monthStart(2026, 6),
        )
        assertEquals(4_845L, out.first().overageMinor)
    }

    @Test
    fun computeBudgetAlerts_mixedSomeSomeNot_filtersCorrectly() {
        val out = computeBudgetAlerts(
            budgets = listOf(
                budget(categoryId = 1L, monthStart = monthStart(2026, 6), amount = 10_000L),  // spent 5,000 → under
                budget(categoryId = 2L, monthStart = monthStart(2026, 6), amount = 10_000L),  // spent 12,000 → over
                budget(categoryId = 3L, monthStart = monthStart(2026, 6), amount = 10_000L),  // no spend → no alert
            ),
            spentByCategory = mapOf(1L to 5_000L, 2L to 12_000L),
            homeCurrency = "USD",
            fxRates = emptyMap(),
            nowMs = monthStart(2026, 6),
        )
        assertEquals(1, out.size)
        assertEquals(2L, out.first().categoryId)
    }

    @Test
    fun computeBudgetAlerts_overageFormattedIsCorrectCurrencyString() {
        val out = computeBudgetAlerts(
            budgets = listOf(budget(categoryId = 1L, monthStart = monthStart(2026, 6), amount = 10_000L)),
            spentByCategory = mapOf(1L to 12_500L),
            homeCurrency = "USD",
            fxRates = emptyMap(),
            nowMs = monthStart(2026, 6),
        )
        assertEquals(MoneyFormat.formatAmountForEdit(2_500L), out.first().overageFormatted)
    }

    @Test
    fun computeBudgetAlerts_noBudgetForCategoryInSpentMap_noAlert() {
        val out = computeBudgetAlerts(
            budgets = listOf(budget(categoryId = 1L, monthStart = monthStart(2026, 6), amount = 10_000L)),
            spentByCategory = mapOf(2L to 50_000L),  // spent in cat 2, but no budget for cat 2
            homeCurrency = "USD",
            fxRates = emptyMap(),
            nowMs = monthStart(2026, 6),
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun computeBudgetAlerts_purityRepeatedCalls() {
        val a = computeBudgetAlerts(
            budgets = listOf(budget(categoryId = 1L, monthStart = monthStart(2026, 6), amount = 10_000L)),
            spentByCategory = mapOf(1L to 12_000L),
            homeCurrency = "USD",
            fxRates = emptyMap(),
            nowMs = monthStart(2026, 6),
        )
        val b = computeBudgetAlerts(
            budgets = listOf(budget(categoryId = 1L, monthStart = monthStart(2026, 6), amount = 10_000L)),
            spentByCategory = mapOf(1L to 12_000L),
            homeCurrency = "USD",
            fxRates = emptyMap(),
            nowMs = monthStart(2026, 6),
        )
        assertEquals(a, b)
    }

    // ---- computeSpentByCategory tests ----

    @Test
    fun computeSpentByCategory_onlyCountsExpenses() {
        val rows = listOf(
            txn(id = 1L, type = "EXPENSE", amountMinor = 5_000L, categoryId = 1L),
            txn(id = 2L, type = "INCOME", amountMinor = 100_000L, categoryId = 1L),
            txn(id = 3L, type = "EXPENSE", amountMinor = 3_000L, categoryId = 2L),
        )
        val out = computeSpentByCategory(rows, "USD", emptyMap())
        assertEquals(5_000L, out[1L])
        assertEquals(3_000L, out[2L])
    }

    @Test
    fun computeSpentByCategory_sumsMultipleRowsSameCategory() {
        val rows = listOf(
            txn(id = 1L, type = "EXPENSE", amountMinor = 1_000L, categoryId = 1L),
            txn(id = 2L, type = "EXPENSE", amountMinor = 2_500L, categoryId = 1L),
            txn(id = 3L, type = "EXPENSE", amountMinor = 500L, categoryId = 1L),
        )
        val out = computeSpentByCategory(rows, "USD", emptyMap())
        assertEquals(4_000L, out[1L])
    }

    // ---- helpers ----

    private fun budget(
        categoryId: Long,
        monthStart: Long,
        amount: Long,
    ): BudgetEntity = BudgetEntity(
        categoryId = categoryId,
        monthStartEpochMs = monthStart,
        amountMinor = amount,
    )

    private fun txn(
        id: Long,
        type: String,
        amountMinor: Long,
        categoryId: Long,
    ): TransactionWithCategory {
        val t = TransactionEntity(
            id = id,
            title = "t",
            amountMinor = amountMinor,
            currencyCode = "USD",
            type = type,
            categoryId = categoryId,
            occurredAtEpochMillis = monthStart(2026, 6),
            note = null,
            createdAtEpochMillis = monthStart(2026, 6),
        )
        val c = CategoryEntity(id = categoryId, name = "C$categoryId", type = type, sortOrder = 0, isBuiltIn = true)
        return TransactionWithCategory(t, c)
    }

    private fun monthStart(year: Int, month: Int): Long {
        // Use the local timezone (matching production `startOfMonth`) so the
        // budget's `monthStartEpochMs` and `nowMs` round-trip through
        // `computeBudgetAlerts` correctly. UTC is not safe here because the
        // production filter uses local TZ; in any non-UTC host the two
        // `Calendar.clear()` / `set(...)` results differ by the TZ offset.
        val cal = Calendar.getInstance()
        cal.clear()
        cal.set(year, month - 1, 1, 0, 0, 0)
        return cal.timeInMillis
    }
}
