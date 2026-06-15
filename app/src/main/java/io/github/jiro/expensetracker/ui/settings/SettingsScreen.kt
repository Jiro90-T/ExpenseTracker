package io.github.jiro.expensetracker.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jiro.expensetracker.BuildConfig
import io.github.jiro.expensetracker.R
import io.github.jiro.expensetracker.backup.BackupFormat
import io.github.jiro.expensetracker.preferences.SUPPORTED_CURRENCIES
import io.github.jiro.expensetracker.preferences.ThemePreference
import io.github.jiro.expensetracker.preferences.parseRates

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val exportUri by viewModel.exportUri.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val themePref by viewModel.theme.collectAsStateWithLifecycle()
    val homeCurrency by viewModel.homeCurrency.collectAsStateWithLifecycle()
    val fxRates by viewModel.fxRates.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingRestoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var showHomeCurrencyDialog by remember { mutableStateOf(false) }
    var showAddRateDialog by remember { mutableStateOf(false) }
    val shareChooserTitle = stringResource(R.string.backup_share_chooser_title)

    val restorePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) pendingRestoreUri = uri
    }

    if (pendingRestoreUri != null) {
        AlertDialog(
            onDismissRequest = { pendingRestoreUri = null },
            title = { Text(stringResource(R.string.backup_restore_confirm_title)) },
            text = {
                Text(stringResource(R.string.backup_restore_confirm_message))
            },
            confirmButton = {
                TextButton(onClick = {
                    val uri = pendingRestoreUri
                    pendingRestoreUri = null
                    uri?.let { viewModel.restoreFromUri(it) }
                }) { Text(stringResource(R.string.action_restore)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestoreUri = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    LaunchedEffect(exportUri) {
        val uri = exportUri ?: return@LaunchedEffect
        viewModel.consumeExportUri()
        val send = Intent(Intent.ACTION_SEND).apply {
            type = BackupFormat.MIME_TYPE_ZIP
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Expense Tracker backup")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, shareChooserTitle))
    }

    LaunchedEffect(message) {
        val m = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(m.text)
        viewModel.consumeMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // --- Appearance ---
            SettingsSectionHeader(stringResource(R.string.settings_section_appearance))
            ThemePickerRow(
                selected = themePref,
                onSelect = viewModel::setTheme,
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // --- Data ---
            SettingsSectionHeader(stringResource(R.string.settings_section_data))
            SettingsRow(
                icon = Icons.Filled.CloudUpload,
                title = stringResource(R.string.backup_export_title),
                subtitle = stringResource(R.string.backup_export_subtitle),
                onClick = { viewModel.prepareExport() },
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsRow(
                icon = Icons.Filled.CloudDownload,
                title = stringResource(R.string.backup_restore_title),
                subtitle = stringResource(R.string.backup_restore_subtitle),
                onClick = {
                    restorePicker.launch(arrayOf("application/json", "application/zip", "*/*"))
                },
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // ---- Home currency section ----
            SettingsSectionHeader(stringResource(R.string.settings_currency_section))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = homeCurrency,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { showHomeCurrencyDialog = true }) {
                        Text(stringResource(R.string.settings_currency_edit))
                    }
                }
            }

            // ---- FX rates section ----
            SettingsSectionHeader(stringResource(R.string.settings_fx_section))
            val rateRows = remember(fxRates) { parseRates(fxRates) }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    if (rateRows.isEmpty()) {
                        Text(
                            text = stringResource(R.string.settings_fx_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    } else {
                        rateRows.forEach { row ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "${row.from}  →  ${row.to}",
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = "%.4f".format(row.rate).trimEnd('0').trimEnd('.'),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                IconButton(onClick = { viewModel.removeFxRate(row.displayKey) }) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.settings_dialog_cancel),
                                    )
                                }
                            }
                        }
                    }
                    TextButton(
                        onClick = { showAddRateDialog = true },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        Text(stringResource(R.string.settings_fx_add))
                    }
                }
            }

            // --- About ---
            SettingsSectionHeader(stringResource(R.string.settings_section_about))
            AboutBlock()
        }
    }

    if (showHomeCurrencyDialog) {
        HomeCurrencyDialog(
            current = homeCurrency,
            onDismiss = { showHomeCurrencyDialog = false },
            onConfirm = { code ->
                viewModel.setHomeCurrency(code)
                showHomeCurrencyDialog = false
            },
        )
    }
    if (showAddRateDialog) {
        AddRateDialog(
            onDismiss = { showAddRateDialog = false },
            onConfirm = { from, to, rate ->
                viewModel.addFxRate(from, to, rate)
                showAddRateDialog = false
            },
        )
    }
}

@Composable
private fun ThemePickerRow(
    selected: ThemePreference,
    onSelect: (ThemePreference) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Palette,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            androidx.compose.foundation.layout.Spacer(Modifier.padding(horizontal = 8.dp))
            Text(
                text = stringResource(R.string.settings_theme_title),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ThemePreference.entries.forEachIndexed { i, pref ->
                SegmentedButton(
                    selected = selected == pref,
                    onClick = { onSelect(pref) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = ThemePreference.entries.size),
                ) {
                    Text(stringResource(pref.labelRes))
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        androidx.compose.foundation.layout.Spacer(Modifier.padding(horizontal = 8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AboutBlock() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            androidx.compose.foundation.layout.Spacer(Modifier.padding(horizontal = 8.dp))
            Column {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        Text(
            text = stringResource(R.string.settings_repo_link),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 48.dp, top = 4.dp),
        )
    }
}

private val ThemePreference.labelRes: Int
    get() = when (this) {
        ThemePreference.SYSTEM -> R.string.settings_theme_system
        ThemePreference.LIGHT -> R.string.settings_theme_light
        ThemePreference.DARK -> R.string.settings_theme_dark
    }

@Composable
private fun HomeCurrencyDialog(
    current: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var selected by remember { mutableStateOf<String?>(null) }
    var customCode by remember { mutableStateOf("") }
    val isCustom = selected == "CUSTOM"
    val effectiveCode = if (isCustom) customCode.uppercase() else (selected ?: current)
    val isValid = effectiveCode.length == 3

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(effectiveCode) }, enabled = isValid) {
                Text(stringResource(R.string.settings_dialog_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_dialog_cancel))
            }
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.settings_currency_section),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.size(8.dp))
                SUPPORTED_CURRENCIES.forEach { code ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = (selected == code) || (selected == null && code == current),
                            onClick = { selected = code },
                        )
                        Text(code, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = isCustom,
                        onClick = { selected = "CUSTOM" },
                    )
                    Text(
                        stringResource(R.string.settings_currency_custom),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                if (isCustom) {
                    OutlinedTextField(
                        value = customCode,
                        onValueChange = { customCode = it.uppercase().take(3) },
                        label = { Text(stringResource(R.string.settings_currency_custom_hint)) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                }
            }
        },
    )
}

@Composable
private fun AddRateDialog(
    onDismiss: () -> Unit,
    onConfirm: (from: String, to: String, rate: Double) -> Unit,
) {
    var from by remember { mutableStateOf<String?>(null) }
    var to by remember { mutableStateOf<String?>(null) }
    var fromCustom by remember { mutableStateOf("") }
    var toCustom by remember { mutableStateOf("") }
    var rateInput by remember { mutableStateOf("") }
    val fromIsCustom = from == "CUSTOM"
    val toIsCustom = to == "CUSTOM"
    val effectiveFrom = if (fromIsCustom) fromCustom.uppercase() else (from ?: "")
    val effectiveTo = if (toIsCustom) toCustom.uppercase() else (to ?: "")
    val parsedRate = rateInput.toDoubleOrNull()
    val isValid = effectiveFrom.length == 3 &&
        effectiveTo.length == 3 &&
        effectiveFrom != effectiveTo &&
        (parsedRate != null && parsedRate > 0.0)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onConfirm(effectiveFrom, effectiveTo, parsedRate!!) },
                enabled = isValid,
            ) { Text(stringResource(R.string.settings_dialog_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_dialog_cancel)) }
        },
        text = {
            Column {
                // From picker
                Text(
                    stringResource(R.string.settings_fx_from),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.size(4.dp))
                SUPPORTED_CURRENCIES.forEach { code ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = (from == code), onClick = { from = code })
                        Text(code, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = fromIsCustom, onClick = { from = "CUSTOM" })
                    Text(
                        stringResource(R.string.settings_currency_custom),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                if (fromIsCustom) {
                    OutlinedTextField(
                        value = fromCustom,
                        onValueChange = { fromCustom = it.uppercase().take(3) },
                        label = { Text(stringResource(R.string.settings_currency_custom_hint)) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    )
                }
                Spacer(Modifier.size(8.dp))
                // To picker
                Text(
                    stringResource(R.string.settings_fx_to),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.size(4.dp))
                SUPPORTED_CURRENCIES.forEach { code ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = (to == code), onClick = { to = code })
                        Text(code, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = toIsCustom, onClick = { to = "CUSTOM" })
                    Text(
                        stringResource(R.string.settings_currency_custom),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                if (toIsCustom) {
                    OutlinedTextField(
                        value = toCustom,
                        onValueChange = { toCustom = it.uppercase().take(3) },
                        label = { Text(stringResource(R.string.settings_currency_custom_hint)) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    )
                }
                Spacer(Modifier.size(8.dp))
                // Rate input
                OutlinedTextField(
                    value = rateInput,
                    onValueChange = { rateInput = it },
                    label = { Text(stringResource(R.string.settings_fx_rate_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}
