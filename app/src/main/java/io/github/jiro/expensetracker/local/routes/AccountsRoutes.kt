package io.github.jiro.expensetracker.local.routes

import android.graphics.Color
import io.github.jiro.expensetracker.data.local.AccountEntity
import io.github.jiro.expensetracker.data.repository.AccountRepository
import io.github.jiro.expensetracker.local.LocalServerState
import io.github.jiro.expensetracker.local.templates.AccountForm
import io.github.jiro.expensetracker.local.templates.AccountRow
import io.github.jiro.expensetracker.local.templates.renderAccountsForm
import io.github.jiro.expensetracker.local.templates.renderAccountsList
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.call
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.flow.first

fun Route.accountsRoutes(token: String, accountRepository: AccountRepository) {
    val state = { LocalServerState(token = token) }

    get("/accounts") {
        val rows = accountRepository.observeActive().first()
            .map { AccountRow(it.id, it.name, it.type, it.currencyCode) }
        call.respondText(
            renderAccountsList(state(), token, rows),
            contentType = ContentType.Text.Html,
        )
    }

    get("/accounts/new") {
        call.respondText(
            renderAccountsForm(state(), token, AccountForm()),
            contentType = ContentType.Text.Html,
        )
    }

    post("/accounts/new") {
        val params = call.receiveParameters()
        val err = validate(params)
        if (err != null) {
            call.respondText(
                renderAccountsForm(state(), token, paramsToForm(params, err)),
                status = HttpStatusCode.BadRequest,
                contentType = ContentType.Text.Html,
            )
            return@post
        }
        val now = System.currentTimeMillis()
        val account = AccountEntity(
            name = params["name"]!!,
            type = params["type"] ?: "CASH",
            icon = params["icon"] ?: "💵",
            color = runCatching { Color.parseColor(params["color"] ?: "#888888") }
                .getOrDefault(0xFF888888.toInt()),
            currencyCode = params["currencyCode"]!!,
            openingBalanceMinor = (params["openingBalanceMinor"] ?: "0").toLong(),
            createdAtEpochMillis = now,
        )
        accountRepository.add(account)
        call.respondRedirect("/accounts?t=$token")
    }

    get("/accounts/{id}/edit") {
        val id = call.parameters["id"]!!.toLong()
        val acc = accountRepository.findById(id)
        if (acc == null) {
            call.respondText("Not found", status = HttpStatusCode.NotFound)
            return@get
        }
        val form = AccountForm(
            id = id,
            name = acc.name,
            type = acc.type,
            currencyCode = acc.currencyCode,
            openingBalanceMinor = acc.openingBalanceMinor.toString(),
            icon = acc.icon,
            color = String.format("#%06X", 0xFFFFFF and acc.color),
        )
        call.respondText(
            renderAccountsForm(state(), token, form),
            contentType = ContentType.Text.Html,
        )
    }

    post("/accounts/{id}/edit") {
        val id = call.parameters["id"]!!.toLong()
        val acc = accountRepository.findById(id)
        if (acc == null) {
            call.respondText("Not found", status = HttpStatusCode.NotFound)
            return@post
        }
        val params = call.receiveParameters()
        val updated = acc.copy(
            name = params["name"] ?: acc.name,
            type = params["type"] ?: acc.type,
            icon = params["icon"] ?: acc.icon,
            color = runCatching { Color.parseColor(params["color"] ?: "#888888") }
                .getOrDefault(acc.color),
            currencyCode = params["currencyCode"] ?: acc.currencyCode,
            openingBalanceMinor = (params["openingBalanceMinor"] ?: acc.openingBalanceMinor.toString()).toLong(),
        )
        accountRepository.update(updated)
        call.respondRedirect("/accounts?t=$token")
    }

    post("/accounts/{id}/delete") {
        val id = call.parameters["id"]!!.toLong()
        val acc = accountRepository.findById(id)
        if (acc != null) {
            val holdings = accountRepository.countHoldings(id)
            if (holdings > 0) {
                call.respondText(
                    "<p>Cannot delete: account has $holdings holdings.</p>" +
                        "<p><a href=\"/accounts?t=$token\">Back to list</a></p>",
                    status = HttpStatusCode.Conflict,
                    contentType = ContentType.Text.Html,
                )
                return@post
            }
            accountRepository.delete(id)
        }
        call.respondRedirect("/accounts?t=$token")
    }
}

private fun validate(params: Parameters): String? {
    val name = params["name"].orEmpty()
    if (name.isBlank()) return "Name is required"
    val code = params["currencyCode"].orEmpty()
    if (code.length != 3) return "Currency code must be 3 letters"
    return null
}

private fun paramsToForm(params: Parameters, error: String): AccountForm =
    AccountForm(
        name = params["name"].orEmpty(),
        type = params["type"] ?: "CASH",
        currencyCode = params["currencyCode"].orEmpty(),
        openingBalanceMinor = params["openingBalanceMinor"] ?: "0",
        icon = params["icon"] ?: "💵",
        color = params["color"] ?: "#888888",
        error = error,
    )
