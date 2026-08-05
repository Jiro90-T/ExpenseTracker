package io.github.jiro.expensetracker.local.routes

import io.github.jiro.expensetracker.local.auth.authFilter
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionsRoutesTest {

    private val token = "test-token"

    private fun io.ktor.server.application.Application.setupTestApp() {
        routing {
            authFilter(token)
            transactionsRoutes(token, null, null, null)
        }
    }

    @Test fun listPage_rendersTableAndNewButton() = runTest {
        testApplication {
            application { setupTestApp() }
            val resp = client.get("/transactions?t=$token")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.bodyAsText()
            assertTrue("expected 'Transactions' heading, was: $body", body.contains("Transactions"))
            assertTrue("expected new-transaction link, was: $body", body.contains("/transactions/new"))
        }
    }

    @Test fun newForm_rendersFields() = runTest {
        testApplication {
            application { setupTestApp() }
            val resp = client.get("/transactions/new?t=$token")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.bodyAsText()
            assertTrue("expected title field, was: $body", body.contains("name=\"title\""))
            assertTrue("expected amount field, was: $body", body.contains("name=\"amount\""))
            assertTrue("expected currencyCode field, was: $body", body.contains("name=\"currencyCode\""))
        }
    }
}
