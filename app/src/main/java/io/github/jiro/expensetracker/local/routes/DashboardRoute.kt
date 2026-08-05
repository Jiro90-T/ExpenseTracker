package io.github.jiro.expensetracker.local.routes

import io.github.jiro.expensetracker.data.local.MoneyFormat
import io.github.jiro.expensetracker.data.repository.TransactionRepository
import io.github.jiro.expensetracker.local.LocalServerState
import io.github.jiro.expensetracker.local.templates.TxRow
import io.github.jiro.expensetracker.local.templates.renderDashboard
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Test-only overload (no repos). Keeps [DashboardRouteTest] working without
 * standing up Room. Production code calls the overload below.
 */
fun Route.dashboardRoute(token: String) {
    get("/") {
        call.respondText(
            """<!doctype html><html><head><title>Dashboard</title>""" +
                """<script src="/static/htmx.min.js" defer></script></head>""" +
                """<body><h1>Dashboard</h1></body></html>""",
            contentType = ContentType.Text.Html,
        )
    }
}

/**
 * Production overload. Reads the first 10 transactions via the repos and
 * renders them through [renderDashboard]. Token is propagated into the
 * [LocalServerState] so the layout's nav links stay authed.
 */
fun Route.dashboardRoute(
    token: String,
    transactionRepository: TransactionRepository,
) {
    get("/") {
        val firstTen = transactionRepository.observeAll().first().take(10)
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val rows = firstTen.map { twc ->
            TxRow(
                date = fmt.format(Date(twc.transaction.occurredAtEpochMillis)),
                title = twc.transaction.title,
                amount = MoneyFormat.formatForDisplay(twc.transaction.amountMinor) +
                    " " + twc.transaction.currencyCode,
            )
        }
        call.respondText(
            renderDashboard(LocalServerState(token = token), rows),
            contentType = ContentType.Text.Html,
        )
    }
}
