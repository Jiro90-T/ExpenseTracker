package io.github.jiro.expensetracker.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface InvestmentHoldingDao {

    @Insert
    suspend fun insert(row: InvestmentHoldingEntity): Long

    @Update
    suspend fun update(row: InvestmentHoldingEntity)

    @Query("DELETE FROM investment_holdings WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM investment_holdings WHERE accountId = :accountId ORDER BY symbol")
    fun observeByAccount(accountId: Long): Flow<List<InvestmentHoldingEntity>>

    @Query("SELECT * FROM investment_holdings WHERE id = :id")
    suspend fun findById(id: Long): InvestmentHoldingEntity?

    @Query("SELECT COUNT(*) FROM investment_holdings WHERE accountId = :accountId")
    suspend fun countByAccount(accountId: Long): Int
}
