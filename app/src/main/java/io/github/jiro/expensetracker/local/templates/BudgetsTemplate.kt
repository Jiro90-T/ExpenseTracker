package io.github.jiro.expensetracker.local.templates

import io.github.jiro.expensetracker.local.LocalServerState

data class BudgetRow(val id: String, val category: String, val month: String, val amount: String)
data class BudgetForm(
    val id: String? = null,
    val categoryId: Long? = null,
    val monthStart: String = "",
    val amount: String = "",
    val categories: List<Pair<String, String>> = emptyList(),
    val error: String? = null,
)

fun renderBudgetsList(state: LocalServerState, token: String, rows: List<BudgetRow>): String {
    val body = buildString {
        append("<hgroup><h1>Budgets</h1>")
        append("<p><a href=\"/budgets/new?t=$token\" role=\"button\">New</a></p></hgroup>")
        if (rows.isEmpty()) {
            append("<p>No budgets set for this month.</p>")
        } else {
            append("<table><thead><tr><th>Category</th><th>Month</th><th>Amount</th><th></th></tr></thead><tbody>")
            for (r in rows) {
                append("<tr><td>${escapeHtml(r.category)}</td><td>${escapeHtml(r.month)}</td><td>${escapeHtml(r.amount)}</td>")
                append("<td><a href=\"/budgets/${r.id}/edit?t=$token\" role=\"button\" class=\"secondary\">Edit</a> ")
                append("<button class=\"secondary\" hx-post=\"/budgets/${r.id}/delete?t=$token\" hx-confirm=\"Delete this budget?\" hx-target=\"body\">Delete</button></td>")
                append("</tr>")
            }
            append("</tbody></table>")
        }
    }
    return renderLayout(state, token, "Budgets", body)
}

fun renderBudgetsForm(state: LocalServerState, token: String, form: BudgetForm): String {
    val heading = if (form.id == null) "New budget" else "Edit budget"
    val action = if (form.id == null) "/budgets/new?t=$token" else "/budgets/${form.id}/edit?t=$token"
    val body = buildString {
        append("<h1>$heading</h1>")
        append("<form method=\"post\" action=\"$action\">")
        append(selectField("Category", "categoryId", form.categories, form.categoryId?.toString()))
        append(textField("Month (yyyy-MM-01)", "monthStart", form.monthStart, "date"))
        append(textField("Amount (minor units)", "amount", form.amount, "number"))
        append(fieldError(form.error))
        append("<button type=\"submit\">Save</button>")
        append("<a href=\"/budgets?t=$token\" role=\"button\" class=\"secondary\">Cancel</a>")
        append("</form>")
    }
    return renderLayout(state, token, "Budget", body)
}