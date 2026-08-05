package io.github.jiro.expensetracker.local.routes

import io.github.jiro.expensetracker.data.repository.AccountRepository
import io.ktor.server.routing.Route

fun Route.accountsRoutes(token: String, accountRepository: AccountRepository) = Unit