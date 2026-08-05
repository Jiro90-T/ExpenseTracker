package io.github.jiro.expensetracker.local.routes

import io.github.jiro.expensetracker.data.repository.CategoryRepository
import io.github.jiro.expensetracker.data.repository.TransactionRepository
import io.ktor.server.routing.Route

fun Route.categoriesRoutes(
    token: String,
    categoryRepository: CategoryRepository,
    transactionRepository: TransactionRepository,
) = Unit