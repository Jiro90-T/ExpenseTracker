package io.github.jiro.expensetracker.local.templates

import io.github.jiro.expensetracker.local.LocalServerState

data class TxRow(
    val date: String,
    val title: String,
    val amount: String,
    val type: String,
    val account: String? = null,
)

data class AccountTile(
    val id: Long,
    val icon: String,
    val name: String,
    val currency: String,
    val balance: String,
    val isNegative: Boolean,
    val type: String,
)

/** Friendly display label for an AccountEntity.type string. Falls back to a
 *  titlecased version of the raw value so custom types render reasonably. */
fun typeLabel(type: String): String = when (type) {
    "CASH" -> "Cash"
    "BANK" -> "Bank accounts"
    "CREDIT_CARD" -> "Credit cards"
    "EWALLET" -> "E-wallets"
    "INVESTMENT" -> "Investments"
    "FD" -> "Fixed deposits"
    "OTHER" -> "Other"
    else -> type.lowercase().replaceFirstChar { it.uppercase() }
}

/** Group order for the dashboard tile grid. Types not listed fall to the end. */
private val TYPE_ORDER = listOf("BANK", "CASH", "CREDIT_CARD", "EWALLET", "INVESTMENT", "FD", "OTHER")

fun renderDashboard(
    state: LocalServerState,
    recent: List<TxRow>,
    accounts: List<AccountTile>,
    netWorth: String,
    netWorthNegative: Boolean,
): String {
    val token = state.token ?: ""
    val body = buildString {
        append("<h1>Dashboard</h1>")
        append("<p>Phone and browser share the same Room database — any edit shows up on both sides automatically.</p>")
        append("<article class=\"net-worth-card${if (netWorthNegative) " negative" else ""}\">")
        append("<p class=\"balance-label\">Net worth</p>")
        append("<p class=\"balance-amount\">${escapeHtml(netWorth)}</p>")
        append("</article>")
        append("<h2>Accounts</h2>")
        if (accounts.isEmpty()) {
            append("<p>No accounts yet. <a href=\"/accounts/new?t=$token\">Create one</a> to start tracking.</p>")
        } else {
            val grouped = accounts.groupBy { it.type }
            val orderedTypes = TYPE_ORDER.filter { it in grouped.keys } +
                grouped.keys.filter { it !in TYPE_ORDER }
            for (type in orderedTypes) {
                val tiles = grouped.getValue(type).sortedWith(
                    compareByDescending<AccountTile> { !it.isNegative }
                        .thenByDescending { it.balance.replace(",", "").replace(" ", "") },
                )
                append("<h3 class=\"tile-group-header\">${escapeHtml(typeLabel(type))}</h3>")
                append("<div class=\"tile-grid\">")
                for (tile in tiles) {
                    val negClass = if (tile.isNegative) " negative" else ""
                    append("<a class=\"tile account-tile$negClass\" href=\"/accounts/${tile.id}?t=$token\">")
                    append("<div class=\"tile-icon\">${escapeHtml(tile.icon)}</div>")
                    append("<div class=\"tile-name\">${escapeHtml(tile.name)}</div>")
                    append("<div class=\"tile-balance\">${escapeHtml(tile.balance)}</div>")
                    append("</a>")
                }
                append("</div>")
            }
        }
        append("<h2>Recent transactions</h2>")
        if (recent.isEmpty()) {
            append("<p>No transactions yet.</p>")
        } else {
            append("<div class=\"table-scroll\">")
            append("<table>")
            append("<thead><tr><th>Date</th><th>Title</th><th>Account</th><th>Amount</th></tr></thead>")
            append("<tbody>")
            for (row in recent) {
                append("<tr><td>${escapeHtml(row.date)}</td>")
                append("<td>${escapeHtml(row.title)}</td>")
                append("<td>${escapeHtml(row.account ?: "—")}</td>")
                append("<td><span class=\"amount ${escapeHtml(row.type.lowercase())}\">${escapeHtml(row.amount)}</span></td></tr>")
            }
            append("</tbody>")
            append("</table>")
            append("</div>")
        }
    }
    return renderLayout(state, token, "Dashboard", body)
}