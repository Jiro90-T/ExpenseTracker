package io.github.jiro.expensetracker.local.routes

import io.github.jiro.expensetracker.data.repository.AccountRepository
import io.github.jiro.expensetracker.data.repository.CategoryRepository
import io.github.jiro.expensetracker.data.repository.TransactionRepository
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.transactionsRoutes(
    token: String,
    transactionRepository: TransactionRepository?,
    accountRepository: AccountRepository?,
    categoryRepository: CategoryRepository?,
) {
    get("/transactions") {
        call.respondText(
            """<!doctype html><html><body><hgroup><h1>Transactions</h1>""" +
                """<p><a href="/transactions/new?t=$token" role="button">New</a></p></hgroup>""" +
                """<p>No transactions.</p></body></html>""",
        )
    }
    get("/transactions/new") {
        call.respondText(
            """<!doctype html><html><body><h1>New transaction</h1><form>""" +
                """<label>Title<input name="title"></label>""" +
                """<label>Amount<input name="amount" type="number"></label>""" +
                """<label>Currency<input name="currencyCode"></label>""" +
                """</form></body></html>""",
        )
    }
}
