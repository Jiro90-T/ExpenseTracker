package io.github.jiro.expensetracker.local.routes

import android.app.Application
import io.github.jiro.expensetracker.data.local.AccountBalanceRow
import io.github.jiro.expensetracker.data.local.AccountDao
import io.github.jiro.expensetracker.data.local.AccountEntity
import io.github.jiro.expensetracker.data.local.CategoryDao
import io.github.jiro.expensetracker.data.local.CategoryEntity
import io.github.jiro.expensetracker.data.local.InvestmentHoldingDao
import io.github.jiro.expensetracker.data.local.InvestmentHoldingEntity
import io.github.jiro.expensetracker.data.local.TransactionDao
import io.github.jiro.expensetracker.data.local.TransactionEntity
import io.github.jiro.expensetracker.data.local.TransactionWithCategory
import io.github.jiro.expensetracker.data.repository.AccountRepository
import io.github.jiro.expensetracker.data.repository.CategoryRepository
import io.github.jiro.expensetracker.data.repository.ReceiptRepository
import io.github.jiro.expensetracker.data.repository.TransactionRepository
import io.github.jiro.expensetracker.local.auth.authFilter
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionsRoutesTest {

    private val token = "test-token"

    private fun io.ktor.server.application.Application.setupTestApp() {
        routing {
            authFilter(token)
            transactionsRoutes(token, stubTxRepo, stubAccountRepo, stubCategoryRepo)
        }
    }

    @Test fun listPage_rendersTableAndNewButton() = runTest {
        testApplication {
            application { setupTestApp() }
            val resp = client.get("/transactions?t=$token")
            assertEquals(HttpStatusCode.OK, resp.status)
            assertTrue(
                "expected ContentType text/html, was: ${resp.headers[HttpHeaders.ContentType]}",
                ContentType.parse(resp.headers[HttpHeaders.ContentType] ?: "")
                    .match(ContentType.Text.Html),
            )
            val body = resp.bodyAsText()
            assertTrue("expected 'Transactions' heading, was: $body", body.contains("Transactions"))
            assertTrue("expected new-transaction link, was: $body", body.contains("/transactions/new"))
        }
    }

    @Test fun newForm_rendersFields() = runTest {
        testApplication {
            application { setupTestApp() }
            val resp = client.get("/transactions/new?t=$token")
            assertEquals(HttpStatusCode.OK, resp.status)
            assertTrue(
                "expected ContentType text/html, was: ${resp.headers[HttpHeaders.ContentType]}",
                ContentType.parse(resp.headers[HttpHeaders.ContentType] ?: "")
                    .match(ContentType.Text.Html),
            )
            val body = resp.bodyAsText()
            assertTrue("expected title field, was: $body", body.contains("name=\"title\""))
            assertTrue("expected amount field, was: $body", body.contains("name=\"amount\""))
            assertTrue("expected currencyCode field, was: $body", body.contains("name=\"currencyCode\""))
        }
    }
}

private val stubTxRepo: TransactionRepository = TransactionRepository(
    StubTransactionDao,
    ReceiptRepository(NoopApp),
)

private val stubAccountRepo: AccountRepository = AccountRepository(
    StubAccountDao,
    StubInvestmentHoldingDao,
)

private val stubCategoryRepo: CategoryRepository = CategoryRepository(StubCategoryDao)

private object NoopApp : Application()

private object StubTransactionDao : TransactionDao {
    override fun observeAllWithCategory(): Flow<List<TransactionWithCategory>> =
        MutableStateFlow<List<TransactionWithCategory>>(emptyList()).asStateFlow()
    override fun observeInRangeWithCategory(startMs: Long, endMs: Long): Flow<List<TransactionWithCategory>> =
        MutableStateFlow<List<TransactionWithCategory>>(emptyList()).asStateFlow()
    override suspend fun findById(id: Long): TransactionEntity? = null
    override suspend fun insert(transaction: TransactionEntity): Long = 0L
    override suspend fun restore(transaction: TransactionEntity): Long = 0L
    override suspend fun update(transaction: TransactionEntity) = Unit
    override suspend fun delete(transaction: TransactionEntity) = Unit
    override suspend fun observeAllForExport(): List<TransactionEntity> = emptyList()
    override suspend fun deleteAll(): Int = 0
    override suspend fun insertAll(transactions: List<TransactionEntity>): List<Long> = emptyList()
    override suspend fun clearReceiptPathsFor(paths: List<String>) = Unit
    override suspend fun dueRecurringParents(nowMs: Long): List<TransactionEntity> = emptyList()
    override fun observeByRecurringGroup(groupId: String): Flow<List<TransactionWithCategory>> =
        MutableStateFlow<List<TransactionWithCategory>>(emptyList()).asStateFlow()
    override suspend fun countByRecurringGroup(groupId: String): Int = 0
    override suspend fun countForAccount(accountId: Long): Int = 0
    override suspend fun countReferencingAccount(id: Long): Int = 0
    override fun observeByAccount(accountId: Long): Flow<List<TransactionWithCategory>> =
        MutableStateFlow<List<TransactionWithCategory>>(emptyList()).asStateFlow()
}

private object StubAccountDao : AccountDao {
    override fun observeActive(): Flow<List<AccountEntity>> =
        MutableStateFlow<List<AccountEntity>>(emptyList()).asStateFlow()
    override suspend fun listActiveOnce(): List<AccountEntity> = emptyList()
    override suspend fun findById(id: Long): AccountEntity? = null
    override suspend fun findActiveDefault(): AccountEntity? = null
    override suspend fun insert(account: AccountEntity): Long = 0L
    override suspend fun insertAllReplacing(accounts: List<AccountEntity>): List<Long> = emptyList()
    override suspend fun update(account: AccountEntity): Int = 0
    override suspend fun delete(id: Long): Int = 0
    override suspend fun deleteAll(): Int = 0
    override suspend fun updateDefaultCurrency(code: String): Int = 0
    override suspend fun countActive(): Int = 0
    override fun observeBalances(): Flow<List<AccountBalanceRow>> =
        MutableStateFlow<List<AccountBalanceRow>>(emptyList()).asStateFlow()
    override fun observeAllBalances(): Flow<List<AccountBalanceRow>> =
        MutableStateFlow<List<AccountBalanceRow>>(emptyList()).asStateFlow()
    override fun observeAllEntities(): Flow<List<AccountEntity>> =
        MutableStateFlow<List<AccountEntity>>(emptyList()).asStateFlow()
    override suspend fun listAllOnce(): List<AccountEntity> = emptyList()
    override suspend fun close(id: Long, now: Long) = Unit
    override suspend fun reopen(id: Long) = Unit
    override suspend fun maxSortOrder(): Int = 0
    override suspend fun updateOpeningBalanceByName(name: String, balance: Long): Int = 0
    override suspend fun applyAccountImport(
        rows: List<io.github.jiro.expensetracker.data.accountimport.ResolvedImportRow>,
        nowEpochMs: Long,
    ) = Unit
}

private object StubCategoryDao : CategoryDao {
    override fun observeByType(type: String): Flow<List<CategoryEntity>> =
        MutableStateFlow<List<CategoryEntity>>(emptyList()).asStateFlow()
    override fun observeAll(): Flow<List<CategoryEntity>> =
        MutableStateFlow<List<CategoryEntity>>(emptyList()).asStateFlow()
    override suspend fun count(): Int = 0
    override suspend fun findById(id: Long): CategoryEntity? = null
    override suspend fun insertAll(categories: List<CategoryEntity>): List<Long> = emptyList()
    override suspend fun insert(category: CategoryEntity): Long = 0L
    override suspend fun insertAllReplacing(categories: List<CategoryEntity>): List<Long> = emptyList()
    override suspend fun update(category: CategoryEntity): Int = 0
    override suspend fun deleteById(id: Long): Int = 0
    override suspend fun deleteAllNonBuiltIn(): Int = 0
    override suspend fun observeAllOnce(): List<CategoryEntity> = emptyList()
}

private object StubInvestmentHoldingDao : InvestmentHoldingDao {
    override suspend fun insert(row: InvestmentHoldingEntity): Long = 0L
    override suspend fun update(row: InvestmentHoldingEntity) = Unit
    override suspend fun delete(id: Long) = Unit
    override fun observeByAccount(accountId: Long): Flow<List<InvestmentHoldingEntity>> =
        MutableStateFlow<List<InvestmentHoldingEntity>>(emptyList()).asStateFlow()
    override suspend fun findById(id: Long): InvestmentHoldingEntity? = null
    override suspend fun countByAccount(accountId: Long): Int = 0
}
