package io.github.jiro.expensetracker.local.templates

import io.github.jiro.expensetracker.local.LocalServerState

data class AccountRow(
    val id: Long,
    val name: String,
    val type: String,
    val currency: String,
    val balance: String,
)

data class AccountDetailTx(
    val id: Long,
    val date: String,
    val title: String,
    val category: String,
    val amount: String,
    val type: String,
    val runningBalance: String,
)

data class AccountDetailView(
    val id: Long,
    val name: String,
    val type: String,
    val currency: String,
    val openingBalance: String,
    val runningBalance: String,
    val icon: String,
    val transactions: List<AccountDetailTx>,
)

data class AccountForm(
    val id: Long? = null,
    val name: String = "",
    val type: String = "CASH",
    val currencyCode: String = "USD",
    val openingBalanceMinor: String = "0",
    val icon: String = "💵",
    val color: String = "#888888",
    val error: String? = null,
)

fun renderAccountsList(state: LocalServerState, token: String, rows: List<AccountRow>): String {
    val body = buildString {
        append("<hgroup><h1>Accounts</h1>")
        append("<p><a href=\"/accounts/new?t=$token\" role=\"button\">New</a></p></hgroup>")
        if (rows.isEmpty()) {
            append("<p>No accounts.</p>")
        } else {
            append("<div class=\"table-scroll\">")
            append("<table>")
            append("<thead><tr><th>Name</th><th>Type</th><th>Currency</th><th>Balance</th><th></th></tr></thead>")
            append("<tbody>")
            for (row in rows) {
                append("<tr>")
                append("<td><a href=\"/accounts/${row.id}?t=$token\">${escapeHtml(row.name)}</a></td>")
                append("<td>${escapeHtml(row.type)}</td>")
                append("<td>${escapeHtml(row.currency)}</td>")
                append("<td><a href=\"/accounts/${row.id}?t=$token\" class=\"balance-cell\">${escapeHtml(row.balance)}</a></td>")
                append("<td class=\"row-actions\">")
                append("<a href=\"/transactions/new?accountId=${row.id}&t=$token\" role=\"button\" class=\"secondary outline compact\" title=\"Add transaction to ${escapeHtml(row.name)}\">+ Tx</a>")
                append("<a href=\"/accounts/${row.id}/edit?t=$token\" role=\"button\" class=\"secondary outline compact\">Edit</a>")
                append(
                    "<button class=\"secondary outline compact\" hx-post=\"/accounts/${row.id}/delete?t=$token\" " +
                        "hx-confirm=\"Delete this account?\" hx-target=\"body\">Delete</button>",
                )
                append("</td>")
                append("</tr>")
            }
            append("</tbody>")
            append("</table>")
            append("</div>")
        }
    }
    return renderLayout(state, token, "Accounts", body)
}

fun renderAccountDetail(state: LocalServerState, token: String, view: AccountDetailView): String {
    val body = buildString {
        append("<p><a href=\"/accounts?t=$token\">← Back to accounts</a></p>")
        append("<article class=\"account-header\">")
        append("<header>")
        append("<hgroup>")
        append("<h1>${escapeHtml(view.icon)} ${escapeHtml(view.name)}</h1>")
        append("<p>${escapeHtml(view.type)} · ${escapeHtml(view.currency)}</p>")
        append("</hgroup>")
        append("<p class=\"balance-label\">Running balance</p>")
        append("<p class=\"balance-amount\">${escapeHtml(view.runningBalance)}</p>")
        append("<p class=\"balance-opens\">Opening: ${escapeHtml(view.openingBalance)}</p>")
        append("</header>")
        append("<footer>")
        append("<a href=\"/transactions/new?accountId=${view.id}&t=$token\" role=\"button\">")
        append("+ Add transaction</a>")
        append("<a href=\"/accounts/${view.id}/edit?t=$token\" role=\"button\" class=\"secondary\">")
        append("Edit account</a>")
        append("</footer>")
        append("</article>")
        if (view.transactions.isEmpty()) {
            append("<p>No transactions for this account yet.</p>")
        } else {
            append("<h2>Reconciliation</h2>")
            append("<p class=\"muted\">Transactions in chronological order. The Running Balance column starts at the opening balance and accumulates each row so you can match against a bank statement.</p>")
            append("<div class=\"table-scroll\">")
            append("<table class=\"reconcile-table\">")
            append("<thead><tr><th>Date</th><th>Title</th><th>Category</th><th>Amount</th><th class=\"num\">Running balance</th><th></th></tr></thead>")
            append("<tbody>")
            for (tx in view.transactions) {
                append("<tr>")
                append("<td>${escapeHtml(tx.date)}</td>")
                append("<td>${escapeHtml(tx.title)}</td>")
                append("<td>${escapeHtml(tx.category)}</td>")
                append("<td><span class=\"amount ${escapeHtml(tx.type.lowercase())}\">${escapeHtml(tx.amount)}</span></td>")
                append("<td class=\"num running\">${escapeHtml(tx.runningBalance)}</td>")
                append("<td class=\"row-actions\">")
                append("<a href=\"/transactions/${tx.id}/edit?t=$token\" role=\"button\" class=\"secondary outline compact\">Edit</a>")
                append(
                    "<button class=\"secondary outline compact\" hx-post=\"/transactions/${tx.id}/delete?t=$token\" " +
                        "hx-confirm=\"Delete this transaction?\" hx-target=\"body\">Del</button>",
                )
                append("</td>")
                append("</tr>")
            }
            append("</tbody>")
            append("</table>")
            append("</div>")
        }
    }
    return renderLayout(state, token, view.name, body)
}

fun renderAccountsForm(state: LocalServerState, token: String, form: AccountForm): String {
    val heading = if (form.id == null) "New account" else "Edit account"
    val action = if (form.id == null) "/accounts/new?t=$token" else "/accounts/${form.id}/edit?t=$token"
    val body = buildString {
        append("<h1>$heading</h1>")
        append("<form method=\"post\" action=\"$action\">")
        append(textField("Name", "name", form.name))
        append(textField("Type", "type", form.type))
        append(textField("Currency", "currencyCode", form.currencyCode))
        append(textField("Opening balance", "openingBalanceMinor", form.openingBalanceMinor, "number"))
        append(textField("Icon", "icon", form.icon))
        append(textField("Color", "color", form.color))
        append(fieldError(form.error))
        append("<button type=\"submit\">Save</button>")
        append("<a href=\"/accounts?t=$token\" role=\"button\" class=\"secondary\">Cancel</a>")
        append("</form>")
    }
    return renderLayout(state, token, "Account", body)
}
