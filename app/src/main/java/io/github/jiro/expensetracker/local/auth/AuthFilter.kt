package io.github.jiro.expensetracker.local.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.util.pipeline.PipelineContext

/**
 * Ktor interceptor that rejects any request whose `?t=` query param or
 * `Authorization: Bearer` header doesn't match [expectedToken]. Requests
 * to `/static/...` are always allowed (those are htmx/picocss assets).
 *
 * Install at the root of the routing graph:
 * ```
 * routing {
 *     authFilter(token)
 *     get("/") { ... }
 * }
 * ```
 */
fun Route.authFilter(expectedToken: String) {
    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.local.uri
        if (path.startsWith("/static/")) return@intercept
        val provided = call.request.queryParameters["t"]
            ?: call.request.headers["Authorization"]?.removePrefix("Bearer ")
        if (provided != expectedToken) {
            renderUnauthorizedAndFinish()
        }
    }
}

private suspend fun PipelineContext<Unit, ApplicationCall>.renderUnauthorizedAndFinish() {
    val body = """
        <!doctype html>
        <html lang="en"><head><meta charset="utf-8"><title>Unauthorized</title></head>
        <body><main><h1>Token missing or wrong</h1>
        <p>Run the server on your phone, then copy the URL from Settings.</p>
        </main></body></html>
    """.trimIndent()
    call.respondText(body, status = HttpStatusCode.Unauthorized)
    finish()
}
