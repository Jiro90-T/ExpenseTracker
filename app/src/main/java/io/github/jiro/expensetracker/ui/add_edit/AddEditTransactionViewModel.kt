package io.github.jiro.expensetracker.ui.add_edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jiro.expensetracker.data.RecurrenceKind
import io.github.jiro.expensetracker.data.local.CategoryEntity
import io.github.jiro.expensetracker.data.local.TransactionEntity
import io.github.jiro.expensetracker.data.nextOccurrence
import io.github.jiro.expensetracker.data.repository.CategoryRepository
import io.github.jiro.expensetracker.data.repository.TransactionRepository
import io.github.jiro.expensetracker.domain.model.TransactionType
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Stable identifiers for each user-facing error, so the UI can map to a string resource. */
enum class FormError { TITLE_REQUIRED, AMOUNT_INVALID, CATEGORY_REQUIRED }

data class AddEditTransactionUiState(
    val id: Long? = null,
    val title: String = "",
    val amountInput: String = "",
    val type: TransactionType = TransactionType.EXPENSE,
    val categoriesForType: List<CategoryEntity> = emptyList(),
    val selectedCategoryId: Long? = null,
    val occurredAtEpochMillis: Long = System.currentTimeMillis(),
    val note: String = "",
    val isLoaded: Boolean = false,
    val isSaving: Boolean = false,
    val saveComplete: Boolean = false,
    val error: FormError? = null,

    // ---- Recurring (Phase 2.1) ----
    val isRecurring: Boolean = false,
    val recurrenceKind: RecurrenceKind = RecurrenceKind.MONTHLY,
    val recurrenceInterval: Int = 1,
    /**
     * Stable id for the series. Generated when the user first flips the
     * "make recurring" toggle on; preserved across edits.
     */
    val recurringGroupId: String? = null,
    val recurrenceEndMode: RecurrenceEndMode = RecurrenceEndMode.NEVER,
    val recurrenceEndAt: Long? = null,
    val recurrenceMaxOccurrences: Int? = null,
)

/** Phase 2.1: the "end" picker on the recurring section. */
enum class RecurrenceEndMode { NEVER, ON_DATE, AFTER_N_OCCURRENCES }

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AddEditTransactionViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository,
) : ViewModel() {

    private val transactionId: Long? = savedStateHandle
        .get<Long>("id")
        ?.takeIf { it >= 0 }

    private val _state = MutableStateFlow(
        AddEditTransactionUiState(id = transactionId)
    )
    val state: StateFlow<AddEditTransactionUiState> = _state.asStateFlow()

    private val currencyCode: String = "USD"

    init {
        // Categories follow the currently selected type. Re-fetches on every type change.
        viewModelScope.launch {
            _state
                .map { it.type }
                .distinctUntilChanged()
                .flatMapLatest { type -> categoryRepository.observeByType(type) }
                .collect { categories ->
                    _state.update { current ->
                        val validIds = categories.map { it.id }.toSet()
                        current.copy(
                            categoriesForType = categories,
                            selectedCategoryId = current.selectedCategoryId
                                ?.takeIf { it in validIds },
                            isLoaded = true,
                        )
                    }
                }
        }

        // If editing, prefill the form from the existing row.
        if (transactionId != null) {
            viewModelScope.launch {
                val existing = transactionRepository.findById(transactionId) ?: return@launch
                _state.update {
                    it.copy(
                        title = existing.title,
                        amountInput = formatAmountForEdit(existing.amountMinor),
                        type = TransactionType.fromStorage(existing.type),
                        selectedCategoryId = existing.categoryId,
                        occurredAtEpochMillis = existing.occurredAtEpochMillis,
                        note = existing.note.orEmpty(),
                        isRecurring = existing.recurringGroupId != null,
                        recurrenceKind = RecurrenceKind.fromStorage(existing.recurrenceKind)
                            ?: RecurrenceKind.MONTHLY,
                        recurrenceInterval = existing.recurrenceInterval.coerceAtLeast(1),
                        recurringGroupId = existing.recurringGroupId,
                        recurrenceEndMode = when {
                            existing.recurrenceEndAt != null -> RecurrenceEndMode.ON_DATE
                            existing.recurrenceMaxOccurrences != null -> RecurrenceEndMode.AFTER_N_OCCURRENCES
                            else -> RecurrenceEndMode.NEVER
                        },
                        recurrenceEndAt = existing.recurrenceEndAt,
                        recurrenceMaxOccurrences = existing.recurrenceMaxOccurrences,
                    )
                }
            }
        }
    }

    fun onTitleChange(value: String) = _state.update { it.copy(title = value, error = null) }
    fun onAmountChange(value: String) = _state.update { it.copy(amountInput = value, error = null) }
    fun onNoteChange(value: String) = _state.update { it.copy(note = value) }
    fun onTypeChange(value: TransactionType) = _state.update {
        it.copy(type = value, selectedCategoryId = null, error = null)
    }
    fun onCategoryChange(value: Long) = _state.update {
        it.copy(selectedCategoryId = value, error = null)
    }
    fun onDateChange(epochMillis: Long) = _state.update { it.copy(occurredAtEpochMillis = epochMillis) }

    fun onRecurringToggle(enabled: Boolean) = _state.update {
        if (enabled && it.recurringGroupId == null) {
            // First time the user enables recurring — mint a stable group id.
            it.copy(isRecurring = true, recurringGroupId = UUID.randomUUID().toString())
        } else {
            it.copy(isRecurring = enabled)
        }
    }
    fun onRecurrenceKindChange(kind: RecurrenceKind) = _state.update { it.copy(recurrenceKind = kind) }
    fun onRecurrenceIntervalChange(interval: Int) = _state.update {
        it.copy(recurrenceInterval = interval.coerceAtLeast(1))
    }
    fun onRecurrenceEndModeChange(mode: RecurrenceEndMode) = _state.update {
        it.copy(recurrenceEndMode = mode)
    }
    fun onRecurrenceEndDateChange(epochMillis: Long) = _state.update {
        it.copy(recurrenceEndAt = epochMillis)
    }
    fun onRecurrenceMaxOccurrencesChange(n: Int) = _state.update {
        it.copy(recurrenceMaxOccurrences = n.coerceAtLeast(1))
    }

    fun save() {
        val s = _state.value
        val title = s.title.trim()
        if (title.isEmpty()) {
            _state.update { it.copy(error = FormError.TITLE_REQUIRED) }
            return
        }
        val amountMinor = parseAmountToMinor(s.amountInput)
        if (amountMinor == null || amountMinor <= 0) {
            _state.update { it.copy(error = FormError.AMOUNT_INVALID) }
            return
        }
        val categoryId = s.selectedCategoryId
        if (categoryId == null) {
            _state.update { it.copy(error = FormError.CATEGORY_REQUIRED) }
            return
        }

        _state.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val (groupId, kind, interval, nextAt, endAt, maxOcc) = if (s.isRecurring) {
                Sextuple(
                    s.recurringGroupId ?: UUID.randomUUID().toString(),
                    s.recurrenceKind,
                    s.recurrenceInterval,
                    nextOccurrence(s.recurrenceKind, s.recurrenceInterval, s.occurredAtEpochMillis),
                    when (s.recurrenceEndMode) {
                        RecurrenceEndMode.NEVER -> null
                        RecurrenceEndMode.ON_DATE -> s.recurrenceEndAt
                        RecurrenceEndMode.AFTER_N_OCCURRENCES -> null
                    },
                    when (s.recurrenceEndMode) {
                        RecurrenceEndMode.NEVER -> null
                        RecurrenceEndMode.ON_DATE -> null
                        RecurrenceEndMode.AFTER_N_OCCURRENCES -> s.recurrenceMaxOccurrences
                    },
                )
            } else {
                Sextuple(null, null, 1, null, null, null)
            }
            val entity = TransactionEntity(
                id = s.id ?: 0L,
                title = title,
                amountMinor = amountMinor,
                currencyCode = currencyCode,
                type = s.type.name,
                categoryId = categoryId,
                occurredAtEpochMillis = s.occurredAtEpochMillis,
                note = s.note.trim().ifEmpty { null },
                createdAtEpochMillis = if (s.id == null) now else now,
                recurringGroupId = groupId,
                recurrenceKind = kind?.name,
                recurrenceInterval = interval,
                recurrenceNextAt = nextAt,
                recurrenceEndAt = endAt,
                recurrenceMaxOccurrences = maxOcc,
            )
            if (s.id == null) transactionRepository.add(entity) else transactionRepository.update(entity)
            _state.update { it.copy(isSaving = false, saveComplete = true) }
        }
    }

    private fun parseAmountToMinor(input: String): Long? {
        val cleaned = input.trim()
        if (cleaned.isEmpty()) return null
        val parts = cleaned.split('.')
        if (parts.size > 2) return null
        val whole = parts[0].toLongOrNull() ?: return null
        if (whole < 0) return null
        val fractionStr = if (parts.size == 2) parts[1].padEnd(2, '0').take(2) else "00"
        if (fractionStr.length > 2) return null
        val fraction = fractionStr.toLongOrNull() ?: return null
        if (whole > MAX_AMOUNT_WHOLE) return null
        return whole * 100 + fraction
    }

    private fun formatAmountForEdit(minor: Long): String {
        val whole = minor / 100
        val fraction = minor % 100
        return "%d.%02d".format(whole, fraction)
    }

    companion object {
        /** Cap whole units at 9_999_999_999 to stay well clear of Long overflow when * 100. */
        private const val MAX_AMOUNT_WHOLE = 9_999_999_999L
    }
}

/** Small holder to keep the save() call site readable. */
private data class Sextuple<A, B, C, D, E, F>(val a: A, val b: B, val c: C, val d: D, val e: E, val f: F)
