package io.github.jiro.expensetracker.ui.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.jiro.expensetracker.R
import io.github.jiro.expensetracker.ui.statistics.DayOfWeekBucket
import io.github.jiro.expensetracker.ui.theme.NetBlue

/**
 * Seven vertical bars (Mon..Sun) showing total spend per weekday. The
 * tallest bucket fills the available height; others scale proportionally.
 * Zero-value buckets render as a 2dp stub at the bottom so the weekday
 * axis stays visible even when a day has no spend.
 */
@Composable
fun DayOfWeekBars(
    buckets: List<DayOfWeekBucket>,
    modifier: Modifier = Modifier,
    barColor: Color = NetBlue,
) {
    val maxValue = buckets.maxOfOrNull { it.amountMinor } ?: 0L
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            buckets.forEach { bucket ->
                val fraction = if (maxValue > 0L) {
                    (bucket.amountMinor.toFloat() / maxValue.toFloat()).coerceIn(0f, 1f)
                } else 0f
                val isStub = bucket.amountMinor <= 0L
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(fraction.coerceAtLeast(0.0125f))
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        .background(if (isStub) MaterialTheme.colorScheme.outlineVariant else barColor),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            buckets.forEach { bucket ->
                Text(
                    text = stringResource(weekdayLabelRes(bucket.isoDayOfWeek)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun weekdayLabelRes(isoDayOfWeek: Int): Int = when (isoDayOfWeek) {
    1 -> R.string.stats_dow_mon
    2 -> R.string.stats_dow_tue
    3 -> R.string.stats_dow_wed
    4 -> R.string.stats_dow_thu
    5 -> R.string.stats_dow_fri
    6 -> R.string.stats_dow_sat
    else -> R.string.stats_dow_sun
}
