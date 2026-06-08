package io.github.jiro.expensetracker.ui.recurring

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jiro.expensetracker.data.RecurrenceKind
import io.github.jiro.expensetracker.data.local.TransactionEntity
import io.github.jiro.expensetracker.data.nextOccurrence
import io.github.jiro.expensetracker.data.repository.TransactionRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SeriesEndMode { NEVER, ON_DATE, AFTER_N_OCCURRENCES }

data class ManageSeriesUiState(
    val isLoaded: Boolean = false,
    val parentId: Long? = null,
    val title: String = "",
    val kind: RecurrenceKind = RecurrenceKind.MONTHLY,
    val interval: Int = 1,
    /** The day the series fires on. For MONTHLY this is the day-of-month. */
    val dayOfSeries: Int = 1,
    val endMode: SeriesEndMode = SeriesEndMode.NEVER,
    val endAt: Long? = null,
    val maxOccurrences: Int? = null,
    val isSaving: Boolean = false,
    val saveComplete: Boolean = false,
)

@HiltViewModel
class ManageSeriesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val transactionRepository: TransactionRepository,
) : ViewModel() {

    private val groupId: String = savedStateHandle.get<String>("groupId")
        ?: error("ManageSeriesViewModel requires a groupId nav arg")

    private val _state = MutableStateFlow(ManageSeriesUiState())
    val state: StateFlow<ManageSeriesUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val parent = findParent() ?: return@launch
            _state.update {
                it.copy(
                    isLoaded = true,
                    parentId = parent.id,
                    title = parent.title,
                    kind = RecurrenceKind.fromStorage(parent.recurrenceKind) ?: RecurrenceKind.MONTHLY,
                    interval = parent.recurrenceInterval.coerceAtLeast(1),
                    dayOfSeries = dayOfSeriesFrom(parent.occurredAtEpochMillis),
                    endMode = when {
                        parent.recurrenceEndAt != null -> SeriesEndMode.ON_DATE
                        parent.recurrenceMaxOccurrences != null -> SeriesEndMode.AFTER_N_OCCURRENCES
                        else -> SeriesEndMode.NEVER
                    },
                    endAt = parent.recurrenceEndAt,
                    maxOccurrences = parent.recurrenceMaxOccurrences,
                )
            }
        }
    }

    private suspend fun findParent(): TransactionEntity? {
        // The parent is the row in the group with recurrenceNextAt set.
        // Take a snapshot of the group's rows from the existing Flow and
        // pick the parent. In degenerate cases (no parent — should never
        // happen for a series the user actually created) we fall back to
        // the first row so the form is still editable.
        val rows = transactionRepository
            .observeGroup(groupId)
            .first()
            .map { it.transaction }
        return rows.firstOrNull { it.recurrenceNextAt != null } ?: rows.firstOrNull()
    }

    fun onKindChange(kind: RecurrenceKind) = _state.update { it.copy(kind = kind) }
    fun onIntervalChange(interval: Int) = _state.update {
        it.copy(interval = interval.coerceAtLeast(1))
    }
    fun onDayChange(day: Int) = _state.update {
        it.copy(dayOfSeries = day.coerceIn(1, 28))
    }
    fun onEndModeChange(mode: SeriesEndMode) = _state.update { it.copy(endMode = mode) }
    fun onEndDateChange(epochMillis: Long) = _state.update { it.copy(endAt = epochMillis) }
    fun onMaxOccurrencesChange(n: Int) = _state.update {
        it.copy(maxOccurrences = n.coerceAtLeast(1))
    }

    fun save() {
        val s = _state.value
        val parentId = s.parentId ?: return
        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val existing = transactionRepository.findById(parentId) ?: return@launch
            val (endAt, maxOcc) = when (s.endMode) {
                SeriesEndMode.NEVER -> null to null
                SeriesEndMode.ON_DATE -> s.endAt to null
                SeriesEndMode.AFTER_N_OCCURRENCES -> null to s.maxOccurrences
            }
            val updated = existing.copy(
                recurrenceKind = s.kind.name,
                recurrenceInterval = s.interval,
                recurrenceEndAt = endAt,
                recurrenceMaxOccurrences = maxOcc,
                // Recompute next occurrence from the new kind+interval. We
                // keep the existing occurredAt as the "day" reference
                // (changing the day is a more invasive operation that
                // requires back-dating the parent row).
                recurrenceNextAt = nextOccurrence(s.kind, s.interval, existing.recurrenceNextAt ?: existing.occurredAtEpochMillis),
            )
            transactionRepository.update(updated)
            _state.update { it.copy(isSaving = false, saveComplete = true) }
        }
    }
}

private fun dayOfSeriesFrom(epochMs: Long): Int {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = epochMs }
    return cal.get(java.util.Calendar.DAY_OF_MONTH)
}
