package io.github.jiro.expensetracker.local.templates

fun renderUnauthorized(): String = """
    <!doctype html>
    <html lang="en">
    <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>Unauthorized</title>
        <link rel="stylesheet" href="/static/pico.min.css">
    </head>
    <body>
        <main class="container">
            <h1>Token missing or wrong</h1>
            <p>Run the server on your phone, then copy the URL from Settings.</p>
        </main>
    </body>
    </html>
""".trimIndent()
