package io.github.jiro.expensetracker.local.templates

import io.github.jiro.expensetracker.local.LocalServerState

data class CategoryRow(val id: Long, val name: String, val type: String)
data class CategoryForm(
    val id: Long? = null,
    val name: String = "",
    val type: String = "EXPENSE",
    val error: String? = null,
)

fun renderCategoriesList(state: LocalServerState, token: String, rows: List<CategoryRow>): String {
    val body = buildString {
        append("<hgroup><h1>Categories</h1>")
        append("<p><a href=\"/categories/new?t=$token\" role=\"button\">New</a></p></hgroup>")
        if (rows.isEmpty()) {
            append("<p>No categories.</p>")
        } else {
            append("<table><thead><tr><th>Name</th><th>Type</th><th></th></tr></thead><tbody>")
            for (r in rows) {
                append("<tr><td>${escapeHtml(r.name)}</td><td>${escapeHtml(r.type)}</td>")
                append("<td><a href=\"/categories/${r.id}/edit?t=$token\" role=\"button\" class=\"secondary\">Edit</a> ")
                append("<button class=\"secondary\" hx-post=\"/categories/${r.id}/delete?t=$token\" hx-confirm=\"Delete this category?\" hx-target=\"body\">Delete</button></td>")
                append("</tr>")
            }
            append("</tbody></table>")
        }
    }
    return renderLayout(state, token, "Categories", body)
}

fun renderCategoriesForm(state: LocalServerState, token: String, form: CategoryForm): String {
    val heading = if (form.id == null) "New category" else "Edit category"
    val action = if (form.id == null) "/categories/new?t=$token" else "/categories/${form.id}/edit?t=$token"
    val radios = listOf("EXPENSE", "INCOME").joinToString("") { t ->
        val checked = if (form.type == t) " checked" else ""
        "<label><input type=\"radio\" name=\"type\" value=\"$t\"$checked> $t</label>"
    }
    val body = buildString {
        append("<h1>$heading</h1>")
        append("<form method=\"post\" action=\"$action\">")
        append(textField("Name", "name", form.name))
        append("<fieldset><legend>Type</legend>$radios</fieldset>")
        append(fieldError(form.error))
        append("<button type=\"submit\">Save</button>")
        append("<a href=\"/categories?t=$token\" role=\"button\" class=\"secondary\">Cancel</a>")
        append("</form>")
    }
    return renderLayout(state, token, "Category", body)
}