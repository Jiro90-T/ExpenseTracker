package io.github.jiro.expensetracker.data.accountimport

import io.github.jiro.expensetracker.data.local.AccountEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountImportResolverTest {

    private fun row(
        line: Int = 2,
        name: String = "Cash",
        type: String = "CASH",
        currency: String = "USD",
        balanceMinor: Long = 0L,
    ) = RawImportRow(line, name, type, currency, balanceMinor)

    private fun acct(
        id: Long = 1L,
        name: String = "Cash",
        currency: String = "USD",
    ) = AccountEntity(
        id = id,
        name = name,
        type = "CASH",
        icon = "💵",
        color = 0xFF43A047.toInt(),
        currencyCode = currency,
        createdAtEpochMillis = 0L,
    )

    private fun resolve(
        rows: List<RawImportRow>,
        accounts: Map<String, AccountEntity> = emptyMap(),
        counts: Map<Long, Int> = emptyMap(),
    ) = AccountImportResolver.resolve(rows, accounts, counts)

    @Test fun resolve_missingAccount_willCreate() {
        val out = resolve(listOf(row(name = "NewAcct")))
        assertEquals(1, out.size)
        assertEquals(ImportStatus.WillCreate, out[0].status)
    }

    @Test fun resolve_existingAccountNoTxns_willUpdate() {
        val accounts = mapOf("cash" to acct(name = "Cash"))
        val out = resolve(listOf(row(name = "Cash")), accounts, mapOf(1L to 0))
        assertEquals(ImportStatus.WillUpdate, out[0].status)
    }

    @Test fun resolve_existingAccount_txnCountKeyAbsent_treatedAsZero() {
        // Existing account, currency matches, txnCountsByAccountId does NOT contain existing.id.
        // The resolver should default the missing count to 0 → WillUpdate.
        val accounts = mapOf("cash" to acct(name = "Cash"))
        val out = resolve(listOf(row(name = "Cash")), accounts, emptyMap())
        assertEquals(ImportStatus.WillUpdate, out[0].status)
    }

    @Test fun resolve_currencyMismatch_rejected() {
        val accounts = mapOf("cash" to acct(name = "Cash", currency = "USD"))
        val out = resolve(listOf(row(name = "Cash", currency = "PHP")), accounts, mapOf(1L to 0))
        val status = out[0].status
        assertTrue("expected Rejected, got $status", status is ImportStatus.Rejected)
        val reason = (status as ImportStatus.Rejected).reason
        assertTrue("reason should mention USD, got: $reason", reason.contains("USD"))
        assertTrue("reason should mention PHP, got: $reason", reason.contains("PHP"))
    }

    @Test fun resolve_accountHasTxns_rejected() {
        val accounts = mapOf("cash" to acct(name = "Cash"))
        val out = resolve(listOf(row(name = "Cash")), accounts, mapOf(1L to 5))
        val status = out[0].status
        assertTrue("expected Rejected, got $status", status is ImportStatus.Rejected)
        val reason = (status as ImportStatus.Rejected).reason
        assertTrue("reason should mention 5 transactions, got: $reason", reason.contains("5 transactions"))
    }

    @Test fun resolve_nameMatchIsCaseInsensitive() {
        val accounts = mapOf("cash" to acct(name = "Cash"))
        val out = resolve(listOf(row(name = "CASH")), accounts, mapOf(1L to 0))
        assertEquals(ImportStatus.WillUpdate, out[0].status)
    }

    @Test fun resolve_preservesLineNumberAndOrder() {
        val a = row(line = 7, name = "A")
        val b = row(line = 2, name = "B")
        val c = row(line = 5, name = "C")
        val out = resolve(listOf(a, b, c))
        assertEquals(3, out.size)
        assertEquals(7, out[0].raw.lineNumber)
        assertEquals(2, out[1].raw.lineNumber)
        assertEquals(5, out[2].raw.lineNumber)
        assertEquals(a, out[0].raw)
        assertEquals(b, out[1].raw)
        assertEquals(c, out[2].raw)
    }

    @Test fun resolve_duplicateNameInFile_secondRowRejected() {
        val first = row(line = 2, name = "Cash")
        val second = row(line = 5, name = "cash")
        val out = resolve(listOf(first, second))
        assertEquals(ImportStatus.WillCreate, out[0].status)
        val status = out[1].status
        assertTrue("expected Rejected, got $status", status is ImportStatus.Rejected)
        val reason = (status as ImportStatus.Rejected).reason
        assertTrue("reason should mention duplicate, got: $reason", reason.contains("duplicate"))
        assertTrue("reason should mention prior line 2, got: $reason", reason.contains("line 2"))
    }
}
