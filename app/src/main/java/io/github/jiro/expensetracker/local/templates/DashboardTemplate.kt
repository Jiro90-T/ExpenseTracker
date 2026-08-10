package io.github.jiro.expensetracker.local.templates

import io.github.jiro.expensetracker.local.LocalServerState

data class TxRow(val date: String, val title: String, val amount: String, val type: String)

data class AccountTile(
    val id: Long,
    val icon: String,
    val name: String,
    val currency: String,
    val balance: String,
    val isNegative: Boolean,
)

fun renderDashboard(
    state: LocalServerState,
    recent: List<TxRow>,
    accounts: List<AccountTile>,
): String {
    val token = state.token ?: ""
    val body = buildString {
        append("<h1>Dashboard</h1>")
        append("<p>Phone and browser share the same Room database — any edit shows up on both sides automatically.</p>")
        append("<h2>Accounts</h2>")
        if (accounts.isEmpty()) {
            append("<p>No accounts yet. <a href=\"/accounts/new?t=$token\">Create one</a> to start tracking.</p>")
        } else {
            append("<div class=\"tile-grid\">")
            for (tile in accounts) {
                val negClass = if (tile.isNegative) " negative" else ""
                append("<a class=\"tile account-tile$negClass\" href=\"/accounts/${tile.id}?t=$token\">")
                append("<div class=\"tile-icon\">${escapeHtml(tile.icon)}</div>")
                append("<div class=\"tile-name\">${escapeHtml(tile.name)}</div>")
                append("<div class=\"tile-balance\">${escapeHtml(tile.balance)}</div>")
                append("</a>")
            }
            append("</div>")
        }
        append("<h2>Recent transactions</h2>")
        if (recent.isEmpty()) {
            append("<p>No transactions yet.</p>")
        } else {
            append("<div class=\"table-scroll\">")
            append("<table>")
            append("<thead><tr><th>Date</th><th>Title</th><th>Amount</th></tr></thead>")
            append("<tbody>")
            for (row in recent) {
                append("<tr><td>${escapeHtml(row.date)}</td><td>${escapeHtml(row.title)}</td>")
                append("<td><span class=\"amount ${escapeHtml(row.type.lowercase())}\">${escapeHtml(row.amount)}</span></td></tr>")
            }
            append("</tbody>")
            append("</table>")
            append("</div>")
        }
    }
    return renderLayout(state, token, "Dashboard", body)
}