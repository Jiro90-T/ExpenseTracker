package io.github.jiro.expensetracker.local.routes

import io.github.jiro.expensetracker.data.repository.CategoryRepository
import io.github.jiro.expensetracker.data.repository.TransactionRepository
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.categoriesRoutes(
    token: String,
    categoryRepository: CategoryRepository,
    transactionRepository: TransactionRepository,
) {
    get("/categories") {
        call.respondText(
            """<!doctype html><html><body><hgroup><h1>Categories</h1>""" +
                """<p><a href="/categories/new?t=$token" role="button">New</a></p></hgroup>""" +
                """<p>No categories.</p></body></html>""",
            contentType = ContentType.Text.Html,
        )
    }
    get("/categories/new") {
        call.respondText(
            """<!doctype html><html><body><h1>New category</h1><form>""" +
                """<label>Name<input name="name"></label>""" +
                """<fieldset><legend>Type</legend>""" +
                """<label><input type="radio" name="type" value="EXPENSE" checked> EXPENSE</label>""" +
                """<label><input type="radio" name="type" value="INCOME"> INCOME</label>""" +
                """</fieldset></form></body></html>""",
            contentType = ContentType.Text.Html,
        )
    }
}