package io.github.jiro.expensetracker.local.routes

import io.github.jiro.expensetracker.data.local.MoneyFormat
import io.github.jiro.expensetracker.data.local.TransactionEntity
import io.github.jiro.expensetracker.data.repository.AccountRepository
import io.github.jiro.expensetracker.data.repository.CategoryRepository
import io.github.jiro.expensetracker.data.repository.TransactionRepository
import io.github.jiro.expensetracker.local.LocalServerState
import io.github.jiro.expensetracker.local.templates.TxForm
import io.github.jiro.expensetracker.local.templates.TxListRow
import io.github.jiro.expensetracker.local.templates.renderTransactionsForm
import io.github.jiro.expensetracker.local.templates.renderTransactionsList
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

private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
    isLenient = false
}

fun Route.transactionsRoutes(
    token: String,
    transactionRepository: TransactionRepository,
    accountRepository: AccountRepository,
    categoryRepository: CategoryRepository,
) {
    val state = { LocalServerState(token = token) }

    get("/transactions") {
        val accounts = accountRepository.listActiveOnce().associateBy { it.id }
        val cats = categoryRepository.observeAll().first().associateBy { it.id }
        val txs = transactionRepository.observeAll().first()
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val rows = txs.map { twc ->
            val t = twc.transaction
            val amount = MoneyFormat.minorToDisplay(t.amountMinor, t.currencyCode) +
                " " + t.currencyCode
            TxListRow(
                id = t.id,
                date = fmt.format(Date(t.occurredAtEpochMillis)),
                title = t.title,
                category = cats[t.categoryId]?.name ?: "—",
                account = accounts[t.accountId]?.name ?: "—",
                amount = amount,
                type = t.type,
            )
        }
        call.respondText(
            renderTransactionsList(state(), token, rows),
            contentType = ContentType.Text.Html,
        )
    }

    get("/transactions/new") {
        val presetAccountId = call.parameters["accountId"]?.toLongOrNull()
        val backHref = if (presetAccountId != null) {
            "/accounts/${presetAccountId}"
        } else {
            "/transactions"
        }
        val presetAccount = presetAccountId?.let { accountRepository.findById(it) }
        val form = TxForm(
            categories = categoryRepository.observeAll().first()
                .map { cat -> cat.id.toString() to cat.name },
            accounts = accountRepository.listActiveOnce()
                .map { acc -> acc.id to acc.name },
            accountId = presetAccountId,
            presetAccountLabel = presetAccount?.let { "${it.icon} ${it.name}" },
            occurredAt = DATE_FMT.format(Date()),
            currencyCode = presetAccount?.currencyCode ?: "USD",
            backHref = backHref,
        )
        call.respondText(
            renderTransactionsForm(state(), token, form),
            contentType = ContentType.Text.Html,
        )
    }

    post("/transactions/new") {
        val params = call.receiveParameters()
        val error = validateTxForm(params)
        if (error != null) {
            val form = paramsToForm(params, error, backHrefFor(params))
            call.respondText(
                renderTransactionsForm(state(), token, form),
                status = HttpStatusCode.BadRequest,
                contentType = ContentType.Text.Html,
            )
            return@post
        }
        val now = System.currentTimeMillis()
        val occurred = DATE_FMT.parse(params["occurredAt"]!!)!!.time
        val amountMinor = MoneyFormat.parseAmountToMinor(params["amount"]!!)
            ?: run {
                call.respondText(
                    renderTransactionsForm(state(), token,
                        paramsToForm(params, "Amount must be a number", backHrefFor(params))),
                    status = HttpStatusCode.BadRequest,
                    contentType = ContentType.Text.Html,
                )
                return@post
            }
        val type = params["type"] ?: "EXPENSE"
        val accountId = params["accountId"]!!.toLong()
        val transferAccountId = params["transferAccountId"]?.toLongOrNull()
        val tx = TransactionEntity(
            title = params["title"]!!,
            amountMinor = amountMinor,
            currencyCode = params["currencyCode"]!!,
            type = type,
            categoryId = params["categoryId"]?.toLongOrNull(),
            accountId = accountId,
            transferAccountId = if (type == "TRANSFER") transferAccountId else null,
            occurredAtEpochMillis = occurred,
            note = params["note"],
            createdAtEpochMillis = now,
        )
        transactionRepository.add(tx)
        call.respondRedirect303(redirectAfterSave(params, token))
    }

    get("/transactions/{id}/edit") {
        val id = call.parameters["id"]!!.toLong()
        val tx = transactionRepository.findById(id)
        if (tx == null) {
            call.respondText("Not found", status = HttpStatusCode.NotFound)
            return@get
        }
        val form = TxForm(
            id = id,
            title = tx.title,
            amount = MoneyFormat.formatAmountForEdit(tx.amountMinor),
            currencyCode = tx.currencyCode,
            occurredAt = DATE_FMT.format(Date(tx.occurredAtEpochMillis)),
            note = tx.note.orEmpty(),
            type = tx.type,
            categoryId = tx.categoryId,
            accountId = tx.accountId,
            transferAccountId = tx.transferAccountId,
            categories = categoryRepository.observeAll().first()
                .map { cat -> cat.id.toString() to cat.name },
            accounts = accountRepository.listActiveOnce()
                .map { acc -> acc.id to acc.name },
            backHref = "/accounts/${tx.accountId}",
        )
        call.respondText(
            renderTransactionsForm(state(), token, form),
            contentType = ContentType.Text.Html,
        )
    }

    post("/transactions/{id}/edit") {
        val id = call.parameters["id"]!!.toLong()
        val existing = transactionRepository.findById(id)
        if (existing == null) {
            call.respondText("Not found", status = HttpStatusCode.NotFound)
            return@post
        }
        val params = call.receiveParameters()
        val error = validateTxForm(params)
        if (error != null) {
            val form = paramsToForm(params, error, "/accounts/${existing.accountId}").copy(id = id)
            call.respondText(
                renderTransactionsForm(state(), token, form),
                status = HttpStatusCode.BadRequest,
                contentType = ContentType.Text.Html,
            )
            return@post
        }
        val amountMinor = MoneyFormat.parseAmountToMinor(params["amount"]!!)
            ?: run {
                call.respondText(
                    renderTransactionsForm(state(), token,
                        paramsToForm(params, "Amount must be a number", "/accounts/${existing.accountId}").copy(id = id)),
                    status = HttpStatusCode.BadRequest,
                    contentType = ContentType.Text.Html,
                )
                return@post
            }
        val updatedType = params["type"] ?: "EXPENSE"
        val updatedAccountId = params["accountId"]!!.toLong()
        val updatedTransferAccountId = params["transferAccountId"]?.toLongOrNull()
        val updated = existing.copy(
            title = params["title"]!!,
            amountMinor = amountMinor,
            currencyCode = params["currencyCode"]!!,
            type = updatedType,
            categoryId = params["categoryId"]?.toLongOrNull(),
            accountId = updatedAccountId,
            transferAccountId = if (updatedType == "TRANSFER") updatedTransferAccountId else null,
            occurredAtEpochMillis = DATE_FMT.parse(params["occurredAt"]!!)!!.time,
            note = params["note"],
        )
        transactionRepository.update(updated)
        call.respondRedirect303("/accounts/${updated.accountId}?t=$token")
    }

    post("/transactions/{id}/delete") {
        val id = call.parameters["id"]!!.toLong()
        val tx = transactionRepository.findById(id)
        val accountId = tx?.accountId
        if (tx != null) transactionRepository.delete(tx)
        val target = if (accountId != null) "/accounts/$accountId?t=$token" else "/transactions?t=$token"
        call.respondRedirect303(target)
    }
}

private fun validateTxForm(params: Parameters): String? {
    val title = params["title"].orEmpty()
    if (title.isBlank()) return "Title is required"
    val amountStr = params["amount"].orEmpty()
    val amount = MoneyFormat.parseAmountToMinor(amountStr)
        ?: return "Amount must be a number (e.g. 12.50)"
    if (amount <= 0) return "Amount must be positive"
    val code = params["currencyCode"].orEmpty()
    if (code.length != 3) return "Currency code must be 3 letters"
    val date = params["occurredAt"].orEmpty()
    if (date.isBlank()) return "Date is required"
    try { DATE_FMT.parse(date) } catch (e: Exception) { return "Date must be yyyy-MM-dd" }
    val type = params["type"] ?: "EXPENSE"
    if (type == "TRANSFER") {
        val accountId = params["accountId"]?.toLongOrNull()
            ?: return "Account is required"
        val transferAccountId = params["transferAccountId"]?.toLongOrNull()
            ?: return "Transfer to account is required for transfers"
        if (transferAccountId == accountId) {
            return "Transfer source and destination must be different accounts"
        }
    }
    return null
}

private fun paramsToForm(params: Parameters, error: String, backHref: String): TxForm =
    TxForm(
        title = params["title"].orEmpty(),
        amount = params["amount"].orEmpty(),
        currencyCode = params["currencyCode"].orEmpty(),
        occurredAt = params["occurredAt"].orEmpty(),
        note = params["note"].orEmpty(),
        type = params["type"] ?: "EXPENSE",
        categoryId = params["categoryId"]?.toLongOrNull(),
        accountId = params["accountId"]?.toLongOrNull(),
        transferAccountId = params["transferAccountId"]?.toLongOrNull(),
        backHref = backHref,
        error = error,
    )

private fun backHrefFor(params: Parameters): String {
    val accountId = params["accountId"]?.toLongOrNull()
    return if (accountId != null) "/accounts/$accountId" else "/transactions"
}

private fun redirectAfterSave(params: Parameters, token: String): String {
    val accountId = params["accountId"]?.toLongOrNull()
    return if (accountId != null) "/accounts/$accountId?t=$token" else "/transactions?t=$token"
}
