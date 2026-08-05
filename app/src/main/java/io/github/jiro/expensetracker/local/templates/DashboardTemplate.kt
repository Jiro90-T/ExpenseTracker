package io.github.jiro.expensetracker.local.templates

import io.github.jiro.expensetracker.local.LocalServerState

data class TxRow(val date: String, val title: String, val amount: String)

fun renderDashboard(state: LocalServerState, recent: List<TxRow>): String {
    val body = buildString {
        append("<h1>Dashboard</h1>")
        append("<p>Last ${recent.size} transactions. Phone and browser share the same Room database — any edit shows up on both sides automatically.</p>")
        if (recent.isEmpty()) {
            append("<p>No transactions yet.</p>")
        } else {
            append("<table>")
            append("<thead><tr><th>Date</th><th>Title</th><th>Amount</th></tr></thead>")
            append("<tbody>")
            for (row in recent) {
                append("<tr><td>${escapeHtml(row.date)}</td><td>${escapeHtml(row.title)}</td><td>${escapeHtml(row.amount)}</td></tr>")
            }
            append("</tbody>")
            append("</table>")
        }
    }
    return renderLayout(state, state.token ?: "", "Dashboard", body)
}