package io.github.jiro.expensetracker.data.repository

import io.github.jiro.expensetracker.data.local.AccountBalanceRow
import io.github.jiro.expensetracker.data.local.AccountDao
import io.github.jiro.expensetracker.data.local.AccountEntity
import io.github.jiro.expensetracker.data.accountimport.ResolvedImportRow
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

@Singleton
open class AccountRepository @Inject constructor(
    private val dao: AccountDao,
) {
    fun observeActive(): Flow<List<AccountEntity>> = dao.observeActive()

    suspend fun listActiveOnce(): List<AccountEntity> = dao.listActiveOnce()

    suspend fun findById(id: Long): AccountEntity? = dao.findById(id)

    suspend fun countActive(): Int = dao.countActive()

    /** Returns the seeded default account (id=1) if it exists. */
    suspend fun findDefault(): AccountEntity? = dao.findDefault()

    /**
     * Insert a new account. Returns the row id. Returns -1 if a duplicate
     * name exists (unique index violation). Callers should check for that
     * and surface a "name already in use" error.
     */
    open suspend fun add(account: AccountEntity): Long = dao.insert(account)

    open suspend fun update(account: AccountEntity) {
        dao.update(account)
    }

    open suspend fun delete(id: Long): Int = dao.delete(id)

    /** Applies a batch of resolved CSV import rows in one Room transaction. */
    open suspend fun applyAccountImport(rows: List<ResolvedImportRow>, nowEpochMs: Long) =
        dao.applyAccountImport(rows, nowEpochMs)

    /** Stream of all non-archived accounts joined with their computed balances. */
    fun observeWithBalances(): Flow<List<AccountWithBalance>> =
        combine(dao.observeActive(), dao.observeBalances()) { accounts, balances ->
            val map = balances.associate { it.accountId to it.balanceMinor }
            accounts.map { acc ->
                AccountWithBalance(
                    account = acc,
                    balanceMinor = map[acc.id] ?: acc.openingBalanceMinor,
                )
            }
        }

    /**
     * Overwrites the seeded default account's currency. Used by [AccountSeeder]
     * to sync the placeholder 'USD' from the v5→v6 migration to the user's
     * SettingsRepository.homeCurrency on first DB open.
     */
    open suspend fun syncDefaultCurrency(code: String) {
        dao.updateDefaultCurrency(code)
    }
}

/** Account row joined with its computed balance. */
data class AccountWithBalance(
    val account: AccountEntity,
    val balanceMinor: Long,
)