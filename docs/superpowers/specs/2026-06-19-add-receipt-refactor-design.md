# Phase 2.15 — Add Receipt Refactor — Design

**Status:** Draft 2026-06-19
**Phase:** 2.15
**Predecessor:** Phase 2.14 shipped PDF OCR (the receipt attach pipeline branched on file extension and the VM had a PDF case). The user has redirected: the PDF support is being unwound in favor of a camera-first top-level "Add Receipt" entry point in the More tab. The inline image attach on Add/Edit transaction stays, but image-only.

## Goal

Split the receipt-capture UX into two distinct entry points:

1. **Inline on Add/Edit transaction**: image attach from gallery/file picker, with OCR auto-fill. PDFs no longer offered here.
2. **Top-level "Add Receipt" (More tab)**: camera-first flow that captures a photo, OCRs it, shows a review screen with extracted fields, and saves a new transaction on confirm. Navigates back to More on save.

The PDF code shipped in Phase 2.14 is removed (merger, `extractFromPdf`, `PdfOcrResult`, PDF snackbar strings, `ReceiptKind`, `OcrSnackbarMeta`). Camera-based receipt capture is the only "rich" flow; the inline image attach is a quick decorator.

Out of scope (intentional, deferred): multi-currency on AddReceipt, recurring transactions from AddReceipt, image search (mentioned by user as a future feature), bulk-add multiple receipts at once, multiple transactions per PDF (a PDF, if ever supported again, would still produce exactly one transaction).

## User-visible behavior

### Inline on Add/Edit (unchanged for images)

| Action | Behavior |
|---|---|
| Tap "Attach receipt" placeholder | Bottom sheet: "Take photo" / "Choose from files" |
| Pick from gallery (jpg/png/webp) | File saved, OCR runs, fields auto-fill (pristine check), snackbar "Receipt scanned. Fields filled." |
| Take photo (jpg) | Same as gallery |
| ~~Pick PDF~~ | No longer offered. The file picker no longer lists `application/pdf`. |

### Top-level Add Receipt (new)

| Step | UI |
|---|---|
| 1. Tap "Add Receipt" in More | Open `AddReceiptScreen` in Idle state |
| 2. Idle | Big "Take photo of receipt" button |
| 3. Tap → permission flow | First time: camera permission prompt. Denied → message + retry button |
| 4. Camera captures | System camera intent. Captures to `<filesDir>/receipts/.capture/<uuid>.jpg` |
| 5. OcrInProgress | Screen shows progress indicator while `ReceiptOcrProcessor.extract()` runs |
| 6. Review | Photo thumbnail + form pre-filled: title (merchant), amount, date, type, category, currency, note. All fields editable. |
| 7. Save | Validates (title required, amount > 0, category required). Creates `TransactionEntity` with the photo as `receiptPath`. Navigates to More. |
| 8. OCR returned nothing | Review screen still shows, with empty fields. User types manually. |
| 9. Camera cancelled | Return to Idle. No transaction created. |

## Architecture

```
┌────────────────────────────────────────────────────┐
│  MoreScreen                                        │
│  • new "Add Receipt" row → onAddReceipt callback   │
└────────────────────────────────────────────────────┘
                       │
                       ▼
┌────────────────────────────────────────────────────┐
│  AppNav                                            │
│  • new composable(Routes.ADD_RECEIPT)              │
│  • wires to AddReceiptScreen + AddReceiptViewModel │
└────────────────────────────────────────────────────┘
                       │
                       ▼
┌────────────────────────────────────────────────────┐
│  AddReceiptScreen (new)                            │
│  • state machine: Idle | OcrInProgress | Review    │
│  • camera launcher (reuses ReceiptSection pattern) │
│  • Save → transactionRepository.add()              │
└────────────────────────────────────────────────────┘
                       │
                       ▼
┌────────────────────────────────────────────────────┐
│  AddReceiptViewModel (new, @HiltViewModel)         │
│  • state: AddReceiptUiState                        │
│  • onPhotoCaptured(uri)                            │
│  • onSave()                                        │
│  • reuses: ReceiptRepository, ReceiptOcrProcessor, │
│            CategoryRepository, TransactionRepository│
└────────────────────────────────────────────────────┘
                       │
        ┌──────────────┴──────────────┐
        ▼                             ▼
┌──────────────────┐         ┌──────────────────┐
│ ReceiptRepository│         │ ReceiptOcr-      │
│ • saveFromUri    │         │ Processor.extract│
│ • absolutePath   │         │                  │
└──────────────────┘         └──────────────────┘
                                       │
                                       ▼
                             ┌──────────────────────┐
                             │ ReceiptOcrParser     │
                             │ parse(text) →        │
                             │ OcrFields            │
                             └──────────────────────┘
```

**No new abstractions** — reuses `ReceiptOcrProcessor.extract()`, `ReceiptOcrParser.parse()`, `ReceiptRepository.saveFromUri()`, `TransactionRepository.add()`, `CategoryRepository.observeByType()`. Adds one new screen, one new ViewModel, one new UiState.

**Reverts from Phase 2.14** — removes `extractFromPdf`, `MAX_PDF_PAGES`, `ReceiptOcrMerger`, `PdfOcrResult`, the `ReceiptKind` enum, `OcrSnackbarMeta`, the 4 PDF snackbar strings, the PDF MIME type in the inline file picker.

## Components

### New types

```kotlin
// ui/add_receipt/
data class AddReceiptUiState(
    val mode: AddReceiptMode = AddReceiptMode.Idle,
    val photoPath: String? = null,                  // relative path under <filesDir>/receipts/
    val title: String = "",
    val amountInput: String = "",
    val occurredAtEpochMillis: Long = System.currentTimeMillis(),
    val type: TransactionType = TransactionType.EXPENSE,
    val categoriesForType: List<CategoryEntity> = emptyList(),
    val selectedCategoryId: Long? = null,
    val currency: String = "USD",
    val note: String = "",
    val isLoaded: Boolean = false,
    val isSaving: Boolean = false,
    val saveComplete: Boolean = false,
    val error: AddReceiptError? = null,
)

enum class AddReceiptMode { Idle, OcrInProgress, Review }

enum class AddReceiptError { TITLE_REQUIRED, AMOUNT_INVALID, CATEGORY_REQUIRED, RECEIPT_SAVE_FAILED }

@HiltViewModel
class AddReceiptViewModel @Inject constructor(
    application: Application,
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository,
    private val receiptRepository: ReceiptRepository,
    private val receiptOcrProcessor: ReceiptOcrProcessor,
) : AndroidViewModel(application) {
    val state: StateFlow<AddReceiptUiState>
    fun onPhotoCaptured(uri: Uri)
    fun onTitleChange(value: String)
    fun onAmountChange(value: String)
    fun onDateChange(epochMillis: Long)
    fun onTypeChange(value: TransactionType)
    fun onCategoryChange(value: Long)
    fun onCurrencyChange(value: String)
    fun onNoteChange(value: String)
    fun onSave()
    fun onCancel()
}
```

### Files modified

| File | Change |
|---|---|
| `app/src/main/res/values/strings.xml` | Add: `action_add_receipt`, `add_receipt_title`, `add_receipt_idle_prompt`, `add_receipt_take_photo`, `add_receipt_review_title`, `add_receipt_camera_denied`, `add_receipt_saving`, `add_receipt_save`, `add_receipt_cancel`. Remove: `receipt_pdf_scanned`, `receipt_pdf_scanned_partial`, `receipt_pdf_scanned_capped`, `receipt_pdf_scanned_capped_partial`. |
| `app/src/main/java/io/github/jiro/expensetracker/ui/add_edit/AddEditTransactionViewModel.kt` | Remove `ReceiptKind` enum, `OcrSnackbarMeta` data class, the `pdf` branch in `runOcrForReceipt`. Keep the `lastOcrSnackbar: OcrSnackbarMeta?` state — but the field type changes to a simpler `Boolean` (or remove entirely if we revert to the pre-2.14 single-string snackbar). |
| `app/src/main/java/io/github/jiro/expensetracker/ui/add_edit/AddEditTransactionScreen.kt` | Update `onOcrSnackbar` callback signature to `() -> Unit` (revert the 2.14 enrichment). |
| `app/src/main/java/io/github/jiro/expensetracker/ui/navigation/AppNav.kt` | Revert `onOcrSnackbar` lambda to single-string snackbar. Add `composable(Routes.ADD_RECEIPT)`. |
| `app/src/main/java/io/github/jiro/expensetracker/ui/more/MoreScreen.kt` | Add `onAddReceipt` callback param + a new "Add Receipt" `MoreItem`. |
| `app/src/main/java/io/github/jiro/expensetracker/ui/navigation/Routes.kt` (or wherever Routes is) | Add `const val ADD_RECEIPT = "add_receipt"`. |

### Files created

| File | Purpose |
|---|---|
| `app/src/main/java/io/github/jiro/expensetracker/ui/add_receipt/AddReceiptScreen.kt` | Composable with state machine (Idle → OcrInProgress → Review) |
| `app/src/main/java/io/github/jiro/expensetracker/ui/add_receipt/AddReceiptViewModel.kt` | HiltViewModel, manages state, camera capture, OCR, save |
| `app/src/test/java/io/github/jiro/expensetracker/ui/add_receipt/AddReceiptViewModelTest.kt` | Unit tests for the VM (state transitions, save validation) |

### Files deleted

| File | Reason |
|---|---|
| `app/src/main/java/io/github/jiro/expensetracker/domain/receipt/ReceiptOcrMerger.kt` | No longer used (no PDF path) |
| `app/src/main/java/io/github/jiro/expensetracker/domain/receipt/PdfOcrResult.kt` | No longer used |
| `app/src/test/java/io/github/jiro/expensetracker/domain/receipt/ReceiptOcrMergerTest.kt` | Tests for deleted code |
| `app/src/main/java/io/github/jiro/expensetracker/data/local/ReceiptOcrProcessor.kt` (modified, not deleted) | Remove `extractFromPdf()` and `MAX_PDF_PAGES`. Revert constructor to no-arg (the receiptRepository injection is no longer needed for OCR — only `extract(bitmap)` remains, no PDF path). |

### Reverted confidence fields

The `OcrFields` confidence floats (`amountConfidence`, `dateConfidence`, `merchantConfidence`, `isComplete`) are removed — the merger is gone, no other code consumes confidence. The pre-2.14 shape is restored:

```kotlin
data class OcrFields(
    val amountMinor: Long?,
    val occurredAtEpochMillis: Long?,
    val merchant: String?,
) {
    val hasAny: Boolean get() = amountMinor != null || occurredAtEpochMillis != null || merchant != null
}
```

And the 9 confidence tests in `ReceiptOcrParserTest.kt` are removed.

## Data flow

**Inline image attach on Add/Edit (unchanged shape, image-only):**

```
User picks image in ReceiptSection
  └→ AddEditTransactionViewModel.onReceiptAttached(uri)
       └→ receiptRepository.saveFromUri(ctx, uri) → relative path
       └→ state.receiptPath = newPath
       └→ runImageOcr(newPath) → OcrFields
       └→ runOcrAndAutoFill(ocr) (pristine check)
       └→ if ocr.hasAny → state.lastOcrSnackbar = Unit  // simplified
       └→ screen observes → calls onOcrSnackbar() → AppNav shows "Receipt scanned. Fields filled."
```

**Top-level Add Receipt:**

```
User taps "Add Receipt" in More tab
  └→ AppNav navigates to Routes.ADD_RECEIPT
       └→ AddReceiptScreen in Idle mode
            │
            ├─ User taps "Take photo" button
            │   └→ if camera permission not granted → request
            │   └→ if granted → launch camera via FileProvider URI
            │
            ├─ Camera returns success
            │   └→ AddReceiptViewModel.onPhotoCaptured(uri)
            │        └→ mode = OcrInProgress
            │        └→ receiptRepository.saveFromUri(ctx, uri) → photoPath
            │        └→ ImageProcessor.decodeSampledBitmap(file, 2048) → bitmap
            │        └→ try { receiptOcrProcessor.extract(bitmap) }
            │        finally { bitmap.recycle() }
            │        └→ ReceiptOcrParser.parse(ocrText) → OcrFields
            │        └→ state.copy(
            │             mode = Review,
            │             title = ocr.merchant ?: "",
            │             amountInput = ocr.amountMinor?.let { MoneyFormat.formatAmountForEdit(it) } ?: "",
            │             occurredAtEpochMillis = ocr.occurredAtEpochMillis ?: System.currentTimeMillis(),
            │           )
            │        └→ UI recomposes → Review form
            │
            └─ User edits fields + taps Save
                 └→ AddReceiptViewModel.onSave()
                      └→ validate (title not blank, amount > 0, category != null)
                      └→ TransactionRepository.add(entity with receiptPath = photoPath)
                      └→ state.saveComplete = true
                      └→ screen observes → navController.popBackStack() to More
```

**State persistence:** No savedStateHandle in AddReceipt. If the user backgrounds the app mid-OCR, the photo is already on disk (camera intent wrote it). On re-entry, the screen restarts at Idle and the photo is orphaned (deleted by `onCleared` cleanup or a future "stale capture" sweep). MVP: no resume from background.

**Threading:** All OCR runs in `viewModelScope.launch` with `withContext(Dispatchers.IO)` for bitmap decode + file I/O. ML Kit's `suspendCancellableCoroutine` runs on its own background dispatcher. UI thread never blocks.

## Error handling

| Failure | Behavior |
|---|---|
| Camera permission denied | `AddReceiptUiState.cameraDenied = true` (or store in a side field); UI shows a message + a "Grant permission" button that re-launches the permission request |
| Camera cancelled (user backs out) | `cameraLauncher` returns `success = false`. VM ignores the result, mode stays Idle |
| OCR returns no text | `ocr.hasAny == false`. State transitions to Review with empty fields. User types manually. **No snackbar** (the form is the feedback). |
| ML Kit throws | Caught in `onPhotoCaptured`'s `try { ... } catch (e: Exception) { ... }`. State transitions to Review with empty fields. Logged via `Log.w`. |
| Bitmap decode OOM | Same as above — caught, empty fields, Review state. |
| Save fails (DB error) | `try { transactionRepository.add(entity) } catch (e: Exception) { state.copy(error = RECEIPT_SAVE_FAILED) }`. UI shows error string. |

**No new error types for the inline path** — it already swallows OCR failures silently, and the user said keep that behavior.

## Testing

### `AddReceiptViewModelTest.kt` (new) — ~7 tests

| Test | What it pins down |
|---|---|
| `initialState_isIdle` | Fresh VM starts at Idle mode, no photo, no fields |
| `onPhotoCaptured_emptyReceiptsDir_savesAndTransitionsToReview` | URI → file saved → OCR runs → Review mode with OCR'd fields |
| `onPhotoCaptured_ocrReturnsNothing_transitionsToReviewWithEmptyFields` | Empty `OcrFields` → Review mode, title/amount/date empty |
| `onTitleChange_updatesState` | title is updated in state |
| `onSave_missingTitle_setsError` | Save with blank title → `error = TITLE_REQUIRED`, no transaction added |
| `onSave_invalidAmount_setsError` | Save with non-parseable amount → `error = AMOUNT_INVALID`, no transaction added |
| `onSave_validInputs_addsTransactionAndSetsSaveComplete` | Save with valid title/amount/category → `transactionRepository.add` called once with the right entity (including `receiptPath = photoPath`), `saveComplete = true` |

### `ReceiptOcrParserTest.kt` (modified) — remove 9 confidence tests

Revert to the pre-2.14 state. The 3 pre-2.14 test classes (parseAmount, parseDate, pickMerchant) keep their nullness-only assertions.

### `AddEditTransactionViewModelTest` (existing, may need minor update)

If the existing VM tests reference `ReceiptKind` / `OcrSnackbarMeta`, update them to use the simplified `lastOcrSnackbar: Boolean` (or whatever the new state shape is — see "Components" above).

### Manual smoke (post-merge, deferred if no device)

1. Tap More → "Add Receipt" → camera opens (or permission prompt on first run)
2. Capture a receipt photo → progress shows → Review form appears with amount/merchant/date pre-filled
3. Edit a field, tap Save → returns to More
4. Open Transactions tab → new row visible with the receipt thumbnail
5. Regression: Add/Edit transaction → "Attach receipt" → bottom sheet no longer offers PDF; image attach + OCR still works; snackbar still shows "Receipt scanned. Fields filled."

## Architecture alignment

This refactor matches the existing pure-vs-Android split:
- **Pure**: `OcrFields` (3-field shape restored), `ReceiptOcrParser` (unchanged logic, confidence heuristic outputs removed).
- **Android-aware**: `ReceiptOcrProcessor` (revert to no-arg constructor, only `extract(bitmap)` remains), `AddReceiptViewModel` (new, handles camera + save), `AddReceiptScreen` (new, state-driven UI).
- **No domain/receipt/* changes** beyond reverting `OcrFields` shape. The merger abstraction is gone.

**Per CLAUDE.md design decisions**: receipts remain "app-internal + bundled in JSON backup" with files in `<filesDir>/receipts/`. No change to the storage model.

**Repository pattern preserved**: `AddReceiptViewModel` talks to repositories, not DAOs. No new DB schema changes — uses the existing `TransactionEntity` and `CategoryEntity`.

## Open question for the user (will be resolved before plan)

**Q: Should `AddReceiptScreen` support the same currency dropdown as AddEditTransaction, or default to a fixed currency (e.g., "USD" or the user's home currency from Settings)?**

The MVP default: single currency dropdown matching AddEditTransaction's behavior. User can change. If the user wants a different default, this is a one-line change in the VM init.

## Self-review

**Spec coverage:**
- Camera-first top-level flow: ✓
- More tab entry: ✓
- Review screen with editable fields: ✓
- Save creates new transaction: ✓
- Navigate to More on save: ✓
- Inline image attach stays: ✓
- Inline file picker is image-only: ✓
- PDF code removed: ✓
- Inline OCR auto-fill preserved: ✓
- Per the user's answers: ✓

**Internal consistency:** Architecture matches data flow. Components match files modified/created/deleted.

**Scope check:** This is one focused refactor (one feature: camera-based Add Receipt). Doesn't need decomposition.

**Ambiguity check:** The state machine has clear transitions. The error cases are listed. The "no resume from background" is explicit.
