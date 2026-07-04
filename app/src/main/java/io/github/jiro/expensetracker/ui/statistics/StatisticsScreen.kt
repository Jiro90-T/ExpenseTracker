package io.github.jiro.expensetracker.ui.statistics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val insights by viewModel.insights.collectAsStateWithLifecycle()
    val topCatsRange by viewModel.topCatsRange.collectAsStateWithLifecycle()
    val savingsRange by viewModel.savingsRange.collectAsStateWithLifecycle()
    val patternsRange by viewModel.patternsRange.collectAsStateWithLifecycle()
    val yoyRange by viewModel.yoyRange.collectAsStateWithLifecycle()
    StatisticsContent(
        topCategories = topCategories,
        savings = savings,
        dayOfWeek = dayOfWeek,
        yoy = yoy,
        insights = insights,
        topCatsRange = topCatsRange,
        savingsRange = savingsRange,
        patternsRange = patternsRange,
        yoyRange = yoyRange,
        onRangeSelected = viewModel::onRangeSelected,
        modifier = modifier,
    )
}

@Composable
internal fun StatisticsContent(
    topCategories: TopCategoriesResult,
    savings: SavingsAndAverage,
    dayOfWeek: List<DayOfWeekBucket>,
    yoy: YearOverYear,
    insights: List<Insight>,
    topCatsRange: LongRange,
    savingsRange: LongRange,
    patternsRange: LongRange,
    yoyRange: LongRange,
    onRangeSelected: (StatisticsTab, LongRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = listOf(StatisticsTab.TOP_CATS, StatisticsTab.SAVINGS, StatisticsTab.PATTERNS, StatisticsTab.YOY, StatisticsTab.INSIGHTS)
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()

    // Per-tab sheet state — only one sheet open at a time, keyed by the currently visible tab.
    var sheetTab by remember { mutableStateOf<StatisticsTab?>(null) }
    val rangesByTab = mapOf(
        StatisticsTab.TOP_CATS to topCatsRange,
        StatisticsTab.SAVINGS to savingsRange,
        StatisticsTab.PATTERNS to patternsRange,
        StatisticsTab.YOY to yoyRange,
    )

    Column(modifier = modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = pagerState.currentPage) {
            tabs.forEachIndexed { i, tab ->
                Tab(
                    selected = pagerState.currentPage == i,
                    onClick = { scope.launch { pagerState.animateScrollToPage(i) } },
                    text = { Text(stringResource(tab.labelRes())) },
                )
            }
        }
        HorizontalPager(state = pagerState) { page ->
            val tab = tabs[page]
            val range = rangesByTab.getValue(tab)
            // Memoize isDefault off the range itself — recomputing defaultFor(tab)
            // every frame could flicker the chip color across month boundaries.
            val isDefault = remember(range) { range == defaultFor(tab) }
            when (tab) {
                StatisticsTab.TOP_CATS -> TopCatsTab(
                    result = topCategories,
                    range = range,
                    isDefault = isDefault,
                    onChipClick = { sheetTab = tab },
                    onReset = { onRangeSelected(tab, defaultFor(tab)) },
                )
                StatisticsTab.SAVINGS -> SavingsTab(
                    savings = savings,
                    range = range,
                    isDefault = isDefault,
                    onChipClick = { sheetTab = tab },
                    onReset = { onRangeSelected(tab, defaultFor(tab)) },
                )
                StatisticsTab.PATTERNS -> PatternsTab(
                    buckets = dayOfWeek,
                    range = range,
                    isDefault = isDefault,
                    onChipClick = { sheetTab = tab },
                    onReset = { onRangeSelected(tab, defaultFor(tab)) },
                )
                StatisticsTab.YOY -> YoyTab(
                    result = yoy,
                    range = range,
                    isDefault = isDefault,
                    onChipClick = { sheetTab = tab },
                    onReset = { onRangeSelected(tab, defaultFor(tab)) },
                )
                StatisticsTab.INSIGHTS -> InsightsTab(insights = insights)
            }
        }
    }

    sheetTab?.let { tab ->
        val r = rangesByTab.getValue(tab)
        // RangeChip stores [startMs, endMs) half-open. The M3 DateRangePicker
        // shows inclusive end-of-day bounds, so we shift by one day on both sides
        // of the sheet: display r.last - 1day as "last visible day", and convert
        // the user's inclusive pick (s..e) into half-open (s..e+1day) for storage.
        RangePickerSheet(
            currentStartMs = r.first,
            currentEndMs = r.last - ONE_DAY_MS,
            onDismiss = { sheetTab = null },
            onConfirm = { s, e ->
                onRangeSelected(tab, s..(e + ONE_DAY_MS))
                sheetTab = null
            },
        )
    }
}

private const val ONE_DAY_MS: Long = 24L * 60L * 60L * 1000L

private fun defaultFor(tab: StatisticsTab): LongRange {
    val now = System.currentTimeMillis()
    val zone = java.time.ZoneId.systemDefault()
    val date = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val ym = java.time.YearMonth.of(date.year, date.monthValue)
    val start = ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val end = ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return start..end
}

private fun StatisticsTab.labelRes(): Int = when (this) {
    StatisticsTab.TOP_CATS -> R.string.stats_tab_top_cats
    StatisticsTab.SAVINGS  -> R.string.stats_tab_savings
    StatisticsTab.PATTERNS -> R.string.stats_tab_patterns
    StatisticsTab.YOY      -> R.string.stats_tab_yoy
    StatisticsTab.INSIGHTS -> R.string.stats_tab_insights
}

// ---- per-tab composables ----

@Composable
private fun TopCatsTab(
    result: TopCategoriesResult,
    range: LongRange,
    isDefault: Boolean,
    onChipClick: () -> Unit,
    onReset: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(16.dp).fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.stats_top_cats_header, result.monthLabel),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            RangeChip(
                startMs = range.first,
                endMs = range.last,
                isDefault = isDefault,
                onClick = onChipClick,
            )
        }
        if (result.slices.isEmpty()) {
            EmptyRangeState(onReset = onReset)
        } else {
            val pieSlices = result.slices.map {
                CategoryBreakdown(it.categoryId, it.categoryName, it.amountMinor)
            }
            PieChartWithLegend(slices = pieSlices)
        }
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

@Composable
private fun SavingsTab(
    savings: SavingsAndAverage,
    range: LongRange,
    isDefault: Boolean,
    onChipClick: () -> Unit,
    onReset: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(16.dp).fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.stats_savings_header, savings.monthLabel),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            RangeChip(
                startMs = range.first,
                endMs = range.last,
                isDefault = isDefault,
                onClick = onChipClick,
            )
        }
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
private fun PatternsTab(
    buckets: List<DayOfWeekBucket>,
    range: LongRange,
    isDefault: Boolean,
    onChipClick: () -> Unit,
    onReset: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(16.dp).fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.stats_patterns_header),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            RangeChip(
                startMs = range.first,
                endMs = range.last,
                isDefault = isDefault,
                onClick = onChipClick,
            )
        }
        if (buckets.all { it.amountMinor == 0L }) {
            EmptyRangeState(onReset = onReset)
        } else {
            DayOfWeekBars(buckets = buckets)
        }
    }
}

@Composable
private fun YoyTab(
    result: YearOverYear,
    range: LongRange,
    isDefault: Boolean,
    onChipClick: () -> Unit,
    onReset: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(16.dp).fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.stats_yoy_header, result.currentWindowLabel, result.previousWindowLabel),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            RangeChip(
                startMs = range.first,
                endMs = range.last,
                isDefault = isDefault,
                onClick = onChipClick,
            )
        }
        YoyCompareCard(result = result)
    }
}

@Composable
private fun EmptyRangeState(onReset: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.stats_empty_in_range),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onReset) {
            Text(stringResource(R.string.stats_empty_reset))
        }
    }
}

@Composable
private fun StatTile(
    primary: String,
    primaryColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
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

@Composable
private fun InsightsTab(insights: List<Insight>) {
    if (insights.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.stats_insights_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items = insights, key = { insight ->
            // Stable per-type key. compute() emits at most one of each subclass,
            // so a per-type key is sufficient to keep items() stable across
            // recompositions.
            when (insight) {
                is Insight.CategoryDelta -> "cat-${insight.categoryName}"
                is Insight.WeekendVsWeekday -> "weekend"
                is Insight.SavingsTrend -> "savings"
                is Insight.TopExpenseSpotlight -> "top-${insight.title}-${insight.dateLabel}"
            }
        }) { insight ->
            InsightCard(insight = insight)
        }
    }
}