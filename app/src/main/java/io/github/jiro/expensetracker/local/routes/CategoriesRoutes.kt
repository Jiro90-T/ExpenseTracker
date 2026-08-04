package io.github.jiro.expensetracker.local.routes

import io.github.jiro.expensetracker.data.repository.CategoryRepository
import io.github.jiro.expensetracker.data.repository.TransactionRepository

fun categoriesRoutes(
    token: String,
    categoryRepository: CategoryRepository,
    transactionRepository: TransactionRepository,
) = Unit
