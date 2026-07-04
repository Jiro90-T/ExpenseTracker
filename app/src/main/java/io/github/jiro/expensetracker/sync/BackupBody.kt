package io.github.jiro.expensetracker.sync

import io.github.jiro.expensetracker.backup.AccountRow
import io.github.jiro.expensetracker.backup.CategoryRow
import io.github.jiro.expensetracker.backup.TransactionRow
import io.github.jiro.expensetracker.backup.accountEntityToJson
import io.github.jiro.expensetracker.backup.accountFromJson
import io.github.jiro.expensetracker.backup.categoryEntityToJson
import io.github.jiro.expensetracker.backup.categoryFromJson
import io.github.jiro.expensetracker.backup.transactionEntityToJson
import io.github.jiro.expensetracker.backup.transactionFromJson
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure-data wrapper around the three arrays that the v4 backup envelope
 * carries (accounts, categories, transactions). The sync codec wraps this
 * in a header (schemaVersion / lastModifiedEpochMillis / deviceId /
 * checksum); the rest of the app can read and write it without touching
 * org.json.
 *
 * `internal` because AccountRow / CategoryRow / TransactionRow in the
 * `backup` package are internal — widen visibility if a future consumer
 * outside `sync/` needs it.
 */
internal data class BackupBody(
    val accounts: List<AccountRow>,
    val categories: List<CategoryRow>,
    val transactions: List<TransactionRow>,
) {
    fun serialize(): String = JSONObject().apply {
        put("accounts", JSONArray().also { arr -> accounts.forEach { arr.put(toJson(it)) } })
        put("categories", JSONArray().also { arr -> categories.forEach { arr.put(toJson(it)) } })
        put("transactions", JSONArray().also { arr -> transactions.forEach { arr.put(toJson(it)) } })
    }.toString()

    companion object {
        fun deserialize(json: String): BackupBody {
            val obj = JSONObject(json)
            val accounts = obj.getJSONArray("accounts").let { arr ->
                List(arr.length()) { i -> accountFromJson(arr.getJSONObject(i)) }
            }
            val categories = obj.getJSONArray("categories").let { arr ->
                List(arr.length()) { i -> categoryFromJson(arr.getJSONObject(i)) }
            }
            val transactions = obj.getJSONArray("transactions").let { arr ->
                List(arr.length()) { i -> transactionFromJson(arr.getJSONObject(i)) }
            }
            return BackupBody(accounts, categories, transactions)
        }

        // One-arg overloads of the existing per-row helpers in BackupFormat.
        // The existing signatures are positional and 5-11 args deep; these
        // thin wrappers keep the call sites in this file readable.
        private fun toJson(row: AccountRow) = accountEntityToJson(
            id = row.id, name = row.name, type = row.type, icon = row.icon,
            color = row.color, currencyCode = row.currencyCode,
            openingBalanceMinor = row.openingBalanceMinor,
            createdAtEpochMillis = row.createdAtEpochMillis,
            archived = row.archived,
            archivedAtEpochMillis = row.archivedAtEpochMillis,
            sortOrder = row.sortOrder,
        )

        private fun toJson(row: CategoryRow) = categoryEntityToJson(
            id = row.id, name = row.name, type = row.type,
            sortOrder = row.sortOrder, isBuiltIn = row.isBuiltIn,
        )

        private fun toJson(row: TransactionRow) = transactionEntityToJson(
            id = row.id, title = row.title, amountMinor = row.amountMinor,
            currencyCode = row.currencyCode, type = row.type, categoryId = row.categoryId,
            occurredAtEpochMillis = row.occurredAtEpochMillis, note = row.note,
            createdAtEpochMillis = row.createdAtEpochMillis,
            recurringGroupId = row.recurringGroupId,
            recurrenceKind = row.recurrenceKind,
            recurrenceInterval = row.recurrenceInterval,
            recurrenceEndAt = row.recurrenceEndAt,
            recurrenceMaxOccurrences = row.recurrenceMaxOccurrences,
            recurrenceNextAt = row.recurrenceNextAt,
            receiptPath = row.receiptPath,
            accountId = row.accountId,
            transferAccountId = row.transferAccountId,
        )
    }
}
