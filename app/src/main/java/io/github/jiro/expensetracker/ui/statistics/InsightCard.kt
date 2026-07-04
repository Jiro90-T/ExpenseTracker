package io.github.jiro.expensetracker.ui.statistics

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.FiberNew
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.jiro.expensetracker.R
import io.github.jiro.expensetracker.data.local.MoneyFormat
import io.github.jiro.expensetracker.ui.theme.IncomeGreen
import kotlin.math.roundToInt

/**
 * Renders any [Insight] subclass as a Card. The mapping from insight type
 * to string resources, icons, and amount formatting lives here so the
 * calculator stays pure.
 */
@Composable
internal fun InsightCard(insight: Insight, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = insight.icon(),
                contentDescription = null,
                tint = insight.tintColor(),
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(insight.headlineRes(), *insight.headlineArgs()),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(insight.supportingTextRes(), *insight.supportingTextArgs()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// --- mapping helpers ---

// icon() is pure data — returns an ImageVector without reading any composition state.
// NOT @Composable.
private fun Insight.icon(): ImageVector = when (this) {
    is Insight.CategoryDelta -> when (direction) {
        Insight.Direction.UP -> Icons.AutoMirrored.Filled.TrendingUp
        Insight.Direction.DOWN -> Icons.AutoMirrored.Filled.TrendingDown
        Insight.Direction.NEW -> Icons.Filled.FiberNew
        Insight.Direction.UNCHANGED -> Icons.AutoMirrored.Filled.TrendingUp
    }
    is Insight.WeekendVsWeekday -> Icons.Filled.CalendarMonth
    is Insight.SavingsTrend -> Icons.Filled.Savings
    is Insight.TopExpenseSpotlight -> Icons.AutoMirrored.Filled.ReceiptLong
}

// tintColor() reads MaterialTheme.colorScheme — IS @Composable.
@Composable
private fun Insight.tintColor(): Color = when (this) {
    is Insight.SavingsTrend -> when (direction) {
        Insight.Direction.UP -> IncomeGreen
        Insight.Direction.DOWN -> MaterialTheme.colorScheme.error
        Insight.Direction.UNCHANGED, Insight.Direction.NEW -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    is Insight.CategoryDelta -> when (direction) {
        Insight.Direction.UP -> MaterialTheme.colorScheme.error
        Insight.Direction.DOWN -> IncomeGreen
        Insight.Direction.NEW -> MaterialTheme.colorScheme.primary
        Insight.Direction.UNCHANGED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    else -> MaterialTheme.colorScheme.primary
}

// headlineRes() wraps stringResource() — IS @Composable.
@Composable
private fun Insight.headlineRes(): Int = when (this) {
    is Insight.CategoryDelta -> when (direction) {
        Insight.Direction.UP -> R.string.stats_insights_cat_up
        Insight.Direction.DOWN -> R.string.stats_insights_cat_down
        Insight.Direction.NEW -> R.string.stats_insights_cat_new
        Insight.Direction.UNCHANGED -> R.string.stats_insights_cat_down
    }
    is Insight.WeekendVsWeekday -> R.string.stats_insights_weekend
    is Insight.SavingsTrend -> when (direction) {
        Insight.Direction.UP -> R.string.stats_insights_savings_up
        Insight.Direction.DOWN -> R.string.stats_insights_savings_down
        Insight.Direction.UNCHANGED -> R.string.stats_insights_savings_same
        Insight.Direction.NEW -> R.string.stats_insights_savings_same
    }
    is Insight.TopExpenseSpotlight -> R.string.stats_insights_top_expense
}

// headlineArgs() is pure data — NOT @Composable.
private fun Insight.headlineArgs(): Array<Any> = when (this) {
    is Insight.CategoryDelta -> arrayOf(
        categoryName,
        (kotlin.math.abs(percentChange) * 100f).roundToInt(),
    )
    is Insight.WeekendVsWeekday -> arrayOf((weekendPercent * 100f).roundToInt())
    is Insight.SavingsTrend -> when (direction) {
        Insight.Direction.UP, Insight.Direction.DOWN ->
            arrayOf((kotlin.math.abs(currentRate - previousRate) * 100f).roundToInt())
        Insight.Direction.UNCHANGED, Insight.Direction.NEW ->
            arrayOf((currentRate * 100f).roundToInt())
    }
    is Insight.TopExpenseSpotlight -> arrayOf(MoneyFormat.formatForDisplay(amountMinor))
}

// supportingTextRes() wraps stringResource() — IS @Composable.
@Composable
private fun Insight.supportingTextRes(): Int = when (this) {
    is Insight.CategoryDelta -> if (direction == Insight.Direction.NEW)
        R.string.stats_insights_cat_supporting_new
    else R.string.stats_insights_cat_supporting
    is Insight.WeekendVsWeekday -> R.string.stats_insights_weekend_support
    is Insight.SavingsTrend -> R.string.stats_insights_savings_support
    is Insight.TopExpenseSpotlight -> R.string.stats_insights_top_expense_support
}

// supportingTextArgs() is pure data — NOT @Composable.
private fun Insight.supportingTextArgs(): Array<Any> = when (this) {
    is Insight.CategoryDelta -> if (direction == Insight.Direction.NEW)
        arrayOf(MoneyFormat.formatForDisplay(currentMinor))
    else arrayOf(
        MoneyFormat.formatForDisplay(currentMinor),
        MoneyFormat.formatForDisplay(previousMinor),
    )
    is Insight.WeekendVsWeekday -> arrayOf(
        MoneyFormat.formatForDisplay(weekendMinor),
        MoneyFormat.formatForDisplay(weekdayMinor),
    )
    is Insight.SavingsTrend -> arrayOf(
        (currentRate * 100f).roundToInt(),
        (previousRate * 100f).roundToInt(),
    )
    is Insight.TopExpenseSpotlight -> arrayOf(title, dateLabel)
}
