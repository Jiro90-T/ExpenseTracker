package io.github.jiro.expensetracker.local.routes

import io.github.jiro.expensetracker.data.local.BudgetEntity
import io.github.jiro.expensetracker.data.repository.BudgetRepository
import io.github.jiro.expensetracker.data.repository.CategoryRepository
import io.github.jiro.expensetracker.local.LocalServerState
import io.github.jiro.expensetracker.local.templates.BudgetForm
import io.github.jiro.expensetracker.local.templates.BudgetRow
import io.github.jiro.expensetracker.local.templates.renderBudgetsForm
import io.github.jiro.expensetracker.local.templates.renderBudgetsList
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

private val MONTH_FMT = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }

fun Route.budgetsRoutes(
    token: String,
    budgetRepository: BudgetRepository,
    categoryRepository: CategoryRepository,
) {
    val state = { LocalServerState(token = token) }
    val monthStart = BudgetRepository.currentMonthStart()

    get("/budgets") {
        val categories = categoryRepository.observeAll().first()
            .associateBy { it.id }
        val rows = budgetRepository.observeByMonth(monthStart).first()
            .map { b ->
                val cat = categories[b.categoryId]?.name ?: "(deleted)"
                BudgetRow(
                    id = "${b.categoryId}_${b.monthStartEpochMs}",
                    category = cat,
                    month = MONTH_FMT.format(Date(b.monthStartEpochMs)),
                    amount = "${b.amountMinor}",
                )
            }
        call.respondText(
            renderBudgetsList(state(), token, rows),
            contentType = ContentType.Text.Html,
        )
    }

    get("/budgets/new") {
        val cats = categoryRepository.observeAll().first()
            .map { cat -> cat.id.toString() to cat.name }
        call.respondText(
            renderBudgetsForm(
                state(), token,
                BudgetForm(
                    categories = cats,
                    monthStart = MONTH_FMT.format(Date(monthStart)),
                ),
            ),
            contentType = ContentType.Text.Html,
        )
    }

    post("/budgets/new") {
        val params = call.receiveParameters()
        val err = validate(params)
        if (err != null) {
            val cats = categoryRepository.observeAll().first()
                .map { cat -> cat.id.toString() to cat.name }
            call.respondText(
                renderBudgetsForm(
                    state(), token,
                    BudgetForm(
                        categoryId = params["categoryId"]?.toLongOrNull(),
                        monthStart = params["monthStart"] ?: "",
                        amount = params["amount"] ?: "",
                        categories = cats,
                        error = err,
                    ),
                ),
                status = HttpStatusCode.BadRequest,
                contentType = ContentType.Text.Html,
            )
            return@post
        }
        val b = BudgetEntity(
            categoryId = params["categoryId"]!!.toLong(),
            monthStartEpochMs = monthStart,
            amountMinor = params["amount"]!!.toLong(),
        )
        budgetRepository.upsert(b)
        call.respondRedirect("/budgets?t=$token")
    }

    post("/budgets/{id}/delete") {
        val id = call.parameters["id"]!!
        val parts = id.split("_")
        val catId = parts[0].toLong()
        val month = parts[1].toLong()
        budgetRepository.deleteByKey(catId, month)
        call.respondRedirect("/budgets?t=$token")
    }
}

private fun validate(params: Parameters): String? {
    val cat = params["categoryId"].orEmpty()
    if (cat.isBlank()) return "Category is required"
    val amt = params["amount"].orEmpty().toLongOrNull()
        ?: return "Amount must be a whole number (minor units)"
    if (amt <= 0) return "Amount must be positive"
    return null
}
