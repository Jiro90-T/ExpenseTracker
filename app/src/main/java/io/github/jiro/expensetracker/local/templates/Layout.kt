package io.github.jiro.expensetracker.local.templates

import io.github.jiro.expensetracker.local.LocalServerState

fun renderLayout(state: LocalServerState, token: String, title: String, body: String): String {
    val safeTitle = escapeHtml(title)
    val nav = listOf(
        "Dashboard" to "/",
        "Transactions" to "/transactions",
        "Accounts" to "/accounts",
        "Categories" to "/categories",
        "Budgets" to "/budgets",
        "Settings" to "/settings",
    )
    val navLinks = nav.joinToString("\n") { (label, path) ->
        "<li><a href=\"${authed(path, token)}\">$label</a></li>"
    }
    return """
        <!doctype html>
        <html lang="en">
        <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>$safeTitle</title>
            <link rel="stylesheet" href="/static/pico.min.css">
            <link rel="stylesheet" href="/static/local.css">
            <script src="/static/htmx.min.js" defer></script>
        </head>
        <body>
            <header class="container">
                <nav>
                    <ul>
                        <li><strong>ExpenseTracker</strong></li>
                    </ul>
                    <ul>
                        $navLinks
                    </ul>
                </nav>
            </header>
            <main class="container">
                $body
            </main>
            <footer class="container">
                <small>Local server · port ${state.port}</small>
            </footer>
        </body>
        </html>
    """.trimIndent()
}

fun escapeHtml(s: String): String =
    s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

fun authed(path: String, token: String): String = "$path?t=$token"
