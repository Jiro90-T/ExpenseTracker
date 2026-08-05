package io.github.jiro.expensetracker.local.routes

import io.github.jiro.expensetracker.data.repository.AccountRepository
import io.github.jiro.expensetracker.data.repository.CategoryRepository
import io.github.jiro.expensetracker.data.repository.TransactionRepository
import io.ktor.server.routing.Route

fun Route.transactionsRoutes(
    token: String,
    transactionRepository: TransactionRepository,
    accountRepository: AccountRepository,
    categoryRepository: CategoryRepository,
) = Unit