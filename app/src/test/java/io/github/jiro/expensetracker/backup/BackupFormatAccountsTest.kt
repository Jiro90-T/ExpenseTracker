package io.github.jiro.expensetracker.backup

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip tests for the v4 backup format additions: the `accounts`
 * envelope array plus the `accountId` / `transferAccountId` fields on
 * each transaction.
 */
class BackupFormatAccountsTest {

    // ---- account JSON ----

    @Test
    fun accountToJson_includesArchivedAtEpochMillis() {
        val obj = accountEntityToJson(
            id = 1L,
            name = "Cash wallet",
            type = "CASH",
            icon = "💵",
            color = 0xFF00FF00.toInt(),
            currencyCode = "USD",
            openingBalanceMinor = 0L,
            createdAtEpochMillis = 1_700_000_000_000L,
            archived = true,
            archivedAtEpochMillis = 1_750_000_000_000L,
            sortOrder = 0,
        )
        assertTrue(obj.getBoolean("archived"))
        assertEquals(1_750_000_000_000L, obj.getLong("archivedAtEpochMillis"))
    }

    @Test
    fun accountToJson_nullArchivedAt_writesJsonNull() {
        val obj = accountEntityToJson(
            id = 1L,
            name = "Active",
            type = "CASH",
            icon = "💵",
            color = 0,
            currencyCode = "USD",
            openingBalanceMinor = 0L,
            createdAtEpochMillis = 0L,
            archived = false,
            archivedAtEpochMillis = null,
            sortOrder = 0,
        )
        assertFalse(obj.getBoolean("archived"))
        assertEquals(JSONObject.NULL, obj.get("archivedAtEpochMillis"))
    }

    @Test
    fun accountFromJson_roundTrip_preservesClosedState() {
        val src = accountEntityToJson(
            id = 5L,
            name = "Savings",
            type = "BANK",
            icon = "🏦",
            color = 0xFF0000FF.toInt(),
            currencyCode = "JPY",
            openingBalanceMinor = 1_000_000L,
            createdAtEpochMillis = 1_700_000_000_000L,
            archived = true,
            archivedAtEpochMillis = 1_750_000_000_000L,
            sortOrder = 2,
        )
        val row = accountFromJson(src)
        assertEquals(5L, row.id)
        assertEquals("Savings", row.name)
        assertEquals("BANK", row.type)
        assertEquals("🏦", row.icon)
        assertEquals(0xFF0000FF.toInt(), row.color)
        assertEquals("JPY", row.currencyCode)
        assertEquals(1_000_000L, row.openingBalanceMinor)
        assertEquals(1_700_000_000_000L, row.createdAtEpochMillis)
        assertEquals(true, row.archived)
        assertEquals(1_750_000_000_000L, row.archivedAtEpochMillis)
        assertEquals(2, row.sortOrder)
    }

    @Test
    fun accountFromJson_preV4MissingFields_defaultsToActive() {
        // Simulate a v1..v3 backup: archived + archivedAtEpochMillis absent.
        val obj = JSONObject().apply {
            put("id", 1L)
            put("name", "Cash wallet")
            put("type", "CASH")
            put("icon", "💵")
            put("color", 0)
            put("currencyCode", "USD")
            put("openingBalanceMinor", 0L)
            put("createdAtEpochMillis", 1_700_000_000_000L)
            put("sortOrder", 0)
        }
        val row = accountFromJson(obj)
        assertFalse(row.archived)
        assertNull(row.archivedAtEpochMillis)
    }

    // ---- transaction JSON: accountId + transferAccountId ----

    @Test
    fun transactionToJson_includesAccountIdAndTransferAccountId() {
        val obj = transactionEntityToJson(
            id = 1L,
            title = "Transfer",
            amountMinor = 5_000L,
            currencyCode = "USD",
            type = "TRANSFER",
            categoryId = 0L,
            occurredAtEpochMillis = 1_700_000_000_000L,
            note = null,
            createdAtEpochMillis = 1_700_000_000_000L,
            recurringGroupId = null,
            recurrenceKind = null,
            recurrenceInterval = 1,
            recurrenceEndAt = null,
            recurrenceMaxOccurrences = null,
            recurrenceNextAt = null,
            receiptPath = null,
            accountId = 1L,
            transferAccountId = 2L,
        )
        assertEquals(1L, obj.getLong("accountId"))
        assertEquals(2L, obj.getLong("transferAccountId"))
    }

    @Test
    fun transactionToJson_nullTransferAccount_writesJsonNull() {
        val obj = transactionEntityToJson(
            id = 1L,
            title = "Coffee",
            amountMinor = 540L,
            currencyCode = "USD",
            type = "EXPENSE",
            categoryId = 2L,
            occurredAtEpochMillis = 1_700_000_000_000L,
            note = null,
            createdAtEpochMillis = 1_700_000_000_000L,
            recurringGroupId = null,
            recurrenceKind = null,
            recurrenceInterval = 1,
            recurrenceEndAt = null,
            recurrenceMaxOccurrences = null,
            recurrenceNextAt = null,
            receiptPath = null,
            accountId = 1L,
            transferAccountId = null,
        )
        assertEquals(JSONObject.NULL, obj.get("transferAccountId"))
    }

    @Test
    fun transactionFromJson_preV4MissingAccountId_defaultsTo1() {
        // Simulate a v3 transaction: accountId + transferAccountId absent.
        val obj = JSONObject().apply {
            put("id", 1L)
            put("title", "Coffee")
            put("amountMinor", 540L)
            put("currencyCode", "USD")
            put("type", "EXPENSE")
            put("categoryId", 2L)
            put("occurredAtEpochMillis", 1_700_000_000_000L)
            put("note", JSONObject.NULL)
            put("createdAtEpochMillis", 1_700_000_000_000L)
            put("recurringGroupId", JSONObject.NULL)
            put("recurrenceKind", JSONObject.NULL)
            put("recurrenceInterval", 1)
            put("recurrenceEndAt", JSONObject.NULL)
            put("recurrenceMaxOccurrences", JSONObject.NULL)
            put("recurrenceNextAt", JSONObject.NULL)
            put("receiptPath", JSONObject.NULL)
        }
        val row = transactionFromJson(obj)
        // Fall back to the seeded "Cash wallet" so FK on restore doesn't blow up.
        assertEquals(1L, row.accountId)
        assertNull(row.transferAccountId)
    }

    @Test
    fun transactionFromJson_v4Transfer_parsesBothAccountIds() {
        val obj = JSONObject().apply {
            put("id", 1L)
            put("title", "Transfer")
            put("amountMinor", 5_000L)
            put("currencyCode", "USD")
            put("type", "TRANSFER")
            put("categoryId", 0L)
            put("occurredAtEpochMillis", 1_700_000_000_000L)
            put("note", JSONObject.NULL)
            put("createdAtEpochMillis", 1_700_000_000_000L)
            put("recurringGroupId", JSONObject.NULL)
            put("recurrenceKind", JSONObject.NULL)
            put("recurrenceInterval", 1)
            put("recurrenceEndAt", JSONObject.NULL)
            put("recurrenceMaxOccurrences", JSONObject.NULL)
            put("recurrenceNextAt", JSONObject.NULL)
            put("receiptPath", JSONObject.NULL)
            put("accountId", 3L)
            put("transferAccountId", 7L)
        }
        val row = transactionFromJson(obj)
        assertEquals(3L, row.accountId)
        assertEquals(7L, row.transferAccountId)
    }

    // ---- envelope accounts array + version gate ----

    @Test
    fun envelope_v4CarriesAccountsArray() {
        val envelope = BackupFormat.envelope(1_700_000_000_000L, "0.18.7")
        BackupFormat.putAccountsArray(
            envelope,
            org.json.JSONArray().apply {
                put(
                    accountEntityToJson(
                        id = 1L, name = "A", type = "CASH", icon = "💵", color = 0,
                        currencyCode = "USD", openingBalanceMinor = 0L,
                        createdAtEpochMillis = 0L, archived = false,
                        archivedAtEpochMillis = null, sortOrder = 0,
                    ),
                )
            },
        )
        val arr = BackupFormat.accountsArrayOf(envelope)
        assertEquals(1, arr.length())
        assertEquals("A", arr.getJSONObject(0).getString("name"))
    }

    @Test
    fun parseEnvelope_v3MissingAccountsArray_returnsEmpty() {
        // Simulate a v3 backup (pre-accounts array).
        val json = """
            {
              "formatVersion": 3,
              "exportedAtEpochMillis": 1_700_000_000_000,
              "appVersionName": "0.18.6",
              "categories": [],
              "transactions": []
            }
        """.trimIndent()
        val envelope = BackupFormat.parseEnvelope(json)
        assertEquals(0, BackupFormat.accountsArrayOf(envelope).length())
    }

    @Test
    fun formatVersion_is4() {
        assertEquals(4, BackupFormat.FORMAT_VERSION)
    }
}