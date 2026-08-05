package io.github.jiro.expensetracker.local.routes

import io.github.jiro.expensetracker.data.repository.AccountRepository
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.accountsRoutes(token: String, accountRepository: AccountRepository) {
    get("/accounts") {
        call.respondText(
            """<!doctype html><html><body><hgroup><h1>Accounts</h1>""" +
                """<p><a href="/accounts/new?t=$token" role="button">New</a></p></hgroup>""" +
                """<p>No accounts.</p></body></html>""",
            contentType = ContentType.Text.Html,
        )
    }
    get("/accounts/new") {
        call.respondText(
            """<!doctype html><html><body><h1>New account</h1><form>""" +
                """<label>Name<input name="name"></label>""" +
                """<label>Type<input name="type"></label>""" +
                """<label>Currency<input name="currencyCode"></label>""" +
                """</form></body></html>""",
            contentType = ContentType.Text.Html,
        )
    }
}
