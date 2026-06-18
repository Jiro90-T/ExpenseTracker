package io.github.jiro.expensetracker.ui.charts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jiro.expensetracker.R
import io.github.jiro.expensetracker.data.local.MoneyFormat
import io.github.jiro.expensetracker.ui.statistics.YearOverYear
import io.github.jiro.expensetracker.ui.theme.ExpenseRed
import io.github.jiro.expensetracker.ui.theme.IncomeGreen

/**
 * Two side-by-side tiles (current vs previous month) plus a colored delta
 * chip below. The chip reads "N% vs last year" when spending is up
 * (ExpenseRed), down (IncomeGreen), or "No spending last year" / "No change"
 * for the boundary cases.
 */
@Composable
fun YoyCompareCard(
    result: YearOverYear,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            YoyTile(
                label = result.currentMonthLabel,
                amount = MoneyFormat.formatAmountForEdit(result.currentExpenseMinor),
                modifier = Modifier.weight(1f),
            )
            YoyTile(
                label = result.previousMonthLabel,
                amount = MoneyFormat.formatAmountForEdit(result.previousExpenseMinor),
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(12.dp))
        YoYDeltaChip(result)
    }
}

@Composable
private fun YoyTile(label: String, amount: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = amount,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun YoYDeltaChip(result: YearOverYear) {
    val (label, color) = when {
        result.isNewSpending -> stringResource(R.string.stats_yoy_new) to MaterialTheme.colorScheme.secondary
        result.percentChange > 0f -> "${(result.percentChange * 100).toInt()}% ${stringResource(R.string.stats_yoy_vs_last_year)}" to ExpenseRed
        result.percentChange < 0f -> "${(result.percentChange * 100).toInt()}% ${stringResource(R.string.stats_yoy_vs_last_year)}" to IncomeGreen
        else -> stringResource(R.string.stats_yoy_no_change) to MaterialTheme.colorScheme.outline
    }
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        AssistChip(
            onClick = {},
            label = { Text(label) },
            colors = AssistChipDefaults.assistChipColors(labelColor = color),
        )
    }
}
