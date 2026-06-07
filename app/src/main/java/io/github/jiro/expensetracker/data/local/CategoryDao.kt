package io.github.jiro.expensetracker.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {

    @Query("SELECT * FROM categories WHERE type = :type ORDER BY sortOrder, name")
    fun observeByType(type: String): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY type, sortOrder, name")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int

    @Query("SELECT * FROM categories WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): CategoryEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(categories: List<CategoryEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(category: CategoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllReplacing(categories: List<CategoryEntity>): List<Long>

    @Update
    suspend fun update(category: CategoryEntity): Int

    @Query("DELETE FROM categories WHERE id = :id AND isBuiltIn = 0")
    suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM categories WHERE isBuiltIn = 0")
    suspend fun deleteAllNonBuiltIn(): Int

    @Query("SELECT * FROM categories")
    suspend fun observeAllOnce(): List<CategoryEntity>
}
