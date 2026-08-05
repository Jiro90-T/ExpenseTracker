package io.github.jiro.expensetracker.local

import android.util.Log
import io.github.jiro.expensetracker.data.repository.AccountRepository
import io.github.jiro.expensetracker.data.repository.BudgetRepository
import io.github.jiro.expensetracker.data.repository.CategoryRepository
import io.github.jiro.expensetracker.data.repository.TransactionRepository
import io.github.jiro.expensetracker.local.auth.authFilter
import io.github.jiro.expensetracker.local.routes.accountsRoutes
import io.github.jiro.expensetracker.local.routes.budgetsRoutes
import io.github.jiro.expensetracker.local.routes.categoriesRoutes
import io.github.jiro.expensetracker.local.routes.dashboardRoute
import io.github.jiro.expensetracker.local.routes.settingsRoute
import io.github.jiro.expensetracker.local.routes.transactionsRoutes
import io.github.jiro.expensetracker.preferences.SettingsRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import org.slf4j.event.Level

class LocalServer(
    private val transactionRepository: TransactionRepository,
    private val accountRepository: AccountRepository,
    private val categoryRepository: CategoryRepository,
    private val budgetRepository: BudgetRepository,
    private val settingsRepository: SettingsRepository,
    private val token: String,
) {

    fun Application.module() {
        install(CallLogging) {
            level = Level.INFO
        }
        install(StatusPages) {
            exception<Throwable> { call, cause ->
                Log.e("LocalServer", "Handler failed", cause)
                call.respondText(
                    "<h1>Something went wrong</h1>",
                    status = HttpStatusCode.InternalServerError,
                )
            }
        }
        routing {
            authFilter(token)
            staticResources("/static", "static")
            dashboardRoute(token, transactionRepository)
            transactionsRoutes(token, transactionRepository, accountRepository, categoryRepository)
            accountsRoutes(token, accountRepository)
            categoriesRoutes(token, categoryRepository, transactionRepository)
            budgetsRoutes(token, budgetRepository, categoryRepository)
            settingsRoute(token, settingsRepository)
        }
    }
}
