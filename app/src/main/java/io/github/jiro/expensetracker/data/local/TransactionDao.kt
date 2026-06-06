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

    @Update
    suspend fun update(transaction: TransactionEntity)

    @Delete
    suspend fun delete(transaction: TransactionEntity)
}
