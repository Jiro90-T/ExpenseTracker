package io.github.jiro.expensetracker.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.jiro.expensetracker.R
import io.github.jiro.expensetracker.ui.theme.ExpenseRed
import io.github.jiro.expensetracker.ui.theme.IncomeGreen
import io.github.jiro.expensetracker.ui.theme.NetBlue
import kotlin.math.abs

/**
 * Three polylines (income, expense, net) over the months in [data]. When
 * [prior] is non-null, three ghost polylines are drawn underneath at 30%
 * alpha for the same three series over the prior period. When
 * [currentMonthMs] is non-null and the month is in [data], a thin vertical
 * dashed line is drawn at that month's X position. Tap any data point on
 * the current period to select it; the [selected] month's dots get a ring
 * indicator. Tap the same point again, or anywhere outside the chart, to
 * clear the selection (caller passes `null` via [onSelect]).
 */
@Composable
fun LineChart(
    data: List<MonthlyTrend>,
    prior: List<MonthlyTrend>?,
    currentMonthMs: Long?,
    selected: MonthlyTrend?,
    onSelect: (MonthlyTrend?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (data.isEmpty()) {
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

    val density = LocalDensity.current
    val strokePx = with(density) { 2.dp.toPx() }
    val strokePxGhost = with(density) { 1.5.dp.toPx() }
    val dotPx = with(density) { 3.dp.toPx() }
    val ringPx = with(density) { 6.dp.toPx() }
    val ringStrokePx = with(density) { 1.dp.toPx() }
    val joinPx = with(density) { 8.dp.toPx() }
    val pathEffect = remember(joinPx) { PathEffect.cornerPathEffect(joinPx) }
    val markerDashPx = with(density) { 6.dp.toPx() }

    Column(modifier = modifier.fillMaxWidth()) {
        // Legend (two rows when prior is present, one row otherwise).
        if (prior != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LegendDot(color = IncomeGreen, label = stringResource(R.string.trends_legend_income))
                Spacer(Modifier.size(8.dp))
                LegendDotGhost(color = IncomeGreen, label = stringResource(R.string.trends_legend_income_prior))
                Spacer(Modifier.size(8.dp))
                LegendDot(color = ExpenseRed, label = stringResource(R.string.trends_legend_expense))
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LegendDotGhost(color = ExpenseRed, label = stringResource(R.string.trends_legend_expense_prior))
                Spacer(Modifier.size(8.dp))
                LegendDot(color = NetBlue, label = stringResource(R.string.trends_legend_net))
                Spacer(Modifier.size(8.dp))
                LegendDotGhost(color = NetBlue, label = stringResource(R.string.trends_legend_net_prior))
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LegendDot(color = IncomeGreen, label = stringResource(R.string.trends_legend_income))
                Spacer(Modifier.size(12.dp))
                LegendDot(color = ExpenseRed, label = stringResource(R.string.trends_legend_expense))
                Spacer(Modifier.size(12.dp))
                LegendDot(color = NetBlue, label = stringResource(R.string.trends_legend_net))
            }
        }
        Spacer(Modifier.size(4.dp))
        // Chart canvas.
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .pointerInput(data) {
                    detectTapGestures { tapOffset ->
                        val n = data.size
                        if (n < 2) return@detectTapGestures
                        val w = size.width.toFloat()
                        val step = w / (n - 1)
                        val nearestIndex = (tapOffset.x / step)
                            .toInt()
                            .coerceIn(0, n - 1)
                        val nearest = data[nearestIndex]
                        if (selected != null && selected.monthStartMs == nearest.monthStartMs) {
                            onSelect(null)
                        } else {
                            onSelect(nearest)
                        }
                    }
                },
        ) {
            val n = data.size
            val w = size.width
            val h = size.height

            // Y scale: max abs value across current AND prior.
            val currentAbs = data.flatMap { listOf(it.incomeMinor, it.expenseMinor, it.netMinor) }
            val priorAbs = prior?.flatMap { listOf(it.incomeMinor, it.expenseMinor, it.netMinor) } ?: emptyList()
            val maxAbs = (currentAbs + priorAbs).maxOfOrNull { abs(it) } ?: 0L
            if (maxAbs <= 0L) return@Canvas  // all zero — nothing to draw
            val midY = h / 2f
            val halfH = h / 2f

            // Helper: convert (value, monthIndex) → Offset
            fun pointFor(valueMinor: Long, monthIndex: Int): Offset {
                val x = if (n == 1) w / 2f else monthIndex * w / (n - 1)
                val normalized = valueMinor.toFloat() / maxAbs.toFloat()  // in [-1, 1]
                val y = midY - normalized * halfH
                return Offset(x, y)
            }

            // Baseline (x-axis).
            drawLine(
                color = Color.Gray.copy(alpha = 0.3f),
                start = Offset(0f, midY),
                end = Offset(w, midY),
                strokeWidth = 1f,
            )

            // Prior ghost polylines (drawn first, underneath).
            if (prior != null && prior.size == n) {
                val ghostPaths = listOf(
                    Pair(IncomeGreen, prior.mapIndexed { i, m -> pointFor(m.incomeMinor, i) }),
                    Pair(ExpenseRed, prior.mapIndexed { i, m -> pointFor(m.expenseMinor, i) }),
                    Pair(NetBlue, prior.mapIndexed { i, m -> pointFor(m.netMinor, i) }),
                )
                for ((color, points) in ghostPaths) {
                    if (points.size < 2) continue
                    val path = Path().apply {
                        moveTo(points.first().x, points.first().y)
                        for (i in 1 until points.size) {
                            lineTo(points[i].x, points[i].y)
                        }
                    }
                    drawPath(
                        path = path,
                        color = color.copy(alpha = 0.30f),
                        style = Stroke(width = strokePxGhost, pathEffect = pathEffect),
                    )
                }
            }

            // Current month marker (vertical dashed line).
            val markerIndex = currentMonthMs?.let { ms ->
                data.indexOfFirst { it.monthStartMs == ms }.takeIf { it >= 0 }
            }
            if (markerIndex != null) {
                val x = if (n == 1) w / 2f else markerIndex * w / (n - 1)
                drawLine(
                    color = Color.Gray.copy(alpha = 0.6f),
                    start = Offset(x, 0f),
                    end = Offset(x, h),
                    strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(markerDashPx, markerDashPx)),
                )
            }

            // Current period polylines (drawn over ghosts, under dots).
            val paths = listOf(
                Pair(IncomeGreen, data.mapIndexed { i, m -> pointFor(m.incomeMinor, i) }),
                Pair(ExpenseRed, data.mapIndexed { i, m -> pointFor(m.expenseMinor, i) }),
                Pair(NetBlue, data.mapIndexed { i, m -> pointFor(m.netMinor, i) }),
            )
            for ((color, points) in paths) {
                if (points.size < 2) continue
                val path = Path().apply {
                    moveTo(points.first().x, points.first().y)
                    for (i in 1 until points.size) {
                        lineTo(points[i].x, points[i].y)
                    }
                }
                drawPath(
                    path = path,
                    color = color,
                    style = Stroke(width = strokePx, pathEffect = pathEffect),
                )
            }

            // Dots: small circles at every data point, one per line.
            // Selected month: ring indicator.
            val selectedIndex = selected?.let { sel ->
                data.indexOfFirst { it.monthStartMs == sel.monthStartMs }.takeIf { it >= 0 }
            }
            for ((color, points) in paths) {
                points.forEachIndexed { i, p ->
                    if (selectedIndex == i) {
                        drawCircle(color = color.copy(alpha = 0.25f), radius = ringPx, center = p)
                        drawCircle(color = color, radius = ringPx, center = p, style = Stroke(width = ringStrokePx))
                    }
                    drawCircle(color = color, radius = dotPx, center = p)
                }
            }
        }
        Spacer(Modifier.size(4.dp))
        // X-axis labels.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            data.forEach { m ->
                Text(
                    text = m.shortLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Small color swatch + label, used in the legend. Renders the swatch at the
 * full color and at full size, matching the "current period" lines.
 */
@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = color, shape = CircleShape, modifier = Modifier.size(8.dp)) {}
        Spacer(Modifier.size(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Like [LegendDot] but with a 30% alpha swatch at a smaller size, used for
 * the "(prior)" legend entries that correspond to ghost lines.
 */
@Composable
private fun LegendDotGhost(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = color.copy(alpha = 0.30f), shape = CircleShape, modifier = Modifier.size(6.dp)) {}
        Spacer(Modifier.size(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
