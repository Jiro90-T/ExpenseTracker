package io.github.jiro.expensetracker.ui.home

import android.text.format.DateUtils
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.jiro.expensetracker.R
import io.github.jiro.expensetracker.data.local.TransactionWithCategory
import io.github.jiro.expensetracker.domain.model.TransactionType
import io.github.jiro.expensetracker.ui.charts.MonthlyTotals
import io.github.jiro.expensetracker.ui.theme.IncomeGreen
import io.github.jiro.expensetracker.ui.transactions.highlightMatches
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/** A single row in a transaction list. Tap to edit (caller wires the click). */
@Composable
internal fun TransactionRow(
    row: TransactionWithCategory,
    onClick: () -> Unit,
    searchQuery: String? = null,
    showTransactionDate: Boolean = false,
) {
    val txn = row.transaction
    val type = TransactionType.fromStorage(txn.type)
    val trimmed = searchQuery?.trim().orEmpty()
    val highlightStyle = SpanStyle(
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
    )

    when (type) {
        TransactionType.TRANSFER -> TransferRow(
            row = row,
            onClick = onClick,
            searchQuery = trimmed,
            highlightStyle = highlightStyle,
            showTransactionDate = showTransactionDate,
        )
        else -> StandardRow(
            row = row,
            onClick = onClick,
            searchQuery = trimmed,
            highlightStyle = highlightStyle,
            showTransactionDate = showTransactionDate,
        )
    }
}

@Composable
private fun StandardRow(
    row: TransactionWithCategory,
    onClick: () -> Unit,
    searchQuery: String,
    highlightStyle: SpanStyle,
    showTransactionDate: Boolean = false,
) {
    val txn = row.transaction
    val category = row.category
    val type = TransactionType.fromStorage(txn.type)
    val sign = if (type == TransactionType.EXPENSE) "-" else "+"
    val amountColor = if (type == TransactionType.EXPENSE) {
        MaterialTheme.colorScheme.error
    } else {
        IncomeGreen
    }
    val displayCategoryName = category?.name ?: stringResource(R.string.type_transfer)
    val ctx = LocalContext.current
    val txnDateText = remember(txn.occurredAtEpochMillis) {
        DateUtils.formatDateTime(
            ctx,
            txn.occurredAtEpochMillis,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR,
        )
    }
    val addedDateText = remember(txn.createdAtEpochMillis) {
        if (txn.createdAtEpochMillis == 0L) null else {
            DateUtils.formatDateTime(
                ctx,
                txn.createdAtEpochMillis,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR,
            )
        }
    }
    val showAddedDate = addedDateText != null &&
        (txn.createdAtEpochMillis - txn.occurredAtEpochMillis) > DAY_MS
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        CategoryIconBadge(name = displayCategoryName, size = 40)
        Spacer(Modifier.padding(start = 12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = highlightMatches(txn.title, searchQuery, highlightStyle),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (txn.recurringGroupId != null) {
                    Icon(
                        imageVector = Icons.Filled.Autorenew,
                        contentDescription = stringResource(R.string.recurring_indicator),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            if (showTransactionDate) {
                Text(
                    text = stringResource(R.string.transaction_date_on, txnDateText),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (showAddedDate) {
                    Text(
                        text = stringResource(R.string.transaction_added_on, addedDateText!!),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (addedDateText != null) {
                Text(
                    text = stringResource(R.string.transaction_added_on, addedDateText),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "$displayCategoryName · ${txn.currencyCode} " +
                    "$sign${txn.amountMinor / 100}.${"%02d".format(txn.amountMinor % 100)}",
                style = MaterialTheme.typography.bodySmall,
                color = amountColor,
            )
            if (!txn.note.isNullOrBlank()) {
                Text(
                    text = highlightMatches(txn.note, searchQuery, highlightStyle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TransferRow(
    row: TransactionWithCategory,
    onClick: () -> Unit,
    searchQuery: String,
    highlightStyle: SpanStyle,
    showTransactionDate: Boolean = false,
) {
    val txn = row.transaction
    val amountText = "${txn.amountMinor / 100}.${"%02d".format(txn.amountMinor % 100)} ${txn.currencyCode}"
    val destLabel = txn.transferAccountId?.let { "acct#$it" } ?: "—"
    val ctx = LocalContext.current
    val txnDateText = remember(txn.occurredAtEpochMillis) {
        DateUtils.formatDateTime(
            ctx,
            txn.occurredAtEpochMillis,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR,
        )
    }
    val addedDateText = remember(txn.createdAtEpochMillis) {
        if (txn.createdAtEpochMillis == 0L) null else {
            DateUtils.formatDateTime(
                ctx,
                txn.createdAtEpochMillis,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR,
            )
        }
    }
    val showAddedDate = addedDateText != null &&
        (txn.createdAtEpochMillis - txn.occurredAtEpochMillis) > DAY_MS
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        CategoryIconBadge(name = "↔", size = 40)
        Spacer(Modifier.padding(start = 12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = highlightMatches(txn.title, searchQuery, highlightStyle),
                style = MaterialTheme.typography.titleMedium,
            )
            if (showTransactionDate) {
                Text(
                    text = stringResource(R.string.transaction_date_on, txnDateText),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (showAddedDate) {
                    Text(
                        text = stringResource(R.string.transaction_added_on, addedDateText!!),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (addedDateText != null) {
                Text(
                    text = stringResource(R.string.transaction_added_on, addedDateText),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // TODO(Phase 2.16+): extend TransactionWithCategory to embed the
            // destination account entity for TRANSFER rows so we can render the
            // account name instead of the id.
            Text(
                text = "→ $destLabel · $amountText",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun CategoryIconBadge(name: String, size: Int) {
    val color = categoryColor(name)
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = categoryIcon(name),
            contentDescription = name,
            tint = Color.White,
            modifier = Modifier.size((size * 0.55f).dp),
        )
    }
}

/**
 * Wraps a [TransactionRow] in a custom swipe-to-reveal layout. Dragging
 * right→left exposes an `errorContainer` Delete button on the trailing
 * edge; the row settles revealed once the drag clears a 50% threshold
 * and snaps back otherwise. The deletion is committed only when the
 * user taps the Delete button — never by the swipe gesture alone —
 * keeping the post-delete undo Snackbar in the caller's hands.
 *
 * Tap on the row while revealed collapses the reveal without editing.
 */
@Composable
internal fun SwipeableTransactionRow(
    row: TransactionWithCategory,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    searchQuery: String? = null,
) {
    val density = LocalDensity.current
    val actionWidth = 88.dp
    val actionWidthPx = with(density) { actionWidth.toPx() }
    val offsetX = remember { Animatable(0f) }
    var revealed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Collapse when the row identity changes (after delete + list
    // re-key, or on scroll-recycle).
    LaunchedEffect(row.transaction.id) {
        if (offsetX.value != 0f) offsetX.animateTo(0f)
        revealed = false
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        // Reveal background: errorContainer panel with a Delete icon
        // button. fillMaxSize fills the Box once the IntrinsicSize pass
        // resolves the row height.
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.errorContainer),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    // Snap the row back so the row disappears smoothly;
                    // the caller's onDelete schedules the actual removal
                    // (and the undo Snackbar).
                    scope.launch { offsetX.animateTo(0f) }
                    revealed = false
                    onDelete()
                },
                modifier = Modifier.size(actionWidth),
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

        // Foreground: the row, draggable horizontally. Right→left
        // (negative dragAmount) reveals the action; left→right is
        // ignored so the row content never goes off the left edge.
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.toInt(), 0) }
                .pointerInput(row.transaction.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                val target =
                                    if (offsetX.value <= -actionWidthPx / 2f) {
                                        -actionWidthPx
                                    } else {
                                        0f
                                    }
                                offsetX.animateTo(target)
                                revealed = target != 0f
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                offsetX.animateTo(0f)
                                revealed = false
                            }
                        },
                    ) { _, dragAmount ->
                        if (dragAmount < 0f) {
                            scope.launch {
                                offsetX.snapTo(
                                    (offsetX.value + dragAmount)
                                        .coerceIn(-actionWidthPx, 0f),
                                )
                            }
                        }
                    }
                },
            color = MaterialTheme.colorScheme.surface,
        ) {
            TransactionRow(
                row = row,
                onClick = {
                    if (revealed) {
                        scope.launch { offsetX.animateTo(0f) }
                        revealed = false
                    } else {
                        onEdit()
                    }
                },
                searchQuery = searchQuery,
            )
        }
    }
}

/** Search input that updates a query string. */
@Composable
internal fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        singleLine = true,
        placeholder = { Text(stringResource(R.string.search_placeholder)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.action_clear_search),
                    )
                }
            }
        },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
    )
}

@Composable
internal fun DayHeader(dayStartMs: Long) {
    Text(
        text = formatDayHeader(dayStartMs),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

internal data class DayGroup(
    val dayStartMs: Long,
    val items: List<TransactionWithCategory>,
)

internal fun groupByDay(rows: List<TransactionWithCategory>): List<DayGroup> {
    val groups = rows.groupBy { startOfDay(it.transaction.occurredAtEpochMillis) }
    return groups.entries
        .sortedByDescending { it.key }
        .map { (day, items) -> DayGroup(day, items) }
}

private fun startOfDay(epochMs: Long): Long {
    val cal = Calendar.getInstance().apply {
        timeInMillis = epochMs
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return cal.timeInMillis
}

private fun formatDayHeader(dayStartMs: Long): String {
    val today = startOfDay(System.currentTimeMillis())
    val diff = (today - dayStartMs) / DAY_MS
    val date = Date(dayStartMs)
    val fmt = SimpleDateFormat("EEE, MMM d, yyyy", Locale.getDefault())
    return when (diff) {
        0L -> "Today"
        1L -> "Yesterday"
        else -> fmt.format(date)
    }
}

private const val DAY_MS: Long = 24L * 60L * 60L * 1000L

/** Wraps the monthly bar chart in a card matching the dashboard summary style. */
@Composable
internal fun MonthlyTrendCard(data: List<MonthlyTotals>) {
    androidx.compose.material3.Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.dashboard_monthly_trend),
                style = MaterialTheme.typography.titleSmall,
            )
            io.github.jiro.expensetracker.ui.charts.MonthlyBarChart(data = data)
        }
    }
}
