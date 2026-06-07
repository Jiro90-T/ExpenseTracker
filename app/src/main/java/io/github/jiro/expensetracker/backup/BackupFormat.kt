package io.github.jiro.expensetracker.backup

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON shape for a full-database export. Stable across versions as long as
 * [FORMAT_VERSION] is unchanged; bump the version when fields are added or
 * renamed so older builds can refuse the import.
 */
internal object BackupFormat {
    const val FORMAT_VERSION = 1
    const val MIME_TYPE = "application/json"
    const val FILE_PREFIX = "expense-tracker-backup-"

    fun envelope(exportedAtEpochMillis: Long, appVersionName: String): JSONObject =
        JSONObject().apply {
            put("formatVersion", FORMAT_VERSION)
            put("exportedAtEpochMillis", exportedAtEpochMillis)
            put("appVersionName", appVersionName)
        }

    fun parseEnvelope(json: String): JSONObject =
        JSONObject(json).also { obj ->
            val v = obj.optInt("formatVersion", -1)
            require(v == FORMAT_VERSION) {
                "Unsupported backup format version: $v (expected $FORMAT_VERSION)"
            }
        }

    fun putCategoriesArray(envelope: JSONObject, items: JSONArray) {
        envelope.put("categories", items)
    }

    fun putTransactionsArray(envelope: JSONObject, items: JSONArray) {
        envelope.put("transactions", items)
    }

    fun categoriesArrayOf(envelope: JSONObject): JSONArray =
        envelope.getJSONArray("categories")

    fun transactionsArrayOf(envelope: JSONObject): JSONArray =
        envelope.getJSONArray("transactions")
}

internal fun categoryEntityToJson(
    id: Long,
    name: String,
    type: String,
    sortOrder: Int,
    isBuiltIn: Boolean,
): JSONObject = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("type", type)
    put("sortOrder", sortOrder)
    put("isBuiltIn", isBuiltIn)
}

internal fun transactionEntityToJson(
    id: Long,
    title: String,
    amountMinor: Long,
    currencyCode: String,
    type: String,
    categoryId: Long,
    occurredAtEpochMillis: Long,
    note: String?,
    createdAtEpochMillis: Long,
): JSONObject = JSONObject().apply {
    put("id", id)
    put("title", title)
    put("amountMinor", amountMinor)
    put("currencyCode", currencyCode)
    put("type", type)
    put("categoryId", categoryId)
    put("occurredAtEpochMillis", occurredAtEpochMillis)
    put("note", note ?: JSONObject.NULL)
    put("createdAtEpochMillis", createdAtEpochMillis)
}

internal fun categoryFromJson(obj: JSONObject): CategoryRow = CategoryRow(
    id = obj.getLong("id"),
    name = obj.getString("name"),
    type = obj.getString("type"),
    sortOrder = obj.optInt("sortOrder", 0),
    isBuiltIn = obj.optBoolean("isBuiltIn", false),
)

internal fun transactionFromJson(obj: JSONObject): TransactionRow = TransactionRow(
    id = obj.getLong("id"),
    title = obj.getString("title"),
    amountMinor = obj.getLong("amountMinor"),
    currencyCode = obj.getString("currencyCode"),
    type = obj.getString("type"),
    categoryId = obj.getLong("categoryId"),
    occurredAtEpochMillis = obj.getLong("occurredAtEpochMillis"),
    note = if (obj.isNull("note")) null else obj.optString("note").ifEmpty { null },
    createdAtEpochMillis = obj.getLong("createdAtEpochMillis"),
)

internal data class CategoryRow(
    val id: Long,
    val name: String,
    val type: String,
    val sortOrder: Int,
    val isBuiltIn: Boolean,
)

internal data class TransactionRow(
    val id: Long,
    val title: String,
    val amountMinor: Long,
    val currencyCode: String,
    val type: String,
    val categoryId: Long,
    val occurredAtEpochMillis: Long,
    val note: String?,
    val createdAtEpochMillis: Long,
)
