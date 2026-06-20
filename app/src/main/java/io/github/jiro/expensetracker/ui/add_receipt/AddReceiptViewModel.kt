package io.github.jiro.expensetracker.ui.add_receipt

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jiro.expensetracker.data.local.CategoryEntity
import io.github.jiro.expensetracker.data.local.ImageProcessor
import io.github.jiro.expensetracker.data.local.MoneyFormat
import io.github.jiro.expensetracker.data.local.ReceiptOcrProcessor
import io.github.jiro.expensetracker.data.local.TransactionEntity
import io.github.jiro.expensetracker.data.repository.CategoryRepository
import io.github.jiro.expensetracker.data.repository.ReceiptRepository
import io.github.jiro.expensetracker.data.repository.TransactionRepository
import io.github.jiro.expensetracker.domain.model.TransactionType
import io.github.jiro.expensetracker.domain.receipt.OcrFields
import io.github.jiro.expensetracker.preferences.SettingsRepository
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

enum class AddReceiptMode { Idle, OcrInProgress, Review }

enum class AddReceiptError { TITLE_REQUIRED, AMOUNT_INVALID, CATEGORY_REQUIRED, RECEIPT_SAVE_FAILED }

data class AddReceiptUiState(
    val mode: AddReceiptMode = AddReceiptMode.Idle,
    val photoPath: String? = null,
    val title: String = "",
    val amountInput: String = "",
    val occurredAtEpochMillis: Long = System.currentTimeMillis(),
    val type: TransactionType = TransactionType.EXPENSE,
    val categoriesForType: List<CategoryEntity> = emptyList(),
    val selectedCategoryId: Long? = null,
    val currency: String = "",
    val note: String = "",
    val isLoaded: Boolean = false,
    val isSaving: Boolean = false,
    val saveComplete: Boolean = false,
    val error: AddReceiptError? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
open class AddReceiptViewModel @Inject constructor(
    application: Application,
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository,
    private val receiptRepository: ReceiptRepository,
    private val receiptOcrProcessor: ReceiptOcrProcessor,
    private val settingsRepository: SettingsRepository,
) : AndroidViewModel(application) {

    /**
     * Exposed as `protected` (not `private`) so the test-only
     * [TestableAddReceiptViewModel] subclass can bypass the bitmap decode
     * pipeline (which can't run under JVM unit tests because
     * `BitmapFactory.decodeFile` is stubbed). The class itself is `open`
     * for the same reason.
     */
    protected val _state: MutableStateFlow<AddReceiptUiState> = MutableStateFlow(
        AddReceiptUiState(currency = settingsRepository.homeCurrency.value)
    )
    val state: StateFlow<AddReceiptUiState> = _state.asStateFlow()

    /**
     * The OCR processor the VM uses. `protected` (not `private`) so the
     * test-only subclass can call it directly without going through the
     * bitmap decode step.
     */
    protected val ocrProcessor: ReceiptOcrProcessor = receiptOcrProcessor

    init {
        // Categories follow the currently selected type. Re-fetches on every type change.
        viewModelScope.launch {
            _state
                .map { it.type }
                .distinctUntilChanged()
                .flatMapLatest { type -> categoryRepository.observeByType(type) }
                .collect { categories ->
                    val validIds = categories.map { it.id }.toSet()
                    _state.update {
                        it.copy(
                            categoriesForType = categories,
                            selectedCategoryId = it.selectedCategoryId
                                ?.takeIf { id -> id in validIds }
                                ?: categories.firstOrNull()?.id,
                            isLoaded = true,
                        )
                    }
                }
        }
    }

    fun onPhotoCaptured(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(mode = AddReceiptMode.OcrInProgress, error = null) }
            val ctx = getApplication<Application>().applicationContext
            val path = try {
                receiptRepository.saveFromUri(ctx, uri)
            } catch (e: Exception) {
                _state.update { it.copy(
                    mode = AddReceiptMode.Idle,
                    error = AddReceiptError.RECEIPT_SAVE_FAILED,
                ) }
                return@launch
            }

            onReceiptSaved(path)
        }
    }

    /**
     * OCR + state-update pipeline once a receipt path is known. Split out of
     * [onPhotoCaptured] so tests can drive the state machine via a path
     * string without needing to construct a real Android [Uri]. `open` so
     * test-only subclasses can bypass the bitmap decode (which can't run
     * under `unitTests.isReturnDefaultValues` because
     * `BitmapFactory.decodeFile` returns null).
     */
    open fun onReceiptSaved(path: String) {
        viewModelScope.launch {
            val ocr = try {
                val file = receiptRepository.absolutePath(path)
                if (!file.isFile) {
                    OcrFields(null, null, null)
                } else {
                    val bitmap = ImageProcessor.decodeSampledBitmap(file, maxEdge = 2048)
                    try {
                        receiptOcrProcessor.extract(bitmap)
                    } finally {
                        bitmap.recycle()
                    }
                }
            } catch (e: Exception) {
                OcrFields(null, null, null)
            }

            _state.update {
                it.copy(
                    mode = AddReceiptMode.Review,
                    photoPath = path,
                    title = ocr.merchant ?: it.title,
                    amountInput = ocr.amountMinor?.let { amt -> MoneyFormat.formatAmountForEdit(amt) } ?: it.amountInput,
                    occurredAtEpochMillis = ocr.occurredAtEpochMillis ?: it.occurredAtEpochMillis,
                )
            }
        }
    }

    fun onTitleChange(value: String) = _state.update { it.copy(title = value, error = null) }
    fun onAmountChange(value: String) = _state.update { it.copy(amountInput = value, error = null) }
    fun onNoteChange(value: String) = _state.update { it.copy(note = value) }
    fun onDateChange(epochMillis: Long) = _state.update { it.copy(occurredAtEpochMillis = epochMillis) }
    fun onTypeChange(value: TransactionType) = _state.update { it.copy(type = value, selectedCategoryId = null, error = null) }
    fun onCategoryChange(value: Long) = _state.update { it.copy(selectedCategoryId = value, error = null) }
    fun onCurrencyChange(value: String) = _state.update { it.copy(currency = value) }

    fun onSave() {
        val s = _state.value
        val title = s.title.trim()
        if (title.isEmpty()) {
            _state.update { it.copy(error = AddReceiptError.TITLE_REQUIRED) }
            return
        }
        val amountMinor = MoneyFormat.parseAmountToMinor(s.amountInput)
        if (amountMinor == null || amountMinor <= 0) {
            _state.update { it.copy(error = AddReceiptError.AMOUNT_INVALID) }
            return
        }
        val categoryId = s.selectedCategoryId
        if (categoryId == null) {
            _state.update { it.copy(error = AddReceiptError.CATEGORY_REQUIRED) }
            return
        }
        val photoPath = s.photoPath
        if (photoPath.isNullOrBlank()) {
            // Should not happen — Review mode requires a photo. But guard anyway.
            return
        }

        _state.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val entity = TransactionEntity(
                id = 0L,
                title = title,
                amountMinor = amountMinor,
                currencyCode = s.currency.ifEmpty { settingsRepository.homeCurrency.value },
                type = s.type.name,
                categoryId = categoryId,
                occurredAtEpochMillis = s.occurredAtEpochMillis,
                note = s.note.trim().ifEmpty { null },
                createdAtEpochMillis = now,
                receiptPath = photoPath,
            )
            try {
                transactionRepository.add(entity)
                _state.update { it.copy(isSaving = false, saveComplete = true) }
            } catch (e: Exception) {
                _state.update { it.copy(isSaving = false, error = AddReceiptError.RECEIPT_SAVE_FAILED) }
            }
        }
    }
}