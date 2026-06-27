package io.github.jiro.expensetracker.ui.budget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jiro.expensetracker.R
import io.github.jiro.expensetracker.data.local.MoneyFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetScreen(viewModel: BudgetViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val dialog by viewModel.editDialog.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.budgets_title)) }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text(
                text = state.monthLabel,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            if (state.missingRateCount > 0) {
                FxWarningRow()
                Spacer(Modifier.height(8.dp))
            }
            if (state.isLoaded) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.rows, key = { it.categoryId }) { row ->
                        BudgetRow(row = row, onClick = { viewModel.openEdit(row.categoryId) })
                    }
                }
            }
        }
    }

    dialog?.let { d ->
        BudgetEditDialog(
            dialog = d,
            onAmountChange = viewModel::onAmountInputChange,
            onSubmit = viewModel::submitEdit,
            onClear = { viewModel.clearLimit(d.categoryId) },
            onDismiss = viewModel::closeEdit,
        )
    }
}

@Composable
private fun FxWarningRow() {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.budgets_fx_missing_warning),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun BudgetRow(row: BudgetRowUiState, onClick: () -> Unit) {
    val limit = row.limitMinor
    val trackColor = if (row.isOverspent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.categoryName,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            if (limit == null) {
                Text(
                    text = stringResource(R.string.budgets_no_budget),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (limit != null) {
            // Inside this branch `limit` smart-casts to `Long` (non-nullable),
            // so `percent` is also non-nullable here — no `!!` needed.
            val percent = ((row.spentMinor * 100L) / limit).toInt()
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { (percent / 100f).coerceIn(0f, 1f) },
                color = trackColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().height(6.dp),
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        R.string.budgets_progress_format,
                        formatMoney(row.spentMinor),
                        formatMoney(limit),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.budgets_percent_format, percent),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (row.isOverspent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (row.isOverspent) {
                val overByMinor = row.spentMinor - limit
                Text(
                    text = stringResource(R.string.budgets_overspent_format, formatMoney(overByMinor)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun BudgetEditDialog(
    dialog: BudgetEditDialogState,
    onAmountChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (dialog.currentLimitMinor == null) {
                    stringResource(R.string.budgets_set, dialog.categoryName)
                } else {
                    stringResource(R.string.budgets_edit, dialog.categoryName)
                },
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = dialog.amountInput,
                    onValueChange = onAmountChange,
                    label = { Text(stringResource(R.string.budgets_amount_label, dialog.homeCurrency)) },
                    placeholder = { Text(stringResource(R.string.budgets_amount_helper)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = dialog.isInvalid,
                    supportingText = {
                        if (dialog.isInvalid) Text(stringResource(R.string.budgets_amount_invalid))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSubmit) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            Row {
                if (dialog.currentLimitMinor != null) {
                    TextButton(onClick = onClear) { Text(stringResource(R.string.budgets_clear)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

/** Format a minor-unit value for display in budget rows (with thousands separator). */
private fun formatMoney(minor: Long): String = MoneyFormat.formatForDisplay(minor)
