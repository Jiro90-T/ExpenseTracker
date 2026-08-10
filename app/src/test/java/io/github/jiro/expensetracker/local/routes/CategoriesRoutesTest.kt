package io.github.jiro.expensetracker.local.routes

import android.app.Application
import io.github.jiro.expensetracker.data.local.CategoryDao
import io.github.jiro.expensetracker.data.local.CategoryEntity
import io.github.jiro.expensetracker.data.local.TransactionDao
import io.github.jiro.expensetracker.data.local.TransactionEntity
import io.github.jiro.expensetracker.data.local.TransactionWithCategory
import io.github.jiro.expensetracker.data.repository.CategoryRepository
import io.github.jiro.expensetracker.data.repository.ReceiptRepository
import io.github.jiro.expensetracker.data.repository.TransactionRepository
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

class CategoriesRoutesTest {

    private val token = "test-token"

    private fun io.ktor.server.application.Application.setupTestApp() {
        routing {
            authFilter(token)
            categoriesRoutes(token, stubCategoryRepo, stubTransactionRepo)
        }
    }

    @Test fun listPage_renders() = runTest {
        testApplication {
            application { setupTestApp() }
            val resp = client.get("/categories?t=$token")
            assertEquals(HttpStatusCode.OK, resp.status)
            assertTrue(
                "expected ContentType text/html, was: ${resp.headers[HttpHeaders.ContentType]}",
                ContentType.parse(resp.headers[HttpHeaders.ContentType] ?: "")
                    .match(ContentType.Text.Html),
            )
            val body = resp.bodyAsText()
            assertTrue("expected 'Categories' heading, was: $body", body.contains("Categories"))
        }
    }

    @Test fun newForm_rendersNameField() = runTest {
        testApplication {
            application { setupTestApp() }
            val resp = client.get("/categories/new?t=$token")
            assertEquals(HttpStatusCode.OK, resp.status)
            assertTrue(
                "expected ContentType text/html, was: ${resp.headers[HttpHeaders.ContentType]}",
                ContentType.parse(resp.headers[HttpHeaders.ContentType] ?: "")
                    .match(ContentType.Text.Html),
            )
            val body = resp.bodyAsText()
            assertTrue("expected name field, was: $body", body.contains("name=\"name\""))
        }
    }
}

private val stubCategoryRepo: CategoryRepository = CategoryRepository(StubCategoriesCategoryDao)

private val stubTransactionRepo: TransactionRepository = TransactionRepository(
    StubCategoriesTransactionDao,
    ReceiptRepository(NoopAppCategories),
)

private object StubCategoriesCategoryDao : CategoryDao {
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

private object NoopAppCategories : Application()

private object StubCategoriesTransactionDao : TransactionDao {
    override fun observeAllWithCategory(): Flow<List<TransactionWithCategory>> =
        MutableStateFlow<List<TransactionWithCategory>>(emptyList()).asStateFlow()
    override fun observeInRangeWithCategory(startMs: Long, endMs: Long): Flow<List<TransactionWithCategory>> =
        MutableStateFlow<List<TransactionWithCategory>>(emptyList()).asStateFlow()
    override suspend fun findById(id: Long): TransactionEntity? = null
    override suspend fun insert(transaction: TransactionEntity): Long = 0L
    override suspend fun restore(transaction: TransactionEntity): Long = 0L
    override suspend fun update(transaction: TransactionEntity) = Unit
    override suspend fun delete(transaction: TransactionEntity) = Unit
    override suspend fun observeAllForExport(): List<TransactionEntity> = emptyList()
    override suspend fun deleteAll(): Int = 0
    override suspend fun insertAll(transactions: List<TransactionEntity>): List<Long> = emptyList()
    override suspend fun clearReceiptPathsFor(paths: List<String>) = Unit
    override suspend fun dueRecurringParents(nowMs: Long): List<TransactionEntity> = emptyList()
    override fun observeByRecurringGroup(groupId: String): Flow<List<TransactionWithCategory>> =
        MutableStateFlow<List<TransactionWithCategory>>(emptyList()).asStateFlow()
    override suspend fun countByRecurringGroup(groupId: String): Int = 0
    override suspend fun countForAccount(accountId: Long): Int = 0
    override suspend fun countReferencingAccount(id: Long): Int = 0
    override fun observeByAccount(accountId: Long): Flow<List<TransactionWithCategory>> =
        MutableStateFlow<List<TransactionWithCategory>>(emptyList()).asStateFlow()
    override fun observeTransfersToAccount(accountId: Long): Flow<List<TransactionWithCategory>> =
        MutableStateFlow<List<TransactionWithCategory>>(emptyList()).asStateFlow()
}