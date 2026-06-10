# Phase 2.4 — Receipts — Design

**Status:** Approved 2026-06-10
**Phase:** 2.4
**Predecessors:** Phase 2.3 (multi-currency, dialogs in form) sets the form pattern Phase 2.4 extends. Backup format widens from JSON to ZIP in the same slice.

## Goal

Let the user attach an image or PDF receipt to a transaction, see it in the AddEdit form, view it fullscreen, and have the transaction's amount/date/title auto-filled from the receipt's text. Back up receipts alongside the JSON manifest in a single `.zip` so restore round-trips them.

Out of scope (later phases): per-receipt "save to Photos" share-sheet affordance, multiple receipts per transaction, cloud backup, pinch-to-zoom on the image viewer, OCR on PDF (would need rasterization first).

## User-visible behavior

In the AddEditTransactionScreen, a new **Receipt** section appears below the note field:

- If no receipt is attached: shows a dashed-outline "Attach receipt" button. Tap → bottom sheet with two options: **Take photo** (camera) and **Choose file** (gallery / document picker).
- If a receipt is attached:
  - Image: shows a 96dp thumbnail (downscaled preview, even if the saved file is 2048px) with small **Replace** and **Remove** icon buttons overlaid in the top-right corner.
  - PDF: shows a 96dp thumbnail of the first page (rendered via `PdfRenderer`) with the same Replace/Remove overlay. A small "PDF" badge in the bottom-left corner disambiguates.
- Tap the thumbnail → navigates to the fullscreen `ReceiptViewerScreen`:
  - Image: shown fullscreen (no pinch-to-zoom in MVP).
  - PDF: `HorizontalPager` over all pages, swipe horizontally.
- One-time snackbar after attach: **"Receipt scanned. Fields filled."** (only if OCR actually filled something; only if the file is an image).
- Replacing a receipt: the new file is saved first; the old file is deleted only after the new one succeeds.
- Removing a receipt: the file is deleted; the form's `receiptPath` becomes null on save.

**Edit behavior**: opening an existing transaction with a receipt re-attaches it (loads the thumbnail from the saved path).

## Data model

Schema migration **v4 → v5** adds one new nullable column to `transactions`:

```sql
ALTER TABLE transactions ADD COLUMN receiptPath TEXT;
```

`receiptPath` is **relative to `<filesDir>/receipts/`** — e.g. `abc123.jpg` means the file is at `<filesDir>/receipts/abc123.jpg`. Relative paths survive backup-restore across devices. Nullable (most existing transactions have no receipt). `ON DELETE RESTRICT` is unchanged; the file is deleted by the application, not the DB.

Bump `AppDatabase.version` from 4 to 5 and add `MIGRATION_4_5` to the companion list.

**Why relative path (not absolute or content URI):**
- Absolute paths break after a backup is restored on a different device or after Android clears the app's data.
- A filename-only form loses the ability to add subfolders later; the relative form keeps that seam open.
- A `content://` URI ties the receipt to the originating app's FileProvider; we want app-internal storage per the design decision.

**MIME detection on save** uses `context.contentResolver.getType(src)` (the system's view of the file). **MIME detection on read** is by file extension (`.jpg`, `.jpeg`, `.png`, `.webp`, `.pdf`) — simpler than magic-byte sniffing and the saved filenames always have an extension because `saveFromUri` always uses a known extension. The two should always agree for files picked through the system picker.

## Components

| File | Purpose |
| --- | --- |
| `data/local/TransactionEntity.kt` | Add `val receiptPath: String?` field. |
| `data/local/AppDatabase.kt` | Add `MIGRATION_4_5`, bump version to 5. |
| `data/repository/ReceiptRepository.kt` | Owns the I/O. `absolutePath(relative): File`, `saveFromUri(src: Uri): String` (branches on MIME), `openInputStream(relative)`, `delete(relative)`, `exists(relative)`, **new: `openPdfPageCount(relative): Int`, `renderPdfPage(relative, pageIndex): Bitmap`**. |
| `data/local/ImageProcessor.kt` | Pure `downscaleToMaxEdge(bitmap, maxEdge): Bitmap` and `decodeSampledBitmap(path, maxEdge)` (uses `BitmapFactory.Options.inSampleSize` for the first pass to avoid OOM). |
| `domain/receipt/ReceiptOcrParser.kt` | Pure `parse(text: String): OcrFields` (no Android types — fully testable). |
| `data/local/ReceiptOcrProcessor.kt` | Wraps `com.google.mlkit:text-recognition:16.0.1` (Latin). `suspend fun extract(bitmap: Bitmap): OcrFields` — returns the parser's result. |
| `ui/add_edit/ReceiptSection.kt` | Compose section for the form. |
| `ui/receipts/ReceiptViewerScreen.kt` | Fullscreen image or paged PDF. |
| `ui/add_edit/AddEditTransactionViewModel.kt` | Modified to wire the OCR pipeline and the receipt-path state. |
| `ui/navigation/AppNav.kt` | New route `receipts/viewer?path=...` (or two routes — `receipts/image` and `receipts/pdf` — depending on what the navigator wants to do). The single-route form is simpler; image vs PDF is detected inside the viewer. |
| `backup/BackupManager.kt` | New `exportToZip(appVersionName): File` (was `exportToJson`). New `importFromZipUri(uri): Result<ImportSummary>`. Old `importFromUri` (the v2 .json path) is kept working unchanged. |
| `backup/BackupFormat.kt` | Bump `FORMAT_VERSION = 3`. Add `receiptPath` to `transactionEntityToJson` / `transactionFromJson`. |
| `res/xml/file_paths.xml` | Add `<files-path name="receipts" path="receipts/"/>` for the FileProvider (camera capture needs a content:// URI). |
| `AndroidManifest.xml` | Add `<uses-permission android:name="android.permission.CAMERA"/>` with `android:required="false"` (the app shouldn't hard-require camera — gallery-only users still need to install). |
| `app/build.gradle.kts` | Add `com.google.mlkit:text-recognition:16.0.1` to the version catalog. |
| `res/values/strings.xml` | New strings. |

### `ReceiptRepository` API

```kotlin
@Singleton
class ReceiptRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val receiptsDir: File = File(context.filesDir, "receipts").apply { mkdirs() }

    fun absolutePath(relativePath: String): File = File(receiptsDir, relativePath)
    fun exists(relativePath: String): Boolean = absolutePath(relativePath).exists()

    suspend fun saveFromUri(src: Uri): String = withContext(Dispatchers.IO) {
        val mime = context.contentResolver.getType(src)
        when {
            mime == "application/pdf" -> copyBytesAsIs(src, ext = "pdf")
            mime?.startsWith("image/") == true -> copyDownscaleAndReencode(src, maxEdge = 2048)
            else -> error("Unsupported receipt MIME type: $mime")
        }
    }

    fun openInputStream(relativePath: String): InputStream? =
        absolutePath(relativePath).takeIf { it.exists() }?.inputStream()

    suspend fun delete(relativePath: String) = withContext(Dispatchers.IO) {
        if (relativePath.isBlank()) return@withContext
        val f = absolutePath(relativePath)
        if (f.exists() && f.canonicalPath.startsWith(receiptsDir.canonicalPath)) {
            f.delete()
        }
    }

    // PDF rendering — caller must close.
    @SuppressLint("RestrictedApi")  // PdfRenderer requires a ParcelFileDescriptor.
    fun openPdfPageCount(relativePath: String): Int = /* open file, count pages, close */ ...
    fun renderPdfPage(relativePath: String, pageIndex: Int): Bitmap = /* open file, render page, close */ ...
}
```

The `canonicalPath.startsWith(receiptsDir.canonicalPath)` guard prevents a malicious or buggy `relativePath` like `../../shared_prefs/foo.xml` from deleting a file outside `receipts/`. (Defense in depth — the relative path is always a UUID in practice.)

### `OcrFields` shape

```kotlin
data class OcrFields(
    val amountMinor: Long?,
    val occurredAtEpochMillis: Long?,
    val merchant: String?,
) {
    val hasAny: Boolean get() = amountMinor != null || occurredAtEpochMillis != null || merchant != null
}
```

### `AddEditTransactionViewModel` changes

Two new methods on the VM:

```kotlin
fun onReceiptAttached(uri: Uri)
fun onReceiptRemoved()
fun onReceiptReplaceRequested(uri: Uri)  // similar to attach, but also deletes the old file
```

State changes:
- New `UiState` field: `receiptPath: String?` (already in the entity; need to add to UiState)
- New transient state: `lastOcrFields: OcrFields?` (for the "Receipt scanned. Fields filled." snackbar)

Flow on `onReceiptAttached`:
1. `viewModelScope.launch`:
   - `newPath = receiptRepository.saveFromUri(uri)`
   - If state had a prior `receiptPath`, schedule its deletion (after the new save succeeds).
   - Update state: `receiptPath = newPath`
   - If image (detect by extension): launch a sub-coroutine to run OCR:
     - `bitmap = imageProcessor.decodeSampledBitmap(newPath, maxEdge = 2048)`
     - `ocr = receiptOcrProcessor.extract(bitmap)`
     - If `ocr.hasAny`: apply the pristine-field check; for each non-null field, if the form field is still pristine, set it. Update `lastOcrFields = ocr` for the snackbar.
   - If PDF: skip OCR (no rasterization in MVP). `lastOcrFields` stays null → no snackbar.
2. The `lastOcrFields` state is **one-shot**: cleared by `consumeOcrSnackbar()` once the snackbar is dismissed, so re-attaching the same receipt (or a different one) shows a fresh snackbar.

Pristine-field check (so OCR doesn't override user input):
- `amountInput.isEmpty()` for amount (treat "0" or "0.00" as also pristine? — no, if the user typed 0, they meant 0; leave it alone)
- `title.isEmpty()` for merchant
- `occurredAtEpochMillis == <initial-now>` for date — capture the initial value at VM construction

### Picker contract

- **Take photo**: `ActivityResultContracts.TakePicture()` with a FileProvider URI at `<filesDir>/receipts/.capture/<uuid>.jpg`. The file in `.capture/` is moved to the live dir on success and `.capture/` is cleaned on VM teardown.
- **Choose file**: `ActivityResultContracts.OpenDocument(arrayOf("image/*", "application/pdf"))`. This works on all Android versions; the system file picker filters by MIME.

A single "Attach receipt" button shows a `ModalBottomSheet` with the two options. After the user picks, the result URI flows to `viewModel.onReceiptAttached(uri)`.

### Receipt deletion & orphan handling

- **Transaction deleted** (existing flow in `TransactionRepository.delete`): after the row is deleted, the `receiptPath` is also deleted via `receiptRepository.delete(receiptPath)`. One new line in the delete path.
- **User replaces a receipt**: the new file is saved first; the old file is deleted only after the new save succeeds (so a failed save doesn't leave a dangling reference).
- **User explicitly removes** (clears the receipt in the form): the file is deleted; `receiptPath = null` on save.
- **Zip restore wipes transactions**: after the DB wipe, the receipts dir is also wiped via `receiptsDir.deleteRecursively()` then re-created. No orphans.
- **Transaction updated with `receiptPath = null`**: if the previous path was non-null, delete the file.

## Backup format v3 (.zip)

The export zip contains:
- `manifest.json` — the v3 envelope: `{ formatVersion: 3, exportedAtEpochMillis, appVersionName, categories, transactions }` where each transaction now has a `receiptPath` field.
- `receipts/<relativePath>` — one entry per receipt referenced by any transaction. Files are stored with their relative path as the zip entry name (e.g. `receipts/abc123.jpg`).

`BackupManager.exportToZip`:
1. Generate the manifest JSON (same as before, with `receiptPath`).
2. Collect every distinct `receiptPath` from the transactions list.
3. Create a `ZipOutputStream` at `<cacheDir>/exports/<prefix><stamp>.zip`.
4. Write `manifest.json` as the first entry.
5. For each receipt: open the local file and copy into the zip under `receipts/<relativePath>`.
6. Close; return a FileProvider URI on the zip.

`BackupManager.importFromZipUri`:
1. Open the zip from the URI.
2. Read `manifest.json` → categories + transactions list.
3. **Wipe step** (in this exact order, all in the same `withContext(Dispatchers.IO)` block):
   a. Wipe `<filesDir>/receipts/` (delete then re-create the dir).
   b. In a single Room `withTransaction { ... }` block, delete all transactions and non-built-in categories, then insert the backup's rows. (Same as the v2 restore.)
4. **Receipt restore step**: for each transaction with a `receiptPath`, find the zip entry `receipts/<relativePath>`, extract it to `<filesDir>/receipts/<relativePath>`. Count any missing entries into `ImportSummary.missingReceiptCount`. If a receipt is referenced but the zip entry is absent, the transaction is restored with `receiptPath = null` so the rest of the data isn't lost.
5. Return `ImportSummary(categoriesRestored, transactionsRestored, missingReceiptCount)`.

The v2 `.json` import path stays unchanged — the existing `importFromUri` keeps working and is what the share sheet hands to for `.json` files. The new `importFromZipUri` is for `.zip` files. The file-extension detection can live in the Settings UI (which is the caller).

The `MIME_TYPE` constant in `BackupFormat` widens:
- Was: `"application/json"`
- New: keep `"application/json"` for v1/v2 compat; add `"application/zip"` for v3.

`FORMAT_VERSION = 3`.

## Error handling

| Failure | Surfaced as |
| --- | --- |
| `CAMERA` permission denied | Toast: "Camera permission is required." Disable the "Take photo" option; "Choose file" remains available. |
| Picker cancelled (user backs out) | No-op (user can retry). |
| Image decode fails / OOM during `saveFromUri` | Snackbar: "Could not read the image. Try a different one." Nothing is persisted. |
| PDF decode fails | Snackbar: "Could not read the PDF." Nothing is persisted. |
| ML Kit not available on device | Log + skip OCR; user manually fills fields. No retry loop. |
| OCR returns no useful fields | No snackbar; the form state is unchanged. |
| OCR returns partial fields | Only the non-null, pristine fields are filled. Snackbar: "Receipt scanned. Some fields filled." (or "Receipt scanned. Fields filled." if all three — but realistically 0–1 is common). |
| `PdfRenderer` can't open the file (corrupt) | Receipt is still saved. Viewer shows "Could not render PDF" placeholder. Form thumbnail falls back to a PDF icon. |
| File write fails (disk full) | Snackbar: "Could not save the receipt." Nothing is persisted. |
| Receipt file missing on disk (user deleted it via a file manager) | `exists()` returns false; thumbnail shows "Receipt file missing" placeholder; viewer shows a "Missing" message. |
| Zip backup missing some receipts | `ImportSummary.missingReceiptCount` is bumped; UI shows a one-time warning toast. |
| v2 .json backup imported (pre-receipt) | Works as today; all `receiptPath` are null. No new code path needed. |
| Receipt file outside `receipts/` (malicious relative path) | `canonicalPath.startsWith(receiptsDir.canonicalPath)` guard prevents deletion. |

## Tests

| Test | File | What it asserts |
| --- | --- | --- |
| `downscaleToMaxEdge_smallImage_untouched` | `ImageProcessorTest.kt` | Bitmap at 100×100 with maxEdge=2048 → returns same size. |
| `downscaleToMaxEdge_largeImage_downscaled` | `ImageProcessorTest.kt` | Bitmap at 4000×3000 with maxEdge=2048 → max edge becomes 2048. |
| `downscaleToMaxEdge_alreadyAtMax_untouched` | `ImageProcessorTest.kt` | 2048×1536 → unchanged. |
| `parseAmount_totalKeyword_picksTotal` | `ReceiptOcrParserTest.kt` | "Subtotal: $5.00\nTax: $0.40\nTotal: $5.40" → 540. |
| `parseAmount_multipleLines_picksLargest` | `ReceiptOcrParserTest.kt` | "Item 1 $2.00\nItem 2 $8.00\nItem 3 $3.00" → 800. |
| `parseAmount_noReasonableValue_returnsNull` | `ReceiptOcrParserTest.kt` | "Item 1234567" (no decimal) → null. |
| `parseAmount_skipsPercentages` | `ReceiptOcrParserTest.kt` | "Discount 10% off" → null. |
| `parseDate_isoFormat` | `ReceiptOcrParserTest.kt` | "Date: 2026-06-09" → epoch for 2026-06-09. |
| `parseDate_usSlashFormat` | `ReceiptOcrParserTest.kt` | "06/09/2026" → epoch for 2026-06-09. |
| `parseDate_europeanDotFormat` | `ReceiptOcrParserTest.kt` | "09.06.2026" → epoch for 2026-06-09. |
| `parseDate_invalid_returnsNull` | `ReceiptOcrParserTest.kt` | "not a date" → null. |
| `pickMerchant_firstNonTrivialLine` | `ReceiptOcrParserTest.kt` | "Whole Foods Market\n123 Main St\n$5.40" → "Whole Foods Market". |
| `pickMerchant_skipsHeaders` | `ReceiptOcrParserTest.kt` | "RECEIPT\nAcme Coffee\n$4.00" → "Acme Coffee". |
| `pickMerchant_empty` | `ReceiptOcrParserTest.kt` | "" → null. |
| `absolutePath_relative` | `ReceiptRepositoryTest.kt` (limited) | "abc.jpg" → `<filesDir>/receipts/abc.jpg`. |
| `exists_realFile` | `ReceiptRepositoryTest.kt` | After writing, exists returns true. |
| `exists_missingFile` | `ReceiptRepositoryTest.kt` | "missing.jpg" → false. |
| `delete_outsideReceiptsDir_noOp` | `ReceiptRepositoryTest.kt` | "../../../etc/passwd" → not deleted. |

`saveFromUri`, OCR processor, PdfRenderer — exercised on device in the manual smoke test.

## Strings to add

```
receipt_section_title          "Receipt"
receipt_attach                 "Attach receipt"
receipt_choose                 "Choose file"
receipt_take_photo             "Take photo"
receipt_replace                "Replace"
receipt_remove                 "Remove"
receipt_no_receipt             "No receipt attached"
receipt_missing                "Receipt file missing"
receipt_ocr_snackbar           "Receipt scanned. Fields filled."
receipt_pdf_badge              "PDF"
receipt_viewer_title           "Receipt"
receipt_viewer_image_missing   "Could not load image"
receipt_viewer_pdf_missing     "Could not render PDF"
receipt_camera_permission_denied "Camera permission is required to take a receipt photo."
receipt_unsupported_mime       "Could not read the file. Try an image or PDF."
receipt_save_failed            "Could not save the receipt."
receipt_backup_missing         "Some receipts in the backup were missing and weren't restored."
```

## Files touched (summary)

**New:** `ReceiptRepository.kt`, `ImageProcessor.kt`, `ReceiptOcrProcessor.kt`, `ReceiptOcrParser.kt`, `ReceiptSection.kt`, `ReceiptViewerScreen.kt`, `ImageProcessorTest.kt`, `ReceiptOcrParserTest.kt`, `ReceiptRepositoryTest.kt`.

**Modified:** `TransactionEntity.kt`, `AppDatabase.kt`, `AddEditTransactionViewModel.kt`, `AddEditTransactionScreen.kt` (host the new section), `AppNav.kt` (route), `BackupManager.kt`, `BackupFormat.kt`, `file_paths.xml`, `AndroidManifest.xml`, `strings.xml`, `app/build.gradle.kts` (ML Kit dep), `gradle/libs.versions.toml` (ML Kit version).

## Out of scope (intentional)

- Per-receipt "save to Photos" via share sheet.
- Multi-receipt per transaction.
- Cloud backup.
- Pinch-to-zoom on the image viewer.
- OCR on PDFs (would need rasterization).
- Compression of PDFs (they're stored as-is; size is what the user provided).
- Background removal / auto-crop on receipt photos.

## Open questions

None. Decisions were taken one at a time during brainstorming and recorded in the User-visible behavior, Data model, Components, and OCR pipeline sections above.
