package io.github.jiro.expensetracker.data.accountimport

/** One parsed-but-not-yet-resolved CSV row. `lineNumber` is 1-based and matches the file. */
data class RawImportRow(
    val lineNumber: Int,
    val name: String,
    val type: String,
    val currency: String,
    val balanceMinor: Long,
)

/** A `RawImportRow` after resolution against the existing account state. */
data class ResolvedImportRow(
    val raw: RawImportRow,
    val status: ImportStatus,
)

/** The resolved status of one CSV row. */
sealed interface ImportStatus {
    data object WillCreate : ImportStatus
    data object WillUpdate : ImportStatus
    data class Rejected(val reason: String) : ImportStatus
}

/** Parser output. `Ok.rejected` carries rows that failed per-row validation. */
sealed interface ParseResult {
    data class Ok(
        val rows: List<RawImportRow>,
        val rejected: List<Pair<Int, String>>,
    ) : ParseResult
    data class Failed(val reason: String) : ParseResult
}

/** Everything the SettingsScreen needs to render the preview. */
data class ImportPreview(
    val fileName: String,
    val rows: List<ResolvedImportRow>,
)

/** Counts after a successful apply. */
data class ImportApplyResult(val created: Int, val updated: Int)