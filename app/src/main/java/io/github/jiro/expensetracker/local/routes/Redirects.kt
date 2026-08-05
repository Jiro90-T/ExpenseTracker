package io.github.jiro.expensetracker.local.routes

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

/**
 * POST-redirect-GET after a successful form submission. Ktor's built-in
 * [io.ktor.server.response.respondRedirect] only supports 301 Moved Permanently
 * and 302 Found (controlled by a `permanent: Boolean` flag) — see
 * https://youtrack.jetbrains.com/issue/KTOR-7301. For POST-redirect-GET, RFC 7231
 * §6.4.4 requires 303 See Other, so 302 should not be used. We set the Location
 * header and respond with 303 directly. Use this everywhere a POST handler
 * redirects to a GET endpoint.
 */
suspend fun ApplicationCall.respondRedirect303(url: String) {
    response.headers.append(HttpHeaders.Location, url)
    respond(HttpStatusCode.SeeOther)
}
