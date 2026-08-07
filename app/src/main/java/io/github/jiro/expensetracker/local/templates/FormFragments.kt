package io.github.jiro.expensetracker.local.templates

fun textField(
    label: String,
    name: String,
    value: String = "",
    type: String = "text",
    placeholder: String = "",
): String {
    val safeLabel = escapeHtml(label)
    val safeValue = escapeHtml(value)
    val safePlaceholder = escapeHtml(placeholder)
    val placeholderAttr = if (placeholder.isEmpty()) "" else " placeholder=\"$safePlaceholder\""
    return """
        <label>
            $safeLabel
            <input type="$type" name="$name" value="$safeValue"$placeholderAttr>
        </label>
    """.trimIndent()
}

fun selectField(
    label: String,
    name: String,
    options: List<Pair<String, String>>,
    selectedId: String? = null,
): String {
    val safeLabel = escapeHtml(label)
    val optionTags = options.joinToString("") { (id, text) ->
        val safeId = escapeHtml(id)
        val safeText = escapeHtml(text)
        val selected = if (id == selectedId) " selected" else ""
        "<option value=\"$safeId\"$selected>$safeText</option>"
    }
    return """
        <label>
            $safeLabel
            <select name="$name">
                $optionTags
            </select>
        </label>
    """.trimIndent()
}

fun checkboxField(label: String, name: String, checked: Boolean): String {
    val safeLabel = escapeHtml(label)
    val checkedAttr = if (checked) " checked" else ""
    return """
        <label>
            <input type="checkbox" name="$name" value="on"$checkedAttr>
            $safeLabel
        </label>
    """.trimIndent()
}

fun fieldError(message: String?): String {
    if (message.isNullOrBlank()) return ""
    val safeMessage = escapeHtml(message)
    return "<small style=\"color:var(--pico-color-red-500)\">$safeMessage</small>"
}
