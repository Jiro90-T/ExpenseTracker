package io.github.jiro.expensetracker.data.repository

import io.github.jiro.expensetracker.data.local.CategoryDao
import io.github.jiro.expensetracker.data.local.CategoryEntity
import io.github.jiro.expensetracker.domain.model.TransactionType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
open class CategoryRepository @Inject constructor(
    private val dao: CategoryDao,
) {
    fun observeAll(): Flow<List<CategoryEntity>> = dao.observeAll()

    open fun observeByType(type: TransactionType): Flow<List<CategoryEntity>> =
        dao.observeByType(type.name)

    suspend fun count(): Int = dao.count()

    suspend fun findById(id: Long): CategoryEntity? = dao.findById(id)

    suspend fun insertAll(categories: List<CategoryEntity>): List<Long> = dao.insertAll(categories)

    suspend fun add(name: String, type: TransactionType): Long =
        dao.insert(CategoryEntity(name = name, type = type.name))

    suspend fun update(category: CategoryEntity) = dao.update(category)

    /** Hard-delete only succeeds for non-built-in categories. */
    suspend fun deleteById(id: Long): Boolean = dao.deleteById(id) > 0
}
