package io.github.jiro.expensetracker.ui.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jiro.expensetracker.BuildConfig
import io.github.jiro.expensetracker.R
import io.github.jiro.expensetracker.backup.BackupManager
import io.github.jiro.expensetracker.preferences.SettingsRepository
import io.github.jiro.expensetracker.preferences.ThemePreference
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One-shot message surfaced via Snackbar. Cleared after display.
 */
data class SettingsMessage(
    val text: String,
    val isError: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val backupManager: BackupManager,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val theme: StateFlow<ThemePreference> = settingsRepository.theme

    fun setTheme(value: ThemePreference) = settingsRepository.setTheme(value)

    private val _exportUri = MutableStateFlow<Uri?>(null)
    val exportUri: StateFlow<Uri?> = _exportUri.asStateFlow()

    private val _message = MutableStateFlow<SettingsMessage?>(null)
    val message: StateFlow<SettingsMessage?> = _message.asStateFlow()

    fun prepareExport() {
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val zipPath = withContext(Dispatchers.IO) {
                    backupManager.exportToZip(
                        appVersionName = BuildConfig.VERSION_NAME,
                        nowEpochMillis = now,
                    )
                }
                val zipFile = File(zipPath)
                val authority = "${appContext.packageName}.fileprovider"
                val uri = FileProvider.getUriForFile(appContext, authority, zipFile)
                _exportUri.value = uri
            } catch (e: Exception) {
                _message.value = SettingsMessage(
                    "Export failed: ${e.message ?: e.javaClass.simpleName}",
                    isError = true,
                )
            }
        }
    }

    fun consumeExportUri() {
        _exportUri.value = null
    }

    fun restoreFromUri(uri: Uri) {
        viewModelScope.launch {
            try {
                val mime = appContext.contentResolver.getType(uri)
                val ext = appContext.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val name = cursor.getString(0)
                        name?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase()
                    } else {
                        null
                    }
                }
                val isZip = mime == "application/zip" || ext == "zip"
                val summary = if (isZip) {
                    backupManager.importFromZipUri(appContext, uri)
                } else {
                    backupManager.importFromUri(appContext, uri)
                }
                summary.fold(
                    onSuccess = { s ->
                        val msg = buildString {
                            append(
                                "Restored ${s.transactionsRestored} transactions, " +
                                    "${s.categoriesRestored} categories.",
                            )
                            if (s.missingReceiptCount > 0) {
                                append(" ${appContext.getString(R.string.receipt_backup_missing)}")
                            }
                        }
                        _message.value = SettingsMessage(msg)
                    },
                    onFailure = { e ->
                        _message.value = SettingsMessage(
                            "Restore failed: ${e.message ?: e.javaClass.simpleName}",
                            isError = true,
                        )
                    },
                )
            } catch (e: Exception) {
                _message.value = SettingsMessage(
                    "Restore failed: ${e.message ?: e.javaClass.simpleName}",
                    isError = true,
                )
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }
}
