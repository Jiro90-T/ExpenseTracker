package io.github.jiro.expensetracker.local.routes

import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.dashboardRoute(token: String) {
    get("/") {
        call.respondText("dashboard placeholder")
    }
}
