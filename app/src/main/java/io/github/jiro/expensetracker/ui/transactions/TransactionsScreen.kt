package io.github.jiro.expensetracker.ui.transactions

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jiro.expensetracker.R
import io.github.jiro.expensetracker.export.TransactionCsvShare
import io.github.jiro.expensetracker.ui.home.DashboardSummaryCard
import io.github.jiro.expensetracker.ui.home.DayHeader
import io.github.jiro.expensetracker.ui.home.HomeViewModel
import io.github.jiro.expensetracker.ui.home.MonthlyTrendCard
import io.github.jiro.expensetracker.ui.home.PeriodSelector
import io.github.jiro.expensetracker.ui.home.SearchField
import io.github.jiro.expensetracker.ui.home.SwipeableTransactionRow
import io.github.jiro.expensetracker.ui.home.groupByDay
import io.github.jiro.expensetracker.ui.home.label
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(
    onTransactionClick: (Long) -> Unit = {},
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
                title = { Text(stringResource(R.string.transactions_title)) },
                actions = {
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
    ) { padding ->
        val groupedTransactions = remember(visibleTransactions) {
            groupByDay(visibleTransactions)
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "search") {
                SearchField(
                    query = searchQuery,
                    onQueryChange = viewModel::setSearchQuery,
                    onClear = viewModel::clearSearch,
                )
            }
            item(key = "period") {
                PeriodSelector(
                    period = period,
                    onPeriodChange = viewModel::setPeriod,
                    onStepMonth = viewModel::stepMonth,
                )
            }
            item(key = "dashboard") {
                DashboardSummaryCard(summary = summary)
            }
            item(key = "monthly") {
                MonthlyTrendCard(data = monthlyTotals)
            }
            when {
                transactions.isEmpty() -> item(key = "empty") {
                    Text(
                        text = stringResource(R.string.home_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                    )
                }
                visibleTransactions.isEmpty() -> item(key = "no_matches") {
                    Text(
                        text = stringResource(R.string.search_no_matches, searchQuery),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                    )
                }
                else -> {
                    groupedTransactions.forEach { group ->
                        item(key = "day_${group.dayStartMs}") {
                            DayHeader(group.dayStartMs)
                        }
                        items(group.items, key = { it.transaction.id }) { row ->
                            SwipeableTransactionRow(
                                row = row,
                                onEdit = { onTransactionClick(row.transaction.id) },
                                onDelete = { viewModel.delete(row) },
                            )
                        }
                    }
                }
            }
        }
    }
}
