package io.github.jiro.expensetracker.data.accountimport

import android.net.Uri

/**
 * Orchestrates the CSV account-import flow: reads a user-picked file via
 * ContentResolver, parses it, resolves each row against the current account
 * state, and applies the resolved rows in a single Room transaction.
 *
 * Lives next to its impl so the ViewModel can depend on this interface
 * (mockable in tests). The pure parser/resolver do the row-level work;
 * this layer owns the I/O and DB transaction.
 */
interface AccountImportRepository {
    /** Read URI, parse, resolve against current accounts. Throws on I/O error. */
    suspend fun preview(uri: Uri): ImportPreview

    /** Apply a previously-previewed import in a single transaction. */
    suspend fun apply(preview: ImportPreview): ImportApplyResult
}