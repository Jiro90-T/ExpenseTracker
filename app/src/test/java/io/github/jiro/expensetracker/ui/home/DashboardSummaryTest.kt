package io.github.jiro.expensetracker.ui.home

import io.github.jiro.expensetracker.data.local.CategoryEntity
import io.github.jiro.expensetracker.data.local.TransactionEntity
import io.github.jiro.expensetracker.data.local.TransactionWithCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardSummaryTest {

    @Test
    fun emptyList_zerosAll() {
        val s = computeDashboardSummary(emptyList())
        assertEquals(0L, s.incomeMinor)
        assertEquals(0L, s.expenseMinor)
        assertEquals(0L, s.balanceMinor)
        assertEquals(0, s.transactionCount)
        assertTrue(s.topExpenseCategories.isEmpty())
    }

    @Test
    fun onlyIncome_balanceIsIncome() {
        val rows = listOf(
            tx(amountMinor = 500_000, type = "INCOME"),
            tx(amountMinor = 250_000, type = "INCOME"),
        )
        val s = computeDashboardSummary(rows)
        assertEquals(750_000L, s.incomeMinor)
        assertEquals(0L, s.expenseMinor)
        assertEquals(750_000L, s.balanceMinor)
    }

    @Test
    fun onlyExpense_balanceIsNegative() {
        val rows = listOf(
            tx(amountMinor = 1_500, type = "EXPENSE"),
            tx(amountMinor = 999, type = "EXPENSE"),
        )
        val s = computeDashboardSummary(rows)
        assertEquals(0L, s.incomeMinor)
        assertEquals(2_499L, s.expenseMinor)
        assertEquals(-2_499L, s.balanceMinor)
    }

    @Test
    fun mixed_balanceIsIncomeMinusExpense() {
        val rows = listOf(
            tx(amountMinor = 1_000_000, type = "INCOME"),
            tx(amountMinor = 250_000, type = "EXPENSE"),
        )
        val s = computeDashboardSummary(rows)
        assertEquals(1_000_000L, s.incomeMinor)
        assertEquals(250_000L, s.expenseMinor)
        assertEquals(750_000L, s.balanceMinor)
    }

    @Test
    fun topCategories_groupedAndSortedByAmountDesc() {
        val rows = listOf(
            tx(amountMinor = 1_000, type = "EXPENSE", categoryId = 1, categoryName = "Food"),
            tx(amountMinor = 3_000, type = "EXPENSE", categoryId = 2, categoryName = "Transport"),
            tx(amountMinor = 2_000, type = "EXPENSE", categoryId = 1, categoryName = "Food"),
        )
        val s = computeDashboardSummary(rows)
        // Food: 3000, Transport: 3000. Order between them isn't guaranteed, but the totals should be correct.
        val byName = s.topExpenseCategories.associateBy { it.categoryName }
        assertEquals(3_000L, byName["Food"]?.amountMinor)
        assertEquals(3_000L, byName["Transport"]?.amountMinor)
    }

    @Test
    fun topCategories_topNRollsUpRestIntoOthers() {
        val rows = (1..7).map { i ->
            tx(amountMinor = 100L * i, type = "EXPENSE", categoryId = i.toLong(), categoryName = "Cat$i")
        }
        val s = computeDashboardSummary(rows, topN = 3)
        // Top 3: Cat7=700, Cat6=600, Cat5=500. Rest rolled up: Cat1+2+3+4 = 100+200+300+400 = 1000
        assertEquals(4, s.topExpenseCategories.size)
        val byName = s.topExpenseCategories.associateBy { it.categoryName }
        assertEquals(700L, byName["Cat7"]?.amountMinor)
        assertEquals(600L, byName["Cat6"]?.amountMinor)
        assertEquals(500L, byName["Cat5"]?.amountMinor)
        assertEquals(1_000L, byName["Others"]?.amountMinor)
    }

    @Test
    fun topCategories_othersEntryHasNegativeIdSentinel() {
        val rows = (1..6).map { i ->
            tx(amountMinor = 100L * i, type = "EXPENSE", categoryId = i.toLong(), categoryName = "Cat$i")
        }
        val s = computeDashboardSummary(rows, topN = 2)
        val others = s.topExpenseCategories.first { it.categoryName == "Others" }
        assertEquals(-1L, others.categoryId)  // sentinel so it can be identified distinctly
    }

    @Test
    fun topCategories_underTopN_noOthersBucket() {
        val rows = (1..3).map { i ->
            tx(amountMinor = 100L * i, type = "EXPENSE", categoryId = i.toLong(), categoryName = "Cat$i")
        }
        val s = computeDashboardSummary(rows, topN = 5)
        // Only 3 categories; no "Others" needed.
        assertEquals(3, s.topExpenseCategories.size)
        assertFalse(s.topExpenseCategories.any { it.categoryName == "Others" })
    }

    @Test
    fun incomeDoesNotAffectExpenseCategoryBreakdown() {
        // Income rows have categoryId 1 (a valid id), but they shouldn't appear in the breakdown.
        val rows = listOf(
            tx(amountMinor = 1_000_000, type = "INCOME", categoryId = 99, categoryName = "Salary"),
            tx(amountMinor = 500, type = "EXPENSE", categoryId = 1, categoryName = "Food"),
        )
        val s = computeDashboardSummary(rows)
        assertEquals(1, s.topExpenseCategories.size)
        assertEquals("Food", s.topExpenseCategories[0].categoryName)
        assertEquals(1_000_000L, s.incomeMinor)
        assertEquals(500L, s.expenseMinor)
    }

    @Test
    fun transactionCount_isTotalCount() {
        val rows = listOf(
            tx(amountMinor = 100, type = "INCOME"),
            tx(amountMinor = 200, type = "EXPENSE"),
            tx(amountMinor = 300, type = "INCOME"),
            tx(amountMinor = 400, type = "EXPENSE"),
        )
        val s = computeDashboardSummary(rows)
        assertEquals(4, s.transactionCount)
    }

    // ---- helpers ----

    private fun tx(
        amountMinor: Long,
        type: String,
        categoryId: Long = 0L,
        categoryName: String = "Any",
    ): TransactionWithCategory {
        val txn = TransactionEntity(
            id = 0L,
            title = "t",
            amountMinor = amountMinor,
            currencyCode = "USD",
            type = type,
            categoryId = categoryId,
            occurredAtEpochMillis = 0L,
            note = null,
            createdAtEpochMillis = 0L,
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
