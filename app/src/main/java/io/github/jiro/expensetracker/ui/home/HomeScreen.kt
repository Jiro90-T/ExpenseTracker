package io.github.jiro.expensetracker.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jiro.expensetracker.R
import io.github.jiro.expensetracker.ui.theme.ExpenseTrackerTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onSeeAllTransactions: () -> Unit = {},
    onNavigateToBudget: () -> Unit = {},
    reselectTrigger: Int = 0,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val listState = rememberLazyListState()
    LaunchedEffect(reselectTrigger) {
        if (reselectTrigger > 0) listState.animateScrollToItem(0)
    }

    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val monthlyTotals by viewModel.monthlyTotals.collectAsStateWithLifecycle()
    val period by viewModel.period.collectAsStateWithLifecycle()
    val undoState by viewModel.undo.collectAsStateWithLifecycle()
    val budgetAlerts by viewModel.budgetAlerts.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val undoLabel = stringResource(R.string.action_undo)
    val deletedLabel = stringResource(R.string.snackbar_transaction_deleted)

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
                    IconButton(onClick = onSeeAllTransactions) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.List,
                            contentDescription = stringResource(R.string.action_see_all_transactions),
                        )
                    }
                },
                // DESIGN 2: vibrant purple header bar with white text.
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (budgetAlerts.isNotEmpty()) {
                item(key = "budget_alerts") {
                    BudgetAlertsSection(alerts = budgetAlerts, onClick = onNavigateToBudget)
                }
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
            item(key = "see_all") {
                TextButton(
                    onClick = onSeeAllTransactions,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                ) {
                    Text(stringResource(R.string.action_see_all_transactions))
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    ExpenseTrackerTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            Text("Home preview (ViewModel-dependent)")
        }
    }
}

@Composable
private fun BudgetAlertsSection(
    alerts: List<BudgetAlert>,
    onClick: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.home_budget_alerts_header),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.size(8.dp))
            alerts.forEach { alert ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = alert.categoryName,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(R.string.home_budget_alert_over_by, alert.overageFormatted),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.home_budget_navigate),
                    )
                }
            }
        }
    }
}
