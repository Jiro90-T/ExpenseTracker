package io.github.jiro.expensetracker.data.repository

import io.github.jiro.expensetracker.data.local.AccountBalanceRow
import io.github.jiro.expensetracker.data.local.AccountDao
import io.github.jiro.expensetracker.data.local.AccountEntity
import io.github.jiro.expensetracker.data.local.InvestmentHoldingDao
import io.github.jiro.expensetracker.data.local.InvestmentHoldingEntity
import io.github.jiro.expensetracker.data.accountimport.ResolvedImportRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountCloseRepositoryTest {

    /**
     * Captures close/reopen calls and stubs every other AccountDao method.
     * Inline (no delegation by `by NoopAccountDao`) so the test is self-contained
     * and doesn't depend on any external fake.
     */
    private class CapturingDao : AccountDao {
        var lastCloseId: Long? = null
        var lastCloseNow: Long? = null
        var lastReopenId: Long? = null

        override suspend fun close(id: Long, now: Long) {
            lastCloseId = id; lastCloseNow = now
        }
        override suspend fun reopen(id: Long) {
            lastReopenId = id
        }

        // ---- default stubs for everything else ----
        override fun observeActive() = flowOf(emptyList<AccountEntity>())
        override suspend fun listActiveOnce() = emptyList<AccountEntity>()
        override suspend fun findById(id: Long) = null
        override suspend fun insert(account: AccountEntity) = 0L
        override suspend fun insertAllReplacing(accounts: List<AccountEntity>) = emptyList<Long>()
        override suspend fun update(account: AccountEntity) = 0
        override suspend fun delete(id: Long) = 0
        override suspend fun deleteAll() = 0
        override suspend fun countActive() = 0
        override fun observeBalances() = flowOf(emptyList<AccountBalanceRow>())
        override fun observeAllBalances() = flowOf(emptyList<AccountBalanceRow>())
        override fun observeAllEntities() = flowOf(emptyList<AccountEntity>())
        override suspend fun listAllOnce() = emptyList<AccountEntity>()
        override suspend fun updateDefaultCurrency(code: String) = 0
        override suspend fun maxSortOrder() = 0
        override suspend fun updateOpeningBalanceByName(name: String, balance: Long) = 0
        override suspend fun findActiveDefault(): AccountEntity? = null
        override suspend fun applyAccountImport(rows: List<ResolvedImportRow>, nowEpochMs: Long) {}
    }

    @Test fun close_passesSystemCurrentTimeMillisToDao() = runBlocking {
        val dao = CapturingDao()
        val repo = AccountRepository(dao, NoopInvestmentHoldingDao)
        val before = System.currentTimeMillis()
        repo.close(id = 7L)
        val after = System.currentTimeMillis()
        assertEquals(7L, dao.lastCloseId)
        val now = dao.lastCloseNow!!
        assertTrue("now ($now) should be between before ($before) and after ($after)",
            now in before..after)
    }

    @Test fun reopen_passesIdToDao() = runBlocking {
        val dao = CapturingDao()
        val repo = AccountRepository(dao, NoopInvestmentHoldingDao)
        repo.reopen(id = 9L)
        assertEquals(9L, dao.lastReopenId)
    }

    @Test fun observeAllWithBalances_joinsEntitiesWithBalances() = runBlocking {
        val activeEntity = AccountEntity(
            id = 1L, name = "Active", type = "CASH", icon = "💵",
            color = 0xFFFFFFFF.toInt(), currencyCode = "USD",
            openingBalanceMinor = 1000L, createdAtEpochMillis = 0L,
        )
        val closedEntity = activeEntity.copy(id = 2L, name = "Closed", archived = true, archivedAtEpochMillis = 1_700L)
        val entities = listOf(activeEntity, closedEntity)
        val balances = listOf(
            AccountBalanceRow(accountId = 1L, balanceMinor = 2500L),
            AccountBalanceRow(accountId = 2L, balanceMinor = 800L),
        )
        val dao = object : AccountDao {
            override fun observeAllEntities() = flowOf(entities)
            override fun observeAllBalances() = flowOf(balances)
            override suspend fun listAllOnce() = entities
            // unused stubs
            override fun observeActive() = flowOf(emptyList<AccountEntity>())
            override suspend fun listActiveOnce() = emptyList<AccountEntity>()
            override suspend fun findById(id: Long) = null
            override suspend fun insert(account: AccountEntity) = 0L
            override suspend fun insertAllReplacing(accounts: List<AccountEntity>) = emptyList<Long>()
            override suspend fun update(account: AccountEntity) = 0
            override suspend fun delete(id: Long) = 0
            override suspend fun deleteAll() = 0
            override suspend fun countActive() = 0
            override fun observeBalances() = flowOf(emptyList<AccountBalanceRow>())
            override suspend fun updateDefaultCurrency(code: String) = 0
            override suspend fun maxSortOrder() = 0
            override suspend fun updateOpeningBalanceByName(name: String, balance: Long) = 0
            override suspend fun findActiveDefault(): AccountEntity? = null
            override suspend fun close(id: Long, now: Long) {}
            override suspend fun reopen(id: Long) {}
            override suspend fun applyAccountImport(rows: List<ResolvedImportRow>, nowEpochMs: Long) {}
        }
        val repo = AccountRepository(dao, NoopInvestmentHoldingDao)
        val rows = repo.observeAllWithBalances().first()
        assertEquals(2, rows.size)
        val byId = rows.associateBy { it.account.id }
        assertEquals(2500L, byId[1L]!!.balanceMinor)
        assertEquals(800L, byId[2L]!!.balanceMinor)
        assertTrue("closed row should be present", byId[2L]!!.account.archived)
    }

    @Test fun observeAllWithBalances_fallsBackToOpeningBalanceWhenBalanceRowMissing() = runBlocking {
        val entity = AccountEntity(
            id = 1L, name = "NoTxn", type = "CASH", icon = "💵",
            color = 0xFFFFFFFF.toInt(), currencyCode = "USD",
            openingBalanceMinor = 1234L, createdAtEpochMillis = 0L,
        )
        val dao = object : AccountDao {
            override fun observeAllEntities() = flowOf(listOf(entity))
            override fun observeAllBalances() = flowOf(emptyList<AccountBalanceRow>())
            override suspend fun listAllOnce() = listOf(entity)
            override fun observeActive() = flowOf(emptyList<AccountEntity>())
            override suspend fun listActiveOnce() = emptyList<AccountEntity>()
            override suspend fun findById(id: Long) = null
            override suspend fun insert(account: AccountEntity) = 0L
            override suspend fun insertAllReplacing(accounts: List<AccountEntity>) = emptyList<Long>()
            override suspend fun update(account: AccountEntity) = 0
            override suspend fun delete(id: Long) = 0
            override suspend fun deleteAll() = 0
            override suspend fun countActive() = 0
            override fun observeBalances() = flowOf(emptyList<AccountBalanceRow>())
            override suspend fun updateDefaultCurrency(code: String) = 0
            override suspend fun maxSortOrder() = 0
            override suspend fun updateOpeningBalanceByName(name: String, balance: Long) = 0
            override suspend fun findActiveDefault(): AccountEntity? = null
            override suspend fun close(id: Long, now: Long) {}
            override suspend fun reopen(id: Long) {}
            override suspend fun applyAccountImport(rows: List<ResolvedImportRow>, nowEpochMs: Long) {}
        }
        val repo = AccountRepository(dao, NoopInvestmentHoldingDao)
        val rows = repo.observeAllWithBalances().first()
        assertEquals(1, rows.size)
        assertEquals(1234L, rows[0].balanceMinor)  // fell back to openingBalanceMinor
    }
}

/** No-op InvestmentHoldingDao for tests that don't exercise holdings. */
private object NoopInvestmentHoldingDao : InvestmentHoldingDao {
    override suspend fun insert(row: InvestmentHoldingEntity): Long = 0L
    override suspend fun update(row: InvestmentHoldingEntity) = Unit
    override suspend fun delete(id: Long) = Unit
    override fun observeByAccount(accountId: Long): Flow<List<InvestmentHoldingEntity>> =
        flowOf(emptyList())
    override suspend fun findById(id: Long): InvestmentHoldingEntity? = null
    override suspend fun countByAccount(accountId: Long): Int = 0
}