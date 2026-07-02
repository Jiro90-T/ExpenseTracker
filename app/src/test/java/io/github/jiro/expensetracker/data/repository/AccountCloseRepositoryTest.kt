package io.github.jiro.expensetracker.data.repository

import io.github.jiro.expensetracker.data.local.AccountBalanceRow
import io.github.jiro.expensetracker.data.local.AccountDao
import io.github.jiro.expensetracker.data.local.AccountEntity
import io.github.jiro.expensetracker.data.accountimport.ResolvedImportRow
import kotlinx.coroutines.flow.Flow
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
        override suspend fun update(account: AccountEntity) = 0
        override suspend fun delete(id: Long) = 0
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
        val repo = AccountRepository(dao)
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
        val repo = AccountRepository(dao)
        repo.reopen(id = 9L)
        assertEquals(9L, dao.lastReopenId)
    }
}