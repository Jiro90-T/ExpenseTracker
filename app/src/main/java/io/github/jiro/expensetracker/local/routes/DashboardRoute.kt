package io.github.jiro.expensetracker.local.routes

import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.dashboardRoute(token: String) {
    get("/") {
        call.respondText(
            """<!doctype html><html><head><title>Dashboard</title><script src="/static/htmx.min.js" defer></script></head><body><h1>Dashboard</h1></body></html>""",
            contentType = ContentType.Text.Html,
        )
    }
}