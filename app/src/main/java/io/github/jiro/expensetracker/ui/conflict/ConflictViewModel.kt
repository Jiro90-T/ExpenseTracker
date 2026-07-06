package io.github.jiro.expensetracker.ui.conflict

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jiro.expensetracker.backup.BackupManager
import io.github.jiro.expensetracker.preferences.SettingsRepository
import io.github.jiro.expensetracker.sync.CloudSyncRepository
import io.github.jiro.expensetracker.sync.PushResult
import io.github.jiro.expensetracker.sync.SyncSnapshot
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the "Resolve sync conflict" screen. The screen passes the
 * remote + local [SyncSnapshot] pair via [init] (once, on first composition);
 * the ViewModel exposes a [ConflictUiState] that simply re-publishes them so
 * the UI can render. Resolving is a fire-and-forget side effect that calls
 * back into [useCloud] / [useLocal] and invokes [onDone] on completion.
 *
 * `settingsRepository` is reserved for future use (e.g. a post-resolution
 * "show last sync" refresh) and intentionally unused in v1.
 */
@HiltViewModel
class ConflictViewModel @Inject internal constructor(
    private val cloudSyncRepository: CloudSyncRepository,
    private val backupManager: BackupManager,
    @Suppress("unused") private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ConflictUiState())
    internal val state: StateFlow<ConflictUiState> = _state.asStateFlow()

    internal fun init(remote: SyncSnapshot, local: SyncSnapshot) {
        _state.update { current ->
            if (current.remote == null && current.local == null) {
                current.copy(remote = remote, local = local)
            } else {
                current
            }
        }
    }

    internal fun useCloud(onDone: () -> Unit) {
        val remote = _state.value.remote ?: return
        _state.update { it.copy(resolving = true, error = null) }
        viewModelScope.launch {
            val result = runCatching { backupManager.applyBackupBodyToDb(remote.body) }
            _state.update { it.copy(resolving = false) }
            result.onFailure { e ->
                _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            }
            result.onSuccess {
                onDone()
            }
        }
    }

    internal fun useLocal(onDone: () -> Unit) {
        val local = _state.value.local ?: return
        _state.update { it.copy(resolving = true, error = null) }
        viewModelScope.launch {
            val pushResult = runCatching { cloudSyncRepository.push(local) }
            _state.update { it.copy(resolving = false) }
            pushResult.onFailure { e ->
                _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
                return@launch
            }
            val result = pushResult.getOrNull()
            when (result) {
                is PushResult.Pushed -> onDone()
                is PushResult.Failed -> _state.update { it.copy(error = result.message) }
                null -> _state.update { it.copy(error = "Push returned no result") }
            }
        }
    }
}

internal data class ConflictUiState(
    val remote: SyncSnapshot? = null,
    val local: SyncSnapshot? = null,
    val resolving: Boolean = false,
    val error: String? = null,
)
