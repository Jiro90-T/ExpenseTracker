package io.github.jiro.expensetracker.local.routes

import io.github.jiro.expensetracker.data.local.BudgetDao
import io.github.jiro.expensetracker.data.local.BudgetEntity
import io.github.jiro.expensetracker.data.local.CategoryDao
import io.github.jiro.expensetracker.data.local.CategoryEntity
import io.github.jiro.expensetracker.data.repository.BudgetRepository
import io.github.jiro.expensetracker.data.repository.CategoryRepository
import io.github.jiro.expensetracker.local.auth.authFilter
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetsRoutesTest {

    private val token = "test-token"

    private fun io.ktor.server.application.Application.setupTestApp() {
        routing {
            authFilter(token)
            budgetsRoutes(token, stubBudgetRepo, stubCategoryRepo)
        }
    }

    @Test fun listPage_renders() = runTest {
        testApplication {
            application { setupTestApp() }
            val resp = client.get("/budgets?t=$token")
            assertEquals(HttpStatusCode.OK, resp.status)
            assertTrue(
                "expected ContentType text/html, was: ${resp.headers[HttpHeaders.ContentType]}",
                ContentType.parse(resp.headers[HttpHeaders.ContentType] ?: "")
                    .match(ContentType.Text.Html),
            )
            val body = resp.bodyAsText()
            assertTrue("expected 'Budgets' heading, was: $body", body.contains("Budgets"))
        }
    }

    @Test fun newForm_rendersFields() = runTest {
        testApplication {
            application { setupTestApp() }
            val resp = client.get("/budgets/new?t=$token")
            assertEquals(HttpStatusCode.OK, resp.status)
            assertTrue(
                "expected ContentType text/html, was: ${resp.headers[HttpHeaders.ContentType]}",
                ContentType.parse(resp.headers[HttpHeaders.ContentType] ?: "")
                    .match(ContentType.Text.Html),
            )
            val body = resp.bodyAsText()
            assertTrue("expected categoryId field, was: $body", body.contains("name=\"categoryId\""))
            assertTrue("expected monthStart field, was: $body", body.contains("name=\"monthStart\""))
            assertTrue("expected amount field, was: $body", body.contains("name=\"amount\""))
        }
    }
}

private val stubBudgetRepo: BudgetRepository = BudgetRepository(StubBudgetsBudgetDao)

private val stubCategoryRepo: CategoryRepository = CategoryRepository(StubBudgetsCategoryDao)

private object StubBudgetsBudgetDao : BudgetDao {
    override fun observeByMonth(monthStart: Long): Flow<List<BudgetEntity>> =
        MutableStateFlow<List<BudgetEntity>>(emptyList()).asStateFlow()
    override suspend fun upsert(budget: BudgetEntity) = Unit
    override suspend fun deleteByKey(categoryId: Long, monthStart: Long): Int = 0
}

private object StubBudgetsCategoryDao : CategoryDao {
    override fun observeByType(type: String): Flow<List<CategoryEntity>> =
        MutableStateFlow<List<CategoryEntity>>(emptyList()).asStateFlow()
    override fun observeAll(): Flow<List<CategoryEntity>> =
        MutableStateFlow<List<CategoryEntity>>(emptyList()).asStateFlow()
    override suspend fun count(): Int = 0
    override suspend fun findById(id: Long): CategoryEntity? = null
    override suspend fun insertAll(categories: List<CategoryEntity>): List<Long> = emptyList()
    override suspend fun insert(category: CategoryEntity): Long = 0L
    override suspend fun insertAllReplacing(categories: List<CategoryEntity>): List<Long> = emptyList()
    override suspend fun update(category: CategoryEntity): Int = 0
    override suspend fun deleteById(id: Long): Int = 0
    override suspend fun deleteAllNonBuiltIn(): Int = 0
    override suspend fun observeAllOnce(): List<CategoryEntity> = emptyList()
}