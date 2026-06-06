package io.github.jiro.expensetracker.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jiro.expensetracker.R

/** Categorical color palette for pie slices and chart bars. Cycles if there are more slices than colors. */
val ChartPalette: List<Color> = listOf(
    Color(0xFF1A6CFF), // blue
    Color(0xFF03DAC5), // teal
    Color(0xFFFF6F00), // orange
    Color(0xFF9C27B0), // purple
    Color(0xFFE91E63), // pink
    Color(0xFF4CAF50), // green
    Color(0xFFFFC107), // amber
    Color(0xFF795548), // brown
    Color(0xFF607D8B), // blue-grey
    Color(0xFF009688), // teal-dark
    Color(0xFFFF5722), // deep orange
    Color(0xFF8BC34A), // light green
)

/**
 * Pie chart of category expenses for the current period, with a legend on the right.
 * If [slices] is empty or sums to 0, renders an empty-state label.
 */
@Composable
fun PieChartWithLegend(
    slices: List<io.github.jiro.expensetracker.ui.home.CategoryBreakdown>,
    modifier: Modifier = Modifier,
) {
    val total = slices.sumOf { it.amountMinor }
    if (total <= 0L) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(180.dp),
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
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PieChart(
            slices = slices,
            modifier = Modifier
                .weight(1f)
                .aspectRatio(1f)
                .padding(8.dp),
        )
        PieLegend(
            slices = slices,
            totalMinor = total,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        )
    }
}

@Composable
private fun PieChart(
    slices: List<io.github.jiro.expensetracker.ui.home.CategoryBreakdown>,
    modifier: Modifier = Modifier,
) {
    val total = slices.sumOf { it.amountMinor }
    val palette = ChartPalette
    Canvas(modifier = modifier.fillMaxSize()) {
        val canvasSize = this.size
        val radius = minOf(canvasSize.width, canvasSize.height) / 2f
        val topLeft = Offset(
            x = (canvasSize.width - radius * 2f) / 2f,
            y = (canvasSize.height - radius * 2f) / 2f,
        )
        var startAngle = -90f
        slices.forEachIndexed { i, slice ->
            val sweep = (slice.amountMinor.toFloat() / total.toFloat()) * 360f
            drawArc(
                color = palette[i % palette.size],
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = true,
                topLeft = topLeft,
                size = Size(radius * 2f, radius * 2f),
            )
            startAngle += sweep
        }
    }
}

@Composable
private fun PieLegend(
    slices: List<io.github.jiro.expensetracker.ui.home.CategoryBreakdown>,
    totalMinor: Long,
    modifier: Modifier = Modifier,
) {
    val palette = ChartPalette
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        slices.forEachIndexed { i, slice ->
            val pct = if (totalMinor > 0) slice.amountMinor.toFloat() / totalMinor.toFloat() else 0f
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(palette[i % palette.size], CircleShape),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = slice.categoryName,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${(pct * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatCurrency(amountMinor: Long): String {
    val abs = if (amountMinor < 0) -amountMinor else amountMinor
    return "%d.%02d".format(abs / 100, abs % 100)
}
