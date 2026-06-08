package io.github.jiro.expensetracker.ui.recurring

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jiro.expensetracker.R
import io.github.jiro.expensetracker.data.RecurrenceKind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageSeriesScreen(
    onBack: () -> Unit,
    viewModel: ManageSeriesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showDatePicker by remember { mutableStateOf(false) }

    LaunchedEffect(state.saveComplete) {
        if (state.saveComplete) onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.manage_series_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (!state.isLoaded) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("…")
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(state.title, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.manage_series_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionLabel(stringResource(R.string.field_recurrence_kind))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                RecurrenceKind.entries.forEachIndexed { index, k ->
                    SegmentedButton(
                        selected = state.kind == k,
                        onClick = { viewModel.onKindChange(k) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = RecurrenceKind.entries.size),
                    ) { Text(stringResource(kindLabelRes(k))) }
                }
            }

            SectionLabel(stringResource(R.string.field_recurrence_interval, state.interval))
            IntervalStepper(
                value = state.interval,
                onChange = viewModel::onIntervalChange,
            )

            SectionLabel(stringResource(R.string.field_recurrence_end))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SeriesEndMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = state.endMode == mode,
                        onClick = { viewModel.onEndModeChange(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = SeriesEndMode.entries.size),
                    ) { Text(stringResource(endModeLabelRes(mode))) }
                }
            }
            when (state.endMode) {
                SeriesEndMode.NEVER -> Unit
                SeriesEndMode.ON_DATE -> EndDateRow(
                    endAtMs = state.endAt,
                    onPickDate = { showDatePicker = true },
                )
                SeriesEndMode.AFTER_N_OCCURRENCES -> MaxOccurrencesStepper(
                    value = state.maxOccurrences,
                    onChange = viewModel::onMaxOccurrencesChange,
                )
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = viewModel::save,
                enabled = !state.isSaving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_save))
            }
        }
    }

    if (showDatePicker) {
        val initial = state.endAt ?: System.currentTimeMillis()
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { viewModel.onEndDateChange(it) }
                    showDatePicker = false
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun IntervalStepper(value: Int, onChange: (Int) -> Unit) {
    androidx.compose.foundation.layout.Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        androidx.compose.material3.OutlinedIconButton(
            onClick = { onChange((value - 1).coerceAtLeast(1)) },
            enabled = value > 1,
        ) {
            Icon(Icons.Filled.Remove, contentDescription = "Decrease")
        }
        Text(text = value.toString(), style = MaterialTheme.typography.titleLarge)
        androidx.compose.material3.OutlinedIconButton(
            onClick = { onChange((value + 1).coerceAtMost(30)) },
            enabled = value < 30,
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Increase")
        }
    }
}

@Composable
private fun MaxOccurrencesStepper(value: Int?, onChange: (Int) -> Unit) {
    val v = value ?: 1
    androidx.compose.foundation.layout.Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        androidx.compose.material3.OutlinedIconButton(
            onClick = { onChange((v - 1).coerceAtLeast(1)) },
            enabled = v > 1,
        ) {
            Icon(Icons.Filled.Remove, contentDescription = "Decrease")
        }
        Text(text = v.toString(), style = MaterialTheme.typography.titleLarge)
        androidx.compose.material3.OutlinedIconButton(
            onClick = { onChange(v + 1) },
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Increase")
        }
    }
}

@Composable
private fun EndDateRow(endAtMs: Long?, onPickDate: () -> Unit) {
    val display = endAtMs?.let {
        SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(it))
    } ?: "—"
    OutlinedTextField(
        value = display,
        onValueChange = {},
        readOnly = true,
        label = { Text(stringResource(R.string.field_end_date)) },
        modifier = Modifier.fillMaxWidth(),
        trailingIcon = {
            TextButton(onClick = onPickDate) { Text(stringResource(R.string.action_pick)) }
        },
    )
}

private fun kindLabelRes(kind: RecurrenceKind): Int = when (kind) {
    RecurrenceKind.DAILY -> R.string.recurrence_daily
    RecurrenceKind.WEEKLY -> R.string.recurrence_weekly
    RecurrenceKind.MONTHLY -> R.string.recurrence_monthly
    RecurrenceKind.YEARLY -> R.string.recurrence_yearly
}

private fun endModeLabelRes(mode: SeriesEndMode): Int = when (mode) {
    SeriesEndMode.NEVER -> R.string.end_mode_never
    SeriesEndMode.ON_DATE -> R.string.end_mode_on_date
    SeriesEndMode.AFTER_N_OCCURRENCES -> R.string.end_mode_after_n
}
