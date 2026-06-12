package io.github.jiro.expensetracker.ui.trends

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jiro.expensetracker.R
import io.github.jiro.expensetracker.data.local.MoneyFormat
import io.github.jiro.expensetracker.ui.charts.ComparisonDelta
import io.github.jiro.expensetracker.ui.charts.LineChart
import io.github.jiro.expensetracker.ui.charts.MonthlyTrend
import io.github.jiro.expensetracker.ui.charts.TrendsPeriod
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrendsScreen(viewModel: TrendsViewModel = hiltViewModel()) {
    val periodTrends by viewModel.periodTrends.collectAsStateWithLifecycle()
    val period by viewModel.period.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_trends)) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
        ) {
            PeriodSelectorRow(
                selected = period,
                onSelect = viewModel::setPeriod,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.trends_tap_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(8.dp))
            LineChart(
                data = periodTrends.current,
                prior = periodTrends.prior,
                currentMonthMs = periodTrends.currentMonthMs,
                selected = selected,
                onSelect = viewModel::select,
            )
            Spacer(Modifier.size(16.dp))
            AnimatedVisibility(
                visible = selected != null,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                selected?.let { sel ->
                    DetailPanel(
                        month = sel,
                        onClear = { viewModel.select(null) },
                    )
                }
            }
            AnimatedVisibility(
                visible = periodTrends.prior != null && periodTrends.delta != null,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                val prior = periodTrends.prior
                val delta = periodTrends.delta
                if (prior != null && delta != null) {
                    ComparisonCard(
                        period = period,
                        delta = delta,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodSelectorRow(
    selected: TrendsPeriod,
    onSelect: (TrendsPeriod) -> Unit,
) {
    val options = TrendsPeriod.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, period ->
            SegmentedButton(
                selected = selected == period,
                onClick = { onSelect(period) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) { Text(stringResource(period.labelRes)) }
        }
    }
}

@Composable
private fun DetailPanel(month: MonthlyTrend, onClear: () -> Unit) {
    val label = remember(month.monthStartMs) {
        SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(month.monthStartMs))
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.trends_detail_title, label),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = stringResource(R.string.trends_detail_income, MoneyFormat.formatAmountForEdit(month.incomeMinor)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.trends_detail_expense, MoneyFormat.formatAmountForEdit(month.expenseMinor)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(
                        R.string.trends_detail_net,
                        (if (month.netMinor >= 0) "+" else "") + MoneyFormat.formatAmountForEdit(month.netMinor),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            IconButton(onClick = onClear) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.trends_clear),
                )
            }
        }
    }
}

@Composable
private fun ComparisonCard(
    period: TrendsPeriod,
    delta: ComparisonDelta,
) {
    val title = if (period == TrendsPeriod.Ytd) {
        stringResource(R.string.trends_compare_panel_title_ytd)
    } else {
        stringResource(R.string.trends_compare_panel_title, period.monthsBack ?: 0)
    }

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.size(8.dp))
            DeltaRow(label = stringResource(R.string.trends_detail_income, formatPctForLabel(delta.incomePct)), pct = delta.incomePct)
            DeltaRow(label = stringResource(R.string.trends_detail_expense, formatPctForLabel(delta.expensePct)), pct = delta.expensePct)
            DeltaRow(label = stringResource(R.string.trends_detail_net, formatPctForLabel(delta.netPct)), pct = delta.netPct)
        }
    }
}

@Composable
private fun DeltaRow(label: String, pct: Double?) {
    // The label string already contains the formatted percent (or "—")
    // because it was built with `stringResource(R.string.trends_detail_*,
    // formatPctForLabel(pct))`. We only need this composable to attach the
    // arrow icon and tint the text.
    val color = when {
        pct == null -> MaterialTheme.colorScheme.onSurfaceVariant
        abs(pct) < 0.05 -> MaterialTheme.colorScheme.onSurfaceVariant
        pct > 0 -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.error
    }
    val showArrow = pct != null && abs(pct) >= 0.05
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            modifier = Modifier.weight(1f),
        )
        if (showArrow) {
            Icon(
                imageVector = if ((pct ?: 0.0) < 0) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

private fun formatPct(pct: Double): String {
    val rounded = (pct * 10.0).toLong() / 10.0
    val sign = if (rounded > 0) "+" else ""
    return if (rounded == rounded.toLong().toDouble()) {
        "$sign${rounded.toLong()}%"
    } else {
        "$sign${"%.1f".format(rounded)}%"
    }
}

/**
 * Formats a percent for use as the placeholder of a parameterized label
 * string (e.g. "Income: %1$s"). Returns "—" when the prior sum is zero
 * (no meaningful percent) and "0%" when the percent rounds to zero
 * (current and prior are equal).
 */
private fun formatPctForLabel(pct: Double?): String {
    if (pct == null) return "—"
    if (abs(pct) < 0.05) return "0%"
    return formatPct(pct)
}
