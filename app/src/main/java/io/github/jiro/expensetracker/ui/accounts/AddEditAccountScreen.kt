package io.github.jiro.expensetracker.ui.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jiro.expensetracker.R
import io.github.jiro.expensetracker.data.local.MoneyFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditAccountScreen(
    onBack: () -> Unit,
    viewModel: AddEditAccountViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.saveComplete) {
        if (state.saveComplete) onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(
                        if (state.isEdit) R.string.account_edit_title
                        else R.string.account_add_title,
                    ))
                },
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
        AddEditAccountForm(
            state = state,
            viewModel = viewModel,
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddEditAccountForm(
    state: AddEditAccountUiState,
    viewModel: AddEditAccountViewModel,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    Column(
        modifier = modifier
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Icon picker
        Text(
            text = stringResource(R.string.field_account_icon),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            ACCOUNT_ICON_CHOICES.forEach { emoji ->
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (state.icon == emoji) MaterialTheme.colorScheme.primaryContainer
                            else Color.Transparent,
                        )
                        .clickable { viewModel.onIconChange(emoji) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(emoji, style = MaterialTheme.typography.titleLarge)
                }
            }
        }

        // Color picker
        Text(
            text = stringResource(R.string.field_account_color),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ACCOUNT_COLOR_CHOICES.forEach { argb ->
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(argb))
                        .border(
                            width = if (state.color == argb) 3.dp else 0.dp,
                            color = MaterialTheme.colorScheme.onSurface,
                            shape = RoundedCornerShape(16.dp),
                        )
                        .clickable { viewModel.onColorChange(argb) },
                )
            }
        }

        // Name
        OutlinedTextField(
            value = state.name,
            onValueChange = viewModel::onNameChange,
            label = { Text(stringResource(R.string.field_account_name)) },
            singleLine = true,
            isError = state.error == AccountFormError.NAME_REQUIRED ||
                state.error == AccountFormError.NAME_DUPLICATE,
            supportingText = {
                when (state.error) {
                    AccountFormError.NAME_REQUIRED -> Text(stringResource(R.string.error_account_name_required))
                    AccountFormError.NAME_DUPLICATE -> Text(stringResource(R.string.error_account_name_duplicate))
                    else -> Unit
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        // Type dropdown
        var typeExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = typeExpanded,
            onExpandedChange = { typeExpanded = it },
        ) {
            OutlinedTextField(
                value = presetLabel(state.type),
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.field_account_type)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth(),
            )
            ExposedDropdownMenu(
                expanded = typeExpanded,
                onDismissRequest = { typeExpanded = false },
            ) {
                ACCOUNT_TYPE_PRESETS.forEach { preset ->
                    DropdownMenuItem(
                        text = { Text(presetLabel(preset)) },
                        onClick = {
                            viewModel.onTypeChange(preset)
                            typeExpanded = false
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.account_type_custom)) },
                    onClick = {
                        viewModel.onTypeChange("OTHER")
                        typeExpanded = false
                    },
                )
            }
        }
        if (state.type == "OTHER") {
            OutlinedTextField(
                value = state.customType,
                onValueChange = viewModel::onCustomTypeChange,
                label = { Text(stringResource(R.string.account_type_custom)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Currency (locked when editing)
        OutlinedTextField(
            value = state.currency,
            onValueChange = viewModel::onCurrencyChange,
            label = { Text(stringResource(R.string.field_account_currency)) },
            enabled = !state.isCurrencyLocked,
            singleLine = true,
            supportingText = {
                if (state.isCurrencyLocked) {
                    Text(stringResource(R.string.hint_account_currency_locked))
                } else if (state.error == AccountFormError.CURRENCY_REQUIRED) {
                    Text(stringResource(R.string.error_account_currency_required))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        // Opening balance (Add only — Edit uses Adjust balance)
        if (!state.isEdit) {
            OutlinedTextField(
                value = state.openingBalanceInput,
                onValueChange = viewModel::onOpeningBalanceChange,
                label = { Text(stringResource(R.string.field_account_opening_balance)) },
                supportingText = { Text(stringResource(R.string.field_account_opening_balance_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = viewModel::save,
            enabled = !state.isSaving && state.isLoaded,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_save))
        }

        if (state.isEdit && state.hasTransactions) {
            OutlinedButton(
                onClick = viewModel::openAdjustDialog,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_adjust_balance))
            }
        }
    }

    state.adjustDialog?.let { dialog ->
        AlertDialog(
            onDismissRequest = viewModel::closeAdjustDialog,
            title = { Text(stringResource(R.string.action_adjust_balance_dialog_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(
                            R.string.action_adjust_balance_dialog_body,
                            MoneyFormat.formatForDisplay(state.currentBalanceMinor),
                        ),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = dialog.newBalanceInput,
                        onValueChange = viewModel::onAdjustNewBalanceChange,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::confirmAdjustBalance,
                    enabled = !dialog.isSaving,
                ) { Text(stringResource(R.string.action_adjust_balance_dialog_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::closeAdjustDialog) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun presetLabel(preset: String): String = when (preset) {
    "CASH" -> stringResource(R.string.account_type_cash)
    "BANK" -> stringResource(R.string.account_type_bank)
    "CREDIT_CARD" -> stringResource(R.string.account_type_credit_card)
    "EWALLET" -> stringResource(R.string.account_type_ewallet)
    "OTHER" -> stringResource(R.string.account_type_other)
    else -> preset
}
