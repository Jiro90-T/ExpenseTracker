# Phase 2.14 — PDF Receipts OCR — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the receipt-attach pipeline so PDF receipts are OCR'd across up to 3 pages (rendered via the existing `ReceiptRepository.renderPdfPage()` and fed to the existing `ReceiptOcrProcessor.extract()`), with per-field confidence scoring used by a pure merger to pick the best value across pages. A distinct snackbar communicates the result. No new dependencies, no new abstractions beyond `ReceiptOcrMerger` + `PdfOcrResult` + a `ReceiptKind` enum.

**Architecture:** Pure-calculator pattern (mirrors Phase 2.13's `StatisticsCalculator.kt` and Phase 2.4's `ReceiptOcrParser.kt`). Three pure types live in `domain/receipt/`: the extended `OcrFields` (with per-field `Float` confidence), a new `ReceiptOcrMerger.merge(pages: List<OcrFields>): OcrFields` (most-confident-field-wins), and a new `PdfOcrResult(fields, pagesScanned, totalPages)`. `ReceiptOcrProcessor` gains a second `suspend fun extractFromPdf(path, maxPages = 3): PdfOcrResult` that loops render → OCR → merge. The ViewModel branches on `.pdf` and refactors `runOcrAndAutoFill` to accept a pre-built `OcrFields`. The screen's `onOcrSnackbar` callback is enriched with a `ReceiptKind` + page info so AppNav picks the right string.

**Tech Stack:** Kotlin, Jetpack Compose + Material 3, Hilt, Coroutines + StateFlow, Room, JUnit 4, Android `PdfRenderer` (built-in, API 21+), ML Kit Text Recognition (already shipped). JDK 21 required (`export JAVA_HOME=C:/tools/jdk-21.0.5+11` before any `./gradlew` command).

---

## Task 1: Add `confidence` fields to `OcrFields` + 9 parser tests

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/domain/receipt/ReceiptOcrParser.kt`
- Modify: `app/src/test/java/io/github/jiro/expensetracker/domain/receipt/ReceiptOcrParserTest.kt`

- [ ] **Step 1: Update `OcrFields` data class with three confidence fields**

Open `app/src/main/java/io/github/jiro/expensetracker/domain/receipt/ReceiptOcrParser.kt`. Replace the existing `OcrFields` data class with:

```kotlin
data class OcrFields(
    val amountMinor: Long?,
    val amountConfidence: Float,        // 0f when amountMinor == null, else 0.6f..1.0f
    val occurredAtEpochMillis: Long?,
    val dateConfidence: Float,          // 0f when occurredAtEpochMillis == null, else 0.6f..1.0f
    val merchant: String?,
    val merchantConfidence: Float,      // 0f when merchant == null, else 0.7f..1.0f
) {
    val hasAny: Boolean
        get() = amountMinor != null || occurredAtEpochMillis != null || merchant != null

    /** True iff all three fields are non-null. */
    val isComplete: Boolean
        get() = amountMinor != null && occurredAtEpochMillis != null && merchant != null
}
```

- [ ] **Step 2: Update `ReceiptOcrParser.parse()` to populate confidences**

Replace the body of the `parse()` function and the three private helpers with:

```kotlin
    fun parse(text: String): OcrFields {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val (amount, amountConf) = parseAmount(lines)
        val (date, dateConf) = parseDate(text)
        val (merchant, merchantConf) = pickMerchant(lines)
        return OcrFields(
            amountMinor = amount,
            amountConfidence = amountConf,
            occurredAtEpochMillis = date,
            dateConfidence = dateConf,
            merchant = merchant,
            merchantConfidence = merchantConf,
        )
    }

    /** Returns (value, confidence). Confidence is 0f when value is null. */
    private fun parseAmount(lines: List<String>): Pair<Long?, Float> {
        val candidates = mutableListOf<Pair<String, Long>>()

        val currencyRegex = Regex("""\$?\s?(\d{1,6}(?:[.,]\d{2}))""")
        for (line in lines) {
            val matches = currencyRegex.findAll(line)
            for (m in matches) {
                val raw = m.groupValues[1].replace(',', '.')
                val value = raw.toDoubleOrNull() ?: continue
                if (value < 0.01 || value > 100_000.0) continue
                val minor = Math.round(value * 100.0)
                candidates.add(line.lowercase() to minor)
            }
        }

        val nonPct = candidates.filterNot { (line, _) ->
            Regex("""\d+\s*%""").containsMatchIn(line)
        }
        if (nonPct.isEmpty()) return null to 0f

        val withKeyword = nonPct.filter { (line, _) ->
            TOTAL_KEYWORDS.any { kw -> line.contains(kw) }
        }
        return if (withKeyword.isNotEmpty()) {
            withKeyword.maxBy { it.second }.second to 1.0f
        } else {
            nonPct.maxBy { it.second }.second to 0.6f
        }
    }

    /** Returns (value, confidence). Confidence is 0f when value is null. */
    private fun parseDate(text: String): Pair<Long?, Float> {
        val iso = Regex("""\b(\d{4})-(\d{2})-(\d{2})\b""").find(text)
        if (iso != null) {
            val cal = java.util.Calendar.getInstance()
            cal.set(iso.groupValues[1].toInt(), iso.groupValues[2].toInt() - 1, iso.groupValues[3].toInt(), 0, 0, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            return cal.timeInMillis to 1.0f
        }

        val eu = Regex("""\b(\d{2})\.(\d{2})\.(\d{4})\b""").find(text)
        if (eu != null) {
            val day = eu.groupValues[1].toInt()
            val mon = eu.groupValues[2].toInt()
            val year = eu.groupValues[3].toInt()
            if (day in 1..31 && mon in 1..12) {
                val cal = java.util.Calendar.getInstance()
                cal.set(year, mon - 1, day, 0, 0, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                return cal.timeInMillis to 0.9f
            }
        }

        val slash = Regex("""\b(\d{1,2})/(\d{1,2})/(\d{4})\b""").find(text)
        if (slash != null) {
            val a = slash.groupValues[1].toInt()
            val b = slash.groupValues[2].toInt()
            val year = slash.groupValues[3].toInt()
            if (a in 1..12 && b in 1..31) {
                val cal = java.util.Calendar.getInstance()
                cal.set(year, a - 1, b, 0, 0, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                return cal.timeInMillis to 0.7f
            }
            if (b in 1..12 && a in 1..31) {
                val cal = java.util.Calendar.getInstance()
                cal.set(year, b - 1, a, 0, 0, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                return cal.timeInMillis to 0.6f
            }
        }
        return null to 0f
    }

    /** Returns (value, confidence). Confidence is 0f when value is null. */
    private fun pickMerchant(lines: List<String>): Pair<String?, Float> {
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.length < 3) continue
            if (line.all { it.isDigit() || it.isWhitespace() || it == '$' || it == '.' || it == ',' }) continue
            if (SKIP_HEADERS.any { line.equals(it, ignoreCase = true) }) continue
            val kept = line.take(60)
            val confidence = if (line.length >= 10 && line.any { it.isLetter() }) 1.0f else 0.7f
            return kept to confidence
        }
        return null to 0f
    }
```

- [ ] **Step 3: Add 9 confidence tests to `ReceiptOcrParserTest.kt`**

Open `app/src/test/java/io/github/jiro/expensetracker/domain/receipt/ReceiptOcrParserTest.kt`. Append at the bottom of the class (before the closing brace):

```kotlin
    // ---- confidence scores (Phase 2.14) ----

    @Test
    fun parseAmount_totalKeyword_hasConfidence1() {
        val out = ReceiptOcrParser.parse(
            "Subtotal: \$5.00\nTax: \$0.40\nTotal: \$5.40"
        )
        assertEquals(540L, out.amountMinor)
        assertEquals(1.0f, out.amountConfidence, 0.0001f)
    }

    @Test
    fun parseAmount_fallbackLargest_hasConfidence06() {
        val out = ReceiptOcrParser.parse("Item 1 \$2.00\nItem 2 \$8.00\nItem 3 \$3.00")
        assertEquals(800L, out.amountMinor)
        assertEquals(0.6f, out.amountConfidence, 0.0001f)
    }

    @Test
    fun parseAmount_percentageOnly_returnsNullWithZeroConfidence() {
        val out = ReceiptOcrParser.parse("Discount 10%")
        assertNull(out.amountMinor)
        assertEquals(0f, out.amountConfidence, 0.0001f)
    }

    @Test
    fun parseDate_iso_hasConfidence1() {
        val out = ReceiptOcrParser.parse("Date: 2026-06-09")
        assertNotNull(out.occurredAtEpochMillis)
        assertEquals(1.0f, out.dateConfidence, 0.0001f)
    }

    @Test
    fun parseDate_euDot_hasConfidence09() {
        val out = ReceiptOcrParser.parse("Date: 09.06.2026")
        assertNotNull(out.occurredAtEpochMillis)
        assertEquals(0.9f, out.dateConfidence, 0.0001f)
    }

    @Test
    fun parseDate_usSlash_hasConfidence07() {
        val out = ReceiptOcrParser.parse("Date: 06/09/2026")
        assertNotNull(out.occurredAtEpochMillis)
        assertEquals(0.7f, out.dateConfidence, 0.0001f)
    }

    @Test
    fun parseDate_ddmmSlashFallback_hasConfidence06() {
        // 09/06/2026 — a=9 (not a valid month in MM/DD), b=6 is valid MM in DD/MM
        val out = ReceiptOcrParser.parse("Date: 09/06/2026")
        assertNotNull(out.occurredAtEpochMillis)
        assertEquals(0.6f, out.dateConfidence, 0.0001f)
    }

    @Test
    fun pickMerchant_longHasLetters_hasConfidence1() {
        val out = ReceiptOcrParser.parse("Coffee & Co Downtown\n\$4.50")
        assertEquals("Coffee & Co Downtown", out.merchant)
        assertEquals(1.0f, out.merchantConfidence, 0.0001f)
    }

    @Test
    fun pickMerchant_shortButAcceptable_hasConfidence07() {
        // 4 chars (>= 3 minimum, < 10 high-confidence threshold) and has letters
        val out = ReceiptOcrParser.parse("Nana\n\$4.50")
        assertEquals("Nana", out.merchant)
        assertEquals(0.7f, out.merchantConfidence, 0.0001f)
    }
```

Add these imports at the top of the file (alongside the existing ones):

```kotlin
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
```

- [ ] **Step 4: Run all parser tests to verify**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew :app:testDebugUnitTest --tests "*ReceiptOcrParserTest*"
```

Expected: BUILD SUCCESSFUL. **All existing tests still pass** (they only check nullness, not confidence) and **all 9 new confidence tests pass**. Total in this file: previous count + 9.

- [ ] **Step 5: Run full test suite to confirm no regressions**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL. All tests pass (the `OcrFields` change is source-compatible because the new fields have defaults — but verify nothing broke).

- [ ] **Step 6: Commit**

```bash
cd F:/AndroidApp/ExpenseTracker
git add app/src/main/java/io/github/jiro/expensetracker/domain/receipt/ReceiptOcrParser.kt app/src/test/java/io/github/jiro/expensetracker/domain/receipt/ReceiptOcrParserTest.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "Receipts: add confidence field to OcrFields + 9 parser tests"
```

---

## Task 2: `ReceiptOcrMerger` pure helper + 6 tests

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/domain/receipt/ReceiptOcrMerger.kt`
- Create: `app/src/test/java/io/github/jiro/expensetracker/domain/receipt/ReceiptOcrMergerTest.kt`

- [ ] **Step 1: Write the 6 failing tests**

Create `app/src/test/java/io/github/jiro/expensetracker/domain/receipt/ReceiptOcrMergerTest.kt`:

```kotlin
package io.github.jiro.expensetracker.domain.receipt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReceiptOcrMergerTest {

    private fun fields(
        amountMinor: Long? = null, amountConfidence: Float = 0f,
        occurredAtEpochMillis: Long? = null, dateConfidence: Float = 0f,
        merchant: String? = null, merchantConfidence: Float = 0f,
    ) = OcrFields(amountMinor, amountConfidence, occurredAtEpochMillis, dateConfidence, merchant, merchantConfidence)

    @Test
    fun merge_emptyList_returnsEmptyFields() {
        val out = ReceiptOcrMerger.merge(emptyList())
        assertNull(out.amountMinor)
        assertNull(out.occurredAtEpochMillis)
        assertNull(out.merchant)
        assertEquals(0f, out.amountConfidence, 0.0001f)
        assertEquals(0f, out.dateConfidence, 0.0001f)
        assertEquals(0f, out.merchantConfidence, 0.0001f)
    }

    @Test
    fun merge_singlePage_returnsThatPage() {
        val page = fields(amountMinor = 100L, amountConfidence = 1.0f)
        val out = ReceiptOcrMerger.merge(listOf(page))
        assertEquals(100L, out.amountMinor)
        assertEquals(1.0f, out.amountConfidence, 0.0001f)
    }

    @Test
    fun merge_picksHighestConfidencePerField() {
        val pages = listOf(
            fields(amountMinor = 100L, amountConfidence = 1.0f),
            fields(amountMinor = 200L, amountConfidence = 0.6f),
        )
        val out = ReceiptOcrMerger.merge(pages)
        assertEquals(100L, out.amountMinor)
        assertEquals(1.0f, out.amountConfidence, 0.0001f)
    }

    @Test
    fun merge_firstPageHasField_secondPageEmpty_stillUsesFirst() {
        val pages = listOf(
            fields(merchant = "A", merchantConfidence = 1.0f),
            fields(),
        )
        val out = ReceiptOcrMerger.merge(pages)
        assertEquals("A", out.merchant)
        assertEquals(1.0f, out.merchantConfidence, 0.0001f)
    }

    @Test
    fun merge_conflictOnOneField_othersIndependent() {
        val pages = listOf(
            fields(amountMinor = 100L, amountConfidence = 1.0f, merchant = "A", merchantConfidence = 1.0f),
            fields(amountMinor = 200L, amountConfidence = 0.6f),
        )
        val out = ReceiptOcrMerger.merge(pages)
        assertEquals(100L, out.amountMinor)
        assertEquals("A", out.merchant)
    }

    @Test
    fun merge_tieOnConfidence_firstPageWins() {
        val pages = listOf(
            fields(amountMinor = 100L, amountConfidence = 0.6f),
            fields(amountMinor = 200L, amountConfidence = 0.6f),
        )
        val out = ReceiptOcrMerger.merge(pages)
        assertEquals(100L, out.amountMinor)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew :app:testDebugUnitTest --tests "*ReceiptOcrMergerTest*"
```

Expected: BUILD FAILED with "Unresolved reference: ReceiptOcrMerger" (the class doesn't exist yet).

- [ ] **Step 3: Implement `ReceiptOcrMerger`**

Create `app/src/main/java/io/github/jiro/expensetracker/domain/receipt/ReceiptOcrMerger.kt`:

```kotlin
package io.github.jiro.expensetracker.domain.receipt

/**
 * Pure merger for multi-page OCR results. Picks the most-confident non-null
 * value per field across pages. Ties → first page wins.
 *
 * No Android types — fully JVM-testable.
 */
object ReceiptOcrMerger {

    fun merge(pages: List<OcrFields>): OcrFields {
        if (pages.isEmpty()) {
            return OcrFields(null, 0f, null, 0f, null, 0f)
        }

        val bestAmount = pages
            .filter { it.amountMinor != null }
            .maxByOrNull { it.amountConfidence }
        val bestDate = pages
            .filter { it.occurredAtEpochMillis != null }
            .maxByOrNull { it.dateConfidence }
        val bestMerchant = pages
            .filter { it.merchant != null }
            .maxByOrNull { it.merchantConfidence }

        return OcrFields(
            amountMinor = bestAmount?.amountMinor,
            amountConfidence = bestAmount?.amountConfidence ?: 0f,
            occurredAtEpochMillis = bestDate?.occurredAtEpochMillis,
            dateConfidence = bestDate?.dateConfidence ?: 0f,
            merchant = bestMerchant?.merchant,
            merchantConfidence = bestMerchant?.merchantConfidence ?: 0f,
        )
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew :app:testDebugUnitTest --tests "*ReceiptOcrMergerTest*"
```

Expected: BUILD SUCCESSFUL. 6/6 passing.

- [ ] **Step 5: Run full test suite**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 6: Commit**

```bash
cd F:/AndroidApp/ExpenseTracker
git add app/src/main/java/io/github/jiro/expensetracker/domain/receipt/ReceiptOcrMerger.kt app/src/test/java/io/github/jiro/expensetracker/domain/receipt/ReceiptOcrMergerTest.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "Receipts: add ReceiptOcrMerger pure helper + 6 tests"
```

---

## Task 3: `ReceiptOcrProcessor.extractFromPdf()` + `PdfOcrResult`

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/data/local/ReceiptOcrProcessor.kt`
- Create: `app/src/main/java/io/github/jiro/expensetracker/domain/receipt/PdfOcrResult.kt`

- [ ] **Step 1: Create `PdfOcrResult` data class**

Create `app/src/main/java/io/github/jiro/expensetracker/domain/receipt/PdfOcrResult.kt`:

```kotlin
package io.github.jiro.expensetracker.domain.receipt

/**
 * The merged OCR result for a PDF receipt, plus the page counts needed to
 * build the snackbar. `pagesScanned` is the number of pages actually OCR'd
 * (capped at [ReceiptOcrProcessor.MAX_PDF_PAGES]). `totalPages` is the PDF's
 * full page count (or 0 if the file is missing/corrupt).
 *
 * Pure data carrier — no Android types.
 */
data class PdfOcrResult(
    val fields: OcrFields,
    val pagesScanned: Int,
    val totalPages: Int,
)
```

- [ ] **Step 2: Add `extractFromPdf()` to `ReceiptOcrProcessor`**

Open `app/src/main/java/io/github/jiro/expensetracker/data/local/ReceiptOcrProcessor.kt` and replace its entire contents with:

```kotlin
package io.github.jiro.expensetracker.data.local

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import io.github.jiro.expensetracker.data.repository.ReceiptRepository
import io.github.jiro.expensetracker.domain.receipt.OcrFields
import io.github.jiro.expensetracker.domain.receipt.PdfOcrResult
import io.github.jiro.expensetracker.domain.receipt.ReceiptOcrMerger
import io.github.jiro.expensetracker.domain.receipt.ReceiptOcrParser
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Wraps ML Kit Text Recognition (Latin, on-device) and feeds the result
 * into [ReceiptOcrParser]. On-device, no API key, ~1s for a typical receipt.
 *
 * Supports both image receipts (via [extract]) and PDF receipts (via
 * [extractFromPdf]). PDF receipts are rasterized to bitmaps via
 * [ReceiptRepository.renderPdfPage], OCR'd page-by-page, then merged via
 * [ReceiptOcrMerger].
 */
@Singleton
class ReceiptOcrProcessor @Inject constructor(
    private val receiptRepository: ReceiptRepository,
) {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Run OCR on [bitmap]. Returns parsed fields; any field may be null if
     * the parser couldn't find a confident match. Throws on unrecoverable
     * ML Kit failure (caller catches and shows a snackbar).
     */
    suspend fun extract(bitmap: Bitmap): OcrFields = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                cont.resume(ReceiptOcrParser.parse(result.text))
            }
            .addOnFailureListener { e ->
                if (cont.isActive) cont.cancel(e)
            }
    }

    /**
     * Run OCR on up to [maxPages] pages of the PDF at [relativePath].
     * Returns a [PdfOcrResult] with the merged fields plus page counts.
     *
     * Best-effort: any per-page failure (corrupted page, ML Kit error, OOM)
     * is logged and skipped. If all pages fail, returns an empty result.
     * Never throws.
     */
    suspend fun extractFromPdf(
        relativePath: String,
        maxPages: Int = MAX_PDF_PAGES,
    ): PdfOcrResult = withContext(Dispatchers.IO) {
        val totalPages = try {
            receiptRepository.countPages(relativePath)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "countPages failed for $relativePath", e)
            0
        }
        if (totalPages == 0) {
            return@withContext PdfOcrResult(OcrFields(null, 0f, null, 0f, null, 0f), 0, 0)
        }

        val pageCount = minOf(totalPages, maxPages)
        val pages = mutableListOf<OcrFields>()
        for (i in 0 until pageCount) {
            currentCoroutineContext().ensureActive()
            val pageResult = runCatching {
                val bitmap = receiptRepository.renderPdfPage(relativePath, i)
                try {
                    extract(bitmap)
                } finally {
                    bitmap.recycle()
                }
            }.getOrElse { e ->
                if (e is CancellationException) throw e
                android.util.Log.w(TAG, "OCR failed for $relativePath page $i", e)
                null
            }
            if (pageResult != null) pages += pageResult
        }

        PdfOcrResult(
            fields = ReceiptOcrMerger.merge(pages),
            pagesScanned = pageCount,
            totalPages = totalPages,
        )
    }

    companion object {
        const val MAX_PDF_PAGES = 3
        private const val TAG = "ReceiptOcrProcessor"
    }
}
```

- [ ] **Step 3: Build to verify compile**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL. Verify by reading the file that `renderPdfPage` and `countPages` exist on `ReceiptRepository` (they do — see `app/src/main/java/io/github/jiro/expensetracker/data/repository/ReceiptRepository.kt`). If the signature of `renderPdfPage(relativePath, pageIndex): Bitmap` or `countPages(relativePath): Int` differs, adjust the call sites accordingly.

- [ ] **Step 4: Run full test suite**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL. The constructor signature change (`@Inject constructor()` → `@Inject constructor(receiptRepository: ReceiptRepository)`) is source-compatible for Hilt because `ReceiptRepository` is already a `@Singleton` in the graph. No other tests should break.

- [ ] **Step 5: Commit**

```bash
cd F:/AndroidApp/ExpenseTracker
git add app/src/main/java/io/github/jiro/expensetracker/data/local/ReceiptOcrProcessor.kt app/src/main/java/io/github/jiro/expensetracker/domain/receipt/PdfOcrResult.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "Receipts: add extractFromPdf to ReceiptOcrProcessor + PdfOcrResult"
```

---

## Task 4: VM PDF branch + state shape + strings + AppNav wiring

**Files:**
- Modify: `app/src/main/res/values/strings.xml` (add 4 PDF snackbar strings)
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/add_edit/AddEditTransactionViewModel.kt` (add `ReceiptKind` enum, `OcrSnackbarMeta` data class, extend state, refactor `runOcrAndAutoFill`, add PDF branch in `onReceiptAttached`)
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/add_edit/AddEditTransactionScreen.kt` (extend the `onOcrSnackbar` callback to accept the metadata; pass to AppNav)
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/navigation/AppNav.kt` (extend the callback signature; pick the right string based on metadata)

- [ ] **Step 1: Add 4 PDF snackbar strings to `strings.xml`**

Open `app/src/main/res/values/strings.xml`. Find the existing `<string name="receipt_ocr_snackbar">Receipt scanned. Fields filled.</string>` line (line 217). Immediately after it, add:

```xml
    <string name="receipt_pdf_scanned">PDF scanned. Fields filled.</string>
    <string name="receipt_pdf_scanned_partial">PDF scanned. Some fields filled.</string>
    <string name="receipt_pdf_scanned_capped">PDF scanned (%1$d of %2$d pages). Fields filled.</string>
    <string name="receipt_pdf_scanned_capped_partial">PDF scanned (%1$d of %2$d pages). Some fields filled.</string>
```

(Behavior decision: image receipts keep using the existing single `receipt_ocr_snackbar` string with no partial variant — Phase 2.14 only adds the PDF distinction.)

- [ ] **Step 2: Add `ReceiptKind` and `OcrSnackbarMeta` to `AddEditTransactionViewModel.kt`**

Open `app/src/main/java/io/github/jiro/expensetracker/ui/add_edit/AddEditTransactionViewModel.kt`. Find the `enum class FormError` declaration (line 34) and immediately after it, add:

```kotlin
/** Where a receipt's OCR text came from — drives the snackbar message. */
enum class ReceiptKind { IMAGE, PDF }

/** Bundles the data needed by the UI to pick the right OCR snackbar string. */
data class OcrSnackbarMeta(
    val kind: ReceiptKind,
    val pagesScanned: Int,
    val totalPages: Int,
    val isComplete: Boolean,
)
```

- [ ] **Step 3: Extend `AddEditTransactionUiState` with `lastOcrSnackbar`**

In the same file, find `val lastOcrFields: OcrFields? = null,` and add immediately after it:

```kotlin
    val lastOcrSnackbar: OcrSnackbarMeta? = null,
```

(The existing `lastOcrFields` field stays — `consumeOcrSnackbar()` clears both.)

- [ ] **Step 4: Update `consumeOcrSnackbar()` to clear both fields**

Find `consumeOcrSnackbar()` and replace it with:

```kotlin
    fun consumeOcrSnackbar() {
        _state.update { it.copy(lastOcrFields = null, lastOcrSnackbar = null) }
    }
```

- [ ] **Step 5: Refactor `runOcrAndAutoFill` to accept a pre-built `OcrFields`**

Find the existing `runOcrAndAutoFill(receiptPath: String)` function (around line 217) and replace it with a new variant that takes the parsed fields directly. The old function's body (decode bitmap, OCR, fill fields) is moved into two new private helpers — one for images, one for PDFs.

First, replace `runOcrAndAutoFill` with:

```kotlin
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
            lastOcrFields = ocr,
        )
        _state.update { s }
    }
```

The two private helpers that produce the `OcrFields` are introduced in the next step.

- [ ] **Step 6: Add `runImageOcr` helper**

Add this new private helper immediately after `runOcrAndAutoFill`:

```kotlin
    private suspend fun runImageOcr(receiptPath: String): OcrFields {
        val file = receiptRepository.absolutePath(receiptPath)
        if (!file.isFile) return OcrFields(null, 0f, null, 0f, null, 0f)
        return try {
            val bitmap = ImageProcessor.decodeSampledBitmap(file, maxEdge = 2048)
            try {
                receiptOcrProcessor.extract(bitmap)
            } finally {
                bitmap.recycle()
            }
        } catch (e: Exception) {
            // OCR failure isn't fatal; the receipt is still attached.
            OcrFields(null, 0f, null, 0f, null, 0f)
        }
    }
```

- [ ] **Step 7: Update `onReceiptAttached` to branch on file extension**

Find the `onReceiptAttached` function (around line 176). Replace its OCR-running portion (everything from the `// Run OCR only for image receipts...` comment to the end of the function) with:

```kotlin
            // Run OCR: image → extract(bitmap); PDF → extractFromPdf(path).
            if (newPath.endsWith(".jpg", ignoreCase = true) ||
                newPath.endsWith(".jpeg", ignoreCase = true) ||
                newPath.endsWith(".png", ignoreCase = true) ||
                newPath.endsWith(".webp", ignoreCase = true)
            ) {
                val ocr = runImageOcr(newPath)
                runOcrAndAutoFill(ocr)
                if (ocr.hasAny) {
                    _state.update {
                        it.copy(lastOcrSnackbar = OcrSnackbarMeta(
                            kind = ReceiptKind.IMAGE,
                            pagesScanned = 1,
                            totalPages = 1,
                            isComplete = ocr.isComplete,
                        ))
                    }
                }
            } else if (newPath.endsWith(".pdf", ignoreCase = true)) {
                val pdfResult = receiptOcrProcessor.extractFromPdf(newPath)
                runOcrAndAutoFill(pdfResult.fields)
                if (pdfResult.fields.hasAny) {
                    _state.update {
                        it.copy(lastOcrSnackbar = OcrSnackbarMeta(
                            kind = ReceiptKind.PDF,
                            pagesScanned = pdfResult.pagesScanned,
                            totalPages = pdfResult.totalPages,
                            isComplete = pdfResult.fields.isComplete,
                        ))
                    }
                }
            }
```

- [ ] **Step 8: Build to verify compile**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL for the VM. The screen + AppNav still use the old callback signature (next step).

- [ ] **Step 9: Update `AddEditTransactionScreen` to pass the metadata to the callback**

Open `app/src/main/java/io/github/jiro/expensetracker/ui/add_edit/AddEditTransactionScreen.kt`. Find:

```kotlin
    onOcrSnackbar: () -> Unit = {},
```

Replace with:

```kotlin
    onOcrSnackbar: (ReceiptKind, Int, Int, Boolean) -> Unit = { _, _, _, _ -> },
```

Then find:

```kotlin
    LaunchedEffect(state.lastOcrFields) {
        if (state.lastOcrFields != null) {
            onOcrSnackbar()
            viewModel.consumeOcrSnackbar()
        }
    }
```

Replace with:

```kotlin
    LaunchedEffect(state.lastOcrSnackbar) {
        val meta = state.lastOcrSnackbar ?: return@LaunchedEffect
        onOcrSnackbar(meta.kind, meta.pagesScanned, meta.totalPages, meta.isComplete)
        viewModel.consumeOcrSnackbar()
    }
```

Add the import at the top of the file (alongside the existing `io.github.jiro.expensetracker.ui.add_edit.*` imports — `ReceiptKind` is in the same package, so no import is actually needed; skip if not required).

- [ ] **Step 10: Update `AppNav.kt` to pick the right snackbar string**

Open `app/src/main/java/io/github/jiro/expensetracker/ui/navigation/AppNav.kt`. Find the `composable(Routes.ADD_EDIT)` block (around line 115) and replace the `onOcrSnackbar` lambda:

```kotlin
                    onOcrSnackbar = {
                        snackbarScope.launch {
                            snackbarHostState.showSnackbar(ocrSnackbarMessage)
                        }
                    },
```

with:

```kotlin
                    onOcrSnackbar = { kind, pagesScanned, totalPages, isComplete ->
                        snackbarScope.launch {
                            val message = when (kind) {
                                ReceiptKind.IMAGE -> ocrSnackbarMessage
                                ReceiptKind.PDF -> if (pagesScanned >= totalPages) {
                                    if (isComplete)
                                        context.getString(R.string.receipt_pdf_scanned)
                                    else
                                        context.getString(R.string.receipt_pdf_scanned_partial)
                                } else {
                                    if (isComplete)
                                        context.getString(R.string.receipt_pdf_scanned_capped, pagesScanned, totalPages)
                                    else
                                        context.getString(R.string.receipt_pdf_scanned_capped_partial, pagesScanned, totalPages)
                                }
                            }
                            snackbarHostState.showSnackbar(message)
                        }
                    },
```

You'll need:
- A `val context = LocalContext.current` declared somewhere in the `NavGraph` composable (above the `NavHost`). If it's not already there, add it alongside the existing `val snackbarScope = rememberCoroutineScope()` line.
- An import: `import androidx.compose.ui.platform.LocalContext`
- An import: `import io.github.jiro.expensetracker.ui.add_edit.ReceiptKind`

- [ ] **Step 11: Build to verify compile**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 12: Run full test suite**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL. All tests pass (no test changes in this task).

- [ ] **Step 13: Commit**

```bash
cd F:/AndroidApp/ExpenseTracker
git add app/src/main/res/values/strings.xml app/src/main/java/io/github/jiro/expensetracker/ui/add_edit/AddEditTransactionViewModel.kt app/src/main/java/io/github/jiro/expensetracker/ui/add_edit/AddEditTransactionScreen.kt app/src/main/java/io/github/jiro/expensetracker/ui/navigation/AppNav.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "Receipts: VM PDF OCR branch + distinct PDF snackbar + state wiring"
```

---

## Task 5: Manual smoke + assembleDebug + tag v0.13.0 + push

**Files:** (none modified; this task is verification + ship)

- [ ] **Step 1: Build the debug APK**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run the full test suite**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, all tests pass. The total should be: previous count + 9 new parser confidence tests + 6 new merger tests.

- [ ] **Step 3: Manual smoke check (on device or emulator)**

Per the project's existing manual smoke protocol:

1. Install the debug APK on a connected device/emulator (`./gradlew installDebug`).
2. Open the app → Add transaction → tap "Choose file" → pick a 1-page PDF receipt (digitally generated, text-extractable).
3. Confirm: receipt thumbnail shows with the PDF badge, fields auto-fill (amount, merchant, date where present), snackbar reads "PDF scanned. Fields filled."
4. Pick a 3-page itemized PDF. Confirm: snackbar reads "PDF scanned. Fields filled." and merger picks the highest-confidence field per slot (verify by deleting data so the same field appears on multiple pages with different confidences).
5. Pick a 10-page statement PDF. Confirm: snackbar reads "PDF scanned (3 of 10 pages). Fields filled." (or partial).
6. Pick a corrupted .pdf (truncate a real one to ~100 bytes). Confirm: no crash, no snackbar, receipt thumbnail shows missing-error.
7. Pick a 1-page image (regression check). Confirm: existing "Receipt scanned. Fields filled." snackbar still works — image path is unchanged.
8. Image OCR — pick an image where only the amount is parseable (e.g., a receipt with no clear date or merchant). Confirm: existing single-string snackbar still fires (no partial variant for images in this phase).

- [ ] **Step 4: Tag v0.13.0 and push to master**

```bash
cd F:/AndroidApp/ExpenseTracker
git tag v0.13.0
git push origin master --tags
```

Expected: tag created, push succeeds.

---

## Self-review notes

**Spec coverage:**
- Confidence Float per field: Task 1 ✓
- 9 confidence tests: Task 1 ✓
- `ReceiptOcrMerger` pure helper: Task 2 ✓
- 6 merger tests: Task 2 ✓
- `extractFromPdf` orchestration: Task 3 ✓
- `PdfOcrResult` data class: Task 3 ✓
- 3-page cap with "scanned M of N" snackbar: Task 4 (string + AppNav wiring) ✓
- 4 PDF snackbar strings: Task 4 ✓
- VM branching + state shape: Task 4 ✓
- Pristine-field check unchanged: Task 4 (runOcrAndAutoFill body preserved) ✓
- Silent failure mode: Task 3 (extractFromPdf uses runCatching + Log.w) ✓
- Bitmap recycling per page: Task 3 (finally block) ✓
- Cancellation between pages: Task 3 (ensureActive in loop) ✓
- Manual smoke protocol: Task 5 ✓
- v0.13.0 tag + push: Task 5 ✓

**Placeholder scan:** None. Code blocks are complete and runnable.

**Type consistency:**
- `OcrFields` shape defined in Task 1, used unchanged in Tasks 2/3/4.
- `ReceiptOcrMerger.merge(pages: List<OcrFields>): OcrFields` signature consistent across Tasks 2/3.
- `PdfOcrResult(fields, pagesScanned, totalPages)` defined in Task 3, consumed in Task 4.
- `ReceiptKind` enum defined in Task 4, consumed in Task 4 only.
- `OcrSnackbarMeta` defined in Task 4, consumed in Task 4 only.
- `onOcrSnackbar` callback signature `(ReceiptKind, Int, Int, Boolean) -> Unit` consistent across screen + AppNav.
- `receipt_pdf_scanned` / `_partial` / `_capped` / `_capped_partial` strings referenced consistently.

**No new abstractions** beyond the three types. Reuses `ReceiptRepository.renderPdfPage`/`countPages`, `ReceiptOcrProcessor.extract`, `ReceiptOcrParser.parse`, `ReceiptOcrMerger.merge`.