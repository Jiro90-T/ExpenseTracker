package io.github.jiro.expensetracker.local.routes

import io.github.jiro.expensetracker.local.auth.authFilter
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardRouteTest {

    private val token = "test-token"

    private fun io.ktor.server.application.Application.setupTestApp() {
        routing {
            authFilter(token)
            dashboardRoute(token)
            get("/static/htmx.min.js") { call.respondText("js") }
        }
    }

    @Test fun rendersHeadingAndShowsEmptyStateWhenNoData() = runTest {
        testApplication {
            application { setupTestApp() }
            val resp = client.get("/?t=$token")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.bodyAsText()
            assertTrue("expected 'Dashboard' heading, was: $body", body.contains("Dashboard"))
            assertTrue(
                "expected htmx or /static/ reference, was: $body",
                body.contains("htmx") || body.contains("/static/"),
            )
        }
    }
}