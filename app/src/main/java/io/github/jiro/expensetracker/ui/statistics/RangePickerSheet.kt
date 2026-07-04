package io.github.jiro.expensetracker.ui.statistics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.jiro.expensetracker.R
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RangePickerSheet(
    currentStartMs: Long,
    currentEndMs: Long,
    onDismiss: () -> Unit,
    onConfirm: (startMs: Long, endMs: Long) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val pickerState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = currentStartMs,
        initialSelectedEndDateMillis = currentEndMs,
    )

    var activePreset: StatisticsPreset? by remember { mutableStateOf(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.stats_picker_title),
                style = MaterialTheme.typography.titleLarge,
            )

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(StatisticsPreset.all) { preset ->
                    FilterChip(
                        selected = activePreset == preset,
                        onClick = {
                            activePreset = preset
                            val nowMs = System.currentTimeMillis()
                            val r = preset.resolve(nowMs)
                            pickerState.setSelection(
                                LocalDate.ofEpochDay(r.first / 86_400_000L)
                                    .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                                LocalDate.ofEpochDay(r.last / 86_400_000L)
                                    .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                            )
                        },
                        label = { Text(preset.label) },
                    )
                }
            }

            DateRangePicker(
                state = pickerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(500.dp),
                showModeToggle = false,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.stats_picker_cancel))
                }
                Button(
                    onClick = {
                        val s = pickerState.selectedStartDateMillis
                        val e = pickerState.selectedEndDateMillis
                        if (s != null && e != null) {
                            // Caller (Task 11) is responsible for converting
                            // [midnight-of-last-day, end] to exclusive end
                            // if it wants [start, end) half-open semantics.
                            onConfirm(s, e)
                        }
                    },
                    enabled = pickerState.selectedStartDateMillis != null &&
                        pickerState.selectedEndDateMillis != null,
                ) {
                    Text(stringResource(R.string.stats_picker_apply))
                }
            }
        }
    }
}