package io.github.jiro.expensetracker.domain.budget

import io.github.jiro.expensetracker.data.local.TransactionWithCategory
import io.github.jiro.expensetracker.domain.FxConverter
import io.github.jiro.expensetracker.domain.model.TransactionType

/**
 * Sum of expense transactions per category, in [homeCurrency] minor units, for
 * the inclusive-exclusive [bounds] range. Income rows are ignored.
 *
 * FX conversion mirrors `HomeViewModel.computeDashboardSummary`: a transaction
 * whose currency has no rate to the home currency is converted 1:1 and counted
 * in [SpentSummary.missingRateCount] so the UI can surface a warning.
 */
data class SpentSummary(
    val byCategoryMinor: Map<Long, Long>,
    val missingRateCount: Int,
)

fun computeSpentByCategory(
    rows: List<TransactionWithCategory>,
    bounds: LongRange,
    homeCurrency: String,
    fxRates: Map<String, Double>,
): SpentSummary {
    val byCategory = mutableMapOf<Long, Long>()
    var missing = 0
    for (row in rows) {
        val t = row.transaction
        if (TransactionType.fromStorage(t.type) != TransactionType.EXPENSE) continue
        if (t.occurredAtEpochMillis !in bounds) continue
        val converted = FxConverter.convertMinor(t.amountMinor, t.currencyCode, homeCurrency, fxRates)
            ?: run {
                missing += 1
                t.amountMinor
            }
        val cid = t.categoryId ?: continue
        byCategory[cid] = (byCategory[cid] ?: 0L) + converted
    }
    return SpentSummary(byCategory, missing)
}
