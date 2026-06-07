package io.github.jiro.expensetracker.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jiro.expensetracker.BuildConfig
import io.github.jiro.expensetracker.backup.BackupManager
import io.github.jiro.expensetracker.preferences.SettingsRepository
import io.github.jiro.expensetracker.preferences.ThemePreference
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
                val json = withContext(Dispatchers.IO) {
                    backupManager.exportToJson(
                        appVersionName = BuildConfig.VERSION_NAME,
                        nowEpochMillis = now,
                    )
                }
                val uri = withContext(Dispatchers.IO) {
                    backupManager.writeExportToCache(appContext, json, now)
                }
                _exportUri.value = uri
            } catch (e: Exception) {
                _message.value = SettingsMessage("Export failed: ${e.message}", isError = true)
            }
        }
    }

    fun consumeExportUri() {
        _exportUri.value = null
    }

    fun restoreFromUri(uri: Uri) {
        viewModelScope.launch {
            try {
                val summary = backupManager.importFromUri(appContext, uri)
                summary.fold(
                    onSuccess = { s ->
                        _message.value = SettingsMessage(
                            "Restored ${s.transactionsRestored} transactions, " +
                                "${s.categoriesRestored} categories.",
                        )
                    },
                    onFailure = { e ->
                        _message.value = SettingsMessage(
                            "Restore failed: ${e.message ?: e::class.simpleName}",
                            isError = true,
                        )
                    },
                )
            } catch (e: Exception) {
                _message.value = SettingsMessage(
                    "Restore failed: ${e.message ?: e::class.simpleName}",
                    isError = true,
                )
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }
}
