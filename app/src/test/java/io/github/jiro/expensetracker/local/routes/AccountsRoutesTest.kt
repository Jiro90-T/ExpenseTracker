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

class AccountsRoutesTest {

    private val token = "test-token"

    private fun io.ktor.server.application.Application.setupTestApp() {
        routing {
            authFilter(token)
            accountsRoutes(token, stubAccountRepo, accountsPageTxRepo, accountsPageCategoryRepo)
        }
    }

    @Test fun listPage_renders() = runTest {
        testApplication {
            application { setupTestApp() }
            val resp = client.get("/accounts?t=$token")
            assertEquals(HttpStatusCode.OK, resp.status)
            assertTrue(
                "expected ContentType text/html, was: ${resp.headers[HttpHeaders.ContentType]}",
                ContentType.parse(resp.headers[HttpHeaders.ContentType] ?: "")
                    .match(ContentType.Text.Html),
            )
            val body = resp.bodyAsText()
            assertTrue("expected 'Accounts' heading, was: $body", body.contains("Accounts"))
        }
    }

    @Test fun newForm_rendersNameField() = runTest {
        testApplication {
            application { setupTestApp() }
            val resp = client.get("/accounts/new?t=$token")
            assertEquals(HttpStatusCode.OK, resp.status)
            assertTrue(
                "expected ContentType text/html, was: ${resp.headers[HttpHeaders.ContentType]}",
                ContentType.parse(resp.headers[HttpHeaders.ContentType] ?: "")
                    .match(ContentType.Text.Html),
            )
            val body = resp.bodyAsText()
            assertTrue("expected name field, was: $body", body.contains("name=\"name\""))
        }
    }

    @Test fun accountDetail_rendersBalanceAndAddLink() = runTest {
        testApplication {
            application { setupTestApp() }
            val resp = client.get("/accounts/42?t=$token")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.bodyAsText()
            assertTrue("expected Add transaction link, was: $body",
                body.contains("/transactions/new?accountId=42"))
            assertTrue("expected 'No transactions' fallback, was: $body",
                body.contains("No transactions for this account"))
            assertTrue("expected 'Running balance' label, was: $body",
                body.contains("Running balance"))
        }
    }
}

private val accountsPageTxRepo: TransactionRepository = TransactionRepository(
    StubAccountsPageTransactionDao,
    ReceiptRepository(NoopAccountsPageApp),
)

private val stubAccountRepo: AccountRepository = AccountRepository(
    StubAccountsPageAccountDao,
    StubAccountsPageInvestmentHoldingDao,
)

private val accountsPageCategoryRepo: CategoryRepository =
    CategoryRepository(StubAccountsPageCategoryDao)

private object NoopAccountsPageApp : Application()

private object StubAccountsPageTransactionDao : TransactionDao {
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

private object StubAccountsPageAccountDao : AccountDao {
    override fun observeActive(): Flow<List<AccountEntity>> =
        MutableStateFlow<List<AccountEntity>>(emptyList()).asStateFlow()
    override suspend fun listActiveOnce(): List<AccountEntity> = emptyList()
    override suspend fun findById(id: Long): AccountEntity? = AccountEntity(
        id = id,
        name = "Checking",
        type = "BANK",
        icon = "🏦",
        color = 0xFF888888.toInt(),
        currencyCode = "USD",
        openingBalanceMinor = 10_000L,
        createdAtEpochMillis = 0L,
    )
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

private object StubAccountsPageCategoryDao : CategoryDao {
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

private object StubAccountsPageInvestmentHoldingDao : InvestmentHoldingDao {
    override suspend fun insert(row: InvestmentHoldingEntity): Long = 0L
    override suspend fun update(row: InvestmentHoldingEntity) = Unit
    override suspend fun delete(id: Long) = Unit
    override fun observeByAccount(accountId: Long): Flow<List<InvestmentHoldingEntity>> =
        MutableStateFlow<List<InvestmentHoldingEntity>>(emptyList()).asStateFlow()
    override suspend fun findById(id: Long): InvestmentHoldingEntity? = null
    override suspend fun countByAccount(accountId: Long): Int = 0
}
