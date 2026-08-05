package io.github.jiro.expensetracker.local.routes

import androidx.test.core.app.ApplicationProvider
import io.github.jiro.expensetracker.local.auth.authFilter
import io.github.jiro.expensetracker.preferences.SettingsRepository
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsRouteTest {

    private val token = "test-token"

    private fun io.ktor.server.application.Application.setupTestApp() {
        routing {
            authFilter(token)
            settingsRoute(token, stubSettingsRepo)
        }
    }

    @Test fun settingsPage_renders() = runTest {
        testApplication {
            application { setupTestApp() }
            val resp = client.get("/settings?t=$token")
            assertEquals(HttpStatusCode.OK, resp.status)
            assertTrue(
                "expected ContentType text/html, was: ${resp.headers[HttpHeaders.ContentType]}",
                ContentType.parse(resp.headers[HttpHeaders.ContentType] ?: "")
                    .match(ContentType.Text.Html),
            )
            val body = resp.bodyAsText()
            assertTrue("expected 'Settings' heading, was: $body", body.contains("Settings"))
            assertTrue("expected 'Home currency' label, was: $body", body.contains("Home currency"))
        }
    }

    @Test fun settingsPage_showsHomeCurrency() = runTest {
        testApplication {
            application { setupTestApp() }
            val resp = client.get("/settings?t=$token")
            assertEquals(HttpStatusCode.OK, resp.status)
            assertTrue(
                "expected ContentType text/html, was: ${resp.headers[HttpHeaders.ContentType]}",
                ContentType.parse(resp.headers[HttpHeaders.ContentType] ?: "")
                    .match(ContentType.Text.Html),
            )
            val body = resp.bodyAsText()
            assertTrue("expected USD currency (the default), was: $body", body.contains("USD"))
        }
    }
}

private val stubSettingsRepo: SettingsRepository =
    SettingsRepository(ApplicationProvider.getApplicationContext())