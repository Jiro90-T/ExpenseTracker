package io.github.jiro.expensetracker.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jiro.expensetracker.R
import io.github.jiro.expensetracker.data.local.TransactionWithCategory
import io.github.jiro.expensetracker.domain.model.TransactionType
import io.github.jiro.expensetracker.export.TransactionCsvShare
import io.github.jiro.expensetracker.ui.charts.MonthlyBarChart
import io.github.jiro.expensetracker.ui.charts.MonthlyTotals
import io.github.jiro.expensetracker.ui.theme.ExpenseTrackerTheme
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import android.content.Intent
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onAddClick: () -> Unit = {},
    onEditClick: (Long) -> Unit = {},
    onManageCategories: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val transactions by viewModel.transactions.collectAsStateWithLifecycle()
    val visibleTransactions by viewModel.visibleTransactions.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val period by viewModel.period.collectAsStateWithLifecycle()
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val monthlyTotals by viewModel.monthlyTotals.collectAsStateWithLifecycle()
    val undoState by viewModel.undo.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val undoLabel = stringResource(R.string.action_undo)
    val deletedLabel = stringResource(R.string.snackbar_transaction_deleted)
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val shareLabel = stringResource(R.string.action_share_csv)
    val shareChooserTitle = stringResource(R.string.share_chooser_title)

    LaunchedEffect(undoState) {
        val pending = undoState ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = deletedLabel,
            actionLabel = undoLabel,
            withDismissAction = true,
        )
        when (result) {
            SnackbarResult.ActionPerformed -> viewModel.undoDelete()
            SnackbarResult.Dismissed -> viewModel.dismissUndo()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                actions = {
                    IconButton(onClick = onManageCategories) {
                        Icon(
                            Icons.Filled.Category,
                            contentDescription = stringResource(R.string.action_manage_categories),
                        )
                    }
                    IconButton(
                        onClick = {
                            coroutineScope.launch {
                                val csv = viewModel.buildCsvForCurrentPeriod()
                                val intent = TransactionCsvShare.share(
                                    context = context,
                                    csv = csv,
                                    periodLabel = period.label(),
                                )
                                context.startActivity(Intent.createChooser(intent, shareChooserTitle))
                            }
                        },
                        enabled = transactions.isNotEmpty(),
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = shareLabel)
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddClick) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.action_add_transaction))
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            SearchField(
                query = searchQuery,
                onQueryChange = viewModel::setSearchQuery,
                onClear = viewModel::clearSearch,
            )
            PeriodSelector(
                period = period,
                onPeriodChange = viewModel::setPeriod,
                onStepMonth = viewModel::stepMonth,
            )
            DashboardSummaryCard(summary = summary)
            MonthlyTrendCard(data = monthlyTotals)
            when {
                transactions.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.home_empty),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                visibleTransactions.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.search_no_matches, searchQuery),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                else -> {
                    val grouped = remember(visibleTransactions) { groupByDay(visibleTransactions) }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        grouped.forEach { group ->
                            item(key = "day_${group.dayStartMs}") {
                                DayHeader(group.dayStartMs)
                            }
                            items(group.items, key = { it.transaction.id }) { row ->
                                SwipeableTransactionRow(
                                    row = row,
                                    onEdit = { onEditClick(row.transaction.id) },
                                    onDelete = { viewModel.delete(row) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchField(
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableTransactionRow(
    row: TransactionWithCategory,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else {
                false
            }
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = { DeleteBackground(dismissState.dismissDirection) },
    ) {
        TransactionRow(row = row, onClick = onEdit)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeleteBackground(direction: SwipeToDismissBoxValue) {
    val color = MaterialTheme.colorScheme.errorContainer
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(color)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        if (direction == SwipeToDismissBoxValue.EndToStart) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun TransactionRow(row: TransactionWithCategory, onClick: () -> Unit) {
    val txn = row.transaction
    val category = row.category
    val type = TransactionType.fromStorage(txn.type)
    val sign = if (type == TransactionType.EXPENSE) "-" else "+"
    val amountColor = if (type == TransactionType.EXPENSE) {
        MaterialTheme.colorScheme.error
    } else {
        Color(0xFF1B5E20) // dark green for income
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Text(txn.title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = "${category.name} · ${txn.currencyCode} " +
                "$sign${txn.amountMinor / 100}.${"%02d".format(txn.amountMinor % 100)}",
            style = MaterialTheme.typography.bodySmall,
            color = amountColor,
        )
        if (!txn.note.isNullOrBlank()) {
            Text(
                text = txn.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MonthlyTrendCard(data: List<MonthlyTotals>) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.dashboard_monthly_trend),
                style = MaterialTheme.typography.titleSmall,
            )
            MonthlyBarChart(data = data)
        }
    }
}

@Composable
private fun DayHeader(dayStartMs: Long) {
    Text(
        text = formatDayHeader(dayStartMs),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

private data class DayGroup(
    val dayStartMs: Long,
    val items: List<TransactionWithCategory>,
)

private fun groupByDay(rows: List<TransactionWithCategory>): List<DayGroup> {
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

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    ExpenseTrackerTheme {
        HomeScreen()
    }
}
