package io.github.jiro.expensetracker.data.local

import io.github.jiro.expensetracker.data.repository.CategoryRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Populates the categories table with the built-in defaults on first launch.
 * Idempotent: a no-op once any categories exist.
 */
@Singleton
class CategorySeeder @Inject constructor(
    private val repository: CategoryRepository,
) {
    suspend fun seedIfEmpty() {
        if (repository.count() > 0) return
        repository.insertAll(defaults())
    }

    private fun defaults(): List<CategoryEntity> = listOf(
        // Expense
        CategoryEntity(name = "Food",          type = "EXPENSE", sortOrder = 0, isBuiltIn = true),
        CategoryEntity(name = "Transport",     type = "EXPENSE", sortOrder = 1, isBuiltIn = true),
        CategoryEntity(name = "Housing",       type = "EXPENSE", sortOrder = 2, isBuiltIn = true),
        CategoryEntity(name = "Bills",         type = "EXPENSE", sortOrder = 3, isBuiltIn = true),
        CategoryEntity(name = "Entertainment", type = "EXPENSE", sortOrder = 4, isBuiltIn = true),
        CategoryEntity(name = "Shopping",      type = "EXPENSE", sortOrder = 5, isBuiltIn = true),
        CategoryEntity(name = "Health",        type = "EXPENSE", sortOrder = 6, isBuiltIn = true),
        CategoryEntity(name = "Other",         type = "EXPENSE", sortOrder = 99, isBuiltIn = true),
        // Income
        CategoryEntity(name = "Salary",        type = "INCOME",  sortOrder = 0, isBuiltIn = true),
        CategoryEntity(name = "Freelance",     type = "INCOME",  sortOrder = 1, isBuiltIn = true),
        CategoryEntity(name = "Gift",          type = "INCOME",  sortOrder = 2, isBuiltIn = true),
        CategoryEntity(name = "Other",         type = "INCOME",  sortOrder = 99, isBuiltIn = true),
    )
}
