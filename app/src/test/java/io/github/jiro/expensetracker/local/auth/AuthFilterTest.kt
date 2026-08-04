package io.github.jiro.expensetracker.local.auth

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthFilterTest {

    private val expectedToken = "expected-secret-token"

    private fun io.ktor.server.application.Application.setupTestApp() {
        routing {
            authFilter(expectedToken)
            get("/protected") { call.respondText("ok") }
            get("/static/foo.js") { call.respondText("js") }
        }
    }

    @Test fun missingToken_returns401() = runTest {
        testApplication {
            application { setupTestApp() }
            val resp = client.get("/protected")
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
            val body = resp.bodyAsText()
            assertTrue("expected unauthorized HTML body, was: $body",
                body.contains("Token missing"))
        }
    }

    @Test fun wrongToken_returns401() = runTest {
        testApplication {
            application { setupTestApp() }
            val resp = client.get("/protected?t=wrong")
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }
    }

    @Test fun correctTokenInQuery_returns200() = runTest {
        testApplication {
            application { setupTestApp() }
            val resp = client.get("/protected?t=$expectedToken")
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals("ok", resp.bodyAsText())
        }
    }

    @Test fun correctTokenInAuthorizationHeader_returns200() = runTest {
        testApplication {
            application { setupTestApp() }
            val resp = client.get("/protected") {
                header(HttpHeaders.Authorization, "Bearer $expectedToken")
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals("ok", resp.bodyAsText())
        }
    }

    @Test fun staticPath_isPublic_evenWithoutToken() = runTest {
        testApplication {
            application { setupTestApp() }
            val resp = client.get("/static/foo.js")
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals("js", resp.bodyAsText())
        }
    }
}
