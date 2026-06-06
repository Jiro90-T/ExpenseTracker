package io.github.jiro.expensetracker.ui.home

import io.github.jiro.expensetracker.data.local.TransactionWithCategory
import io.github.jiro.expensetracker.domain.model.TransactionType

/** Aggregated totals for the current period. */
data class DashboardSummary(
    val incomeMinor: Long = 0L,
    val expenseMinor: Long = 0L,
    val balanceMinor: Long = 0L,
    /** Top expense categories by amount, descending. "Others" is the rolled-up remainder. */
    val topExpenseCategories: List<CategoryBreakdown> = emptyList(),
    val totalExpenseForBreakdownMinor: Long = 0L,
    val transactionCount: Int = 0,
)

data class CategoryBreakdown(
    val categoryId: Long,
    val categoryName: String,
    val amountMinor: Long,
)

/**
 * Pure function: aggregates a (period-filtered) list of joined rows into a summary.
 * Top expense categories returns at most [topN] entries; if more exist, a synthetic
 * "Others" bucket is appended with the rolled-up remainder.
 */
fun computeDashboardSummary(
    rows: List<TransactionWithCategory>,
    topN: Int = 5,
): DashboardSummary {
    var income = 0L
    var expense = 0L
    val byCategory = mutableMapOf<Long, CategoryBreakdown>()

    for (row in rows) {
        val t = row.transaction
        when (TransactionType.fromStorage(t.type)) {
            TransactionType.INCOME -> income += t.amountMinor
            TransactionType.EXPENSE -> {
                expense += t.amountMinor
                val existing = byCategory[t.categoryId]
                if (existing == null) {
                    byCategory[t.categoryId] = CategoryBreakdown(
                        categoryId = t.categoryId,
                        categoryName = row.category.name,
                        amountMinor = t.amountMinor,
                    )
                } else {
                    byCategory[t.categoryId] = existing.copy(
                        amountMinor = existing.amountMinor + t.amountMinor,
                    )
                }
            }
        }
    }

    val sorted = byCategory.values.sortedByDescending { it.amountMinor }
    val (top, rest) = if (sorted.size > topN) {
        sorted.take(topN) to sorted.drop(topN)
    } else {
        sorted to emptyList()
    }
    val topWithOthers = if (rest.isNotEmpty()) {
        top + CategoryBreakdown(
            categoryId = -1L,
            categoryName = "Others",
            amountMinor = rest.sumOf { it.amountMinor },
        )
    } else top

    return DashboardSummary(
        incomeMinor = income,
        expenseMinor = expense,
        balanceMinor = income - expense,
        topExpenseCategories = topWithOthers,
        totalExpenseForBreakdownMinor = expense,
        transactionCount = rows.size,
    )
}
