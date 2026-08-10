package io.github.jiro.expensetracker.local.templates

import io.github.jiro.expensetracker.local.LocalServerState

data class TxListRow(
    val id: Long,
    val date: String,
    val title: String,
    val category: String,
    val account: String,
    val amount: String,
    val type: String,
)

data class TxForm(
    val id: Long? = null,
    val title: String = "",
    val amount: String = "",
    val currencyCode: String = "USD",
    val occurredAt: String = "",
    val note: String = "",
    val type: String = "EXPENSE",
    val categoryId: Long? = null,
    val accountId: Long? = null,
    val presetAccountLabel: String? = null,
    val categories: List<Pair<String, String>> = emptyList(),
    val accounts: List<Pair<Long, String>> = emptyList(),
    val backHref: String = "/transactions",
    val error: String? = null,
)

fun renderTransactionsList(state: LocalServerState, token: String, rows: List<TxListRow>): String {
    val body = buildString {
        append("<hgroup><h1>Transactions</h1>")
        append("<p><a href=\"/transactions/new?t=$token\" role=\"button\">New</a></p></hgroup>")
        if (rows.isEmpty()) {
            append("<p>No transactions.</p>")
        } else {
            append("<div class=\"table-scroll\">")
            append("<table>")
            append(
                "<thead><tr><th>Date</th><th>Title</th><th>Category</th>" +
                    "<th>Account</th><th>Amount</th><th></th></tr></thead>",
            )
            append("<tbody>")
            for (row in rows) {
                append("<tr>")
                append("<td>${escapeHtml(row.date)}</td>")
                append("<td>${escapeHtml(row.title)}</td>")
                append("<td>${escapeHtml(row.category)}</td>")
                append("<td>${escapeHtml(row.account)}</td>")
                append("<td><span class=\"amount ${escapeHtml(row.type.lowercase())}\">${escapeHtml(row.amount)}</span></td>")
                append("<td class=\"row-actions\">")
                append("<a href=\"/transactions/${row.id}/edit?t=$token\" role=\"button\" class=\"secondary outline compact\">Edit</a>")
                append(
                    "<button class=\"secondary outline compact\" hx-post=\"/transactions/${row.id}/delete?t=$token\" " +
                        "hx-confirm=\"Delete this transaction?\" hx-target=\"body\">Delete</button>",
                )
                append("</td>")
                append("</tr>")
            }
            append("</tbody>")
            append("</table>")
            append("</div>")
        }
    }
    return renderLayout(state, token, "Transactions", body)
}

fun renderTransactionsForm(state: LocalServerState, token: String, form: TxForm): String {
    val heading = if (form.id == null) "New transaction" else "Edit transaction"
    val action = if (form.id == null) "/transactions/new?t=$token" else "/transactions/${form.id}/edit?t=$token"
    val typeOptions = listOf("EXPENSE", "INCOME", "TRANSFER", "ADJUSTMENT")
    val typeRadios = typeOptions.joinToString("") { option ->
        val checked = if (option == form.type) " checked" else ""
        "<label><input type=\"radio\" name=\"type\" value=\"$option\"$checked>$option</label>"
    }
    val body = buildString {
        append("<p><a href=\"${escapeHtml(form.backHref)}?t=$token\">← Back</a></p>")
        append("<h1>$heading</h1>")
        if (form.presetAccountLabel != null && form.id == null) {
            append("<article class=\"context-card\">")
            append("<strong>Adding to:</strong> ${escapeHtml(form.presetAccountLabel)}")
            append("</article>")
        }
        append("<form method=\"post\" action=\"$action\">")
        append(textField("Title", "title", form.title))
        append(textField("Amount", "amount", form.amount, "number", placeholder = "e.g. 12.50"))
        append(textField("Currency", "currencyCode", form.currencyCode))
        append(textField("Date", "occurredAt", form.occurredAt, placeholder = "yyyy-MM-dd"))
        append(textField("Note", "note", form.note))
        append(selectField("Category", "categoryId", form.categories, form.categoryId?.toString()))
        if (form.presetAccountLabel != null && form.id == null) {
            // Locked to the preset account — show as a hidden field with the
            // name visible, so the user still sees what they're entering against.
            append("<label>Account")
            append("<input type=\"text\" value=\"${escapeHtml(form.presetAccountLabel)}\" disabled>")
            append("<input type=\"hidden\" name=\"accountId\" value=\"${form.accountId}\">")
            append("</label>")
        } else {
            append(
                selectField(
                    "Account",
                    "accountId",
                    form.accounts.map { (id, label) -> id.toString() to label },
                    form.accountId?.toString(),
                ),
            )
        }
        append("<fieldset><legend>Type</legend>$typeRadios</fieldset>")
        append(fieldError(form.error))
        append("<button type=\"submit\">Save</button>")
        append("<a href=\"${escapeHtml(form.backHref)}?t=$token\" role=\"button\" class=\"secondary\">Cancel</a>")
        append("</form>")
    }
    return renderLayout(state, token, "Transaction", body)
}
