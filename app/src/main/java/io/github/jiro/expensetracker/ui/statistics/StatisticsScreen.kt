package io.github.jiro.expensetracker.ui.statistics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jiro.expensetracker.R
import io.github.jiro.expensetracker.data.local.MoneyFormat
import io.github.jiro.expensetracker.ui.charts.DayOfWeekBars
import io.github.jiro.expensetracker.ui.charts.PieChartWithLegend
import io.github.jiro.expensetracker.ui.charts.YoyCompareCard
import io.github.jiro.expensetracker.ui.home.CategoryBreakdown
import io.github.jiro.expensetracker.ui.theme.IncomeGreen
import kotlin.math.abs
import kotlinx.coroutines.launch

@Composable
fun StatisticsScreen(
    modifier: Modifier = Modifier,
    viewModel: StatisticsViewModel = hiltViewModel(),
) {
    val topCategories by viewModel.topCategories.collectAsStateWithLifecycle()
    val savings by viewModel.savings.collectAsStateWithLifecycle()
    val dayOfWeek by viewModel.dayOfWeek.collectAsStateWithLifecycle()
    val yoy by viewModel.yoy.collectAsStateWithLifecycle()
    StatisticsContent(
        topCategories = topCategories,
        savings = savings,
        dayOfWeek = dayOfWeek,
        yoy = yoy,
        modifier = modifier,
    )
}

@Composable
internal fun StatisticsContent(
    topCategories: TopCategoriesResult,
    savings: SavingsAndAverage,
    dayOfWeek: List<DayOfWeekBucket>,
    yoy: YearOverYear,
    modifier: Modifier = Modifier,
) {
    val tabs = listOf(StatTab.TopCats, StatTab.Savings, StatTab.Patterns, StatTab.YoY)
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()
    Column(modifier = modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = pagerState.currentPage) {
            tabs.forEachIndexed { i, tab ->
                Tab(
                    selected = pagerState.currentPage == i,
                    onClick = {
                        scope.launch { pagerState.animateScrollToPage(i) }
                    },
                    text = { Text(stringResource(tab.labelRes)) },
                )
            }
        }
        HorizontalPager(state = pagerState) { page ->
            when (tabs[page]) {
                StatTab.TopCats -> TopCatsTab(topCategories, Modifier.fillMaxSize())
                StatTab.Savings -> SavingsTab(savings, Modifier.fillMaxSize())
                StatTab.Patterns -> PatternsTab(dayOfWeek, Modifier.fillMaxSize())
                StatTab.YoY -> YoyTab(yoy, Modifier.fillMaxSize())
            }
        }
    }
}

internal enum class StatTab(val labelRes: Int) {
    TopCats(R.string.stats_tab_top_cats),
    Savings(R.string.stats_tab_savings),
    Patterns(R.string.stats_tab_patterns),
    YoY(R.string.stats_tab_yoy),
}

// ---------- Top Cats ----------

@Composable
private fun TopCatsTab(result: TopCategoriesResult, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.stats_top_cats_header, result.monthLabel),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        val pieSlices = result.slices.map {
            CategoryBreakdown(it.categoryId, it.categoryName, it.amountMinor)
        }
        PieChartWithLegend(slices = pieSlices)
        if (result.missingRateCount > 0) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
            ) {
                Text(
                    text = stringResource(R.string.stats_fx_missing),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

// ---------- Savings ----------

@Composable
private fun SavingsTab(savings: SavingsAndAverage, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.stats_savings_header, savings.monthLabel),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatTile(
                primary = "${(savings.savingsRate * 100).toInt()}%",
                primaryColor = if (savings.savingsRate >= 0.20f) IncomeGreen else MaterialTheme.colorScheme.onSurface,
                label = stringResource(R.string.stats_savings_rate_label),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                primary = MoneyFormat.formatForDisplay(savings.averageMonthlyExpenseMinor),
                label = stringResource(R.string.stats_avg_monthly_label),
                subLabel = if (savings.averageMonthlySampleMonths > 0)
                    stringResource(R.string.stats_avg_monthly_subtitle, savings.averageMonthlySampleMonths)
                else stringResource(R.string.stats_no_data),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                primary = MoneyFormat.formatForDisplay(savings.topTransactionMinor),
                label = stringResource(R.string.stats_top_tx_label),
                modifier = Modifier.weight(1f),
            )
        }
        NetRow(savings)
    }
}

@Composable
private fun StatTile(
    primary: String,
    primaryColor: Color = MaterialTheme.colorScheme.onSurface,
    label: String,
    subLabel: String? = null,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = primary,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = primaryColor,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (subLabel != null) {
                Text(
                    text = subLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun NetRow(savings: SavingsAndAverage) {
    val sign = when {
        savings.netMinor > 0L -> "+"
        savings.netMinor < 0L -> "−"
        else -> ""
    }
    val absMinor = abs(savings.netMinor)
    val color = when {
        savings.netMinor > 0L -> IncomeGreen
        else -> MaterialTheme.colorScheme.onSurface
    }
    Text(
        text = "${stringResource(R.string.stats_net_label)}: $sign${MoneyFormat.formatForDisplay(absMinor)}",
        style = MaterialTheme.typography.titleMedium,
        color = color,
    )
}

// ---------- Patterns ----------

@Composable
private fun PatternsTab(buckets: List<DayOfWeekBucket>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.stats_patterns_header),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (buckets.all { it.amountMinor == 0L }) {
            Box(modifier = Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.stats_no_data),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            DayOfWeekBars(buckets = buckets)
        }
    }
}

// ---------- YoY ----------

@Composable
private fun YoyTab(result: YearOverYear, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.stats_yoy_header, result.currentMonthLabel, result.previousMonthLabel),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        YoyCompareCard(result = result)
    }
}