package io.github.jiro.expensetracker.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jiro.expensetracker.BuildConfig
import io.github.jiro.expensetracker.R
import io.github.jiro.expensetracker.backup.BackupFormat
import io.github.jiro.expensetracker.data.accountimport.AccountTypeDefaults
import io.github.jiro.expensetracker.data.accountimport.ImportPreview
import io.github.jiro.expensetracker.data.accountimport.ImportStatus
import io.github.jiro.expensetracker.data.accountimport.ResolvedImportRow
import io.github.jiro.expensetracker.data.local.MoneyFormat
import io.github.jiro.expensetracker.local.LocalServerState
import io.github.jiro.expensetracker.preferences.SUPPORTED_CURRENCIES
import io.github.jiro.expensetracker.preferences.ThemePreference
import io.github.jiro.expensetracker.preferences.parseRates

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit = {},
    onConflictClick: () -> Unit = {},
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

    val pendingImportPreview by viewModel.pendingImportPreview.collectAsStateWithLifecycle()
    val importInFlight by viewModel.importInFlight.collectAsStateWithLifecycle()

    val importCsvPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.onImportCsvPicked(uri)
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
                .verticalScroll(rememberScrollState())
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

            // --- Import accounts from CSV ---
            SettingsSectionHeader(stringResource(R.string.import_csv_section_title))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.import_csv_section_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = {
                            importCsvPicker.launch(
                                arrayOf(
                                    "text/csv",
                                    "text/comma-separated-values",
                                    "application/vnd.ms-excel",
                                    "text/*",
                                ),
                            )
                        },
                        enabled = !importInFlight,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.FileUpload,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.import_csv_button))
                    }
                }
            }
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
                                        contentDescription = stringResource(R.string.settings_fx_delete),
                                    )
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = { showAddRateDialog = true },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.settings_fx_add))
                        }
                        val refreshing by viewModel.fxRefreshInFlight.collectAsStateWithLifecycle()
                        TextButton(
                            onClick = viewModel::refreshFxRates,
                            enabled = !refreshing,
                        ) {
                            if (refreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Text(stringResource(R.string.settings_fx_refresh))
                            }
                        }
                    }
                }
            }

            // --- Local server ---
            SettingsSectionHeader(stringResource(R.string.local_server_title))
            val localServerState by viewModel.localServerState.collectAsStateWithLifecycle()
            LocalServerSection(
                state = localServerState,
                url = viewModel.fullUrl(localServerState),
                onToggle = { running ->
                    if (running) viewModel.stopLocalServer() else viewModel.startLocalServer()
                },
                onCopyUrl = viewModel::copyToClipboard,
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // --- Cloud sync ---
            SettingsSectionHeader(stringResource(R.string.settings_sync_section_title))
            val cloudSession by viewModel.cloudSyncSession.collectAsStateWithLifecycle()
            val signInLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult(),
            ) { result -> viewModel.onSignInResult(result.data) }
            CloudSyncSection(
                session = cloudSession,
                dropboxConfigured = true,
                googleDriveConfigured = BuildConfig.DEFAULT_WEB_CLIENT_ID.isNotEmpty() &&
                    BuildConfig.DEFAULT_WEB_CLIENT_ID != "changeme",
                onProviderSelected = viewModel::setSyncProvider,
                onSignInClick = { signInLauncher.launch(viewModel.signInIntent) },
                onSignOutClick = viewModel::onSignOutClick,
                onSyncNowClick = viewModel::onSyncNow,
                onConflictClick = onConflictClick,
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

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

    pendingImportPreview?.let { preview ->
        ImportPreviewDialog(
            preview = preview,
            inFlight = importInFlight,
            onDismiss = { viewModel.onImportDismiss() },
            onConfirm = { viewModel.onImportConfirm() },
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

/**
 * Toggle for the on-device web server, plus the URL to open on a PC once it's up.
 * The URL only appears when both the WiFi IP and the session token have resolved.
 */
@Composable
private fun LocalServerSection(
    state: LocalServerState,
    url: String?,
    onToggle: (running: Boolean) -> Unit,
    onCopyUrl: (String) -> Boolean,
) {
    var copyFailed by remember { mutableStateOf(false) }
    val copyFailedText = stringResource(R.string.local_server_copy_failed)
    val unknownIp = stringResource(R.string.local_server_ip_unknown)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (state.isRunning) {
                        stringResource(
                            R.string.local_server_status_running,
                            state.port,
                            state.ipAddress ?: unknownIp,
                        )
                    } else {
                        stringResource(R.string.local_server_status_off)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = state.isRunning,
                    onCheckedChange = {
                        copyFailed = false
                        onToggle(state.isRunning)
                    },
                )
            }

            if (state.isRunning && url != null) {
                Text(
                    text = stringResource(R.string.local_server_url_label),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = url,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.size(4.dp))
                Button(onClick = { copyFailed = !onCopyUrl(url) }) {
                    Text(stringResource(R.string.local_server_copy_url))
                }
            }

            if (copyFailed) {
                Text(
                    text = copyFailedText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
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
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
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
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
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

@Composable
private fun ImportPreviewDialog(
    preview: ImportPreview,
    inFlight: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val willCreateCount = preview.rows.count { it.status is ImportStatus.WillCreate }
    val willUpdateCount = preview.rows.count { it.status is ImportStatus.WillUpdate }
    val rejectedCount = preview.rows.count { it.status is ImportStatus.Rejected }
    val applyCount = willCreateCount + willUpdateCount
    val allRejected = applyCount == 0

    AlertDialog(
        onDismissRequest = { if (!inFlight) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = {
            Text(
                text = stringResource(
                    R.string.import_csv_preview_title,
                    preview.rows.size,
                    preview.fileName,
                ),
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(
                        R.string.import_csv_summary,
                        willCreateCount,
                        willUpdateCount,
                        rejectedCount,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (allRejected) {
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = stringResource(R.string.import_csv_all_rejected),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.size(12.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    preview.rows.forEach { row ->
                        ImportRowItem(row = row)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !inFlight && applyCount > 0,
            ) {
                Text(stringResource(R.string.import_csv_apply, applyCount))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !inFlight,
            ) {
                Text(stringResource(R.string.import_csv_cancel))
            }
        },
    )
    if (inFlight) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun ImportRowItem(row: ResolvedImportRow) {
    val status = row.status
    val raw = row.raw
    val (bgColor, label) = when (status) {
        is ImportStatus.WillCreate -> Color(0x1A43A047) to "🟢"
        is ImportStatus.WillUpdate -> Color(0x1A1976D2) to "🔵"
        is ImportStatus.Rejected -> Color(0x1AC62828) to "🔴"
    }
    val detailText = when (status) {
        is ImportStatus.WillCreate -> {
            val icon = AccountTypeDefaults.iconFor(raw.type)
            val balance = MoneyFormat.formatForDisplay(raw.balanceMinor)
            "$icon ${raw.name} (${raw.type}, ${raw.currency}) → $balance"
        }
        is ImportStatus.WillUpdate -> {
            val balance = MoneyFormat.formatForDisplay(raw.balanceMinor)
            "$label ${raw.name} (existing) → new opening balance: $balance"
        }
        is ImportStatus.Rejected -> {
            "$label ${stringResource(R.string.import_csv_status_rejected, status.reason)}"
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = bgColor,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = detailText,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}

@Composable
internal fun CloudSyncSection(
    session: io.github.jiro.expensetracker.sync.CloudSyncSessionState,
    dropboxConfigured: Boolean,
    googleDriveConfigured: Boolean,
    onProviderSelected: (io.github.jiro.expensetracker.sync.SyncProviderId) -> Unit,
    onSignInClick: () -> Unit,
    onSignOutClick: () -> Unit,
    onSyncNowClick: () -> Unit,
    onConflictClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (session.conflictPending) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clickable(onClick = onConflictClick),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_sync_conflict_banner),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_sync_section_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                val isSignedIn = session.state is io.github.jiro.expensetracker.sync.SyncState.SignedIn
                Text(
                    text = if (isSignedIn) {
                        val providerLabel = when (session.providerId) {
                            io.github.jiro.expensetracker.sync.SyncProviderId.DROPBOX -> stringResource(R.string.settings_sync_provider_dropbox)
                            io.github.jiro.expensetracker.sync.SyncProviderId.GOOGLE_DRIVE -> stringResource(R.string.settings_sync_provider_google_drive)
                        }
                        stringResource(
                            R.string.settings_sync_status_signed_in,
                            session.accountEmail ?: "?",
                            providerLabel,
                        )
                    } else {
                        stringResource(R.string.settings_sync_status_signed_out)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = when (val ls = session.lastSyncedAtEpochMillis) {
                        null -> stringResource(R.string.settings_sync_last_synced_never)
                        else -> stringResource(
                            R.string.settings_sync_last_synced_format,
                            android.text.format.DateUtils.getRelativeTimeSpanString(ls),
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text(
                    text = stringResource(R.string.settings_sync_provider_label),
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProviderChip(
                        label = stringResource(R.string.settings_sync_provider_dropbox),
                        enabled = dropboxConfigured,
                        selected = session.providerId == io.github.jiro.expensetracker.sync.SyncProviderId.DROPBOX,
                        onClick = { onProviderSelected(io.github.jiro.expensetracker.sync.SyncProviderId.DROPBOX) },
                    )
                    Spacer(Modifier.size(8.dp))
                    ProviderChip(
                        label = stringResource(R.string.settings_sync_provider_google_drive),
                        enabled = googleDriveConfigured,
                        selected = session.providerId == io.github.jiro.expensetracker.sync.SyncProviderId.GOOGLE_DRIVE,
                        onClick = { onProviderSelected(io.github.jiro.expensetracker.sync.SyncProviderId.GOOGLE_DRIVE) },
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isSignedIn) {
                        TextButton(onClick = onSignOutClick) {
                            Text(stringResource(R.string.settings_sync_action_sign_out))
                        }
                    } else {
                        Button(
                            onClick = onSignInClick,
                            enabled = (dropboxConfigured || googleDriveConfigured),
                        ) {
                            Text(stringResource(R.string.settings_sync_action_sign_in))
                        }
                    }
                    TextButton(
                        onClick = onSyncNowClick,
                        enabled = isSignedIn,
                    ) {
                        Text(stringResource(R.string.settings_sync_action_sync_now))
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderChip(
    label: String,
    enabled: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = if (selected) {
        FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
        )
    } else FilterChipDefaults.filterChipColors()
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label) },
        colors = colors,
    )
}
