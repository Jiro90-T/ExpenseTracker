package io.github.jiro.expensetracker.backup

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.room.withTransaction
import io.github.jiro.expensetracker.data.local.AppDatabase
import io.github.jiro.expensetracker.data.local.CategoryEntity
import io.github.jiro.expensetracker.data.local.TransactionEntity
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Owns the JSON backup format and the export/import round-trip.
 *
 * Receipts are out of scope for this iteration — when the receipt
 * feature lands, the backup widens from a `.json` to a `.zip`
 * containing the manifest + media. The format version is bumped
 * at that point.
 */
@Singleton
class BackupManager @Inject constructor(
    private val database: AppDatabase,
) {
    /** Export the entire database to a JSON string. */
    suspend fun exportToJson(
        appVersionName: String,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): String = withContext(Dispatchers.IO) {
        val envelope = BackupFormat.envelope(nowEpochMillis, appVersionName)

        // Read everything in a single suspend transaction for snapshot
        // consistency — if the user is mid-typing, we don't want the
        // export to mix pre-edit and post-edit rows.
        val (categories, transactions) = database.withTransaction {
            val catList = database.categoryDao().observeAllOnce()
            val txnList = database.transactionDao().observeAllForExport()
            catList to txnList
        }

        BackupFormat.putCategoriesArray(
            envelope,
            JSONArray().also { arr ->
                categories.forEach { c ->
                    arr.put(categoryEntityToJson(c.id, c.name, c.type, c.sortOrder, c.isBuiltIn))
                }
            },
        )
        BackupFormat.putTransactionsArray(
            envelope,
            JSONArray().also { arr ->
                transactions.forEach { t ->
                    arr.put(
                        transactionEntityToJson(
                            id = t.id,
                            title = t.title,
                            amountMinor = t.amountMinor,
                            currencyCode = t.currencyCode,
                            type = t.type,
                            categoryId = t.categoryId,
                            occurredAtEpochMillis = t.occurredAtEpochMillis,
                            note = t.note,
                            createdAtEpochMillis = t.createdAtEpochMillis,
                            recurringGroupId = t.recurringGroupId,
                            recurrenceKind = t.recurrenceKind,
                            recurrenceInterval = t.recurrenceInterval,
                            recurrenceEndAt = t.recurrenceEndAt,
                            recurrenceMaxOccurrences = t.recurrenceMaxOccurrences,
                            recurrenceNextAt = t.recurrenceNextAt,
                        ),
                    )
                }
            },
        )

        envelope.toString(2) // pretty-print so the file is human-readable
    }

    /**
     * Write the export to a file in the cache and return a content://
     * Uri to share via the system share sheet.
     */
    fun writeExportToCache(context: Context, json: String, nowEpochMillis: Long): Uri {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date(nowEpochMillis))
        val file = File(dir, "${BackupFormat.FILE_PREFIX}$stamp.json")
        file.writeText(json)
        val authority = "${context.packageName}.fileprovider"
        return FileProvider.getUriForFile(context, authority, file)
    }

    /**
     * Import a backup from a content Uri. Replaces all current data
     * (categories + transactions) with the backup's contents. Caller
     * is responsible for user confirmation — this is destructive.
     *
     * Returns a [Result] for the UI to surface success/failure toasts.
     */
    suspend fun importFromUri(context: Context, uri: Uri): Result<ImportSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val json = context.contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader().readText()
            } ?: error("Could not open input stream for $uri")

            val envelope = BackupFormat.parseEnvelope(json)
            val catArr: JSONArray = BackupFormat.categoriesArrayOf(envelope)
            val txnArr: JSONArray = BackupFormat.transactionsArrayOf(envelope)

            val categories = (0 until catArr.length()).map { i ->
                categoryFromJson(catArr.getJSONObject(i))
            }
            val transactions = (0 until txnArr.length()).map { i ->
                transactionFromJson(txnArr.getJSONObject(i))
            }

            // Wipe + re-insert. We use REPLACE so the original IDs from
            // the backup are preserved where they don't collide with new
            // built-ins; built-ins are re-inserted as-is.
            database.withTransaction {
                database.transactionDao().deleteAll()
                database.categoryDao().deleteAllNonBuiltIn()
                database.categoryDao().insertAllReplacing(
                    categories.map { c ->
                        CategoryEntity(
                            id = c.id,
                            name = c.name,
                            type = c.type,
                            sortOrder = c.sortOrder,
                            isBuiltIn = c.isBuiltIn,
                        )
                    }
                )
                database.transactionDao().insertAll(
                    transactions.map { t ->
                        TransactionEntity(
                            id = t.id,
                            title = t.title,
                            amountMinor = t.amountMinor,
                            currencyCode = t.currencyCode,
                            type = t.type,
                            categoryId = t.categoryId,
                            occurredAtEpochMillis = t.occurredAtEpochMillis,
                            note = t.note,
                            createdAtEpochMillis = t.createdAtEpochMillis,
                            recurringGroupId = t.recurringGroupId,
                            recurrenceKind = t.recurrenceKind,
                            recurrenceInterval = t.recurrenceInterval,
                            recurrenceEndAt = t.recurrenceEndAt,
                            recurrenceMaxOccurrences = t.recurrenceMaxOccurrences,
                            recurrenceNextAt = t.recurrenceNextAt,
                        )
                    }
                )
            }

            ImportSummary(
                categoriesRestored = categories.size,
                transactionsRestored = transactions.size,
            )
        }
    }
}

data class ImportSummary(
    val categoriesRestored: Int,
    val transactionsRestored: Int,
)
