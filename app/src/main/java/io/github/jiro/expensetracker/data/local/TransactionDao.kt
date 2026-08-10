package io.github.jiro.expensetracker.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {

    @Transaction
    @Query("SELECT * FROM transactions ORDER BY occurredAtEpochMillis DESC")
    fun observeAllWithCategory(): Flow<List<TransactionWithCategory>>

    @Transaction
    @Query(
        "SELECT * FROM transactions " +
            "WHERE occurredAtEpochMillis >= :startMs AND occurredAtEpochMillis < :endMs " +
            "ORDER BY occurredAtEpochMillis DESC"
    )
    fun observeInRangeWithCategory(
        startMs: Long,
        endMs: Long,
    ): Flow<List<TransactionWithCategory>>

    @Query("SELECT * FROM transactions WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): TransactionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(transaction: TransactionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restore(transaction: TransactionEntity): Long

    @Update
    suspend fun update(transaction: TransactionEntity)

    @Delete
    suspend fun delete(transaction: TransactionEntity)

    // ---- Backup & restore helpers (Phase 1.5) ----

    @Query("SELECT * FROM transactions")
    suspend fun observeAllForExport(): List<TransactionEntity>

    @Query("DELETE FROM transactions")
    suspend fun deleteAll(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(transactions: List<TransactionEntity>): List<Long>

    @Query("UPDATE transactions SET receiptPath = NULL WHERE receiptPath IN (:paths)")
    suspend fun clearReceiptPathsFor(paths: List<String>)

    // ---- Recurring transactions (Phase 2.1) ----

    /**
     * Returns every row that is a "parent" of a recurring series and whose
     * next occurrence is at or before [nowMs]. These are the rows the
     * [io.github.jiro.expensetracker.work.RecurringTransactionWorker] should
     * materialise. A parent is identified by having a non-null
     * [TransactionEntity.recurrenceNextAt]; materialised instances leave
     * that column null.
     */
    @Query(
        "SELECT * FROM transactions " +
            "WHERE recurrenceNextAt IS NOT NULL " +
            "AND recurrenceNextAt <= :nowMs " +
            "ORDER BY recurrenceNextAt ASC"
    )
    suspend fun dueRecurringParents(nowMs: Long): List<TransactionEntity>

    /**
     * Materialised instances + the parent (one SELECT per id, so the
     * materialisation worker can re-render the list after each cycle).
     */
    @Transaction
    @Query("SELECT * FROM transactions WHERE recurringGroupId = :groupId")
    fun observeByRecurringGroup(groupId: String): Flow<List<TransactionWithCategory>>

    @Query("SELECT COUNT(*) FROM transactions WHERE recurringGroupId = :groupId")
    suspend fun countByRecurringGroup(groupId: String): Int

    // ---- Accounts (Phase 2.16) ----

    @Query("SELECT COUNT(*) FROM transactions WHERE accountId = :accountId")
    suspend fun countForAccount(accountId: Long): Int

    @Query(
        "SELECT COUNT(*) FROM transactions " +
            "WHERE accountId = :id OR transferAccountId = :id"
    )
    suspend fun countReferencingAccount(id: Long): Int

    @Transaction
    @Query("SELECT * FROM transactions WHERE accountId = :accountId ORDER BY occurredAtEpochMillis DESC")
    fun observeByAccount(accountId: Long): Flow<List<TransactionWithCategory>>

    /**
     * Incoming transfers where [accountId] is the destination (transferAccountId).
     * Used by the account-detail reconciliation flow so transfers that ADD to
     * this account's balance show up alongside regular outflows. Pairs with
     * [observeByAccount] which returns outflows.
     */
    @Transaction
    @Query("SELECT * FROM transactions WHERE transferAccountId = :accountId AND type = 'TRANSFER' ORDER BY occurredAtEpochMillis DESC")
    fun observeTransfersToAccount(accountId: Long): Flow<List<TransactionWithCategory>>
}
