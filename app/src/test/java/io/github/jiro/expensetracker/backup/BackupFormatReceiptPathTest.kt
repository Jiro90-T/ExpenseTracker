package io.github.jiro.expensetracker.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupFormatReceiptPathTest {

    @Test
    fun transactionToJson_includesReceiptPath() {
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
            receiptPath = "abc123.jpg",
        )
        assertEquals("abc123.jpg", obj.getString("receiptPath"))
    }

    @Test
    fun transactionToJson_nullReceiptPath_writesJsonNull() {
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
        )
        assertEquals(org.json.JSONObject.NULL, obj.get("receiptPath"))
    }

    @Test
    fun transactionFromJson_receiptPath_returnsValue() {
        val obj = org.json.JSONObject().apply {
            put("id", 1L)
            put("title", "Coffee")
            put("amountMinor", 540L)
            put("currencyCode", "USD")
            put("type", "EXPENSE")
            put("categoryId", 2L)
            put("occurredAtEpochMillis", 1_700_000_000_000L)
            put("note", org.json.JSONObject.NULL)
            put("createdAtEpochMillis", 1_700_000_000_000L)
            put("recurringGroupId", org.json.JSONObject.NULL)
            put("recurrenceKind", org.json.JSONObject.NULL)
            put("recurrenceInterval", 1)
            put("recurrenceEndAt", org.json.JSONObject.NULL)
            put("recurrenceMaxOccurrences", org.json.JSONObject.NULL)
            put("recurrenceNextAt", org.json.JSONObject.NULL)
            put("receiptPath", "abc123.jpg")
        }
        val row = transactionFromJson(obj)
        assertEquals("abc123.jpg", row.receiptPath)
    }

    @Test
    fun transactionFromJson_missingReceiptPath_returnsNull() {
        val obj = org.json.JSONObject().apply {
            put("id", 1L)
            put("title", "Coffee")
            put("amountMinor", 540L)
            put("currencyCode", "USD")
            put("type", "EXPENSE")
            put("categoryId", 2L)
            put("occurredAtEpochMillis", 1_700_000_000_000L)
            put("note", org.json.JSONObject.NULL)
            put("createdAtEpochMillis", 1_700_000_000_000L)
            put("recurringGroupId", org.json.JSONObject.NULL)
            put("recurrenceKind", org.json.JSONObject.NULL)
            put("recurrenceInterval", 1)
            put("recurrenceEndAt", org.json.JSONObject.NULL)
            put("recurrenceMaxOccurrences", org.json.JSONObject.NULL)
            put("recurrenceNextAt", org.json.JSONObject.NULL)
            // receiptPath absent — simulates a v2 backup.
        }
        val row = transactionFromJson(obj)
        assertNull(row.receiptPath)
    }
}
