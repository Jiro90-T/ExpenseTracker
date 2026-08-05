package io.github.jiro.expensetracker.local.templates

import io.github.jiro.expensetracker.local.LocalServerState

data class AccountRow(
    val id: Long,
    val name: String,
    val type: String,
    val currency: String,
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
            append("<table>")
            append("<thead><tr><th>Name</th><th>Type</th><th>Currency</th><th></th></tr></thead>")
            append("<tbody>")
            for (row in rows) {
                append("<tr>")
                append("<td>${escapeHtml(row.name)}</td>")
                append("<td>${escapeHtml(row.type)}</td>")
                append("<td>${escapeHtml(row.currency)}</td>")
                append("<td>")
                append("<a href=\"/accounts/${row.id}/edit?t=$token\" role=\"button\" class=\"secondary\">Edit</a>")
                append(
                    "<button class=\"secondary\" hx-post=\"/accounts/${row.id}/delete?t=$token\" " +
                        "hx-confirm=\"Delete this account?\" hx-target=\"body\">Delete</button>",
                )
                append("</td>")
                append("</tr>")
            }
            append("</tbody>")
            append("</table>")
        }
    }
    return renderLayout(state, token, "Accounts", body)
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
