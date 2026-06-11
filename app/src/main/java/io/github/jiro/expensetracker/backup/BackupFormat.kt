package io.github.jiro.expensetracker.backup

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON shape for a full-database export. Stable across versions as long as
 * [FORMAT_VERSION] is unchanged; bump the version when fields are added or
 * renamed so older builds can refuse the import.
 */
internal object BackupFormat {
    /**
     * v3: adds receiptPath. Older backups (v1, v2) restore fine — the new
     * column is nullable and defaults to null.
     */
    const val FORMAT_VERSION = 3
    const val MIME_TYPE = "application/json"
    const val MIME_TYPE_ZIP = "application/zip"
    const val FILE_PREFIX = "expense-tracker-backup-"

    const val BACKUP_FILE_EXT = "zip"
    const val BACKUP_FILE_EXT_LEGACY = "json"

    fun envelope(exportedAtEpochMillis: Long, appVersionName: String): JSONObject =
        JSONObject().apply {
            put("formatVersion", FORMAT_VERSION)
            put("exportedAtEpochMillis", exportedAtEpochMillis)
            put("appVersionName", appVersionName)
        }

    fun parseEnvelope(json: String): JSONObject =
        JSONObject(json).also { obj ->
            val v = obj.optInt("formatVersion", -1)
            // Accept v1 and v2 too — older backups restore fine; newer
            // fields are simply absent and the new rows get the column
            // default (null / 1).
            require(v in 1..FORMAT_VERSION) {
                "Unsupported backup format version: $v (expected 1, 2, or $FORMAT_VERSION)"
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
    recurringGroupId: String?,
    recurrenceKind: String?,
    recurrenceInterval: Int,
    recurrenceEndAt: Long?,
    recurrenceMaxOccurrences: Int?,
    recurrenceNextAt: Long?,
    receiptPath: String? = null,
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
    put("recurringGroupId", recurringGroupId ?: JSONObject.NULL)
    put("recurrenceKind", recurrenceKind ?: JSONObject.NULL)
    put("recurrenceInterval", recurrenceInterval)
    put("recurrenceEndAt", recurrenceEndAt ?: JSONObject.NULL)
    put("recurrenceMaxOccurrences", recurrenceMaxOccurrences ?: JSONObject.NULL)
    put("recurrenceNextAt", recurrenceNextAt ?: JSONObject.NULL)
    put("receiptPath", receiptPath ?: JSONObject.NULL)
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
    // Recurring fields are absent in v1 backups — opt...() returns the
    // supplied default (null / 1) in that case.
    recurringGroupId = if (obj.has("recurringGroupId") && !obj.isNull("recurringGroupId")) obj.getString("recurringGroupId") else null,
    recurrenceKind = if (obj.has("recurrenceKind") && !obj.isNull("recurrenceKind")) obj.getString("recurrenceKind") else null,
    recurrenceInterval = obj.optInt("recurrenceInterval", 1),
    recurrenceEndAt = if (obj.has("recurrenceEndAt") && !obj.isNull("recurrenceEndAt")) obj.getLong("recurrenceEndAt") else null,
    recurrenceMaxOccurrences = if (obj.has("recurrenceMaxOccurrences") && !obj.isNull("recurrenceMaxOccurrences")) obj.getInt("recurrenceMaxOccurrences") else null,
    recurrenceNextAt = if (obj.has("recurrenceNextAt") && !obj.isNull("recurrenceNextAt")) obj.getLong("recurrenceNextAt") else null,
    receiptPath = if (obj.has("receiptPath") && !obj.isNull("receiptPath")) obj.getString("receiptPath") else null,
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
    val recurringGroupId: String?,
    val recurrenceKind: String?,
    val recurrenceInterval: Int,
    val recurrenceEndAt: Long?,
    val recurrenceMaxOccurrences: Int?,
    val recurrenceNextAt: Long?,
    val receiptPath: String?,
)
