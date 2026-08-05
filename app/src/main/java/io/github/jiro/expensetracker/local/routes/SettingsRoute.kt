package io.github.jiro.expensetracker.local.routes

import io.github.jiro.expensetracker.preferences.SettingsRepository
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.settingsRoute(token: String, settingsRepository: SettingsRepository) {
    get("/settings") {
        call.respondText(
            """<!doctype html><html><body><h1>Settings</h1>""" +
                """<p>Home currency: <strong>USD</strong></p>""" +
                """<h2>FX rates</h2>""" +
                """<p>No rates stored.</p>""" +
                """<p><small>Edit rates on the phone.</small></p>""" +
                """</body></html>""",
            contentType = ContentType.Text.Html,
        )
    }
}