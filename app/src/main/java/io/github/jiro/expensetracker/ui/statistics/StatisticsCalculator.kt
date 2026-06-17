package io.github.jiro.expensetracker.ui.statistics

import io.github.jiro.expensetracker.data.local.CategoryEntity
import io.github.jiro.expensetracker.data.local.TransactionWithCategory
import io.github.jiro.expensetracker.domain.FxConverter
import io.github.jiro.expensetracker.domain.model.TransactionType
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

data class CategorySpend(
    val categoryId: Long,
    val categoryName: String,
    val amountMinor: Long,
)

data class TopCategoriesResult(
    val monthLabel: String,
    val slices: List<CategorySpend>,
    val missingRateCount: Int,
)

data class SavingsAndAverage(
    val monthLabel: String,
    val incomeMinor: Long,
    val expenseMinor: Long,
    val netMinor: Long,
    val savingsRate: Float,
    val averageMonthlyExpenseMinor: Long,
    val topTransactionMinor: Long,
    val averageMonthlySampleMonths: Int,
)

data class DayOfWeekBucket(
    val isoDayOfWeek: Int,
    val amountMinor: Long,
)

data class YearOverYear(
    val currentMonthLabel: String,
    val previousMonthLabel: String,
    val currentExpenseMinor: Long,
    val previousExpenseMinor: Long,
    val percentChange: Float,
    val isNewSpending: Boolean,
)

object StatisticsCalculator {

    internal fun monthBounds(year: Int, month: Int): Pair<Long, Long> {
        require(month in 1..12) { "month must be 1..12, got $month" }
        val ym = YearMonth.of(year, month)
        val zone = ZoneId.systemDefault()
        val startMs = ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return startMs to endMs
    }

    internal fun monthLabel(nowMs: Long): String {
        val date = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).toLocalDate()
        val month = date.month.name.lowercase().replaceFirstChar { it.uppercase() }
        return "$month ${date.year}"
    }

    fun topCategories(
        txns: List<TransactionWithCategory>,
        cats: List<CategoryEntity>,
        homeCurrency: String,
        fxRates: Map<String, Double>,
        nowMs: Long,
    ): TopCategoriesResult = TopCategoriesResult(monthLabel(nowMs), emptyList(), 0)

    fun savingsAndAverage(
        txns: List<TransactionWithCategory>,
        homeCurrency: String,
        fxRates: Map<String, Double>,
        nowMs: Long,
    ): SavingsAndAverage = SavingsAndAverage(
        monthLabel = monthLabel(nowMs),
        incomeMinor = 0L,
        expenseMinor = 0L,
        netMinor = 0L,
        savingsRate = 0f,
        averageMonthlyExpenseMinor = 0L,
        topTransactionMinor = 0L,
        averageMonthlySampleMonths = 0,
    )

    fun dayOfWeekPattern(
        txns: List<TransactionWithCategory>,
        homeCurrency: String,
        fxRates: Map<String, Double>,
        nowMs: Long,
    ): List<DayOfWeekBucket> = (1..7).map { DayOfWeekBucket(it, 0L) }

    fun yearOverYear(
        txns: List<TransactionWithCategory>,
        homeCurrency: String,
        fxRates: Map<String, Double>,
        nowMs: Long,
    ): YearOverYear {
        val today = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).toLocalDate()
        val currentLabel = monthLabel(nowMs)
        val previousLabel = monthLabel(
            YearMonth.of(today.year - 1, today.monthValue).atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        )
        return YearOverYear(
            currentMonthLabel = currentLabel,
            previousMonthLabel = previousLabel,
            currentExpenseMinor = 0L,
            previousExpenseMinor = 0L,
            percentChange = 0f,
            isNewSpending = false,
        )
    }
}
