package io.github.jiro.expensetracker.local.routes

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
import io.ktor.server.response.respondRedirect
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
            TxListRow(
                id = twc.transaction.id,
                date = fmt.format(Date(twc.transaction.occurredAtEpochMillis)),
                title = twc.transaction.title,
                category = cats[twc.transaction.categoryId]?.name ?: "—",
                account = accounts[twc.transaction.accountId]?.name ?: "—",
                amount = "${twc.transaction.amountMinor} ${twc.transaction.currencyCode}",
            )
        }
        call.respondText(
            renderTransactionsList(state(), token, rows),
            contentType = ContentType.Text.Html,
        )
    }

    get("/transactions/new") {
        val form = TxForm(
            categories = categoryRepository.observeAll().first()
                .map { cat -> cat.id.toString() to cat.name },
            accounts = accountRepository.listActiveOnce()
                .map { acc -> acc.id to acc.name },
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
            val form = paramsToForm(params, error)
            call.respondText(
                renderTransactionsForm(state(), token, form),
                status = HttpStatusCode.BadRequest,
                contentType = ContentType.Text.Html,
            )
            return@post
        }
        val now = System.currentTimeMillis()
        val occurred = DATE_FMT.parse(params["occurredAt"]!!)!!.time
        val tx = TransactionEntity(
            title = params["title"]!!,
            amountMinor = params["amount"]!!.toLong(),
            currencyCode = params["currencyCode"]!!,
            type = params["type"] ?: "EXPENSE",
            categoryId = params["categoryId"]?.toLongOrNull(),
            accountId = params["accountId"]!!.toLong(),
            occurredAtEpochMillis = occurred,
            note = params["note"],
            createdAtEpochMillis = now,
        )
        transactionRepository.add(tx)
        call.respondRedirect("/transactions?t=$token")
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
            amount = tx.amountMinor.toString(),
            currencyCode = tx.currencyCode,
            occurredAt = DATE_FMT.format(Date(tx.occurredAtEpochMillis)),
            note = tx.note.orEmpty(),
            type = tx.type,
            categoryId = tx.categoryId,
            accountId = tx.accountId,
            categories = categoryRepository.observeAll().first()
                .map { cat -> cat.id.toString() to cat.name },
            accounts = accountRepository.listActiveOnce()
                .map { acc -> acc.id to acc.name },
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
            val form = paramsToForm(params, error).copy(id = id)
            call.respondText(
                renderTransactionsForm(state(), token, form),
                status = HttpStatusCode.BadRequest,
                contentType = ContentType.Text.Html,
            )
            return@post
        }
        val updated = existing.copy(
            title = params["title"]!!,
            amountMinor = params["amount"]!!.toLong(),
            currencyCode = params["currencyCode"]!!,
            type = params["type"] ?: "EXPENSE",
            categoryId = params["categoryId"]?.toLongOrNull(),
            accountId = params["accountId"]!!.toLong(),
            occurredAtEpochMillis = DATE_FMT.parse(params["occurredAt"]!!)!!.time,
            note = params["note"],
        )
        transactionRepository.update(updated)
        call.respondRedirect("/transactions?t=$token")
    }

    post("/transactions/{id}/delete") {
        val id = call.parameters["id"]!!.toLong()
        val tx = transactionRepository.findById(id)
        if (tx != null) transactionRepository.delete(tx)
        call.respondRedirect("/transactions?t=$token")
    }
}

private fun validateTxForm(params: Parameters): String? {
    val title = params["title"].orEmpty()
    if (title.isBlank()) return "Title is required"
    val amount = params["amount"].orEmpty().toLongOrNull()
        ?: return "Amount must be a whole number (minor units)"
    if (amount <= 0) return "Amount must be positive"
    val code = params["currencyCode"].orEmpty()
    if (code.length != 3) return "Currency code must be 3 letters"
    val date = params["occurredAt"].orEmpty()
    if (date.isBlank()) return "Date is required"
    try { DATE_FMT.parse(date) } catch (e: Exception) { return "Date must be yyyy-MM-dd" }
    return null
}

private fun paramsToForm(params: Parameters, error: String): TxForm =
    TxForm(
        title = params["title"].orEmpty(),
        amount = params["amount"].orEmpty(),
        currencyCode = params["currencyCode"].orEmpty(),
        occurredAt = params["occurredAt"].orEmpty(),
        note = params["note"].orEmpty(),
        type = params["type"] ?: "EXPENSE",
        categoryId = params["categoryId"]?.toLongOrNull(),
        accountId = params["accountId"]?.toLongOrNull(),
        error = error,
    )
