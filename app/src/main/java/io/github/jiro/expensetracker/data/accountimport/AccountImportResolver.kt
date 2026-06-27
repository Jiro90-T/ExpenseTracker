package io.github.jiro.expensetracker.data.accountimport

import io.github.jiro.expensetracker.data.local.AccountEntity

/** Pure resolver: assigns each parser-valid row a WillCreate/WillUpdate/Rejected status. */
object AccountImportResolver {

    fun resolve(
        rawRows: List<RawImportRow>,
        accountsByName: Map<String, AccountEntity>,
        txnCountsByAccountId: Map<Long, Int>,
    ): List<ResolvedImportRow> {
        val seenNames = mutableMapOf<String, Int>()
        return rawRows.map { raw -> resolveOne(raw, accountsByName, txnCountsByAccountId, seenNames) }
    }

    private fun resolveOne(
        raw: RawImportRow,
        accountsByName: Map<String, AccountEntity>,
        txnCountsByAccountId: Map<Long, Int>,
        seenNames: MutableMap<String, Int>,
    ): ResolvedImportRow {
        val key = raw.name.lowercase()
        seenNames[key]?.let { priorLine ->
            return ResolvedImportRow(raw, ImportStatus.Rejected("duplicate name in file (also on line $priorLine)"))
        }
        val existing = accountsByName[key]
        val status: ImportStatus = when {
            existing == null -> ImportStatus.WillCreate
            existing.currencyCode != raw.currency ->
                ImportStatus.Rejected("currency mismatch: account is ${existing.currencyCode}, CSV says ${raw.currency}")
            (txnCountsByAccountId[existing.id] ?: 0) > 0 ->
                ImportStatus.Rejected("account has ${txnCountsByAccountId[existing.id]} transactions; delete them first")
            else -> ImportStatus.WillUpdate
        }
        seenNames[key] = raw.lineNumber
        return ResolvedImportRow(raw, status)
    }
}
