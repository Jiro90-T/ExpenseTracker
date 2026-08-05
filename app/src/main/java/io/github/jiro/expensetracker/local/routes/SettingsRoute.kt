package io.github.jiro.expensetracker.local.routes

import io.github.jiro.expensetracker.preferences.SettingsRepository
import io.ktor.server.routing.Route

fun Route.settingsRoute(token: String, settingsRepository: SettingsRepository) = Unit