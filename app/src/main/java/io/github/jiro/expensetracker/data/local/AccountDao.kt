package io.github.jiro.expensetracker.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import io.github.jiro.expensetracker.data.accountimport.AccountTypeDefaults
import io.github.jiro.expensetracker.data.accountimport.ImportStatus
import io.github.jiro.expensetracker.data.accountimport.ResolvedImportRow
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

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Query("UPDATE accounts SET currencyCode = :code WHERE id = 1")
    suspend fun updateDefaultCurrency(code: String): Int

    @Query("SELECT COUNT(*) FROM accounts WHERE archived = 0")
    suspend fun countActive(): Int

    @Query("SELECT IFNULL(MAX(sortOrder), 0) FROM accounts")
    suspend fun maxSortOrder(): Int

    /**
     * Overwrites an existing account's opening balance by case-insensitive
     * name match. Used by the CSV import apply path. No `nowEpochMs` column
     * on [AccountEntity], so the timestamp is intentionally omitted.
     */
    @Query("UPDATE accounts SET openingBalanceMinor = :balance WHERE LOWER(name) = LOWER(:name)")
    suspend fun updateOpeningBalanceByName(name: String, balance: Long): Int

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
               + COALESCE((SELECT SUM(CASE WHEN type = 'EXPENSE' THEN -amountMinor ELSE amountMinor END)
                           FROM transactions
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

    /**
     * Applies a list of resolved CSV import rows in a single Room transaction.
     *
     *   - [ImportStatus.WillCreate] inserts a new [AccountEntity] with
     *     type-derived icon/color and a unique [AccountEntity.sortOrder]
     *     continuing from the current max.
     *   - [ImportStatus.WillUpdate] overwrites the existing account's
     *     `openingBalanceMinor` via case-insensitive name match.
     *   - [ImportStatus.Rejected] rows are no-ops.
     */
    @Transaction
    suspend fun applyAccountImport(rows: List<ResolvedImportRow>, nowEpochMs: Long) {
        var nextSortOrder = maxSortOrder() + 1
        for (row in rows) {
            when (row.status) {
                ImportStatus.WillCreate -> {
                    insert(
                        AccountEntity(
                            id = 0,
                            name = row.raw.name,
                            type = row.raw.type,
                            icon = AccountTypeDefaults.iconFor(row.raw.type),
                            color = AccountTypeDefaults.colorFor(row.raw.type),
                            currencyCode = row.raw.currency,
                            openingBalanceMinor = row.raw.balanceMinor,
                            createdAtEpochMillis = nowEpochMs,
                            sortOrder = nextSortOrder++,
                        )
                    )
                }
                is ImportStatus.WillUpdate -> {
                    updateOpeningBalanceByName(row.raw.name, row.raw.balanceMinor)
                }
                is ImportStatus.Rejected -> Unit
            }
        }
    }
}

/** Projection returned by [AccountDao.observeBalances]. */
data class AccountBalanceRow(
    val accountId: Long,
    val balanceMinor: Long,
)
