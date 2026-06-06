package io.github.jiro.expensetracker.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jiro.expensetracker.R
import io.github.jiro.expensetracker.ui.charts.PieChartWithLegend

private val IncomeGreen = Color(0xFF1B5E20)

@Composable
fun DashboardSummaryCard(summary: DashboardSummary, modifier: Modifier = Modifier) {
    val expenseColor = MaterialTheme.colorScheme.error
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Totals row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                StatColumn(
                    label = stringResource(R.string.dashboard_income),
                    amountMinor = summary.incomeMinor,
                    color = IncomeGreen,
                    modifier = Modifier.weight(1f),
                )
                StatColumn(
                    label = stringResource(R.string.dashboard_expense),
                    amountMinor = summary.expenseMinor,
                    color = expenseColor,
                    modifier = Modifier.weight(1f),
                )
                StatColumn(
                    label = stringResource(R.string.dashboard_balance),
                    amountMinor = summary.balanceMinor,
                    color = if (summary.balanceMinor >= 0) IncomeGreen else expenseColor,
                    showSign = true,
                    modifier = Modifier.weight(1f),
                )
            }

            if (summary.topExpenseCategories.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.dashboard_top_expenses),
                    style = MaterialTheme.typography.titleSmall,
                )
                PieChartWithLegend(slices = summary.topExpenseCategories)
            }
        }
    }
}

@Composable
private fun StatColumn(
    label: String,
    amountMinor: Long,
    color: Color,
    showSign: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = formatCurrency(amountMinor, showSign = showSign),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
    }
}

private fun formatCurrency(amountMinor: Long, showSign: Boolean = false): String {
    val abs = if (amountMinor < 0) -amountMinor else amountMinor
    val whole = abs / 100
    val fraction = abs % 100
    val sign = when {
        showSign && amountMinor > 0 -> "+"
        amountMinor < 0 -> "-"
        else -> ""
    }
    return "$sign$whole.${"%02d".format(fraction)}"
}
