package io.github.jiro.expensetracker.local.templates

import io.github.jiro.expensetracker.local.LocalServerState

fun renderSettingsPage(
    state: LocalServerState,
    token: String,
    homeCurrency: String,
    fxRates: List<Pair<String, Double>>,
): String {
    val rates = if (fxRates.isEmpty()) "<p>No rates stored.</p>" else {
        buildString {
            append("<table><thead><tr><th>Pair</th><th>Rate</th></tr></thead><tbody>")
            for ((k, v) in fxRates) {
                append("<tr><td>${escapeHtml(k)}</td><td>$v</td></tr>")
            }
            append("</tbody></table>")
        }
    }
    val body = buildString {
        append("<h1>Settings</h1>")
        append("<p>Home currency: <strong>${escapeHtml(homeCurrency)}</strong></p>")
        append("<h2>FX rates</h2>")
        append(rates)
        append("<p><small>Edit rates on the phone.</small></p>")
    }
    return renderLayout(state, token, "Settings", body)
}