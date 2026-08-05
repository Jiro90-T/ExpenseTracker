package io.github.jiro.expensetracker.local.routes

import io.github.jiro.expensetracker.data.repository.CategoryRepository
import io.github.jiro.expensetracker.data.repository.TransactionRepository
import io.github.jiro.expensetracker.domain.model.TransactionType
import io.github.jiro.expensetracker.local.LocalServerState
import io.github.jiro.expensetracker.local.templates.CategoryForm
import io.github.jiro.expensetracker.local.templates.CategoryRow
import io.github.jiro.expensetracker.local.templates.renderCategoriesForm
import io.github.jiro.expensetracker.local.templates.renderCategoriesList
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.flow.first

fun Route.categoriesRoutes(
    token: String,
    categoryRepository: CategoryRepository,
    transactionRepository: TransactionRepository,
) {
    val state = { LocalServerState(token = token) }

    get("/categories") {
        val rows = categoryRepository.observeAll().first()
            .map { cat -> CategoryRow(cat.id, cat.name, cat.type) }
        call.respondText(
            renderCategoriesList(state(), token, rows),
            contentType = ContentType.Text.Html,
        )
    }

    get("/categories/new") {
        call.respondText(
            renderCategoriesForm(state(), token, CategoryForm()),
            contentType = ContentType.Text.Html,
        )
    }

    post("/categories/new") {
        val params = call.receiveParameters()
        val name = params["name"].orEmpty().trim()
        if (name.isBlank()) {
            call.respondText(
                renderCategoriesForm(
                    state(), token,
                    CategoryForm(name = name, error = "Name is required"),
                ),
                status = HttpStatusCode.BadRequest,
                contentType = ContentType.Text.Html,
            )
            return@post
        }
        val type = runCatching { TransactionType.valueOf(params["type"] ?: "EXPENSE") }
            .getOrDefault(TransactionType.EXPENSE)
        runCatching { categoryRepository.add(name, type) }
            .onFailure {
                call.respondText(
                    renderCategoriesForm(
                        state(), token,
                        CategoryForm(
                            name = name,
                            type = type.name,
                            error = "Could not save: ${it.message}",
                        ),
                    ),
                    status = HttpStatusCode.BadRequest,
                    contentType = ContentType.Text.Html,
                )
                return@post
            }
        call.respondRedirect303("/categories?t=$token")
    }

    get("/categories/{id}/edit") {
        val id = call.parameters["id"]!!.toLong()
        val c = categoryRepository.findById(id)
        if (c == null) {
            call.respondText("Not found", status = HttpStatusCode.NotFound)
            return@get
        }
        call.respondText(
            renderCategoriesForm(state(), token, CategoryForm(id = id, name = c.name, type = c.type)),
            contentType = ContentType.Text.Html,
        )
    }

    post("/categories/{id}/edit") {
        val id = call.parameters["id"]!!.toLong()
        val c = categoryRepository.findById(id)
        if (c == null) {
            call.respondText("Not found", status = HttpStatusCode.NotFound)
            return@post
        }
        val params = call.receiveParameters()
        val name = params["name"].orEmpty().trim()
        if (name.isBlank()) {
            call.respondText(
                renderCategoriesForm(
                    state(), token,
                    CategoryForm(id = id, name = name, error = "Name is required"),
                ),
                status = HttpStatusCode.BadRequest,
                contentType = ContentType.Text.Html,
            )
            return@post
        }
        runCatching { categoryRepository.update(c.copy(name = name)) }
            .onFailure {
                call.respondText(
                    renderCategoriesForm(
                        state(), token,
                        CategoryForm(
                            id = id, name = name, type = c.type,
                            error = "Could not save: ${it.message}",
                        ),
                    ),
                    status = HttpStatusCode.BadRequest,
                    contentType = ContentType.Text.Html,
                )
                return@post
            }
        call.respondRedirect303("/categories?t=$token")
    }

    post("/categories/{id}/delete") {
        val id = call.parameters["id"]!!.toLong()
        val c = categoryRepository.findById(id)
        if (c != null) {
            runCatching { categoryRepository.deleteById(id) }
                .onFailure {
                    call.respondText(
                        "<p>Cannot delete category — referenced by transactions.</p>" +
                            "<p><a href=\"/categories?t=$token\">Back to list</a></p>",
                        status = HttpStatusCode.Conflict,
                        contentType = ContentType.Text.Html,
                    )
                    return@post
                }
        }
        call.respondRedirect303("/categories?t=$token")
    }
}
