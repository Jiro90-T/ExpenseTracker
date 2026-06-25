package io.github.jiro.expensetracker.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {

    @Query("SELECT * FROM accounts WHERE archived = 0 ORDER BY sortOrder, name")
    fun observeActive(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE archived = 0 ORDER BY sortOrder, name")
    suspend fun listActiveOnce(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): AccountEntity?

    @Query("SELECT * FROM accounts WHERE id = 1 LIMIT 1")
    suspend fun findDefault(): AccountEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(account: AccountEntity): Long

    @Update
    suspend fun update(account: AccountEntity): Int

    @Query("UPDATE accounts SET currencyCode = :code WHERE id = 1")
    suspend fun updateDefaultCurrency(code: String): Int

    @Query("SELECT COUNT(*) FROM accounts WHERE archived = 0")
    suspend fun countActive(): Int

    /**
     * Returns a row per non-archived account with its computed balance.
     * Mirrors the spec formula:
     *   opening + SUM(INCOME/EXPENSE/ADJUSTMENT on accountId)
     *         - SUM(TRANSFER where accountId = source)
     *         + SUM(TRANSFER where transferAccountId = destination)
     *
     * Rendered by the AccountsListViewModel as a map `accountId -> balanceMinor`.
     */
    @Query("""
        SELECT a.id AS accountId,
               a.openingBalanceMinor
               + COALESCE((SELECT SUM(amountMinor) FROM transactions
                           WHERE accountId = a.id AND type IN ('INCOME','EXPENSE','ADJUSTMENT')), 0)
               - COALESCE((SELECT SUM(amountMinor) FROM transactions
                           WHERE accountId = a.id AND type = 'TRANSFER'), 0)
               + COALESCE((SELECT SUM(amountMinor) FROM transactions
                           WHERE transferAccountId = a.id AND type = 'TRANSFER'), 0)
               AS balanceMinor
        FROM accounts a
        WHERE a.archived = 0
    """)
    fun observeBalances(): Flow<List<AccountBalanceRow>>
}

/** Projection returned by [AccountDao.observeBalances]. */
data class AccountBalanceRow(
    val accountId: Long,
    val balanceMinor: Long,
)
