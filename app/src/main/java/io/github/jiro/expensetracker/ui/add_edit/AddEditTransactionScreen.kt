package io.github.jiro.expensetracker.ui.add_edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jiro.expensetracker.R
import io.github.jiro.expensetracker.data.RecurrenceKind
import io.github.jiro.expensetracker.domain.model.TransactionType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditTransactionScreen(
    onBack: () -> Unit,
    onManageSeries: (String) -> Unit = {},
    viewModel: AddEditTransactionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.saveComplete) {
        if (state.saveComplete) onBack()
    }

    val isEdit = state.id != null
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (isEdit) R.string.title_edit_transaction
                            else R.string.title_add_transaction
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        AddEditForm(
            state = state,
            viewModel = viewModel,
            onManageSeries = onManageSeries,
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddEditForm(
    state: AddEditTransactionUiState,
    viewModel: AddEditTransactionViewModel,
    onManageSeries: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDatePicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Type toggle
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = state.type == TransactionType.EXPENSE,
                onClick = { viewModel.onTypeChange(TransactionType.EXPENSE) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) { Text(stringResource(R.string.type_expense)) }
            SegmentedButton(
                selected = state.type == TransactionType.INCOME,
                onClick = { viewModel.onTypeChange(TransactionType.INCOME) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text(stringResource(R.string.type_income)) }
        }

        // Title
        OutlinedTextField(
            value = state.title,
            onValueChange = viewModel::onTitleChange,
            label = { Text(stringResource(R.string.field_title)) },
            singleLine = true,
            isError = state.error == FormError.TITLE_REQUIRED,
            supportingText = {
                if (state.error == FormError.TITLE_REQUIRED) {
                    Text(stringResource(R.string.error_title_required))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        // Amount
        OutlinedTextField(
            value = state.amountInput,
            onValueChange = viewModel::onAmountChange,
            label = { Text(stringResource(R.string.field_amount)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            isError = state.error == FormError.AMOUNT_INVALID,
            supportingText = {
                if (state.error == FormError.AMOUNT_INVALID) {
                    Text(stringResource(R.string.error_amount_invalid))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        // Category dropdown
        CategoryDropdown(
            state = state,
            onCategoryChange = viewModel::onCategoryChange,
        )

        // Date
        OutlinedTextField(
            value = formatDate(state.occurredAtEpochMillis),
            onValueChange = {},
            label = { Text(stringResource(R.string.field_date)) },
            readOnly = true,
            modifier = Modifier
                .fillMaxWidth(),
            trailingIcon = {
                TextButton(onClick = { showDatePicker = true }) {
                    Text(stringResource(R.string.action_pick))
                }
            },
        )

        // Note
        OutlinedTextField(
            value = state.note,
            onValueChange = viewModel::onNoteChange,
            label = { Text(stringResource(R.string.field_note)) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )

        // Recurring (Phase 2.1) — a simple toggle + a couple of fields.
        // End-condition UI is out of scope for the MVP; series run
        // indefinitely until the user deletes them.
        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Switch(
                checked = state.isRecurring,
                onCheckedChange = viewModel::onRecurringToggle,
            )
            Spacer(Modifier.padding(start = 8.dp))
            Text(
                text = stringResource(R.string.field_recurring),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        if (state.isRecurring) {
            Spacer(Modifier.height(8.dp))
            RecurrenceOptions(
                kind = state.recurrenceKind,
                interval = state.recurrenceInterval,
                onKindChange = viewModel::onRecurrenceKindChange,
                onIntervalChange = viewModel::onRecurrenceIntervalChange,
            )

            // End-condition picker. Limit 1 of the recurring work — the
            // schema was already there (recurrenceEndAt / recurrenceMaxOccurrences);
            // this just surfaces the controls.
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.field_recurrence_end),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                RecurrenceEndMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = state.recurrenceEndMode == mode,
                        onClick = { viewModel.onRecurrenceEndModeChange(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = RecurrenceEndMode.entries.size),
                    ) { Text(stringResource(endModeLabelRes(mode))) }
                }
            }
            when (state.recurrenceEndMode) {
                RecurrenceEndMode.NEVER -> Unit
                RecurrenceEndMode.ON_DATE -> {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.recurrenceEndAt?.let {
                            SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(it))
                        } ?: "—",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.field_end_date)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                // Trigger the existing date picker
                                onDateClick()
                            },
                        trailingIcon = {
                            TextButton(onClick = onDateClick) { Text(stringResource(R.string.action_pick)) }
                        },
                    )
                }
                RecurrenceEndMode.AFTER_N_OCCURRENCES -> {
                    Spacer(Modifier.height(8.dp))
                    MaxOccurrencesStepper(
                        value = state.recurrenceMaxOccurrences,
                        onChange = viewModel::onRecurrenceMaxOccurrencesChange,
                    )
                }
            }

            // Manage-series entry point. Visible only for EXISTING recurring
            // transactions (where recurringGroupId is set). For new
            // recurring transactions, the form fields above are enough.
            if (state.recurringGroupId != null) {
                Spacer(Modifier.height(12.dp))
                TextButton(
                    onClick = { onManageSeries(state.recurringGroupId) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.action_manage_series))
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = viewModel::save,
            enabled = !state.isSaving && state.isLoaded,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_save))
        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = state.occurredAtEpochMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val millis = datePickerState.selectedDateMillis
                    if (millis != null) viewModel.onDateChange(millis)
                    showDatePicker = false
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showEndDatePicker) {
        val endDatePickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.recurrenceEndAt ?: System.currentTimeMillis(),
        )
        DatePickerDialog(
            onDismissRequest = { showEndDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val millis = endDatePickerState.selectedDateMillis
                    if (millis != null) viewModel.onRecurrenceEndDateChange(millis)
                    showEndDatePicker = false
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showEndDatePicker = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        ) {
            DatePicker(state = endDatePickerState)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryDropdown(
    state: AddEditTransactionUiState,
    onCategoryChange: (Long) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = state.categoriesForType.firstOrNull { it.id == state.selectedCategoryId }
    val isError = state.error == FormError.CATEGORY_REQUIRED

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selected?.name.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.field_category)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            isError = isError,
            supportingText = {
                if (isError) Text(stringResource(R.string.error_category_required))
            },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            if (state.categoriesForType.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.empty_categories)) },
                    onClick = { expanded = false },
                    enabled = false,
                )
            } else {
                state.categoriesForType.forEach { cat ->
                    DropdownMenuItem(
                        text = { Text(cat.name) },
                        onClick = {
                            onCategoryChange(cat.id)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

/**
 * Recurring-transaction options: kind (DAILY / WEEKLY / MONTHLY / YEARLY)
 * and interval (every N). End-condition UI is out of scope for the MVP —
 * the series runs indefinitely until the user deletes the parent row.
 */
@Composable
private fun RecurrenceOptions(
    kind: RecurrenceKind,
    interval: Int,
    onKindChange: (RecurrenceKind) -> Unit,
    onIntervalChange: (Int) -> Unit,
) {
    Column {
        Text(
            text = stringResource(R.string.field_recurrence_kind),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            RecurrenceKind.entries.forEachIndexed { index, k ->
                SegmentedButton(
                    selected = kind == k,
                    onClick = { onKindChange(k) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = RecurrenceKind.entries.size),
                ) {
                    Text(stringResource(kindLabelRes(k)))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.field_recurrence_interval, interval),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedIconButton(
                onClick = { onIntervalChange((interval - 1).coerceAtLeast(1)) },
                enabled = interval > 1,
            ) {
                Icon(Icons.Filled.Remove, contentDescription = "Decrease interval")
            }
            Text(
                text = interval.toString(),
                style = MaterialTheme.typography.titleLarge,
            )
            OutlinedIconButton(
                onClick = { onIntervalChange((interval + 1).coerceAtMost(30)) },
                enabled = interval < 30,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Increase interval")
            }
        }
    }
}

private fun kindLabelRes(kind: RecurrenceKind): Int = when (kind) {
    RecurrenceKind.DAILY -> R.string.recurrence_daily
    RecurrenceKind.WEEKLY -> R.string.recurrence_weekly
    RecurrenceKind.MONTHLY -> R.string.recurrence_monthly
    RecurrenceKind.YEARLY -> R.string.recurrence_yearly
}

private fun formatDate(epochMillis: Long): String =
    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(epochMillis))
