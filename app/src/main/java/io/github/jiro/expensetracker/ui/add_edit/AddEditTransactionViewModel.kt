package io.github.jiro.expensetracker.ui.add_edit

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jiro.expensetracker.data.RecurrenceKind
import io.github.jiro.expensetracker.data.local.AccountEntity
import io.github.jiro.expensetracker.data.local.CategoryEntity
import io.github.jiro.expensetracker.data.local.ImageProcessor
import io.github.jiro.expensetracker.data.local.MoneyFormat
import io.github.jiro.expensetracker.data.local.ReceiptOcrProcessor
import io.github.jiro.expensetracker.data.local.TransactionEntity
import io.github.jiro.expensetracker.data.nextOccurrence
import io.github.jiro.expensetracker.data.repository.AccountRepository
import io.github.jiro.expensetracker.data.repository.CategoryRepository
import io.github.jiro.expensetracker.data.repository.ReceiptRepository
import io.github.jiro.expensetracker.data.repository.TransactionRepository
import io.github.jiro.expensetracker.domain.model.TransactionType
import io.github.jiro.expensetracker.domain.receipt.OcrFields
import io.github.jiro.expensetracker.preferences.SettingsRepository
import io.github.jiro.expensetracker.sync.TransactionMutationBus
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
enum class FormError { TITLE_REQUIRED, AMOUNT_INVALID, CATEGORY_REQUIRED, RECEIPT_SAVE_FAILED, ACCOUNT_REQUIRED, TRANSFER_ACCOUNTS_MUST_DIFFER }

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
     * One-shot state: set when OCR runs after attaching a receipt and at least
     * one field was filled. The screen reads this to show a snackbar, then
     * calls [consumeOcrSnackbar] to clear it.
     */
    val lastOcrSnackbar: Boolean = false,

    /**
     * Set true when the user explicitly picks a date via the date picker. Used
     * by the OCR auto-fill pristine-field check so the OCR date doesn't
     * override a date the user already chose.
     */
    val dateTouched: Boolean = false,

    // ---- Phase 2.19: created-at timestamp shown in detail/edit header ----
    val createdAtEpochMillis: Long = 0L,

    // ---- Phase 2.16: per-tx account ----
    val accounts: List<AccountEntity> = emptyList(),
    val selectedAccountId: Long? = null,
    /** TRANSFER only: the destination account. null for EXPENSE/INCOME/ADJUSTMENT. */
    val selectedTransferAccountId: Long? = null,
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
    private val accountRepository: AccountRepository,
    private val receiptRepository: ReceiptRepository,
    private val receiptOcrProcessor: ReceiptOcrProcessor,
    private val settingsRepository: SettingsRepository,
    private val transactionMutationBus: TransactionMutationBus,
) : AndroidViewModel(application) {

    private val transactionId: Long? = savedStateHandle
        .get<Long>("id")
        ?.takeIf { it >= 0 }

    private val _state = MutableStateFlow(
        AddEditTransactionUiState(
            id = transactionId,
            // Default new transactions to the user's home currency. Editing
            // an existing row overrides this with its own currencyCode below.
            currency = if (transactionId == null) settingsRepository.homeCurrency.value else "USD",
        )
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

        // Accounts list — observable for the dropdown. Refreshes the valid
        // id set so a deleted account doesn't keep its selection stuck.
        viewModelScope.launch {
            accountRepository.observeActive().collect { accounts ->
                _state.update { current ->
                    val validIds = accounts.map { it.id }.toSet()
                    current.copy(
                        accounts = accounts,
                        selectedAccountId = current.selectedAccountId?.takeIf { it in validIds },
                        selectedTransferAccountId = current.selectedTransferAccountId?.takeIf { it in validIds },
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
                        selectedAccountId = existing.accountId,
                        selectedTransferAccountId = existing.transferAccountId,
                        occurredAtEpochMillis = existing.occurredAtEpochMillis,
                        note = existing.note.orEmpty(),
                        isRecurring = existing.recurringGroupId != null,
                        currency = existing.currencyCode,
                        receiptPath = existing.receiptPath,
                        createdAtEpochMillis = existing.createdAtEpochMillis,
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
        it.copy(
            type = value,
            selectedCategoryId = null,
            // Clear transfer account when type is no longer TRANSFER.
            selectedTransferAccountId = if (value == TransactionType.TRANSFER) it.selectedTransferAccountId else null,
            error = null,
        )
    }
    fun onCategoryChange(value: Long) = _state.update {
        it.copy(selectedCategoryId = value, error = null)
    }
    fun onAccountChange(value: Long) = _state.update {
        it.copy(selectedAccountId = value, error = null)
    }
    fun onTransferAccountChange(value: Long) = _state.update {
        it.copy(selectedTransferAccountId = value, error = null)
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

            // Run OCR (image only — PDF path removed in Phase 2.15).
            runOcrForReceipt(newPath)
        }
    }

    fun onReceiptRemoved() {
        val current = _state.value.receiptPath
        _state.update { it.copy(receiptPath = null) }
        if (!current.isNullOrBlank()) {
            viewModelScope.launch { receiptRepository.delete(current) }
        }
    }

    fun consumeOcrSnackbar() {
        _state.update { it.copy(lastOcrSnackbar = false) }
    }

    private suspend fun runOcrAndAutoFill(ocr: OcrFields) {
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
        )
        _state.update { s }
    }

    private suspend fun runImageOcr(receiptPath: String): OcrFields {
        val file = receiptRepository.absolutePath(receiptPath)
        if (!file.isFile) return OcrFields(null, null, null)
        return try {
            val bitmap = ImageProcessor.decodeSampledBitmap(file, maxEdge = 2048)
            try {
                receiptOcrProcessor.extract(bitmap)
            } finally {
                bitmap.recycle()
            }
        } catch (e: Exception) {
            // OCR failure isn't fatal; the receipt is still attached.
            OcrFields(null, null, null)
        }
    }

    private suspend fun runOcrForReceipt(path: String) {
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext !in setOf("jpg", "jpeg", "png", "webp")) return
        val ocr = runImageOcr(path)
        runOcrAndAutoFill(ocr)
        if (ocr.hasAny) {
            _state.update { it.copy(lastOcrSnackbar = true) }
        }
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
        val accountId = s.selectedAccountId
        if (accountId == null) {
            _state.update { it.copy(error = FormError.ACCOUNT_REQUIRED) }
            return
        }
        val transferTo = s.selectedTransferAccountId
        if (s.type == TransactionType.TRANSFER && (transferTo == null || transferTo == accountId)) {
            _state.update { it.copy(error = FormError.TRANSFER_ACCOUNTS_MUST_DIFFER) }
            return
        }
        val categoryId = s.selectedCategoryId
        if (s.type != TransactionType.TRANSFER && categoryId == null) {
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
                accountId = accountId,
                transferAccountId = if (s.type == TransactionType.TRANSFER) transferTo else null,
                occurredAtEpochMillis = s.occurredAtEpochMillis,
                note = s.note.trim().ifEmpty { null },
                createdAtEpochMillis = now,
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
            transactionMutationBus.tryEmit()
        }
    }

}

/** Small holder to keep the save() call site readable. */
private data class Sextuple<A, B, C, D, E, F>(val a: A, val b: B, val c: C, val d: D, val e: E, val f: F)
