package io.github.jiro.expensetracker.ui.add_edit

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jiro.expensetracker.data.RecurrenceKind
import io.github.jiro.expensetracker.data.local.CategoryEntity
import io.github.jiro.expensetracker.data.local.ImageProcessor
import io.github.jiro.expensetracker.data.local.MoneyFormat
import io.github.jiro.expensetracker.data.local.ReceiptOcrProcessor
import io.github.jiro.expensetracker.data.local.TransactionEntity
import io.github.jiro.expensetracker.data.nextOccurrence
import io.github.jiro.expensetracker.data.repository.CategoryRepository
import io.github.jiro.expensetracker.data.repository.ReceiptRepository
import io.github.jiro.expensetracker.data.repository.TransactionRepository
import io.github.jiro.expensetracker.domain.model.TransactionType
import io.github.jiro.expensetracker.domain.receipt.OcrFields
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
enum class FormError { TITLE_REQUIRED, AMOUNT_INVALID, CATEGORY_REQUIRED, RECEIPT_SAVE_FAILED }

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

    // ---- Phase 2.2: per-tx currency ----
    val currency: String = "USD",

    // ---- Phase 2.4: receipts ----
    val receiptPath: String? = null,
    /**
     * One-shot state: set when OCR runs after attaching an image receipt and
     * at least one field was filled. The screen reads this to show a snackbar,
     * then calls [consumeOcrSnackbar] to clear it.
     */
    val lastOcrFields: OcrFields? = null,

    /**
     * Set true when the user explicitly picks a date via the date picker. Used
     * by the OCR auto-fill pristine-field check so the OCR date doesn't
     * override a date the user already chose.
     */
    val dateTouched: Boolean = false,
)

/** Phase 2.1: the "end" picker on the recurring section. */
enum class RecurrenceEndMode { NEVER, ON_DATE, AFTER_N_OCCURRENCES }

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AddEditTransactionViewModel @Inject constructor(
    application: Application,
    savedStateHandle: SavedStateHandle,
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository,
    private val receiptRepository: ReceiptRepository,
    private val receiptOcrProcessor: ReceiptOcrProcessor,
) : AndroidViewModel(application) {

    private val transactionId: Long? = savedStateHandle
        .get<Long>("id")
        ?.takeIf { it >= 0 }

    private val _state = MutableStateFlow(
        AddEditTransactionUiState(id = transactionId)
    )
    val state: StateFlow<AddEditTransactionUiState> = _state.asStateFlow()

    // currencyCode comes from state.currency (set via the dropdown).

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
                        amountInput = MoneyFormat.formatAmountForEdit(existing.amountMinor),
                        type = TransactionType.fromStorage(existing.type),
                        selectedCategoryId = existing.categoryId,
                        occurredAtEpochMillis = existing.occurredAtEpochMillis,
                        note = existing.note.orEmpty(),
                        isRecurring = existing.recurringGroupId != null,
                        currency = existing.currencyCode,
                        receiptPath = existing.receiptPath,
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
    fun onCurrencyChange(value: String) = _state.update {
        it.copy(currency = value)
    }
    fun onDateChange(epochMillis: Long) = _state.update {
        it.copy(occurredAtEpochMillis = epochMillis, dateTouched = true)
    }

    fun onReceiptAttached(uri: Uri) {
        viewModelScope.launch {
            val oldPath = _state.value.receiptPath
            val newPath = try {
                // Use application context to resolve the content URI.
                // The composable hands us a content:// URI from the system picker.
                val ctx = getApplication<Application>().applicationContext
                receiptRepository.saveFromUri(ctx, uri)
            } catch (e: Exception) {
                _state.update { it.copy(error = FormError.RECEIPT_SAVE_FAILED) }
                return@launch
            }
            // Delete the old file (if any) only after the new one is saved.
            if (!oldPath.isNullOrBlank() && oldPath != newPath) {
                receiptRepository.delete(oldPath)
            }
            _state.update { it.copy(receiptPath = newPath) }

            // Run OCR only for image receipts (not PDFs — would need rasterization).
            if (newPath.endsWith(".jpg", ignoreCase = true) ||
                newPath.endsWith(".jpeg", ignoreCase = true) ||
                newPath.endsWith(".png", ignoreCase = true) ||
                newPath.endsWith(".webp", ignoreCase = true)
            ) {
                runOcrAndAutoFill(newPath)
            }
        }
    }

    fun onReceiptRemoved() {
        val current = _state.value.receiptPath
        _state.update { it.copy(receiptPath = null, lastOcrFields = null) }
        if (!current.isNullOrBlank()) {
            viewModelScope.launch { receiptRepository.delete(current) }
        }
    }

    fun consumeOcrSnackbar() {
        _state.update { it.copy(lastOcrFields = null) }
    }

    private suspend fun runOcrAndAutoFill(receiptPath: String) {
        val file = receiptRepository.absolutePath(receiptPath)
        if (!file.isFile) return
        val ocr = try {
            val bitmap = ImageProcessor.decodeSampledBitmap(file, maxEdge = 2048)
            try {
                receiptOcrProcessor.extract(bitmap)
            } finally {
                bitmap.recycle()
            }
        } catch (e: Exception) {
            // OCR failure isn't fatal; the receipt is still attached.
            return
        }
        if (!ocr.hasAny) return

        // Pristine-field check: only fill fields the user hasn't touched.
        val current = _state.value
        val s = current.copy(
            amountInput = if (current.amountInput.isBlank() && ocr.amountMinor != null) {
                MoneyFormat.formatAmountForEdit(ocr.amountMinor)
            } else current.amountInput,
            title = if (current.title.isBlank() && ocr.merchant != null) {
                ocr.merchant
            } else current.title,
            occurredAtEpochMillis = if (ocr.occurredAtEpochMillis != null && !current.dateTouched) {
                ocr.occurredAtEpochMillis
            } else current.occurredAtEpochMillis,
            lastOcrFields = ocr,
        )
        _state.value = s
    }

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
    fun onRecurrenceMaxOccurrencesChange(n: Int?) = _state.update {
        it.copy(recurrenceMaxOccurrences = n?.coerceAtLeast(1))
    }

    fun save() {
        val s = _state.value
        val title = s.title.trim()
        if (title.isEmpty()) {
            _state.update { it.copy(error = FormError.TITLE_REQUIRED) }
            return
        }
        val amountMinor = MoneyFormat.parseAmountToMinor(s.amountInput)
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
                currencyCode = s.currency,
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
                receiptPath = s.receiptPath,
            )
            if (s.id == null) transactionRepository.add(entity) else transactionRepository.update(entity)
            _state.update { it.copy(isSaving = false, saveComplete = true) }
        }
    }

}

/** Small holder to keep the save() call site readable. */
private data class Sextuple<A, B, C, D, E, F>(val a: A, val b: B, val c: C, val d: D, val e: E, val f: F)
