package io.github.jiro.expensetracker.ui.add_receipt

import android.app.Application
import android.content.Context
import android.net.Uri
import io.github.jiro.expensetracker.data.local.CategoryDao
import io.github.jiro.expensetracker.data.local.CategoryEntity
import io.github.jiro.expensetracker.data.local.ReceiptOcrProcessor
import io.github.jiro.expensetracker.data.local.TransactionDao
import io.github.jiro.expensetracker.data.local.TransactionEntity
import io.github.jiro.expensetracker.data.local.TransactionWithCategory
import io.github.jiro.expensetracker.data.repository.CategoryRepository
import io.github.jiro.expensetracker.data.repository.ReceiptRepository
import io.github.jiro.expensetracker.data.repository.TransactionRepository
import io.github.jiro.expensetracker.domain.model.TransactionType
import io.github.jiro.expensetracker.domain.receipt.OcrFields
import io.github.jiro.expensetracker.preferences.SettingsRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Build the VM with all-fake dependencies. The real [ReceiptReviewPipeline]
     * is used (it's a pure object — no injection needed), but BitmapFactory
     * is stubbed under JVM tests so the pipeline's try/catch returns empty
     * OCR fields. The pipeline itself is tested independently in
     * [io.github.jiro.expensetracker.domain.receipt.ReceiptReviewPipelineTest].
     */
    private fun buildVm(
        homeCurrency: String = "USD",
    ): Pair<AddReceiptViewModel, FakeTransactionRepo> {
        val txRepo = FakeTransactionRepo()
        val vm = AddReceiptViewModel(
            application = NoopApplication(),
            transactionRepository = txRepo,
            categoryRepository = FakeCategoryRepo(),
            receiptRepository = FakeReceiptRepo(),
            receiptOcrProcessor = FakeOcrProcessor(OcrFields(null, null, null)),
            settingsRepository = FakeSettingsRepository(homeCurrency),
            // Pin the pipeline's IO work to the test scheduler so
            // advanceUntilIdle actually waits for it. Otherwise the real
            // Dispatchers.IO thread pool runs the bitmap decode and our
            // assertions race ahead.
            ioDispatcher = testDispatcher,
        )
        return vm to txRepo
    }

    @Test
    fun initialState_isIdle() = runTest(testDispatcher) {
        val (vm, _) = buildVm()
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
    fun onPhotoSaved_emptyOcr_transitionsToReviewWithEmptyFields() = runTest(testDispatcher) {
        val (vm, _) = buildVm()
        vm.onPhotoSaved("receipts/fake.jpg")
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(AddReceiptMode.Review, s.mode)
        assertNotNull(s.photoPath)
        assertEquals("", s.title)
        assertEquals("", s.amountInput)
    }

    @Test
    fun onTitleChange_updatesState() = runTest(testDispatcher) {
        val (vm, _) = buildVm()
        vm.onTitleChange("Walmart")
        assertEquals("Walmart", vm.state.value.title)
    }

    @Test
    fun onSave_missingTitle_setsError() = runTest(testDispatcher) {
        val (vm, _) = buildVm()
        vm.onPhotoSaved("receipts/fake.jpg")
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
        val (vm, _) = buildVm()
        vm.onPhotoSaved("receipts/fake.jpg")
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
        val (vm, txRepo) = buildVm()
        vm.onPhotoSaved("receipts/fake.jpg")
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

// ---- fakes (test-only, no mocking framework) ----

class NoopApplication : Application()

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
    override fun absolutePath(relativePath: String): File =
        File.createTempFile("fake-receipt-", ".jpg").also { it.deleteOnExit() }
}

class FakeOcrProcessor(private val result: OcrFields) : ReceiptOcrProcessor() {
    override suspend fun extract(bitmap: android.graphics.Bitmap): OcrFields = result
}

class FakeSettingsRepository(homeCurrency: String) : SettingsRepository(
    context = NoopApplication(),
) {
    private val flow = MutableStateFlow(homeCurrency)
    override val homeCurrency: kotlinx.coroutines.flow.StateFlow<String> = flow.asStateFlow()
    override fun setHomeCurrency(code: String) {
        flow.value = code
    }
}

// ---- minimal DAO stubs (interfaces we don't exercise) ----

@Suppress("UNUSED_PARAMETER")
private class StubTransactionDao : TransactionDao {
    override fun observeAllWithCategory(): Flow<List<TransactionWithCategory>> =
        MutableStateFlow<List<TransactionWithCategory>>(emptyList()).asStateFlow()
    override fun observeInRangeWithCategory(startMs: Long, endMs: Long): Flow<List<TransactionWithCategory>> =
        MutableStateFlow<List<TransactionWithCategory>>(emptyList()).asStateFlow()
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
    override fun observeByRecurringGroup(groupId: String) = MutableStateFlow(emptyList<TransactionWithCategory>()).asStateFlow()
    override suspend fun countByRecurringGroup(groupId: String) = error("not used in tests")
    override suspend fun countForAccount(accountId: Long) = error("not used in tests")
    override fun observeByAccount(accountId: Long): Flow<List<TransactionWithCategory>> =
        MutableStateFlow<List<TransactionWithCategory>>(emptyList()).asStateFlow()
}

@Suppress("UNUSED_PARAMETER")
private class StubCategoryDao : CategoryDao {
    override fun observeByType(type: String) = MutableStateFlow(emptyList<CategoryEntity>()).asStateFlow()
    override fun observeAll() = MutableStateFlow(emptyList<CategoryEntity>()).asStateFlow()
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
