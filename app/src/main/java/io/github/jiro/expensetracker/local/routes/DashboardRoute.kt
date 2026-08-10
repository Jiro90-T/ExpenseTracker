package io.github.jiro.expensetracker.local.routes

import io.github.jiro.expensetracker.data.local.MoneyFormat
import io.github.jiro.expensetracker.data.repository.AccountRepository
import io.github.jiro.expensetracker.data.repository.TransactionRepository
import io.github.jiro.expensetracker.local.LocalServerState
import io.github.jiro.expensetracker.local.templates.AccountTile
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
    accountRepository: AccountRepository,
) {
    get("/") {
        val accountsById = accountRepository.observeActive().first().associateBy { it.id }
        val firstTen = transactionRepository.observeAll().first().take(10)
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val rows = firstTen.map { twc ->
            val t = twc.transaction
            val amount = MoneyFormat.minorToDisplay(t.amountMinor, t.currencyCode) +
                " " + t.currencyCode
            TxRow(
                date = fmt.format(Date(t.occurredAtEpochMillis)),
                title = t.title,
                amount = amount,
                type = t.type,
                account = accountsById[t.accountId]?.name,
            )
        }
        val balances = accountRepository.observeBalances().first().associateBy { it.accountId }
        val accounts = accountRepository.observeActive().first()
        var netWorthMinor = 0L
        val netWorthCurrency = accounts.firstOrNull()?.currencyCode ?: "MYR"
        val tiles = accounts.map { acc ->
            val balMinor = balances[acc.id]?.balanceMinor ?: acc.openingBalanceMinor
            netWorthMinor += balMinor
            AccountTile(
                id = acc.id,
                icon = acc.icon,
                name = acc.name,
                currency = acc.currencyCode,
                balance = MoneyFormat.minorToDisplay(balMinor, acc.currencyCode) +
                    " " + acc.currencyCode,
                isNegative = balMinor < 0L,
                type = acc.type,
            )
        }
        val netWorthDisplay = MoneyFormat.minorToDisplay(netWorthMinor, netWorthCurrency) +
            " " + netWorthCurrency
        call.respondText(
            renderDashboard(
                LocalServerState(token = token),
                rows,
                tiles,
                netWorthDisplay,
                netWorthNegative = netWorthMinor < 0L,
            ),
            contentType = ContentType.Text.Html,
        )
    }
}
