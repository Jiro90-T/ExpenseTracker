package io.github.jiro.expensetracker.ui.accounts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jiro.expensetracker.R
import io.github.jiro.expensetracker.data.local.MoneyFormat
import io.github.jiro.expensetracker.ui.home.TransactionRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountDetailScreen(
    onBack: () -> Unit,
    onEditAccount: (Long) -> Unit,
    onTransactionClick: (Long) -> Unit,
    viewModel: AccountDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val aw = state.accountWithBalance

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(aw?.account?.name ?: stringResource(R.string.accounts_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    if (aw != null) {
                        if (aw.account.id != 1L) {
                            IconButton(onClick = viewModel::onDeleteClick) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = stringResource(R.string.account_delete),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        IconButton(onClick = { onEditAccount(aw.account.id) }) {
                            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.action_edit))
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (aw == null && !state.isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("—")
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
        ) {
            aw?.let {
                item("header") {
                    BalanceHeader(name = it.account.name, balanceMinor = it.balanceMinor, currencyCode = it.account.currencyCode)
                    Spacer(Modifier.height(16.dp))
                }
            }
            items(state.transactions, key = { it.transaction.id }) { row ->
                TransactionRow(row = row, onClick = { onTransactionClick(row.transaction.id) })
            }
        }
    }

    LaunchedEffect(state.deleted) {
        if (state.deleted) onBack()
    }

    if (state.showDeleteConfirm) {
        val account = state.accountWithBalance?.account
        when (state.deleteGuard) {
            DeleteGuard.ALLOW -> AlertDialog(
                onDismissRequest = viewModel::onDeleteDismiss,
                title = { Text(stringResource(R.string.account_delete_confirm_title)) },
                text = { Text(stringResource(R.string.account_delete_confirm_message, account?.name.orEmpty())) },
                confirmButton = {
                    TextButton(onClick = viewModel::onDeleteConfirm) {
                        Text(stringResource(R.string.account_delete), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::onDeleteDismiss) {
                        Text(stringResource(R.string.action_cancel))
                    }
                },
            )
            DeleteGuard.BLOCK_TRANSACTIONS_EXIST -> AlertDialog(
                onDismissRequest = viewModel::onDeleteDismiss,
                title = { Text(stringResource(R.string.account_delete_blocked_title)) },
                text = { Text(stringResource(R.string.account_delete_blocked_message, account?.name.orEmpty(), state.referenceCount)) },
                confirmButton = {
                    TextButton(onClick = viewModel::onDeleteDismiss) {
                        Text(stringResource(R.string.action_ok))
                    }
                },
            )
            null -> Unit // defensive: dialog shouldn't show in this state
        }
    }
}

@Composable
private fun BalanceHeader(name: String, balanceMinor: Long, currencyCode: String) {
    val isNegative = balanceMinor < 0
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            text = (if (isNegative) "−" else "") + MoneyFormat.formatAmountForEdit(if (isNegative) -balanceMinor else balanceMinor) + " " + currencyCode,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = if (isNegative) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
    }
}
