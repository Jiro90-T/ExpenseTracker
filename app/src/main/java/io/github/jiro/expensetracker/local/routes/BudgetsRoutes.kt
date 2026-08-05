package io.github.jiro.expensetracker.local.routes

import io.github.jiro.expensetracker.data.repository.BudgetRepository
import io.github.jiro.expensetracker.data.repository.CategoryRepository
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.budgetsRoutes(
    token: String,
    budgetRepository: BudgetRepository,
    categoryRepository: CategoryRepository,
) {
    get("/budgets") {
        call.respondText(
            """<!doctype html><html><body><hgroup><h1>Budgets</h1>""" +
                """<p><a href="/budgets/new?t=$token" role="button">New</a></p></hgroup>""" +
                """<p>No budgets set for this month.</p></body></html>""",
            contentType = ContentType.Text.Html,
        )
    }
    get("/budgets/new") {
        call.respondText(
            """<!doctype html><html><body><h1>New budget</h1><form>""" +
                """<label>Category<select name="categoryId"></select></label>""" +
                """<label>Month<input name="monthStart" type="date"></label>""" +
                """<label>Amount<input name="amount" type="number"></label>""" +
                """</form></body></html>""",
            contentType = ContentType.Text.Html,
        )
    }
}