package io.github.jiro.expensetracker.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CachedQuoteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: CachedQuoteEntity)

    @Query("SELECT * FROM cached_quotes WHERE symbol = :symbol LIMIT 1")
    suspend fun findBySymbol(symbol: String): CachedQuoteEntity?

    @Query("SELECT * FROM cached_quotes WHERE symbol IN (:symbols)")
    fun observeBySymbols(symbols: List<String>): Flow<List<CachedQuoteEntity>>

    @Query("SELECT * FROM cached_quotes WHERE symbol IN (:symbols)")
    suspend fun findBySymbols(symbols: List<String>): List<CachedQuoteEntity>
}
