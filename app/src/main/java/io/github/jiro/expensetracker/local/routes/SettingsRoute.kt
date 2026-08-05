package io.github.jiro.expensetracker.local.routes

import io.github.jiro.expensetracker.local.LocalServerState
import io.github.jiro.expensetracker.local.templates.renderSettingsPage
import io.github.jiro.expensetracker.preferences.SettingsRepository
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Read-only settings page. Surfaces home currency and FX rates so a PC browser
 * can sanity-check what the phone has without a separate device-side flow.
 * Edits stay on the phone — the browser only mirrors the state.
 */
fun Route.settingsRoute(token: String, settingsRepository: SettingsRepository) {
    get("/settings") {
        val rates = settingsRepository.fxRates.value.toList().sortedBy { it.first }
        call.respondText(
            renderSettingsPage(
                state = LocalServerState(token = token),
                token = token,
                homeCurrency = settingsRepository.homeCurrency.value,
                fxRates = rates,
            ),
            contentType = ContentType.Text.Html,
        )
    }
}
