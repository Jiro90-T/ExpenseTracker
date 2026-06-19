# Phase 2.14 — PDF Receipts OCR — Design

**Status:** Draft 2026-06-19
**Phase:** 2.14
**Predecessors:** Phase 2.4 (Receipts) ships the image-only OCR pipeline (`ReceiptOcrProcessor.extract(bitmap)` → `ReceiptOcrParser.parse()` → auto-fill), the `ReceiptRepository.renderPdfPage()` rasterizer (used by the receipt thumbnail and viewer), and the PDF attach picker (`application/pdf` accepted). Phase 2.4's spec lists "OCR on PDF (would need rasterization first)" as out-of-scope. Phase 2.14 closes that gap.

## Goal

Extend the Add/Edit receipt-attach pipeline so PDF receipts are OCR'd across up to 3 pages, with per-field confidence scoring, and a distinct snackbar. Reuses the existing `ReceiptRepository.renderPdfPage()` and `ReceiptOcrProcessor.extract()` primitives. No new dependencies, no new abstractions.

Out of scope (intentional, deferred): rasterizing PDFs larger than 3 pages (we cap and OCR only the first 3), password-protected PDFs (caught and silently skipped — manual entry), selecting specific pages to OCR, OCR confidence surfaced in the UI (confidence is internal-only; used only for merge priority), re-OCR'ing a previously attached PDF, exporting OCR text to a file.

## User-visible behavior

When the user attaches a PDF receipt in Add/Edit transaction:

| Scenario | Snackbar | Auto-fill |
|---|---|---|
| 1-page PDF, fields extracted | "PDF scanned (1 pages). Fields filled." | Yes (pristine check) |
| 1-page PDF, only amount extracted | "PDF scanned (1 pages). Some fields filled." | Yes (amount only) |
| 3-page PDF, fields extracted | "PDF scanned (3 pages). Fields filled." | Yes |
| 10-page PDF, fields extracted (capped) | "PDF scanned (3 pages of 10). Fields filled." | Yes |
| 10-page PDF, no fields extracted | (none) | No |
| Corrupted PDF | (none) | No |
| Password-protected PDF | (none) | No |
| 1-page PDF with no text layer (scanned image) | Same as above; OCR runs against the rasterized bitmap | Yes if ML Kit finds anything |

Image receipt behavior is unchanged (uses existing `receipt_image_scanned*` snackbar strings).

## Architecture

**Layered split (matches existing pure-vs-Android pattern in Phase 2.4):**

```
┌─────────────────────────────────────────────────┐
│  AddEditTransactionViewModel                    │  UI/VM layer
│  • onReceiptAttached branches on file extension │
│  • calls processor.extractFromPdf() for PDFs    │
│  • builds snackbar text from PdfOcrResult       │
└─────────────────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────┐
│  ReceiptOcrProcessor (@Singleton, Android-aware)│  OCR layer
│  • extract(bitmap) — unchanged                  │
│  • extractFromPdf(path, maxPages=3) — new       │
│      → render each page via ReceiptRepository   │
│      → OCR each via existing extract(bitmap)    │
│      → merge via ReceiptOcrMerger               │
│      → returns PdfOcrResult                     │
└─────────────────────────────────────────────────┘
                       │
        ┌──────────────┴──────────────┐
        ▼                             ▼
┌──────────────────┐         ┌──────────────────────┐
│ ReceiptRepository│         │ ReceiptOcrMerger     │  Pure layer
│ (already has     │         │ (new, no Android)    │  (JVM-testable)
│  renderPdfPage,  │         │  merge(pages):       │
│  countPages)     │         │    OcrFields         │
└──────────────────┘         └──────────────────────┘
                                       │
                                       ▼
                             ┌──────────────────────┐
                             │ ReceiptOcrParser     │  Pure layer
                             │ (modified: adds      │  (JVM-testable)
                             │  confidence Float    │
                             │  to OcrFields)       │
                             └──────────────────────┘
```

**No new abstractions** — reuses `ReceiptRepository.renderPdfPage()`, `ReceiptOcrProcessor.extract()`, `ReceiptOcrParser`. Adds two small types (`PdfOcrResult`, `ReceiptOcrMerger`) and three confidence fields on `OcrFields`.

**No new dependencies** — `PdfRenderer` is built into Android (API 21+; our `minSdk = 24` is fine).

## Components

### New types

```kotlin
// Pure (domain/receipt/)
data class OcrFields(
    val amountMinor: Long?,
    val amountConfidence: Float,        // 0f when null, else 0.6..1f
    val occurredAtEpochMillis: Long?,
    val dateConfidence: Float,          // 0f when null, else 0.6..1f
    val merchant: String?,
    val merchantConfidence: Float,      // 0f when null, else 0.7..1f
) {
    val hasAny: Boolean get() = amountMinor != null || occurredAtEpochMillis != null || merchant != null
}

// Pure (domain/receipt/)
object ReceiptOcrMerger {
    /** Pick the most-confident non-null field across pages. Ties → first page wins. */
    fun merge(pages: List<OcrFields>): OcrFields
}

// Data carrier for the merged result + page count (no behavior).
data class PdfOcrResult(
    val fields: OcrFields,
    val pagesScanned: Int,
    val totalPages: Int,        // for the "(N pages scanned)" snackbar
)
```

### Modified files

| File | Change |
|---|---|
| `ReceiptOcrParser.kt` | Each parse fn returns its value AND a confidence score. `parse()` returns `OcrFields` with all three confidences populated. |
| `ReceiptOcrProcessor.kt` | Add constructor dep on `ReceiptRepository`. Add `suspend fun extractFromPdf(relativePath, maxPages = 3): PdfOcrResult`. |
| `AddEditTransactionViewModel.kt` | In `onReceiptAttached`, branch on `.pdf` and call `extractFromPdf()`. Refactor `runOcrAndAutoFill` to accept a pre-built `OcrFields`. Build distinct snackbar message. |
| `ReceiptOcrParserTest.kt` | Add ~9 tests for confidence scores. |
| `ReceiptOcrMergerTest.kt` (new) | ~6 tests: empty, single page, multi-page conflict, all-null, tie-break. |
| `ReceiptOcrProcessorExtractFromPdfTest.kt` (new, instrumented) | ~3 tests with real PDF fixtures in `app/src/androidTest/assets/`. |
| `strings.xml` | Add 2 strings: `receipt_pdf_scanned`, `receipt_pdf_scanned_partial`. |

### Confidence heuristics in the parser

These expose the existing internal heuristics as scores:

```kotlin
// amount
TOTAL_KEYWORDS hit       → 1.0f   // existing parseAmount "withKeyword" branch
largest-value fallback    → 0.6f   // existing parseAmount fallback branch
percentage-only line      → field stays null, confidence 0f (no change)

// date
ISO (YYYY-MM-DD)          → 1.0f
EU dot (DD.MM.YYYY)       → 0.9f
US slash (MM/DD/YYYY)     → 0.7f
DD/MM slash fallback      → 0.6f
no parseable date         → field stays null, confidence 0f (no change)

// merchant
len ≥ 10 and has letters  → 1.0f
short or all-symbols      → 0.7f   // borderline but still kept
existing rejection cases  → field stays null, confidence 0f (no change)
```

### Snackbar strings

```xml
<string name="receipt_pdf_scanned">PDF scanned (%1$d pages). Fields filled.</string>
<string name="receipt_pdf_scanned_partial">PDF scanned (%1$d pages). Some fields filled.</string>
```

When `pagesScanned < totalPages`, the format is "PDF scanned (3 pages of 10). Fields filled." — passed as two `%1$d` / `%2$d` args:

```xml
<string name="receipt_pdf_scanned_capped">PDF scanned (%1$d pages of %2$d). Fields filled.</string>
<string name="receipt_pdf_scanned_capped_partial">PDF scanned (%1$d pages of %2$d). Some fields filled.</string>
```

Reuses the existing `receipt_image_scanned` / `receipt_image_scanned_partial` for image receipts — no change.

## Data flow

**Receipt attached (PDF path):**

```
User taps "Choose file" in ReceiptSection
  └→ fileLauncher picks application/pdf URI
       └→ AddEditTransactionViewModel.onReceiptAttached(uri)
            │
            ├─ 1. Save PDF to app storage
            │    receiptRepository.saveFromUri(ctx, uri) → "receipts/<uuid>.pdf"
            │
            ├─ 2. State update: receiptPath = newPath
            │
            ├─ 3. Branch on .pdf extension:
            │    │
            │    └─► receiptOcrProcessor.extractFromPdf(newPath, maxPages = 3)
            │         │
            │         ├─► totalPages = countPages(newPath)
            │         ├─► for i in 0 until min(3, totalPages):
            │         │     ├─► bitmap = renderPdfPage(path, i)         // Dispatchers.IO
            │         │     ├─► pageResult = extract(bitmap)            // ML Kit
            │         │     ├─► bitmap.recycle()
            │         │     └─► pages += pageResult
            │         └─► PdfOcrResult(
            │              fields = ReceiptOcrMerger.merge(pages),
            │              pagesScanned = pages.size,
            │              totalPages = totalPages,
            │             )
            │
            ├─ 4. runOcrAndAutoFill(ocrFields)            // refactored: takes OcrFields, not path
            │    │
            │    ├─► if !ocr.hasAny → return (no snackbar)
            │    ├─► apply pristine-field check (existing logic, unchanged)
            │    └─► state.update { lastOcrFields = ocr, ...filled fields }
            │
            └─ 5. UI observes lastOcrFields → shows snackbar
                 │
                 ├─ PDF + pagesScanned == totalPages:           "PDF scanned (N pages). Fields filled." | partial
                 └─ PDF + pagesScanned <  totalPages:           "PDF scanned (M pages of N). Fields filled." | partial
```

**Key change to `runOcrAndAutoFill`:** today it takes a `String` path and does image decoding + OCR inline. After this change, it accepts a pre-built `OcrFields` (extracted either by the image path or the PDF path). The auto-fill pristine check is identical — moved to a shared private method.

**Threading:** All PDF rendering happens inside `extractFromPdf`, which is `suspend`. Internally wraps `renderPdfPage` + `extract` in `withContext(Dispatchers.IO)`. UI thread is never blocked. Bitmap recycling happens between pages so a 3-page PDF doesn't hold 3 large bitmaps at once.

**State persistence:** `lastOcrFields` stays `OcrFields?` (not `PdfOcrResult?`). The result type only matters for the snackbar. Storing `PdfOcrResult` in form state would leak the page count into places it doesn't belong.

## Error handling

The current image-OCR path silently swallows all OCR failures (the receipt stays attached, no snackbar). The PDF path inherits the same "best-effort, never block the user" policy, with these specific failure modes:

| Failure | Behavior |
|---|---|
| PDF missing/corrupted at `renderPdfPage` | `countPages` returns 0 → `extractFromPdf` returns `PdfOcrResult(emptyOcrFields, 0, 0)`. VM sees `!hasAny`, no snackbar. Receipt still attached. **No exception propagation.** |
| Password-protected PDF | `PdfRenderer` throws `SecurityException` → caught inside `extractFromPdf`, returns empty result. **No exception propagation.** |
| `pageCount == 0` | Treat as corrupted PDF. Empty result, no snackbar. |
| `pageCount > 3` | Render pages 0, 1, 2 only. `pagesScanned = 3`, `totalPages = N`. Snackbar (if fields extracted) reads "PDF scanned (3 pages of N). Fields filled." — communicates the cap. |
| ML Kit fails on a specific page | Catch inside the per-page loop, skip that page, continue with others. If all pages fail, return empty result. **No exception propagation.** |
| Bitmap OOM during `renderPdfPage` | `Bitmap.createBitmap` can throw `OutOfMemoryError`. Catch, log, return empty result. **No exception propagation.** |
| VM scope cancelled (user navigates away mid-OCR) | `suspendCancellableCoroutine` already handles this in `extract(bitmap)`. The `extractFromPdf` loop checks `currentCoroutineContext().isActive` between pages and exits early. |
| `Bitmap.recycle()` throws (it doesn't, but defensive) | Not caught — would be a bug. Let it propagate so we notice. |

**The one observable failure:** the receipt is attached but no fields get auto-filled. The user sees the PDF thumbnail with the "PDF" badge and fills fields manually. Same UX as a blank image receipt today.

**Why no new error types?** `extractFromPdf` returns `PdfOcrResult` (a value, not a sealed result). Failure is encoded as `fields.hasAny == false`. This matches the existing pattern (image OCR returns empty `OcrFields` on failure rather than throwing).

**Logging:** one `Log.w` per skipped page (so we can see in logcat if ML Kit is misbehaving on a specific page). No user-facing error UI.

## Testing

### `ReceiptOcrParserTest.kt` (modified) — 9 new tests

| Test | What it pins down |
|---|---|
| `parseAmount_totalKeyword_hasConfidence1` | "Subtotal: $5.00\nTax: $0.40\n**Total: $5.40**" → amount=540, amountConfidence=1.0f |
| `parseAmount_fallbackLargest_hasConfidence06` | "Item 1 $2.00\nItem 2 $8.00" → amount=800, amountConfidence=0.6f |
| `parseAmount_percentageOnly_returnsNull` | "Discount 10%" → amount=null, amountConfidence=0f (existing test, unchanged) |
| `parseDate_iso_hasConfidence1` | "2026-06-09" → epoch, dateConfidence=1.0f |
| `parseDate_euDot_hasConfidence09` | "09.06.2026" → epoch, dateConfidence=0.9f |
| `parseDate_usSlash_hasConfidence07` | "06/09/2026" → epoch, dateConfidence=0.7f |
| `parseDate_ddmmSlashFallback_hasConfidence06` | "09/06/2026" (EU) → epoch, dateConfidence=0.6f |
| `pickMerchant_longHasLetters_hasConfidence1` | "Coffee & Co Downtown" → "Coffee & Co Downtown", merchantConfidence=1.0f |
| `pickMerchant_shortOrSymbolic_hasConfidence07` | borderline short merchant → kept with 0.7f |

(Existing tests stay green — they only check nullness, not confidence.)

### `ReceiptOcrMergerTest.kt` (new) — 6 tests

| Test | What it pins down |
|---|---|
| `merge_emptyList_returnsEmptyFields` | All null, all confidences 0f. |
| `merge_singlePage_returnsThatPage` | Single `OcrFields` round-trips unchanged. |
| `merge_picksHighestConfidencePerField` | Page 1 amount=100 (conf 1.0), page 2 amount=200 (conf 0.6) → result amount=100. |
| `merge_firstPageHasField_secondPageEmpty_stillUsesFirst` | Page 1 has merchant "A" (conf 1.0), page 2 all null → result merchant="A". |
| `merge_conflictOnOneField_othersIndependent` | Page 1 amount=100 (conf 1.0) merchant="A" (conf 1.0); page 2 amount=200 (conf 0.6) merchant=null → amount=100, merchant="A". |
| `merge_tieOnConfidence_firstPageWins` | Both pages amount=100 (conf 0.6f) → result amount=100 (deterministic). |

### `ReceiptOcrProcessorExtractFromPdfTest.kt` (new, instrumented) — 3 tests

Drop two real PDFs into `app/src/androidTest/assets/`:

- `single-page-text.pdf` — 1-page digitally generated receipt
- `three-page-itemized.pdf` — 3-page itemized receipt (different field strengths per page to test merging)

| Test | What it pins down |
|---|---|
| `extractFromPdf_singlePage_findsAmountAndMerchant` | Real ML Kit against 1-page PDF; expect non-null amount + merchant. |
| `extractFromPdf_threePages_mergesBestPerField` | Real ML Kit against 3-page PDF; expect merger to pick the highest-confidence field per slot. |
| `extractFromPdf_corruptedPdf_returnsEmpty` | Truncated PDF bytes (100 bytes from a real PDF header); expect `hasAny == false`, no exception. |

Instrumented tests run on a device/emulator. This matches Phase 2.4 / Phase 2.8 receipts' existing instrumented-test posture (deferred to "manual smoke" if no emulator is available locally). For now: write the tests, mark `@Ignore` if no emulator available locally — same posture as Phase 2.8.

### Manual smoke protocol (on device, post-merge)

1. Take a 1-page PDF receipt (digitally generated, text-extractable) → attach → confirm fields auto-fill and snackbar shows "PDF scanned (1 pages). Fields filled."
2. Take a 3-page itemized PDF → attach → confirm fields merge correctly and snackbar shows "PDF scanned (3 pages). Fields filled."
3. Open a 10-page statement PDF → attach → confirm snackbar shows "PDF scanned (3 pages of 10). Fields filled."
4. Open a corrupted .pdf (truncate a real one to 100 bytes) → attach → confirm no crash, no snackbar, thumbnail shows missing-error.
5. Open a 1-page image-only PDF (scanned, no text layer) → attach → confirm OCR runs against the rasterized bitmap and snackbar fires if any field is extracted.