package io.github.jiro.expensetracker.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MemberCardDao {

    @Query("SELECT * FROM member_cards ORDER BY LOWER(name) ASC, id ASC")
    fun observeAll(): Flow<List<MemberCardEntity>>

    @Query(
        "SELECT * FROM member_cards " +
            "WHERE LOWER(name) LIKE '%' || LOWER(:query) || '%' " +
            "ORDER BY LOWER(name) ASC, id ASC"
    )
    fun searchByName(query: String): Flow<List<MemberCardEntity>>

    @Query("SELECT * FROM member_cards WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): MemberCardEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: MemberCardEntity): Long

    @Update
    suspend fun update(entity: MemberCardEntity): Int

    @Query("DELETE FROM member_cards WHERE id = :id")
    suspend fun deleteById(id: Long): Int
}
