package io.github.jiro.expensetracker.ui.investments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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

private val SUPPORTED_CURRENCIES = listOf(
    "USD", "EUR", "GBP", "JPY", "AUD", "CAD", "CHF",
    "SGD", "HKD", "MYR", "CNY", "BTC", "ETH",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditHoldingScreen(
    onBack: () -> Unit,
    viewModel: AddEditHoldingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.saveComplete) {
        if (state.saveComplete) onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.isEdit) R.string.holding_edit_title else R.string.holding_add_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.symbol,
                onValueChange = viewModel::onSymbolChange,
                label = { Text(stringResource(R.string.field_holding_symbol)) },
                singleLine = true,
                isError = state.error == HoldingFormError.SYMBOL_REQUIRED ||
                    state.error == HoldingFormError.SYMBOL_TOO_LONG,
                supportingText = {
                    when (state.error) {
                        HoldingFormError.SYMBOL_REQUIRED -> Text(stringResource(R.string.error_holding_symbol_required))
                        HoldingFormError.SYMBOL_TOO_LONG -> Text(stringResource(R.string.error_holding_symbol_too_long))
                        else -> Unit
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.quantityInput,
                onValueChange = viewModel::onQuantityChange,
                label = { Text(stringResource(R.string.field_holding_quantity)) },
                singleLine = true,
                isError = state.error == HoldingFormError.QUANTITY_INVALID,
                supportingText = {
                    if (state.error == HoldingFormError.QUANTITY_INVALID)
                        Text(stringResource(R.string.error_holding_quantity_invalid))
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.costBasisInput,
                onValueChange = viewModel::onCostBasisChange,
                label = { Text(stringResource(R.string.field_holding_cost_basis)) },
                singleLine = true,
                isError = state.error == HoldingFormError.COST_INVALID,
                supportingText = {
                    if (state.error == HoldingFormError.COST_INVALID)
                        Text(stringResource(R.string.error_holding_cost_invalid))
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )

            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = state.currency,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.field_holding_currency)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    SUPPORTED_CURRENCIES.forEach { code ->
                        DropdownMenuItem(
                            text = { Text(code) },
                            onClick = {
                                viewModel.onCurrencyChange(code)
                                expanded = false
                            },
                        )
                    }
                }
            }

            Button(
                onClick = viewModel::save,
                enabled = !state.isSaving && state.isLoaded,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.action_save)) }
        }
    }
}
