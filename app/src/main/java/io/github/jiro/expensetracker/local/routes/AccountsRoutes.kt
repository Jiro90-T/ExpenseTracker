package io.github.jiro.expensetracker.local.routes

import android.graphics.Color
import io.github.jiro.expensetracker.data.local.AccountEntity
import io.github.jiro.expensetracker.data.local.MoneyFormat
import io.github.jiro.expensetracker.data.local.TransactionEntity
import io.github.jiro.expensetracker.data.local.TransactionWithCategory
import io.github.jiro.expensetracker.data.repository.AccountRepository
import io.github.jiro.expensetracker.data.repository.CategoryRepository
import io.github.jiro.expensetracker.data.repository.TransactionRepository
import io.github.jiro.expensetracker.local.LocalServerState
import io.github.jiro.expensetracker.local.templates.AccountDetailTx
import io.github.jiro.expensetracker.local.templates.AccountDetailView
import io.github.jiro.expensetracker.local.templates.AccountForm
import io.github.jiro.expensetracker.local.templates.AccountRow
import io.github.jiro.expensetracker.local.templates.renderAccountDetail
import io.github.jiro.expensetracker.local.templates.renderAccountsForm
import io.github.jiro.expensetracker.local.templates.renderAccountsList
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.call
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }

fun Route.accountsRoutes(
    token: String,
    accountRepository: AccountRepository,
    transactionRepository: TransactionRepository,
    categoryRepository: CategoryRepository,
) {
    val state = { LocalServerState(token = token) }

    get("/accounts") {
        val balances = accountRepository.observeBalances().first().associateBy { it.accountId }
        val rows = accountRepository.observeActive().first()
            .map { acc ->
                val balanceMinor = balances[acc.id]?.balanceMinor ?: 0L
                AccountRow(
                    id = acc.id,
                    name = acc.name,
                    type = acc.type,
                    currency = acc.currencyCode,
                    balance = MoneyFormat.minorToDisplay(balanceMinor, acc.currencyCode) +
                        " " + acc.currencyCode,
                )
            }
        call.respondText(
            renderAccountsList(state(), token, rows),
            contentType = ContentType.Text.Html,
        )
    }

    get("/accounts/{id}") {
        val id = call.parameters["id"]!!.toLong()
        val acc = accountRepository.findById(id)
        if (acc == null) {
            call.respondText("Not found", status = HttpStatusCode.NotFound)
            return@get
        }
        val balanceMinor = accountRepository.observeBalances().first()
            .firstOrNull { it.accountId == id }?.balanceMinor ?: 0L
        val cats = categoryRepository.observeAll().first().associateBy { it.id }
        // Merge outflows (accountId == this) with inflows (transferAccountId == this),
        // sort ASCENDING so the running balance accumulates in chronological order
        // (opening → ... → final). The list is reversed for display so the newest
        // row sits at the top of the table — at which point its running balance is
        // the final balance, matching the header card.
        val outflows = transactionRepository.observeByAccount(id).first()
        val inflows = transactionRepository.observeTransfersToAccount(id).first()
        val merged = (outflows + inflows).distinctBy { it.transaction.id }
            .sortedBy { it.transaction.occurredAtEpochMillis }
        var running = acc.openingBalanceMinor
        data class Row(val twc: TransactionWithCategory, val signed: Long, val running: Long)
        val rowsAsc = merged.map { twc ->
            val signed = signedDeltaForAccount(twc.transaction, id)
            running += signed
            Row(twc, signed, running)
        }
        val txs = rowsAsc.reversed().map { row ->
            val t = row.twc.transaction
            AccountDetailTx(
                id = t.id,
                date = DATE_FMT.format(Date(t.occurredAtEpochMillis)),
                title = t.title,
                category = cats[t.categoryId]?.name ?: "—",
                amount = MoneyFormat.minorToDisplay(row.signed, t.currencyCode) +
                    " " + t.currencyCode,
                type = t.type,
                amountNegative = row.signed < 0L,
                runningBalance = MoneyFormat.minorToDisplay(row.running, t.currencyCode) +
                    " " + t.currencyCode,
                runningBalanceNegative = row.running < 0L,
            )
        }
        val view = AccountDetailView(
            id = acc.id,
            name = acc.name,
            type = acc.type,
            currency = acc.currencyCode,
            openingBalance = MoneyFormat.minorToDisplay(acc.openingBalanceMinor, acc.currencyCode) +
                " " + acc.currencyCode,
            runningBalance = MoneyFormat.minorToDisplay(balanceMinor, acc.currencyCode) +
                " " + acc.currencyCode,
            icon = acc.icon,
            transactions = txs,
            categories = categoryRepository.observeAll().first()
                .map { cat -> cat.id.toString() to cat.name },
            today = DATE_FMT.format(Date()),
        )
        call.respondText(
            renderAccountDetail(state(), token, view),
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
        val openingBalanceMinor = MoneyFormat.parseSignedAmountToMinor(
            params["openingBalanceMinor"] ?: "0",
        ) ?: 0L
        val account = AccountEntity(
            name = params["name"]!!,
            type = params["type"] ?: "CASH",
            icon = params["icon"] ?: "💵",
            color = runCatching { Color.parseColor(params["color"] ?: "#888888") }
                .getOrDefault(0xFF888888.toInt()),
            currencyCode = params["currencyCode"]!!,
            openingBalanceMinor = openingBalanceMinor,
            createdAtEpochMillis = now,
        )
        accountRepository.add(account)
        call.respondRedirect303("/accounts?t=$token")
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
            openingBalanceMinor = MoneyFormat.formatAmountForEdit(acc.openingBalanceMinor),
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
        val newOpening = MoneyFormat.parseSignedAmountToMinor(
            params["openingBalanceMinor"] ?: MoneyFormat.formatAmountForEdit(acc.openingBalanceMinor),
        ) ?: acc.openingBalanceMinor
        val updated = acc.copy(
            name = params["name"] ?: acc.name,
            type = params["type"] ?: acc.type,
            icon = params["icon"] ?: acc.icon,
            color = runCatching { Color.parseColor(params["color"] ?: "#888888") }
                .getOrDefault(acc.color),
            currencyCode = params["currencyCode"] ?: acc.currencyCode,
            openingBalanceMinor = newOpening,
        )
        accountRepository.update(updated)
        call.respondRedirect303("/accounts/${updated.id}?t=$token")
    }

    post("/accounts/{id}/delete") {
        val id = call.parameters["id"]!!.toLong()
        val acc = accountRepository.findById(id)
        if (acc != null) {
            val holdings = accountRepository.countHoldings(id)
            if (holdings > 0) {
                call.respondText(
                    "<p>Cannot delete: account has $holdings holdings.</p>" +
                        "<p><a href=\"/accounts/${id}?t=$token\">Back to account</a></p>",
                    status = HttpStatusCode.Conflict,
                    contentType = ContentType.Text.Html,
                )
                return@post
            }
            accountRepository.delete(id)
        }
        call.respondRedirect303("/accounts?t=$token")
    }
}

private fun validate(params: Parameters): String? {
    val name = params["name"].orEmpty()
    if (name.isBlank()) return "Name is required"
    val code = params["currencyCode"].orEmpty()
    if (code.length != 3) return "Currency code must be 3 letters"
    val opening = params["openingBalanceMinor"].orEmpty()
    if (opening.isNotBlank()) {
        MoneyFormat.parseSignedAmountToMinor(opening)
            ?: return "Opening balance must be a number (e.g. 100.00 or -50.00)"
    }
    return null
}

/**
 * Signed change in minor units that [tx] applies to the balance of [accountId].
 * Mirrors the SQL in AccountDao.observeBalances: amountMinor is stored positive,
 * EXPENSE subtracts, INCOME/ADJUSTMENT add, and TRANSFER subtracts when this
 * account is the source (accountId) or adds when it's the destination
 * (transferAccountId).
 */
private fun signedDeltaForAccount(tx: TransactionEntity, accountId: Long): Long = when (tx.type) {
    "EXPENSE" -> -tx.amountMinor
    "INCOME" -> tx.amountMinor
    "ADJUSTMENT" -> tx.amountMinor
    "TRANSFER" -> if (tx.accountId == accountId) -tx.amountMinor else tx.amountMinor
    else -> 0L
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
