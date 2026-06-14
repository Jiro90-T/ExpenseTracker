package io.github.jiro.expensetracker.ui.home

import io.github.jiro.expensetracker.data.local.BudgetEntity
import io.github.jiro.expensetracker.data.local.MoneyFormat
import io.github.jiro.expensetracker.data.local.TransactionWithCategory
import io.github.jiro.expensetracker.domain.FxConverter
import io.github.jiro.expensetracker.domain.model.TransactionType
import java.util.Calendar

/** A single budget alert — one category whose spending has crossed its cap. */
data class BudgetAlert(
    val categoryId: Long,
    val categoryName: String,
    val budgetMinor: Long,
    val spentMinor: Long,
    val overageMinor: Long,        // = spentMinor - budgetMinor (always > 0)
    val overageFormatted: String,  // precomputed "X.XX" string
    val homeCurrency: String,
)

/**
 * Pure: returns the list of budget alerts (categories where spentMinor >
 * budgetMinor for the current month). Sorted by overage descending (worst
 * first). All amounts are normalized to [homeCurrency] via [fxRates].
 *
 * Only considers budgets whose [BudgetEntity.monthStartEpochMs] matches the
 * start of [nowMs]'s month. Budgets from other months are out of scope for v1.
 */
fun computeBudgetAlerts(
    budgets: List<BudgetEntity>,
    spentByCategory: Map<Long, Long>,
    homeCurrency: String,
    fxRates: Map<String, Double>,
    nowMs: Long,
): List<BudgetAlert> {
    val thisMonthStart = startOfMonth(nowMs)
    return budgets
        .asSequence()
        .filter { it.monthStartEpochMs == thisMonthStart }
        .mapNotNull { budget ->
            val spent = spentByCategory[budget.categoryId] ?: return@mapNotNull null
            if (spent <= budget.amountMinor) return@mapNotNull null
            BudgetAlert(
                categoryId = budget.categoryId,
                categoryName = "Category #${budget.categoryId}",  // placeholder; VM provides real name
                budgetMinor = budget.amountMinor,
                spentMinor = spent,
                overageMinor = spent - budget.amountMinor,
                overageFormatted = MoneyFormat.formatAmountForEdit(spent - budget.amountMinor),
                homeCurrency = homeCurrency,
            )
        }
        .sortedByDescending { it.overageMinor }
        .toList()
}

/**
 * Aggregates expense transactions into a per-category total, normalizing each
 * to [homeCurrency] via [fxRates]. Transactions whose currency has no rate
 * to [homeCurrency] are converted 1:1 (defensive fallback, same as
 * [computeDashboardSummary]). Pure, JVM-testable.
 */
internal fun computeSpentByCategory(
    rows: List<TransactionWithCategory>,
    homeCurrency: String,
    fxRates: Map<String, Double>,
): Map<Long, Long> = rows
    .filter { TransactionType.fromStorage(it.transaction.type) == TransactionType.EXPENSE }
    .groupBy { it.transaction.categoryId }
    .mapValues { (_, rows) ->
        rows.sumOf { row ->
            val t = row.transaction
            FxConverter.convertMinor(t.amountMinor, t.currencyCode, homeCurrency, fxRates)
                ?: t.amountMinor
        }
    }

private fun startOfMonth(epochMs: Long): Long {
    val cal = Calendar.getInstance().apply {
        timeInMillis = epochMs
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return cal.timeInMillis
}
