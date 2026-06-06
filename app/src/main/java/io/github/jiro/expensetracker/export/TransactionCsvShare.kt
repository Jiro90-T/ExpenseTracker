package io.github.jiro.expensetracker.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Writes a CSV to the app's cache and fires a system share-sheet Intent for it.
 *
 * File lives in <cacheDir>/exports/ so the OS can clean it up; the FileProvider
 * declared in the manifest hands a content:// URI to the chooser, with
 * FLAG_GRANT_READ_URI_PERMISSION so receiving apps can read it.
 */
object TransactionCsvShare {

    private const val SUBDIR = "exports"
    private const val FILE_PROVIDER_SUFFIX = ".fileprovider"

    suspend fun share(context: Context, csv: String, periodLabel: String): Intent = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, SUBDIR).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val safePeriod = periodLabel.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val file = File(dir, "transactions-${safePeriod}-${stamp}.csv")
        file.writeText(csv)

        val authority = "${context.packageName}$FILE_PROVIDER_SUFFIX"
        val uri = FileProvider.getUriForFile(context, authority, file)

        Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Transactions $periodLabel")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
