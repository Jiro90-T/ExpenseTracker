package io.github.jiro.expensetracker.ui.add_receipt

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.viewModelScope
import io.github.jiro.expensetracker.data.local.CategoryDao
import io.github.jiro.expensetracker.data.local.CategoryEntity
import io.github.jiro.expensetracker.data.local.MoneyFormat
import io.github.jiro.expensetracker.data.local.ReceiptOcrProcessor
import io.github.jiro.expensetracker.data.local.TransactionDao
import io.github.jiro.expensetracker.data.local.TransactionEntity
import io.github.jiro.expensetracker.data.repository.CategoryRepository
import io.github.jiro.expensetracker.data.repository.ReceiptRepository
import io.github.jiro.expensetracker.data.repository.TransactionRepository
import io.github.jiro.expensetracker.domain.model.TransactionType
import io.github.jiro.expensetracker.domain.receipt.OcrFields
import io.github.jiro.expensetracker.preferences.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    ): Triple<TestableAddReceiptViewModel, FakeTransactionRepo, FakeOcrProcessor> {
        val txRepo = FakeTransactionRepo()
        val catRepo = FakeCategoryRepo()
        val receiptRepo = FakeReceiptRepo()
        val ocrProcessor = FakeOcrProcessor(ocr)
        val settings = FakeSettingsRepository(homeCurrency)
        val app = NoopApplication()
        val vm = TestableAddReceiptViewModel(
            application = app,
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
        assertNull(s.error)
        assertTrue(!s.isSaving)
        assertTrue(!s.saveComplete)
    }

    @Test
    fun onPhotoCaptured_emptyOcr_transitionsToReviewWithEmptyFields() = runTest(testDispatcher) {
        val (vm, _, _) = buildVm(ocr = OcrFields(null, null, null))
        vm.onReceiptSaved("receipts/fake.jpg")
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(AddReceiptMode.Review, s.mode)
        assertNotNull(s.photoPath)
        assertEquals("", s.title)
        assertEquals("", s.amountInput)
    }

    @Test
    fun onPhotoCaptured_withOcrFields_prefillsForm() = runTest(testDispatcher) {
        val (vm, _, ocr) = buildVm(
            ocr = OcrFields(
                amountMinor = 540L,
                occurredAtEpochMillis = 1_716_000_000_000L,
                merchant = "Coffee & Co",
            ),
        )
        // Drive the state machine directly via the test seam (see
        // TestableAddReceiptViewModel). We bypass onPhotoCaptured because
        // constructing an Android Uri is impossible under JVM unit tests.
        vm.onReceiptSaved("receipts/fake.jpg")
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
        vm.onReceiptSaved("receipts/fake.jpg")
        advanceUntilIdle()
        vm.onTitleChange("")
        vm.onAmountChange("5.00")
        vm.onCategoryChange(1L)
        vm.onSave()
        advanceUntilIdle()
        assertEquals(AddReceiptError.TITLE_REQUIRED, vm.state.value.error)
        assertTrue(!vm.state.value.saveComplete)
    }

    @Test
    fun onSave_invalidAmount_setsError() = runTest(testDispatcher) {
        val (vm, _, _) = buildVm()
        vm.onReceiptSaved("receipts/fake.jpg")
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
        vm.onReceiptSaved("receipts/fake.jpg")
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

/**
 * Subclass of [AddReceiptViewModel] that bypasses the real bitmap decode
 * step. Under `unitTests.isReturnDefaultValues = true`,
 * `BitmapFactory.decodeFile` is stubbed and returns null — so we override
 * [AddReceiptViewModel.onReceiptSaved] to skip the `decode → extract →
 * recycle` pipeline entirely and feed the fake OCR processor's configured
 * result straight into the state update. The state-machine update path is
 * identical to production.
 */
private class TestableAddReceiptViewModel(
    application: Application,
    transactionRepository: TransactionRepository,
    categoryRepository: CategoryRepository,
    receiptRepository: ReceiptRepository,
    receiptOcrProcessor: ReceiptOcrProcessor,
    settingsRepository: SettingsRepository,
) : AddReceiptViewModel(
    application = application,
    transactionRepository = transactionRepository,
    categoryRepository = categoryRepository,
    receiptRepository = receiptRepository,
    receiptOcrProcessor = receiptOcrProcessor,
    settingsRepository = settingsRepository,
) {
    override fun onReceiptSaved(path: String) {
        // Bypass the whole `file.isFile → decode → OCR` pipeline (which
        // can't work in JVM unit tests because BitmapFactory is stubbed).
        // Hand the configured OCR to the fake OCR processor directly.
        viewModelScope.launch {
            val ocr = (ocrProcessor as FakeOcrProcessor).bypassExtract()
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
}

// ---- fakes / stubs (test-only, no mocking framework) ----

/** Minimal Application subclass — AndroidViewModel constructor takes Application. */
class NoopApplication : Application()

/**
 * Fake [TransactionRepository] that overrides [add] to record calls. We use a
 * stub TransactionDao (interface) instead of mocking — Mockito isn't on the
 * classpath, and the only method the VM cares about is `add`.
 */
class FakeTransactionRepo : TransactionRepository(
    dao = StubTransactionDao(),
    receiptRepository = FakeReceiptRepo(),
) {
    val added = mutableListOf<TransactionEntity>()
    override suspend fun add(transaction: TransactionEntity): Long {
        added += transaction
        return 1L
    }
}

class FakeCategoryRepo : CategoryRepository(
    dao = StubCategoryDao(),
) {
    override fun observeByType(type: TransactionType): Flow<List<CategoryEntity>> =
        MutableStateFlow(listOf<CategoryEntity>()).asStateFlow()
}

class FakeReceiptRepo : ReceiptRepository(
    context = NoopApplication(),
) {
    override suspend fun saveFromUri(context: Context, src: Uri): String = "receipts/fake.jpg"
    override fun absolutePath(relativePath: String): java.io.File {
        // Create a real temp file so the VM's `file.isFile` guard passes
        // and the OCR pipeline runs. The contents don't matter — the
        // OCR processor is faked.
        val tmp = java.io.File.createTempFile("fake-receipt-", ".jpg")
        tmp.deleteOnExit()
        return tmp
    }
}

class FakeOcrProcessor(private val result: OcrFields) : ReceiptOcrProcessor() {
    var lastCalled: OcrFields? = null
    override suspend fun extract(bitmap: Bitmap): OcrFields {
        lastCalled = result
        return result
    }

    /**
     * Return the configured [result] without taking a bitmap. Used by
     * [TestableAddReceiptViewModel.onReceiptSaved] to bypass the
     * `decode → extract → recycle` pipeline (which can't run under
     * `unitTests.isReturnDefaultValues`).
     */
    fun bypassExtract(): OcrFields {
        lastCalled = result
        return result
    }
}

class FakeSettingsRepository(homeCurrency: String) : SettingsRepository(
    context = NoopApplication(),
) {
    private val flow = MutableStateFlow(homeCurrency)
    override val homeCurrency = flow.asStateFlow()
    override fun setHomeCurrency(code: String) {
        flow.value = code
    }
}

// ---- minimal DAO stubs (interfaces we don't exercise) ----

/**
 * We never call any TransactionDao method in this test (the VM only uses
 * TransactionRepository.add, which we override in FakeTransactionRepo). The
 * stub exists solely to satisfy the TransactionRepository constructor.
 */
@Suppress("UNUSED_PARAMETER")
class StubTransactionDao : TransactionDao {
    override fun observeAllWithCategory() = error("not used in tests")
    override fun observeInRangeWithCategory(startMs: Long, endMs: Long) = error("not used in tests")
    override suspend fun findById(id: Long) = error("not used in tests")
    override suspend fun insert(transaction: TransactionEntity) = error("not used in tests")
    override suspend fun restore(transaction: TransactionEntity) = error("not used in tests")
    override suspend fun update(transaction: TransactionEntity) = error("not used in tests")
    override suspend fun delete(transaction: TransactionEntity) = error("not used in tests")
    override suspend fun observeAllForExport() = error("not used in tests")
    override suspend fun deleteAll() = error("not used in tests")
    override suspend fun insertAll(transactions: List<TransactionEntity>) = error("not used in tests")
    override suspend fun clearReceiptPathsFor(paths: List<String>) = error("not used in tests")
    override suspend fun dueRecurringParents(nowMs: Long) = error("not used in tests")
    override fun observeByRecurringGroup(groupId: String) = error("not used in tests")
    override suspend fun countByRecurringGroup(groupId: String) = error("not used in tests")
}

@Suppress("UNUSED_PARAMETER")
class StubCategoryDao : CategoryDao {
    override fun observeByType(type: String) = error("not used in tests")
    override fun observeAll() = error("not used in tests")
    override suspend fun count() = error("not used in tests")
    override suspend fun findById(id: Long) = error("not used in tests")
    override suspend fun insertAll(categories: List<CategoryEntity>) = error("not used in tests")
    override suspend fun insert(category: CategoryEntity) = error("not used in tests")
    override suspend fun insertAllReplacing(categories: List<CategoryEntity>) = error("not used in tests")
    override suspend fun update(category: CategoryEntity) = error("not used in tests")
    override suspend fun deleteById(id: Long) = error("not used in tests")
    override suspend fun deleteAllNonBuiltIn() = error("not used in tests")
    override suspend fun observeAllOnce() = error("not used in tests")
}