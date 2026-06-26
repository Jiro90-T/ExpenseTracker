package io.github.jiro.expensetracker.ui.accounts

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import io.github.jiro.expensetracker.data.local.AccountDao
import io.github.jiro.expensetracker.data.local.AccountEntity
import io.github.jiro.expensetracker.data.local.TransactionDao
import io.github.jiro.expensetracker.data.local.TransactionEntity
import io.github.jiro.expensetracker.data.local.TransactionWithCategory
import io.github.jiro.expensetracker.data.repository.AccountRepository
import io.github.jiro.expensetracker.data.repository.ReceiptRepository
import io.github.jiro.expensetracker.data.repository.TransactionRepository
import io.github.jiro.expensetracker.ui.navigation.Routes
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountDetailViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- helpers ----

    private fun accountEntity(id: Long, name: String = "Test"): AccountEntity = AccountEntity(
        id = id,
        name = name,
        type = "CASH",
        icon = "💵",
        color = 0xFFFFFFFF.toInt(),
        currencyCode = "USD",
        openingBalanceMinor = 0L,
        createdAtEpochMillis = 0L,
    )

    private fun buildVm(
        accountId: Long,
        accounts: List<AccountEntity> = listOf(accountEntity(accountId, "Test")),
        referenceCount: Int = 0,
    ): Pair<AccountDetailViewModel, FakeAccountRepository> {
        val accountRepo = FakeAccountRepository(accounts)
        val txRepo = FakeTransactionRepository(referenceCount)
        val savedState = SavedStateHandle(mapOf(Routes.ACCOUNT_DETAIL_ARG_ID to accountId))
        val vm = AccountDetailViewModel(
            savedStateHandle = savedState,
            accountRepository = accountRepo,
            transactionRepository = txRepo,
        )
        return vm to accountRepo
    }

    // ---- tests ----

    @Test
    fun onDeleteClick_zeroReferences_showsConfirmWithAllow() = runTest(testDispatcher) {
        val (vm, _) = buildVm(accountId = 2L, referenceCount = 0)
        advanceUntilIdle()

        vm.onDeleteClick()
        advanceUntilIdle()

        val s = vm.state.value
        assertTrue(s.showDeleteConfirm)
        assertEquals(DeleteGuard.ALLOW, s.deleteGuard)
        assertEquals(0, s.referenceCount)
        assertFalse(s.deleted)
    }

    @Test
    fun onDeleteClick_threeReferences_showsConfirmWithBlock() = runTest(testDispatcher) {
        val (vm, _) = buildVm(accountId = 2L, referenceCount = 3)
        advanceUntilIdle()

        vm.onDeleteClick()
        advanceUntilIdle()

        val s = vm.state.value
        assertTrue(s.showDeleteConfirm)
        assertEquals(DeleteGuard.BLOCK_TRANSACTIONS_EXIST, s.deleteGuard)
        assertEquals(3, s.referenceCount)
    }

    @Test
    fun onDeleteClick_defaultAccount_isNoOp() = runTest(testDispatcher) {
        val (vm, _) = buildVm(accountId = 1L, referenceCount = 0)
        advanceUntilIdle()

        vm.onDeleteClick()
        advanceUntilIdle()

        val s = vm.state.value
        assertFalse(s.showDeleteConfirm)
        assertNull(s.deleteGuard)
        assertEquals(0, s.referenceCount)
    }

    @Test
    fun onDeleteConfirm_allow_callsDeleteAndSetsDeleted() = runTest(testDispatcher) {
        val (vm, accountRepo) = buildVm(accountId = 2L, referenceCount = 0)
        advanceUntilIdle()

        vm.onDeleteClick()
        advanceUntilIdle()
        vm.onDeleteConfirm()
        advanceUntilIdle()

        assertEquals(listOf(2L), accountRepo.deletedIds)
        val s = vm.state.value
        assertTrue(s.deleted)
        assertFalse(s.showDeleteConfirm)
    }

    @Test
    fun onDeleteConfirm_block_doesNotDelete() = runTest(testDispatcher) {
        val (vm, accountRepo) = buildVm(accountId = 2L, referenceCount = 3)
        advanceUntilIdle()

        vm.onDeleteClick()
        advanceUntilIdle()
        vm.onDeleteConfirm()
        advanceUntilIdle()

        assertTrue(accountRepo.deletedIds.isEmpty())
        // Dialog stays open in BLOCK case (user must dismiss)
        assertTrue(vm.state.value.showDeleteConfirm)
    }

    @Test
    fun onDeleteDismiss_clearsShowDeleteConfirm() = runTest(testDispatcher) {
        val (vm, accountRepo) = buildVm(accountId = 2L, referenceCount = 0)
        advanceUntilIdle()

        vm.onDeleteClick()
        advanceUntilIdle()
        assertTrue(vm.state.value.showDeleteConfirm)

        vm.onDeleteDismiss()
        advanceUntilIdle()

        assertFalse(vm.state.value.showDeleteConfirm)
        assertTrue(accountRepo.deletedIds.isEmpty())
    }
}

// ---- fakes (test-only, no mocking framework) ----

private class NoopApplication : Application()

private class FakeAccountRepository(
    accounts: List<AccountEntity>,
) : AccountRepository(dao = StubAccountDao(accounts)) {
    val deletedIds = mutableListOf<Long>()
    override suspend fun delete(id: Long): Int {
        deletedIds += id
        return 1
    }
}

private class FakeTransactionRepository(
    private val referenceCount: Int,
) : TransactionRepository(
    dao = StubTransactionDao(),
    receiptRepository = ReceiptRepository(context = NoopApplication()),
) {
    override suspend fun countReferencingAccount(id: Long): Int = referenceCount
}

// ---- minimal DAO stubs (interfaces we don't exercise) ----

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
    override fun observeByRecurringGroup(groupId: String) =
        MutableStateFlow(emptyList<TransactionWithCategory>()).asStateFlow()
    override suspend fun countByRecurringGroup(groupId: String) = error("not used in tests")
    override suspend fun countForAccount(accountId: Long) = error("not used in tests")
    override suspend fun countReferencingAccount(id: Long) = error("not used in tests")
    override fun observeByAccount(accountId: Long): Flow<List<TransactionWithCategory>> =
        MutableStateFlow<List<TransactionWithCategory>>(emptyList()).asStateFlow()
}

private class StubAccountDao(
    private val accounts: List<AccountEntity>,
) : AccountDao {
    override fun observeActive(): Flow<List<AccountEntity>> =
        MutableStateFlow(accounts).asStateFlow()
    override suspend fun listActiveOnce(): List<AccountEntity> = accounts
    override suspend fun findById(id: Long): AccountEntity? = accounts.find { it.id == id }
    override suspend fun findDefault(): AccountEntity? = accounts.find { it.id == 1L }
    override suspend fun insert(account: AccountEntity): Long = 0L
    override suspend fun update(account: AccountEntity): Int = 0
    override suspend fun delete(id: Long): Int = 0
    override suspend fun updateDefaultCurrency(code: String): Int = 0
    override suspend fun countActive(): Int = accounts.size
    override fun observeBalances(): Flow<List<io.github.jiro.expensetracker.data.local.AccountBalanceRow>> =
        MutableStateFlow(
            accounts.map { io.github.jiro.expensetracker.data.local.AccountBalanceRow(it.id, 0L) }
        ).asStateFlow()
}
