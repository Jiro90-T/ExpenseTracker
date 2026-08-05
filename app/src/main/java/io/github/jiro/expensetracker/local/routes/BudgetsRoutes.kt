package io.github.jiro.expensetracker.local.routes

import io.github.jiro.expensetracker.data.repository.BudgetRepository
import io.github.jiro.expensetracker.data.repository.CategoryRepository
import io.ktor.server.routing.Route

fun Route.budgetsRoutes(
    token: String,
    budgetRepository: BudgetRepository,
    categoryRepository: CategoryRepository,
) = Unit