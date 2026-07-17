package io.github.jiro.expensetracker.ui.investments

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jiro.expensetracker.R
import io.github.jiro.expensetracker.data.local.MoneyFormat
import io.github.jiro.expensetracker.ui.theme.IncomeGreen
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvestmentAccountDetailScreen(
    onBack: () -> Unit,
    onEditAccount: (Long) -> Unit,
    onAddHolding: (Long) -> Unit,
    onEditHolding: (accountId: Long, holdingId: Long) -> Unit,
    viewModel: InvestmentAccountDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val account = state.account

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(account?.name ?: stringResource(R.string.accounts_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !state.isRefreshing) {
                        if (state.isRefreshing) {
                            CircularProgressIndicator(modifier = Modifier.padding(8.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.investment_refresh))
                        }
                    }
                    if (account != null) {
                        if (!account.archived) {
                            IconButton(onClick = viewModel::onCloseClick) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.account_close))
                            }
                            IconButton(onClick = viewModel::onDeleteClick) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = stringResource(R.string.action_delete),
                                )
                            }
                        }
                        IconButton(onClick = { onEditAccount(account.id) }) {
                            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.action_edit))
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (account != null) {
                ExtendedFloatingActionButton(
                    onClick = { onAddHolding(account.id) },
                    text = { Text(stringResource(R.string.investment_add_holding)) },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                )
            }
        },
    ) { padding ->
        if (account == null) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("—")
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("total") { TotalCard(state = state, currency = account.currencyCode) }
            if (state.missingFxPairs.isNotEmpty()) {
                item("fx_warning") { FxWarningChip(pairs = state.missingFxPairs) }
            }
            if (state.holdings.isEmpty()) {
                item("empty") {
                    Text(
                        text = stringResource(R.string.investment_empty_title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    )
                }
            } else {
                items(state.holdings, key = { it.holding.id }) { row ->
                    HoldingRowView(
                        row = row,
                        currency = account.currencyCode,
                        onClick = { onEditHolding(account.id, row.holding.id) },
                    )
                }
            }
        }
    }

    if (state.showCloseConfirm) {
        AlertDialog(
            onDismissRequest = viewModel::onCloseDismiss,
            title = { Text(stringResource(R.string.investment_close_confirm_title)) },
            text = { Text(stringResource(R.string.investment_close_confirm_message)) },
            confirmButton = {
                TextButton(onClick = viewModel::onCloseConfirm) { Text(stringResource(R.string.account_close)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::onCloseDismiss) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    if (state.showDeleteBlocked) {
        AlertDialog(
            onDismissRequest = viewModel::onDeleteDismiss,
            title = { Text(stringResource(R.string.account_delete_blocked_holdings_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.investment_delete_blocked_message,
                        state.holdingsCount,
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::onDeleteDismiss) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    if (state.showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = viewModel::onDeleteDismiss,
            title = { Text(stringResource(R.string.investment_delete_confirm_title)) },
            text = { Text(stringResource(R.string.investment_delete_confirm_message)) },
            confirmButton = {
                TextButton(onClick = viewModel::onDeleteConfirm) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::onDeleteDismiss) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    LaunchedEffect(viewModel) {
        viewModel.closeEvent.collectLatest { onBack() }
    }

    LaunchedEffect(viewModel) {
        viewModel.deleteEvent.collectLatest { onBack() }
    }

    // Auto-refresh once when holdings first become non-empty. Done at the
    // screen layer (not in VM init) to keep VM unit tests invariant-clean:
    // tests assert refreshCount == 0 after advanceUntilIdle() with no UI
    // attached, which this LaunchedEffect only fires inside the Compose tree.
    LaunchedEffect(viewModel, state.holdings.isNotEmpty()) {
        if (state.holdings.isNotEmpty()) {
            viewModel.refresh()
        }
    }
}

@Composable
private fun TotalCard(state: InvestmentDetailUiState, currency: String) {
    val currencies = remember(state.holdings) {
        state.holdings.mapNotNull { it.cachedPriceCurrency }.distinct()
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = MoneyFormat.formatForDisplay(state.totalValueMinor) + " " + currency,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        if (state.holdings.isNotEmpty() && currencies.size > 1) {
            Text(
                text = stringResource(
                    R.string.investment_total_across,
                    state.holdings.size,
                    currencies.size,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(
                R.string.investment_total_invested,
                MoneyFormat.formatForDisplay(state.totalCostMinor) + " " + currency,
            ) + " · " +
                stringResource(
                    R.string.investment_total_current,
                    MoneyFormat.formatForDisplay(state.totalValueMinor) + " " + currency,
                ),
            style = MaterialTheme.typography.bodyMedium,
        )
        val sign = if (state.unrealizedMinor >= 0) "+" else "−"
        val absMinor = abs(state.unrealizedMinor)
        val color = if (state.unrealizedMinor >= 0) IncomeGreen else MaterialTheme.colorScheme.error
        Text(
            text = stringResource(
                R.string.investment_unrealized,
                "$sign${MoneyFormat.formatForDisplay(absMinor)} $currency",
            ),
            style = MaterialTheme.typography.titleMedium,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun FxWarningChip(pairs: List<String>) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            pairs.forEach { pair ->
                val (from, to) = pair.split("_to_").let { it[0] to it[1] }
                Text(
                    text = stringResource(R.string.investment_fx_missing, from, to),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun HoldingRowView(row: HoldingRow, currency: String, onClick: () -> Unit) {
    val valueText = row.marketValueInAccountCurrencyMinor?.let {
        MoneyFormat.formatForDisplay(it) + " " + currency
    } ?: stringResource(R.string.investment_no_price)
    val avgCostPerShareText = if (row.holding.quantity > 0) {
        val perShareMinor = (row.holding.costBasisMinor / row.holding.quantity).toLong()
        MoneyFormat.formatForDisplay(perShareMinor) + " " + row.holding.currencyCode
    } else "—"
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        onClick = onClick,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.holding.symbol,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(
                            R.string.investment_holding_subtitle,
                            "%.4f".format(Locale.US, row.holding.quantity).trimEnd('0').trimEnd('.'),
                            row.holding.currencyCode,
                            avgCostPerShareText,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(valueText, style = MaterialTheme.typography.titleMedium)
                    val unreal = row.unrealizedInAccountCurrencyMinor
                    if (unreal != null) {
                        val sign = if (unreal >= 0) "+" else "−"
                        val color = if (unreal >= 0) IncomeGreen else MaterialTheme.colorScheme.error
                        Text(
                            text = "$sign${MoneyFormat.formatForDisplay(abs(unreal))} $currency",
                            style = MaterialTheme.typography.bodySmall,
                            color = color,
                        )
                    }
                }
            }
            if (row.stale && row.cachedAtEpochMillis != null) {
                val relTime = DateUtils.getRelativeTimeSpanString(
                    row.cachedAtEpochMillis,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                )
                Text(
                    text = stringResource(R.string.investment_stale_updated, relTime),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
