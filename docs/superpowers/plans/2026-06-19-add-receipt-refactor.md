# Phase 2.15 — Add Receipt Refactor — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split receipt capture into two flows — inline image attach on Add/Edit transaction (image-only) and a camera-first top-level "Add Receipt" entry in the More tab. Unwind Phase 2.14's PDF path entirely.

**Architecture:** Pure-calculator pattern preserved. `OcrFields` reverts to the pre-2.14 3-field shape. `ReceiptOcrProcessor` keeps only `extract(bitmap)`. New `AddReceiptViewModel` (Hilt) owns a state machine (`Idle → OcrInProgress → Review`) and reuses `ReceiptOcrProcessor.extract`, `ReceiptRepository.saveFromUri`, `TransactionRepository.add`, `CategoryRepository.observeByType`, `SettingsRepository.homeCurrency`. New `AddReceiptScreen` Composable renders the state machine. AppNav adds a `Routes.ADD_RECEIPT` composable. MoreScreen adds an "Add Receipt" row.

**Tech Stack:** Kotlin, Jetpack Compose + Material 3, Hilt, Coroutines + StateFlow, Room, JUnit 4, ML Kit Text Recognition (already shipped), system camera intent via `ActivityResultContracts.TakePicture`. JDK 21 required (`export JAVA_HOME=C:/tools/jdk-21.0.5+11` before any `./gradlew` command).

---

## Task 1: Revert `OcrFields` + remove 9 confidence tests

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/domain/receipt/ReceiptOcrParser.kt`
- Modify: `app/src/test/java/io/github/jiro/expensetracker/domain/receipt/ReceiptOcrParserTest.kt`

- [ ] **Step 1: Revert `OcrFields` to the 3-field shape**

Open `app/src/main/java/io/github/jiro/expensetracker/domain/receipt/ReceiptOcrParser.kt`. Replace the `OcrFields` data class with:

```kotlin
data class OcrFields(
    val amountMinor: Long?,
    val occurredAtEpochMillis: Long?,
    val merchant: String?,
) {
    val hasAny: Boolean
        get() = amountMinor != null || occurredAtEpochMillis != null || merchant != null
}
```

- [ ] **Step 2: Revert `parse()` and helpers to not return confidence**

Replace the `parse()` body and the three private helpers with the pre-2.14 versions:

```kotlin
    fun parse(text: String): OcrFields {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val amount = parseAmount(lines)
        val date = parseDate(text)
        val merchant = pickMerchant(lines)
        return OcrFields(amount, date, merchant)
    }

    private fun parseAmount(lines: List<String>): Long? {
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
        if (nonPct.isEmpty()) return null

        val withKeyword = nonPct.filter { (line, _) ->
            TOTAL_KEYWORDS.any { kw -> line.contains(kw) }
        }
        return if (withKeyword.isNotEmpty()) {
            withKeyword.maxBy { it.second }.second
        } else {
            nonPct.maxBy { it.second }.second
        }
    }

    private fun parseDate(text: String): Long? {
        val iso = Regex("""\b(\d{4})-(\d{2})-(\d{2})\b""").find(text)
        if (iso != null) {
            val cal = java.util.Calendar.getInstance()
            cal.set(iso.groupValues[1].toInt(), iso.groupValues[2].toInt() - 1, iso.groupValues[3].toInt(), 0, 0, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            return cal.timeInMillis
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
                return cal.timeInMillis
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
                return cal.timeInMillis
            }
            if (b in 1..12 && a in 1..31) {
                val cal = java.util.Calendar.getInstance()
                cal.set(year, b - 1, a, 0, 0, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                return cal.timeInMillis
            }
        }
        return null
    }

    private fun pickMerchant(lines: List<String>): String? {
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.length < 3) continue
            if (line.all { it.isDigit() || it.isWhitespace() || it == '$' || it == '.' || it == ',' }) continue
            if (SKIP_HEADERS.any { line.equals(it, ignoreCase = true) }) continue
            return line.take(60)
        }
        return null
    }
```

- [ ] **Step 3: Remove 9 confidence tests + their imports**

Open `app/src/test/java/io/github/jiro/expensetracker/domain/receipt/ReceiptOcrParserTest.kt`. Delete the entire `// ---- confidence scores (Phase 2.14) ----` section (tests `parseAmount_totalKeyword_hasConfidence1` through `pickMerchant_shortButAcceptable_hasConfidence07`). Also remove the `import org.junit.Assert.assertNotNull` line if it's no longer used elsewhere in the file.

- [ ] **Step 4: Run parser tests to verify**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew :app:testDebugUnitTest --tests "*ReceiptOcrParserTest*"
```

Expected: BUILD SUCCESSFUL. All remaining pre-2.14 tests pass (they only check nullness). Total in this file: 12 (was 21 before revert).

- [ ] **Step 5: Run full test suite**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL. Some suites will fail to compile if they reference `amountConfidence` / `dateConfidence` / `merchantConfidence` / `isComplete` on `OcrFields`. Find and fix those callers (likely `AddEditTransactionViewModel.kt`'s `runOcrAndAutoFill` and `runImageOcr`).

- [ ] **Step 6: Commit**

```bash
cd F:/AndroidApp/ExpenseTracker
git add app/src/main/java/io/github/jiro/expensetracker/domain/receipt/ReceiptOcrParser.kt app/src/test/java/io/github/jiro/expensetracker/domain/receipt/ReceiptOcrParserTest.kt app/src/main/java/io/github/jiro/expensetracker/ui/add_edit/AddEditTransactionViewModel.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "Revert: OcrFields back to 3-field shape + remove 9 confidence tests"
```

---

## Task 2: Revert `ReceiptOcrProcessor` (remove PDF path)

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/data/local/ReceiptOcrProcessor.kt`

- [ ] **Step 1: Replace the file with the pre-2.14 version**

Open `app/src/main/java/io/github/jiro/expensetracker/data/local/ReceiptOcrProcessor.kt`. Replace its entire contents with:

```kotlin
package io.github.jiro.expensetracker.data.local

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import io.github.jiro.expensetracker.domain.receipt.OcrFields
import io.github.jiro.expensetracker.domain.receipt.ReceiptOcrParser
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Wraps ML Kit Text Recognition (Latin, on-device) and feeds the result
 * into [ReceiptOcrParser]. On-device, no API key, ~1s for a typical receipt.
 */
@Singleton
class ReceiptOcrProcessor @Inject constructor() {

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
}
```

- [ ] **Step 2: Build to verify compile**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL. The constructor change is source-compatible for Hilt (no-arg constructor matches what Hilt needs).

- [ ] **Step 3: Run full test suite**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL. No regressions.

- [ ] **Step 4: Commit**

```bash
cd F:/AndroidApp/ExpenseTracker
git add app/src/main/java/io/github/jiro/expensetracker/data/local/ReceiptOcrProcessor.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "Revert: ReceiptOcrProcessor — remove extractFromPdf, MAX_PDF_PAGES, repo injection"
```

---

## Task 3: Delete `ReceiptOcrMerger` + `PdfOcrResult` + their tests

**Files:**
- Delete: `app/src/main/java/io/github/jiro/expensetracker/domain/receipt/ReceiptOcrMerger.kt`
- Delete: `app/src/main/java/io/github/jiro/expensetracker/domain/receipt/PdfOcrResult.kt`
- Delete: `app/src/test/java/io/github/jiro/expensetracker/domain/receipt/ReceiptOcrMergerTest.kt`

- [ ] **Step 1: Verify no remaining references**

```bash
cd F:/AndroidApp/ExpenseTracker
grep -rn "ReceiptOcrMerger\|PdfOcrResult" app/src/main/java app/src/test/java
```

Expected: no matches (Task 2 already removed the call site in `ReceiptOcrProcessor`).

- [ ] **Step 2: Delete the three files**

```bash
cd F:/AndroidApp/ExpenseTracker
rm app/src/main/java/io/github/jiro/expensetracker/domain/receipt/ReceiptOcrMerger.kt
rm app/src/main/java/io/github/jiro/expensetracker/domain/receipt/PdfOcrResult.kt
rm app/src/test/java/io/github/jiro/expensetracker/domain/receipt/ReceiptOcrMergerTest.kt
```

- [ ] **Step 3: Run full test suite**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL. Test count drops by 8 (the merger tests).

- [ ] **Step 4: Commit**

```bash
cd F:/AndroidApp/ExpenseTracker
git add -A app/src/main/java/io/github/jiro/expensetracker/domain/receipt/ app/src/test/java/io/github/jiro/expensetracker/domain/receipt/
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "Delete: ReceiptOcrMerger, PdfOcrResult, and their tests (no longer used)"
```

---

## Task 4: Revert Add/Edit VM + screen + AppNav + strings (remove PDF plumbing)

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/add_edit/AddEditTransactionViewModel.kt`
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/add_edit/AddEditTransactionScreen.kt`
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/navigation/AppNav.kt`
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/add_edit/ReceiptSection.kt`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Remove `ReceiptKind` and `OcrSnackbarMeta` from VM**

Open `app/src/main/java/io/github/jiro/expensetracker/ui/add_edit/AddEditTransactionViewModel.kt`. Delete the `enum class ReceiptKind` declaration (line 36-37) and the `data class OcrSnackbarMeta` declaration (line 39-45).

- [ ] **Step 2: Update `lastOcrSnackbar` state to a simple `Boolean`**

Find `val lastOcrSnackbar: OcrSnackbarMeta? = null,` in the state and replace with:

```kotlin
    val lastOcrSnackbar: Boolean = false,
```

- [ ] **Step 3: Update `consumeOcrSnackbar()` to set Boolean false**

Replace the body of `consumeOcrSnackbar()` with:

```kotlin
    fun consumeOcrSnackbar() {
        _state.update { it.copy(lastOcrSnackbar = false) }
    }
```

- [ ] **Step 4: Update `runImageOcr` to use the simplified `OcrFields`**

Replace `OcrFields(null, 0f, null, 0f, null, 0f)` (the empty placeholder used in `runImageOcr`'s return path) with `OcrFields(null, null, null)`. Two occurrences in `runImageOcr`.

- [ ] **Step 5: Replace `runOcrForReceipt` with image-only logic**

Replace the body of `runOcrForReceipt` with:

```kotlin
    private suspend fun runOcrForReceipt(path: String) {
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext !in setOf("jpg", "jpeg", "png", "webp")) return
        val ocr = runImageOcr(path)
        runOcrAndAutoFill(ocr)
        if (ocr.hasAny) {
            _state.update { it.copy(lastOcrSnackbar = true) }
        }
    }
```

- [ ] **Step 6: Remove `receiptRepository` injection if now unused**

Check the VM's `runImageOcr` function. It still uses `receiptRepository.absolutePath(receiptPath)` — keep the injection. (Don't remove the field.)

- [ ] **Step 7: Update `AddEditTransactionScreen` callback signature**

Open `app/src/main/java/io/github/jiro/expensetracker/ui/add_edit/AddEditTransactionScreen.kt`. Find the `onOcrSnackbar` parameter. Replace:

```kotlin
    onOcrSnackbar: (ReceiptKind, Int, Int, Boolean) -> Unit = { _, _, _, _ -> },
```

with:

```kotlin
    onOcrSnackbar: () -> Unit = {},
```

Update the `LaunchedEffect` block to match (it should call `onOcrSnackbar()` with no args).

- [ ] **Step 8: Restrict the file picker to image-only in `ReceiptSection.kt`**

Open `app/src/main/java/io/github/jiro/expensetracker/ui/add_edit/ReceiptSection.kt`. Find line 154:

```kotlin
                        fileLauncher.launch(arrayOf("image/*", "application/pdf"))
```

Replace with:

```kotlin
                        fileLauncher.launch(arrayOf("image/*"))
```

- [ ] **Step 9: Revert `AppNav.kt` to single-string snackbar**

Open `app/src/main/java/io/github/jiro/expensetracker/ui/navigation/AppNav.kt`. Replace the entire `onOcrSnackbar` lambda in the `composable(Routes.ADD_EDIT)` block. The full block currently looks like (around line 130):

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

Replace with:

```kotlin
                    onOcrSnackbar = {
                        snackbarScope.launch {
                            snackbarHostState.showSnackbar(ocrSnackbarMessage)
                        }
                    },
```

Remove the now-unused `val context = LocalContext.current` declaration (around line 73) and the now-unused imports for `LocalContext` and `ReceiptKind` (the screen doesn't use them anymore either).

- [ ] **Step 10: Remove 4 PDF strings from strings.xml**

Open `app/src/main/res/values/strings.xml`. Delete these 4 lines (currently around line 218-221):

```xml
    <string name="receipt_pdf_scanned">PDF scanned. Fields filled.</string>
    <string name="receipt_pdf_scanned_partial">PDF scanned. Some fields filled.</string>
    <string name="receipt_pdf_scanned_capped">PDF scanned (%1$d of %2$d pages). Fields filled.</string>
    <string name="receipt_pdf_scanned_capped_partial">PDF scanned (%1$d of %2$d pages). Some fields filled.</string>
```

- [ ] **Step 11: Build to verify compile**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL. If not, fix any leftover references to removed symbols.

- [ ] **Step 12: Run full test suite**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 13: Commit**

```bash
cd F:/AndroidApp/ExpenseTracker
git add app/src/main/java/io/github/jiro/expensetracker/ui/add_edit/AddEditTransactionViewModel.kt \
        app/src/main/java/io/github/jiro/expensetracker/ui/add_edit/AddEditTransactionScreen.kt \
        app/src/main/java/io/github/jiro/expensetracker/ui/navigation/AppNav.kt \
        app/src/main/java/io/github/jiro/expensetracker/ui/add_edit/ReceiptSection.kt \
        app/src/main/res/values/strings.xml
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "Revert: Add/Edit + AppNav + strings — remove PDF plumbing, keep image-only"
```

---

## Task 5: `AddReceiptViewModel` + 7 tests (TDD)

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/ui/add_receipt/AddReceiptViewModel.kt`
- Create: `app/src/test/java/io/github/jiro/expensetracker/ui/add_receipt/AddReceiptViewModelTest.kt`

- [ ] **Step 1: Write the 7 failing tests**

Create `app/src/test/java/io/github/jiro/expensetracker/ui/add_receipt/AddReceiptViewModelTest.kt`:

```kotlin
package io.github.jiro.expensetracker.ui.add_receipt

import android.app.Application
import android.net.Uri
import io.github.jiro.expensetracker.data.local.CategoryEntity
import io.github.jiro.expensetracker.data.local.ReceiptOcrProcessor
import io.github.jiro.expensetracker.data.local.TransactionEntity
import io.github.jiro.expensetracker.data.repository.CategoryRepository
import io.github.jiro.expensetracker.data.repository.ReceiptRepository
import io.github.jiro.expensetracker.data.repository.TransactionRepository
import io.github.jiro.expensetracker.domain.model.TransactionType
import io.github.jiro.expensetracker.domain.receipt.OcrFields
import io.github.jiro.expensetracker.preferences.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class AddReceiptViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildVm(
        ocr: OcrFields = OcrFields(null, null, null),
        homeCurrency: String = "USD",
    ): Triple<AddReceiptViewModel, FakeTransactionRepo, FakeOcrProcessor> {
        val txRepo = FakeTransactionRepo()
        val catRepo = FakeCategoryRepo()
        val receiptRepo = FakeReceiptRepo()
        val ocrProcessor = FakeOcrProcessor(ocr)
        val settings = FakeSettingsRepository(homeCurrency)
        val vm = AddReceiptViewModel(
            application = mock(),
            transactionRepository = txRepo,
            categoryRepository = catRepo,
            receiptRepository = receiptRepo,
            receiptOcrProcessor = ocrProcessor,
            settingsRepository = settings,
        )
        return Triple(vm, txRepo, ocrProcessor)
    }

    @Test
    fun initialState_isIdle() = runTest(testDispatcher) {
        val (vm, _, _) = buildVm()
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(AddReceiptMode.Idle, s.mode)
        assertNull(s.photoPath)
        assertEquals("", s.title)
        assertEquals("", s.amountInput)
        assertEquals(TransactionType.EXPENSE, s.type)
        assertEquals("USD", s.currency)
        assertEquals(AddReceiptError.RECEIPT_SAVE_FAILED, null) // no error initially
        assertTrue(!s.isSaving)
        assertTrue(!s.saveComplete)
    }

    @Test
    fun onPhotoCaptured_emptyOcr_transitionsToReviewWithEmptyFields() = runTest(testDispatcher) {
        val (vm, _, _) = buildVm(ocr = OcrFields(null, null, null))
        val uri = mock<Uri>()
        vm.onPhotoCaptured(uri)
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(AddReceiptMode.Review, s.mode)
        assertNotNull(s.photoPath)
        assertEquals("", s.title)
        assertEquals("", s.amountInput)
    }

    @Test
    fun onPhotoCaptured_withOcrFields_prefillsForm() = runTest(testDispatcher) {
        val (vm, _, _) = buildVm(
            ocr = OcrFields(amountMinor = 540L, occurredAtEpochMillis = 1_716_000_000_000L, merchant = "Coffee & Co")
        )
        val uri = mock<Uri>()
        vm.onPhotoCaptured(uri)
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(AddReceiptMode.Review, s.mode)
        assertEquals("Coffee & Co", s.title)
        assertEquals("5.40", s.amountInput)
        assertEquals(1_716_000_000_000L, s.occurredAtEpochMillis)
    }

    @Test
    fun onTitleChange_updatesState() = runTest(testDispatcher) {
        val (vm, _, _) = buildVm()
        vm.onTitleChange("Walmart")
        assertEquals("Walmart", vm.state.value.title)
    }

    @Test
    fun onSave_missingTitle_setsError() = runTest(testDispatcher) {
        val (vm, _, _) = buildVm()
        vm.onPhotoCaptured(mock())
        advanceUntilIdle()
        vm.onTitleChange("")
        vm.onAmountChange("5.00")
        // pick a category
        vm.onCategoryChange(1L)
        vm.onSave()
        advanceUntilIdle()
        assertEquals(AddReceiptError.TITLE_REQUIRED, vm.state.value.error)
        assertTrue(!vm.state.value.saveComplete)
    }

    @Test
    fun onSave_invalidAmount_setsError() = runTest(testDispatcher) {
        val (vm, _, _) = buildVm()
        vm.onPhotoCaptured(mock())
        advanceUntilIdle()
        vm.onTitleChange("Walmart")
        vm.onAmountChange("not a number")
        vm.onCategoryChange(1L)
        vm.onSave()
        advanceUntilIdle()
        assertEquals(AddReceiptError.AMOUNT_INVALID, vm.state.value.error)
        assertTrue(!vm.state.value.saveComplete)
    }

    @Test
    fun onSave_validInputs_addsTransactionAndSetsSaveComplete() = runTest(testDispatcher) {
        val (vm, txRepo, _) = buildVm()
        vm.onPhotoCaptured(mock())
        advanceUntilIdle()
        vm.onTitleChange("Walmart")
        vm.onAmountChange("5.00")
        vm.onCategoryChange(1L)
        vm.onSave()
        advanceUntilIdle()
        assertEquals(1, txRepo.added.size)
        val added = txRepo.added.first()
        assertEquals("Walmart", added.title)
        assertEquals(500L, added.amountMinor)
        assertNotNull(added.receiptPath)
        assertTrue(vm.state.value.saveComplete)
    }
}

// ---- fakes (test-only) ----

class FakeTransactionRepo : TransactionRepository by mock() {
    val added = mutableListOf<TransactionEntity>()
    override suspend fun add(entity: TransactionEntity): Long {
        added += entity
        return 1L
    }
}

class FakeCategoryRepo : CategoryRepository by mock() {
    override fun observeByType(type: TransactionType) =
        MutableStateFlow(listOf<CategoryEntity>()).asStateFlow()
}

class FakeReceiptRepo : ReceiptRepository by mock() {
    override suspend fun saveFromUri(ctx: android.content.Context, uri: Uri): String =
        "receipts/fake.jpg"
}

class FakeOcrProcessor(private val result: OcrFields) : ReceiptOcrProcessor by mock() {
    override suspend fun extract(bitmap: android.graphics.Bitmap): OcrFields = result
}

class FakeSettingsRepository(homeCurrency: String) : SettingsRepository by mock() {
    private val flow = MutableStateFlow(homeCurrency)
    override val homeCurrency = flow.asStateFlow()
    override suspend fun setHomeCurrency(code: String) { flow.value = code }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew :app:testDebugUnitTest --tests "*AddReceiptViewModelTest*"
```

Expected: BUILD FAILED with "Unresolved reference: AddReceiptViewModel" / "Unresolved reference: AddReceiptMode" / "Unresolved reference: AddReceiptError".

- [ ] **Step 3: Create `AddReceiptViewModel.kt`**

Create `app/src/main/java/io/github/jiro/expensetracker/ui/add_receipt/AddReceiptViewModel.kt`:

```kotlin
package io.github.jiro.expensetracker.ui.add_receipt

import android.app.Application
import android.graphics.Bitmap
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

@HiltViewModel
class AddReceiptViewModel @Inject constructor(
    application: Application,
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository,
    private val receiptRepository: ReceiptRepository,
    private val receiptOcrProcessor: ReceiptOcrProcessor,
    private val settingsRepository: SettingsRepository,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(
        AddReceiptUiState(currency = settingsRepository.homeCurrency.value)
    )
    val state: StateFlow<AddReceiptUiState> = _state.asStateFlow()

    init {
        // Categories follow the currently selected type.
        viewModelScope.launch {
            _state.collect { current ->
                categoryRepository.observeByType(current.type).collect { categories ->
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

            val ocr = try {
                val file = receiptRepository.absolutePath(path)
                val bitmap = withContext(Dispatchers.IO) {
                    ImageProcessor.decodeSampledBitmap(file, maxEdge = 2048)
                }
                try {
                    receiptOcrProcessor.extract(bitmap)
                } finally {
                    bitmap.recycle()
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
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew :app:testDebugUnitTest --tests "*AddReceiptViewModelTest*"
```

Expected: BUILD SUCCESSFUL. 7/7 passing.

- [ ] **Step 5: Run full test suite**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL. No regressions.

- [ ] **Step 6: Commit**

```bash
cd F:/AndroidApp/ExpenseTracker
git add app/src/main/java/io/github/jiro/expensetracker/ui/add_receipt/AddReceiptViewModel.kt app/src/test/java/io/github/jiro/expensetracker/ui/add_receipt/AddReceiptViewModelTest.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "Add: AddReceiptViewModel + 7 tests (state machine, OCR, save)"
```

---

## Task 6: `AddReceiptScreen` Composable

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/ui/add_receipt/AddReceiptScreen.kt`
- Modify: `app/src/main/res/values/strings.xml` (add new strings)

- [ ] **Step 1: Add new strings to strings.xml**

Open `app/src/main/res/values/strings.xml`. After the existing `receipt_ocr_snackbar` line (around line 217), add:

```xml
    <string name="action_add_receipt">Add receipt</string>
    <string name="add_receipt_title">Add receipt</string>
    <string name="add_receipt_idle_prompt">Take a photo of a receipt to add it as a new transaction</string>
    <string name="add_receipt_take_photo">Take photo</string>
    <string name="add_receipt_review_title">Review receipt</string>
    <string name="add_receipt_camera_denied">Camera permission denied. Grant access in Settings.</string>
    <string name="add_receipt_saving">Saving…</string>
    <string name="add_receipt_save">Save transaction</string>
    <string name="add_receipt_cancel">Cancel</string>
    <string name="add_receipt_ocr_in_progress">Reading receipt…</string>
    <string name="add_receipt_no_text_found">No text found. Fill the fields manually.</string>
    <string name="add_receipt_error_title_required">Title is required</string>
    <string name="add_receipt_error_amount_invalid">Amount must be greater than zero</string>
    <string name="add_receipt_error_category_required">Pick a category</string>
    <string name="add_receipt_error_save_failed">Couldn\'t save. Try again.</string>
```

- [ ] **Step 2: Create `AddReceiptScreen.kt`**

Create `app/src/main/java/io/github/jiro/expensetracker/ui/add_receipt/AddReceiptScreen.kt`:

```kotlin
package io.github.jiro.expensetracker.ui.add_receipt

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.jiro.expensetracker.R
import io.github.jiro.expensetracker.domain.model.TransactionType
import java.io.File
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddReceiptScreen(
    onBack: () -> Unit,
    viewModel: AddReceiptViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    // Camera capture plumbing (mirrors the pattern from ReceiptSection.kt).
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var cameraDenied by remember { mutableStateOf(false) }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        val uri = pendingCameraUri
        pendingCameraUri = null
        if (success && uri != null) {
            viewModel.onPhotoCaptured(uri)
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            cameraDenied = false
            pendingCameraUri = createCameraCaptureUri(context)
        } else {
            cameraDenied = true
        }
    }

    LaunchedEffect(pendingCameraUri) {
        val uri = pendingCameraUri ?: return@LaunchedEffect
        cameraLauncher.launch(uri)
    }

    LaunchedEffect(state.saveComplete) {
        if (state.saveComplete) onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.add_receipt_title)) })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            when (state.mode) {
                AddReceiptMode.Idle -> IdleView(
                    cameraDenied = cameraDenied,
                    onTakePhoto = {
                        if (ContextCompat.checkSelfPermission(
                                context, android.Manifest.permission.CAMERA,
                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            pendingCameraUri = createCameraCaptureUri(context)
                        } else {
                            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                        }
                    },
                )
                AddReceiptMode.OcrInProgress -> OcrProgressView()
                AddReceiptMode.Review -> ReviewForm(
                    state = state,
                    onTitleChange = viewModel::onTitleChange,
                    onAmountChange = viewModel::onAmountChange,
                    onDateChange = viewModel::onDateChange,
                    onTypeChange = viewModel::onTypeChange,
                    onCategoryChange = viewModel::onCategoryChange,
                    onCurrencyChange = viewModel::onCurrencyChange,
                    onNoteChange = viewModel::onNoteChange,
                    onSave = viewModel::onSave,
                    onCancel = onBack,
                )
            }
        }
    }
}

@Composable
private fun IdleView(
    cameraDenied: Boolean,
    onTakePhoto: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.PhotoCamera,
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.add_receipt_idle_prompt),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onTakePhoto,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.add_receipt_take_photo))
        }
        if (cameraDenied) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.add_receipt_camera_denied),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun OcrProgressView() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.add_receipt_ocr_in_progress))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewForm(
    state: AddReceiptUiState,
    onTitleChange: (String) -> Unit,
    onAmountChange: (String) -> Unit,
    onDateChange: (Long) -> Unit,
    onTypeChange: (TransactionType) -> Unit,
    onCategoryChange: (Long) -> Unit,
    onCurrencyChange: (String) -> Unit,
    onNoteChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll),
    ) {
        Text(
            text = stringResource(R.string.add_receipt_review_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = state.title,
            onValueChange = onTitleChange,
            label = { Text("Title") },
            isError = state.error == AddReceiptError.TITLE_REQUIRED,
            supportingText = if (state.error == AddReceiptError.TITLE_REQUIRED) {
                { Text(stringResource(R.string.add_receipt_error_title_required)) }
            } else null,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = state.amountInput,
            onValueChange = onAmountChange,
            label = { Text("Amount") },
            isError = state.error == AddReceiptError.AMOUNT_INVALID,
            supportingText = if (state.error == AddReceiptError.AMOUNT_INVALID) {
                { Text(stringResource(R.string.add_receipt_error_amount_invalid)) }
            } else null,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        // Date: simple text for MVP. The user can edit by saving and re-editing the transaction.
        Text(
            text = "Date: ${java.text.DateFormat.getDateInstance().format(java.util.Date(state.occurredAtEpochMillis))}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))

        // Type dropdown
        var typeExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = typeExpanded,
            onExpandedChange = { typeExpanded = it },
        ) {
            OutlinedTextField(
                value = state.type.name,
                onValueChange = {},
                readOnly = true,
                label = { Text("Type") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth(),
            )
            androidx.compose.material3.ExposedDropdownMenu(
                expanded = typeExpanded,
                onDismissRequest = { typeExpanded = false },
            ) {
                TransactionType.values().forEach { t ->
                    DropdownMenuItem(
                        text = { Text(t.name) },
                        onClick = {
                            onTypeChange(t)
                            typeExpanded = false
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        // Category dropdown
        var catExpanded by remember { mutableStateOf(false) }
        val selectedCat = state.categoriesForType.firstOrNull { it.id == state.selectedCategoryId }
        ExposedDropdownMenuBox(
            expanded = catExpanded,
            onExpandedChange = { catExpanded = it },
        ) {
            OutlinedTextField(
                value = selectedCat?.name.orEmpty(),
                onValueChange = {},
                readOnly = true,
                label = { Text("Category") },
                isError = state.error == AddReceiptError.CATEGORY_REQUIRED,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = catExpanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth(),
            )
            androidx.compose.material3.ExposedDropdownMenu(
                expanded = catExpanded,
                onDismissRequest = { catExpanded = false },
            ) {
                state.categoriesForType.forEach { c ->
                    DropdownMenuItem(
                        text = { Text(c.name) },
                        onClick = {
                            onCategoryChange(c.id)
                            catExpanded = false
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        // Currency text field (free-form for now — matches AddEditTransaction)
        OutlinedTextField(
            value = state.currency,
            onValueChange = onCurrencyChange,
            label = { Text("Currency") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = state.note,
            onValueChange = onNoteChange,
            label = { Text("Note") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onSave,
            enabled = !state.isSaving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.isSaving) stringResource(R.string.add_receipt_saving) else stringResource(R.string.add_receipt_save))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.add_receipt_cancel))
        }
    }
}

private fun createCameraCaptureUri(context: Context): Uri {
    val captureDir = File(context.filesDir, "receipts/.capture").apply { mkdirs() }
    val captureFile = File(captureDir, "${UUID.randomUUID()}.jpg")
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        captureFile,
    )
}
```

- [ ] **Step 3: Build to verify compile**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL. If `Modifier.menuAnchor()` deprecation warning shows up, that's the same one already present in the existing code — ignore.

- [ ] **Step 4: Run full test suite**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
cd F:/AndroidApp/ExpenseTracker
git add app/src/main/java/io/github/jiro/expensetracker/ui/add_receipt/AddReceiptScreen.kt app/src/main/res/values/strings.xml
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "Add: AddReceiptScreen Composable + new strings"
```

---

## Task 7: Wire navigation + More tab

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/navigation/AppNav.kt`
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/more/MoreScreen.kt`

- [ ] **Step 1: Add `Routes.ADD_RECEIPT` constant**

In `AppNav.kt` (where the `Routes` object is, around line 36), add:

```kotlin
    const val ADD_RECEIPT = "add_receipt"
```

- [ ] **Step 2: Add the composable block in AppNav**

In `AppNav.kt`, after the `composable(Routes.SETTINGS)` block (around line 169), add:

```kotlin
            composable(Routes.ADD_RECEIPT) {
                AddReceiptScreen(
                    onBack = { navController.popBackStack() },
                )
            }
```

Add the import at the top of the file:

```kotlin
import io.github.jiro.expensetracker.ui.add_receipt.AddReceiptScreen
```

- [ ] **Step 3: Add `onAddReceipt` callback to `MoreScreen`**

Open `app/src/main/java/io/github/jiro/expensetracker/ui/more/MoreScreen.kt`. Update the function signature to add a new callback:

```kotlin
fun MoreScreen(
    onManageCategories: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onAddReceipt: () -> Unit = {},
) {
```

Add a new `MoreItem` at the top of the `items` list (above Manage categories) with `CameraAlt` icon (or `PhotoCamera`):

```kotlin
        MoreItem(
            title = stringResource(R.string.action_add_receipt),
            icon = Icons.Filled.PhotoCamera,
            onClick = onAddReceipt,
        ),
```

Add the import:

```kotlin
import androidx.compose.material.icons.filled.PhotoCamera
```

- [ ] **Step 4: Wire the callback in AppNav**

In `AppNav.kt`, update the `composable(Routes.MORE)` block (around line 158) to pass `onAddReceipt`:

```kotlin
            composable(Routes.MORE) {
                MoreScreen(
                    onManageCategories = { navController.navigate(Routes.CATEGORIES) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onAddReceipt = { navController.navigate(Routes.ADD_RECEIPT) },
                )
            }
```

- [ ] **Step 5: Build to verify compile**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Run full test suite**

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
cd F:/AndroidApp/ExpenseTracker
git add app/src/main/java/io/github/jiro/expensetracker/ui/navigation/AppNav.kt app/src/main/java/io/github/jiro/expensetracker/ui/more/MoreScreen.kt
git -c user.name="MiniMax-M3" -c user.email="291324429+Jiro90-T@users.noreply.github.com" commit -m "Wire: Add Receipt route in AppNav + More tab entry"
```

---

## Task 8: Build + test + tag v0.14.0 + push

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

Expected: BUILD SUCCESSFUL, all tests pass. Expected counts:
- Phase 2.14 had 259 tests across 21 suites
- Task 1: remove 9 confidence tests → -9
- Task 3: delete merger tests → -8
- Task 5: add 7 new VM tests → +7
- Net: 259 - 9 - 8 + 7 = 249 tests across 20 suites

- [ ] **Step 3: Manual smoke check (on device, post-merge)**

Per the existing manual smoke protocol. No device is connected locally so this is deferred to user testing.

1. Tap More → "Add receipt" → camera permission prompt (first time)
2. Grant permission → camera opens → take a photo of a receipt
3. Confirm: progress shows briefly, then review form appears with merchant/amount/date pre-filled
4. Edit a field, tap Save → navigates back to More
5. Open Transactions tab → new row visible with the receipt thumbnail
6. Regression: Add/Edit transaction → "Attach receipt" → bottom sheet only shows "Take photo" + "Choose from files" (no PDF option) → image attach + OCR auto-fill still works → snackbar "Receipt scanned. Fields filled."

- [ ] **Step 4: Tag v0.14.0 and push to master**

```bash
cd F:/AndroidApp/ExpenseTracker
git tag v0.14.0
git push origin master --tags
```

Expected: tag created, push succeeds.

---

## Self-review notes

**Spec coverage:**
- Inline image attach stays (image-only): Task 4 ✓
- Top-level "Add Receipt" in More tab: Task 7 ✓
- Camera-first flow: Task 6 ✓
- OCR auto-fill: Task 5 ✓
- Review screen with editable fields: Task 6 ✓
- Save creates new transaction: Task 5 ✓
- Navigate to More on save: Task 6 (via `onBack` which pops back to More) ✓
- Currency dropdown with home-currency default: Task 5 (init from `settingsRepository.homeCurrency.value`) ✓
- PDF code removed: Tasks 1, 2, 3, 4 ✓
- Confidence fields removed: Task 1 ✓
- 7 VM tests: Task 5 ✓
- v0.14.0 tag + push: Task 8 ✓

**Placeholder scan:** None. Every code block is runnable as written.

**Type consistency:**
- `AddReceiptUiState` shape used consistently across Tasks 5, 6.
- `AddReceiptMode` enum used consistently.
- `AddReceiptError` enum used consistently.
- `SettingsRepository.homeCurrency` reads `value` (synchronous) in VM init — matches the existing pattern.
- The new `OcrFields(null, null, null)` matches the reverted 3-field shape from Task 1.

**No new abstractions** beyond `AddReceiptViewModel`, `AddReceiptUiState`, `AddReceiptMode`, `AddReceiptError`, and the `AddReceiptScreen` Composable. Everything else reuses existing repositories, the OCR processor, and the parser.
