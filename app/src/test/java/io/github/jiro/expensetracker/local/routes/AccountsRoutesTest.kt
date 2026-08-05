package io.github.jiro.expensetracker.local.routes

import io.github.jiro.expensetracker.data.local.AccountBalanceRow
import io.github.jiro.expensetracker.data.local.AccountDao
import io.github.jiro.expensetracker.data.local.AccountEntity
import io.github.jiro.expensetracker.data.local.InvestmentHoldingDao
import io.github.jiro.expensetracker.data.local.InvestmentHoldingEntity
import io.github.jiro.expensetracker.data.repository.AccountRepository
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
            accountsRoutes(token, stubAccountRepo)
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
}

private val stubAccountRepo: AccountRepository = AccountRepository(
    StubAccountsPageAccountDao,
    StubAccountsPageInvestmentHoldingDao,
)

private object StubAccountsPageAccountDao : AccountDao {
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

private object StubAccountsPageInvestmentHoldingDao : InvestmentHoldingDao {
    override suspend fun insert(row: InvestmentHoldingEntity): Long = 0L
    override suspend fun update(row: InvestmentHoldingEntity) = Unit
    override suspend fun delete(id: Long) = Unit
    override fun observeByAccount(accountId: Long): Flow<List<InvestmentHoldingEntity>> =
        MutableStateFlow<List<InvestmentHoldingEntity>>(emptyList()).asStateFlow()
    override suspend fun findById(id: Long): InvestmentHoldingEntity? = null
    override suspend fun countByAccount(accountId: Long): Int = 0
}
