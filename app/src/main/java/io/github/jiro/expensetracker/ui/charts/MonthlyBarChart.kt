package io.github.jiro.expensetracker.ui.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.jiro.expensetracker.R

private val IncomeGreen = Color(0xFF1B5E20)
private val ExpenseRed = Color(0xFFB00020)

/**
 * Side-by-side income/expense bars per month, oldest left to newest right.
 * Rendered with Compose Row + weighted Box heights — no nativeCanvas.
 */
@Composable
fun MonthlyBarChart(
    data: List<MonthlyTotals>,
    modifier: Modifier = Modifier,
) {
    val maxValue = data.flatMap { listOf(it.incomeMinor, it.expenseMinor) }.maxOrNull() ?: 0L
    if (maxValue <= 0L) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(160.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.charts_no_data),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            data.forEach { month ->
                BarGroup(
                    month = month,
                    maxValue = maxValue,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            data.forEach { month ->
                Text(
                    text = month.shortLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun BarGroup(
    month: MonthlyTotals,
    maxValue: Long,
    modifier: Modifier = Modifier,
) {
    val incomeRatio = (month.incomeMinor.toFloat() / maxValue.toFloat()).coerceIn(0f, 1f)
    val expenseRatio = (month.expenseMinor.toFloat() / maxValue.toFloat()).coerceIn(0f, 1f)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(incomeRatio)
                .background(IncomeGreen, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(expenseRatio)
                .background(ExpenseRed, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)),
        )
    }
}
