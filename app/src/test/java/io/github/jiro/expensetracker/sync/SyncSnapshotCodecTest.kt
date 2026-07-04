package io.github.jiro.expensetracker.sync

import io.github.jiro.expensetracker.backup.AccountRow
import io.github.jiro.expensetracker.backup.BackupFormat
import io.github.jiro.expensetracker.backup.CategoryRow
import io.github.jiro.expensetracker.backup.TransactionRow
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class SyncSnapshotCodecTest {

    private fun sampleBody(): BackupBody = BackupBody(
        accounts = listOf(
            AccountRow(
                id = 1L, name = "Cash wallet", type = "CASH", icon = "💵",
                color = 0xFF00FF00.toInt(), currencyCode = "USD",
                openingBalanceMinor = 0L, createdAtEpochMillis = 1_700_000_000_000L,
                archived = false, archivedAtEpochMillis = null, sortOrder = 0,
            ),
        ),
        categories = listOf(
            CategoryRow(id = 1L, name = "Food", type = "EXPENSE", sortOrder = 0, isBuiltIn = true),
        ),
        transactions = listOf(
            TransactionRow(
                id = 10L, title = "Groceries", amountMinor = 1500L, currencyCode = "USD",
                type = "EXPENSE", categoryId = 1L, occurredAtEpochMillis = 1_710_000_000_000L,
                note = "weekly", createdAtEpochMillis = 1_710_000_000_000L,
                recurringGroupId = null, recurrenceKind = null, recurrenceInterval = 1,
                recurrenceEndAt = null, recurrenceMaxOccurrences = null, recurrenceNextAt = null,
                receiptPath = null, accountId = 1L, transferAccountId = null,
            ),
        ),
    )

    private fun sampleSnapshot(
        lastModified: Long = 1_750_000_000_000L,
        deviceId: String = "device-abc",
    ): SyncSnapshot = SyncSnapshot(
        body = sampleBody(),
        lastModifiedEpochMillis = lastModified,
        deviceId = deviceId,
        checksum = "",
    )

    @Test
    fun encode_then_decode_returnsEquivalentSnapshot() {
        val original = sampleSnapshot()
        val json = SyncSnapshotCodec.encode(original)
        // The codec computes the checksum during encode, so rebuild
        // the "original" with the checksum the encoder will write to make
        // the round-trip equality check meaningful.
        val expectedChecksum = org.json.JSONObject(json).getString("checksum")
        val expected = original.copy(checksum = expectedChecksum)
        val decoded = SyncSnapshotCodec.decode(json)
        assertEquals(expected, decoded)
    }

    @Test
    fun encode_producesValidChecksum() {
        val snapshot = sampleSnapshot()
        val json = SyncSnapshotCodec.encode(snapshot)
        // Strip the wrapper, recompute the checksum on the body, compare.
        val outer = org.json.JSONObject(json)
        val bodyJson = outer.getJSONObject("body").toString()
        val expected = outer.getString("checksum")
        val actual = sha256Hex(bodyJson)
        assertEquals(expected, actual)
    }

    @Test
    fun decode_throwsOnChecksumMismatch() {
        val snapshot = sampleSnapshot()
        val json = SyncSnapshotCodec.encode(snapshot)
        // Flip a byte in the body to invalidate the checksum.
        val tampered = json.replace("\"Food\"", "\"Foodz\"")
        try {
            SyncSnapshotCodec.decode(tampered)
            fail("Expected SyncException(CHECKSUM_MISMATCH)")
        } catch (e: SyncException) {
            assertEquals(SyncErrorCode.CHECKSUM_MISMATCH, e.code)
        }
    }

    @Test
    fun decode_throwsOnSchemaTooNew() {
        val snapshot = sampleSnapshot()
        val json = SyncSnapshotCodec.encode(snapshot)
        val tampered = json.replace(
            "\"schemaVersion\":${BackupFormat.FORMAT_VERSION}",
            "\"schemaVersion\":${BackupFormat.FORMAT_VERSION + 1}",
        )
        try {
            SyncSnapshotCodec.decode(tampered)
            fail("Expected SyncException(SCHEMA_INCOMPATIBLE)")
        } catch (e: SyncException) {
            assertEquals(SyncErrorCode.SCHEMA_INCOMPATIBLE, e.code)
        }
    }

    @Test
    fun decode_throwsOnMalformedJson() {
        try {
            SyncSnapshotCodec.decode("not json")
            fail("Expected SyncException(MALFORMED)")
        } catch (e: SyncException) {
            assertEquals(SyncErrorCode.MALFORMED, e.code)
        }
    }

    @Test
    fun decode_throwsOnMissingBody() {
        val snapshot = sampleSnapshot()
        val json = SyncSnapshotCodec.encode(snapshot)
        // Remove the body key entirely.
        val outer = org.json.JSONObject(json)
        outer.remove("body")
        try {
            SyncSnapshotCodec.decode(outer.toString())
            fail("Expected SyncException(MALFORMED)")
        } catch (e: SyncException) {
            assertEquals(SyncErrorCode.MALFORMED, e.code)
        }
    }

    @Test
    fun decode_throwsOnMissingChecksum() {
        val snapshot = sampleSnapshot()
        val json = SyncSnapshotCodec.encode(snapshot)
        val outer = org.json.JSONObject(json)
        outer.remove("checksum")
        try {
            SyncSnapshotCodec.decode(outer.toString())
            fail("Expected SyncException(CHECKSUM_MISMATCH)")
        } catch (e: SyncException) {
            assertEquals(SyncErrorCode.CHECKSUM_MISMATCH, e.code)
        }
    }

    private fun sha256Hex(input: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}