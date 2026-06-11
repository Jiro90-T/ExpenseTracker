package io.github.jiro.expensetracker.backup

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.room.withTransaction
import io.github.jiro.expensetracker.data.local.AppDatabase
import io.github.jiro.expensetracker.data.local.CategoryEntity
import io.github.jiro.expensetracker.data.local.TransactionEntity
import io.github.jiro.expensetracker.data.repository.ReceiptRepository
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Owns the v3 ZIP backup format (manifest + media) and the v1/v2 JSON
 * fallback format. The export is a `.zip` containing `manifest.json`
 * (the v3 envelope) and a `receipts/` folder with the receipt media.
 * Restore accepts both the new `.zip` and the legacy `.json` (v1, v2)
 * for backward compatibility — v2 backups restore with `receiptPath = null`.
 */
@Singleton
class BackupManager @Inject constructor(
    private val database: AppDatabase,
    private val receiptRepository: ReceiptRepository,
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
                            receiptPath = t.receiptPath,
                        ),
                    )
                }
            },
        )

        envelope.toString(2) // pretty-print so the file is human-readable
    }

    /**
     * Export the entire database to a `.zip` containing the manifest JSON
     * (v3 envelope) and the receipt media files. Returns the file path
     * the caller can hand to a FileProvider for sharing.
     */
    suspend fun exportToZip(
        appVersionName: String,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): String = withContext(Dispatchers.IO) {
        // 1. Build the manifest (reuses the JSON path; v3 just adds receiptPath).
        val json = exportToJson(appVersionName, nowEpochMillis)

        // 2. Collect distinct receipt paths.
        val receiptPaths: Set<String> = withContext(Dispatchers.IO) {
            database.withTransaction {
                database.transactionDao().observeAllForExport()
                    .mapNotNull { it.receiptPath }
                    .toSet()
            }
        }

        // 3. Create the zip in cache.
        val dir = File(System.getProperty("java.io.tmpdir") ?: "/data/local/tmp", "exports").apply { mkdirs() }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date(nowEpochMillis))
        val zipFile = File(dir, "${BackupFormat.FILE_PREFIX}$stamp.${BackupFormat.BACKUP_FILE_EXT}")

        java.util.zip.ZipOutputStream(java.io.FileOutputStream(zipFile)).use { zos ->
            // 3a. Write manifest.json as the first entry.
            zos.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
            zos.write(json.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // 3b. Write each receipt under receipts/<relativePath>.
            for (relativePath in receiptPaths) {
                val src = receiptRepository.absolutePath(relativePath)
                if (!src.isFile) continue
                zos.putNextEntry(java.util.zip.ZipEntry("receipts/$relativePath"))
                src.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        return@withContext zipFile.absolutePath
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
                            receiptPath = t.receiptPath,
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

    /**
     * Restore a v3 `.zip` backup. Replaces all current data (categories +
     * transactions + receipts) with the backup's contents. Caller is
     * responsible for user confirmation — this is destructive.
     */
    suspend fun importFromZipUri(context: Context, uri: Uri): Result<ImportSummary> = withContext(Dispatchers.IO) {
        runCatching {
            var missingReceiptCount = 0
            val missingPaths = mutableListOf<String>()
            // 1. Open the zip and read manifest.json.
            val json: String = context.contentResolver.openInputStream(uri)?.use { input ->
                java.util.zip.ZipInputStream(input).use { zis ->
                    var entry = zis.nextEntry
                    var found: String? = null
                    while (entry != null) {
                        if (entry.name == "manifest.json") {
                            found = zis.readBytes().toString(Charsets.UTF_8)
                            break
                        }
                        entry = zis.nextEntry
                    }
                    found ?: error("manifest.json not found in zip")
                }
            } ?: error("Could not open input stream for $uri")

            val envelope = BackupFormat.parseEnvelope(json)
            val catArr: JSONArray = BackupFormat.categoriesArrayOf(envelope)
            val txnArr: JSONArray = BackupFormat.transactionsArrayOf(envelope)

            val categories = (0 until catArr.length()).map { categoryFromJson(catArr.getJSONObject(it)) }
            val transactions = (0 until txnArr.length()).map { transactionFromJson(txnArr.getJSONObject(it)) }
            val referencedReceipts = transactions.mapNotNull { it.receiptPath }.toSet()

            // 2. Wipe + restore (receipts dir + DB).
            val receiptsDir = receiptRepository.receiptsDir
            receiptsDir.deleteRecursively()
            receiptsDir.mkdirs()

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
                            receiptPath = t.receiptPath,
                        )
                    }
                )
            }

            // 3. Extract receipts. For each referenced path, look for the zip
            //    entry `receipts/<relativePath>`; if found, write to
            //    `<filesDir>/receipts/<relativePath>`. If missing, count it.
            val byName = mutableMapOf<String, java.util.zip.ZipEntry>()
            context.contentResolver.openInputStream(uri)?.use { raw ->
                java.util.zip.ZipInputStream(raw).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        byName[entry.name] = entry
                        entry = zis.nextEntry
                    }
                }
            }
            for (relativePath in referencedReceipts) {
                if (byName["receipts/$relativePath"] == null) {
                    missingPaths.add(relativePath)
                    missingReceiptCount += 1
                }
            }

            // Second pass: actually copy the bytes. (We needed the first pass
            // to enumerate entry names without consuming them; ZipInputStream
            // is single-pass.)
            context.contentResolver.openInputStream(uri)?.use { raw ->
                java.util.zip.ZipInputStream(raw).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (entry.name.startsWith("receipts/")) {
                            val rel = entry.name.removePrefix("receipts/")
                            if (rel in referencedReceipts) {
                                val dest = File(receiptsDir, rel)
                                dest.parentFile?.mkdirs()
                                java.io.FileOutputStream(dest).use { out -> zis.copyTo(out) }
                            }
                        }
                        entry = zis.nextEntry
                    }
                }
            }

            // 4. Wipe receiptPath for transactions whose files we couldn't restore.
            //    Only the missing ones — leave the successfully-restored paths intact.
            if (missingPaths.isNotEmpty()) {
                database.withTransaction {
                    database.transactionDao().clearReceiptPathsFor(missingPaths)
                }
            }

            ImportSummary(
                categoriesRestored = categories.size,
                transactionsRestored = transactions.size,
                missingReceiptCount = missingReceiptCount,
            )
        }
    }
}

data class ImportSummary(
    val categoriesRestored: Int,
    val transactionsRestored: Int,
    val missingReceiptCount: Int = 0,
)
