package io.github.jiro.expensetracker.sync

import io.github.jiro.expensetracker.backup.BackupFormat
import java.security.MessageDigest
import org.json.JSONException
import org.json.JSONObject

/**
 * Wraps a [SyncSnapshot] in the on-the-wire JSON envelope used by every
 * cloud-sync provider. The envelope is the v4 backup arrays plus four
 * sync-specific metadata fields:
 *
 *   - schemaVersion : mirror of BackupFormat.FORMAT_VERSION; future-
 *     versioned snapshots are rejected with SCHEMA_INCOMPATIBLE.
 *   - lastModifiedEpochMillis : writer's wall clock at encode time, used
 *     for last-write-wins conflict resolution.
 *   - deviceId      : stable UUID per install (see [DeviceIdProvider]).
 *   - checksum      : SHA-256 hex of the serialized body, verified before
 *     deserialize. Absent or wrong → CHECKSUM_MISMATCH, never trusted.
 */
internal object SyncSnapshotCodec {

    fun encode(snapshot: SyncSnapshot): String {
        val bodyJson = snapshot.body.serialize()
        val checksum = sha256Hex(bodyJson)
        val outer = JSONObject().apply {
            put("schemaVersion", snapshot.schemaVersion)
            put("lastModifiedEpochMillis", snapshot.lastModifiedEpochMillis)
            put("deviceId", snapshot.deviceId)
            put("checksum", checksum)
            put("body", JSONObject(bodyJson))
        }
        return outer.toString()
    }

    fun decode(json: String): SyncSnapshot {
        val outer = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw SyncException(SyncErrorCode.MALFORMED, "Snapshot is not valid JSON", e)
        }
        val schemaVersion = outer.optInt("schemaVersion", -1)
        if (schemaVersion !in 1..BackupFormat.FORMAT_VERSION) {
            throw SyncException(
                SyncErrorCode.SCHEMA_INCOMPATIBLE,
                "Snapshot schema version $schemaVersion not supported (expected 1..${BackupFormat.FORMAT_VERSION})",
            )
        }
        val expected = outer.optString("checksum")
        if (expected.isEmpty()) {
            throw SyncException(SyncErrorCode.CHECKSUM_MISMATCH, "Snapshot has no checksum")
        }
        val bodyObj = outer.optJSONObject("body")
            ?: throw SyncException(SyncErrorCode.MALFORMED, "Snapshot missing body")
        val bodyJson = bodyObj.toString()
        val actual = sha256Hex(bodyJson)
        if (!expected.equals(actual, ignoreCase = true)) {
            throw SyncException(SyncErrorCode.CHECKSUM_MISMATCH, "Snapshot checksum mismatch")
        }
        val body = BackupBody.deserialize(bodyJson)
        return SyncSnapshot(
            body = body,
            lastModifiedEpochMillis = outer.getLong("lastModifiedEpochMillis"),
            deviceId = outer.getString("deviceId"),
            schemaVersion = schemaVersion,
            checksum = expected,
        )
    }

    private fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}