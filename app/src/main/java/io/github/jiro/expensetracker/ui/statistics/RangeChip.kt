package io.github.jiro.expensetracker.ui.statistics

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Pill chip showing the currently-selected date range. Tap → opens picker.
 * Muted color when the range equals the current calendar month (default);
 * default color when the user has picked a non-default range.
 */
@Composable
fun RangeChip(
    startMs: Long,
    endMs: Long,
    isDefault: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val labelColor = if (isDefault) MaterialTheme.colorScheme.onSurfaceVariant
                     else MaterialTheme.colorScheme.primary
    val label = compactRangeLabel(startMs, endMs)

    AssistChip(
        onClick = onClick,
        modifier = modifier.semantics {
            contentDescription = "Range: $label. Tap to change."
        },
        label = {
            Text(
                text = label,
                color = labelColor,
                style = MaterialTheme.typography.labelLarge,
            )
        },
        trailingIcon = {
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = labelColor,
            )
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = Color.Transparent,
        ),
    )
}

/**
 * Compact "Mar 1 – 31" / "Jan 15 – Feb 3" / "Dec 28, 2025 – Jan 4, 2026" labels.
 * Used by the chip; the more verbose form is in StatisticsCalculator.rangeLabel.
 */
private fun compactRangeLabel(startMs: Long, endMs: Long): String {
    val zone = java.time.ZoneId.systemDefault()
    val startDate = java.time.Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate()
    val endDate = java.time.Instant.ofEpochMilli(endMs - 1L).atZone(zone).toLocalDate()
    val monthDay = java.time.format.DateTimeFormatter.ofPattern("MMM d", java.util.Locale.US)
    val month = java.time.format.DateTimeFormatter.ofPattern("MMM", java.util.Locale.US)
    val full = java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy", java.util.Locale.US)

    return when {
        startDate.year == endDate.year && startDate.month == endDate.month ->
            "${startDate.format(month)} ${startDate.dayOfMonth}–${endDate.dayOfMonth}"
        startDate.year == endDate.year ->
            "${startDate.format(monthDay)} – ${endDate.format(monthDay)}"
        else ->
            "${startDate.format(full)} – ${endDate.format(full)}"
    }
}