package io.github.jiro.expensetracker.data.accountimport

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jiro.expensetracker.data.repository.AccountRepository
import io.github.jiro.expensetracker.data.repository.TransactionRepository
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Default [AccountImportRepository] impl. Reads the URI through a
 * [android.content.ContentResolver], feeds the bytes to
 * [AccountImportParser], interleaves parser-rejected rows back into the
 * preview at their original line numbers, and delegates the apply path to
 * [AccountRepository.applyAccountImport] which runs the whole batch in a
 * single Room transaction.
 *
 * The URI-read helpers are `open` so tests can supply bytes without
 * standing up a [android.content.ContentProvider] — mirrors the pattern
 * [io.github.jiro.expensetracker.data.repository.ReceiptRepository] uses
 * for `saveFromUri`.
 */
@Singleton
open class AccountImportRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountRepository: AccountRepository,
    private val transactionRepository: TransactionRepository,
) : AccountImportRepository {

    override suspend fun preview(uri: Uri): ImportPreview = withContext(Dispatchers.IO) {
        val bytes = readInput(uri)
            ?: throw IOException("Could not open URI: $uri")

        val fileName = readDisplayName(uri) ?: "<unknown>"

        val parsed = AccountImportParser.parse(bytes)
        if (parsed is ParseResult.Failed) {
            throw IOException(parsed.reason)
        }
        val ok = parsed as ParseResult.Ok

        val accounts = accountRepository.observeActive().first()
        val accountsByName = accounts.associateBy { it.name.lowercase() }

        val txnCountsByAccountId: Map<Long, Int> = accounts.associate { acct ->
            acct.id to transactionRepository.countForAccount(acct.id)
        }

        val resolved = AccountImportResolver.resolve(ok.rows, accountsByName, txnCountsByAccountId)
        val merged = interleave(ok.rejected, resolved)

        ImportPreview(fileName, merged)
    }

    override suspend fun apply(preview: ImportPreview): ImportApplyResult = withContext(Dispatchers.IO) {
        val toApply = preview.rows.filter {
            it.status is ImportStatus.WillCreate || it.status is ImportStatus.WillUpdate
        }
        accountRepository.applyAccountImport(toApply, System.currentTimeMillis())
        val created = preview.rows.count { it.status is ImportStatus.WillCreate }
        val updated = preview.rows.count { it.status is ImportStatus.WillUpdate }
        ImportApplyResult(created, updated)
    }

    /**
     * Override in tests to bypass [android.content.ContentResolver]. Default
     * implementation reads the URI through the app's content resolver.
     */
    protected open fun readInput(uri: Uri): ByteArray? =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }

    /**
     * Override in tests. Default implementation queries
     * [OpenableColumns.DISPLAY_NAME] on the URI.
     */
    protected open fun readDisplayName(uri: Uri): String? {
        val cursor = context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        ) ?: return null
        return cursor.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx) else null
            } else {
                null
            }
        }
    }

    /**
     * Merge parser-rejected rows back into the resolver's output in
     * line-number order. The resolver only sees parser-valid rows, so the
     * UI would lose the rejected ones without this step.
     */
    private fun interleave(
        rejected: List<Pair<Int, String>>,
        resolved: List<ResolvedImportRow>,
    ): List<ResolvedImportRow> {
        if (rejected.isEmpty()) return resolved
        // Sort rejected by line number; resolver output is already in input
        // (line-number) order because resolve() preserves rawRows order.
        val rejectedSorted = rejected.sortedBy { it.first }
        val out = mutableListOf<ResolvedImportRow>()
        var rejIdx = 0
        for (r in resolved) {
            while (rejIdx < rejectedSorted.size && rejectedSorted[rejIdx].first < r.raw.lineNumber) {
                val (line, reason) = rejectedSorted[rejIdx]
                out += ResolvedImportRow(
                    raw = RawImportRow(
                        lineNumber = line,
                        name = "",
                        type = "",
                        currency = "",
                        balanceMinor = 0L,
                    ),
                    status = ImportStatus.Rejected(reason),
                )
                rejIdx++
            }
            out += r
        }
        while (rejIdx < rejectedSorted.size) {
            val (line, reason) = rejectedSorted[rejIdx]
            out += ResolvedImportRow(
                raw = RawImportRow(
                    lineNumber = line,
                    name = "",
                    type = "",
                    currency = "",
                    balanceMinor = 0L,
                ),
                status = ImportStatus.Rejected(reason),
            )
            rejIdx++
        }
        return out
    }
}