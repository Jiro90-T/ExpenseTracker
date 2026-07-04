package io.github.jiro.expensetracker.sync

import io.github.jiro.expensetracker.backup.BackupFormat

/**
 * One cloud-synced snapshot of the local database. The codec wraps [body]
 * in a JSON header (schemaVersion / lastModifiedEpochMillis / deviceId /
 * checksum) so any reader can verify integrity before deserialization.
 *
 * [schemaVersion] mirrors the v4 envelope's formatVersion; [checksum] is
 * SHA-256 hex of the serialized body. [deviceId] is the stable UUID the
 * provider assigned at install time.
 *
 * `internal` because [BackupBody] in this package is internal — widen
 * visibility if a future consumer outside `sync/` needs it.
 */
internal data class SyncSnapshot(
    val body: BackupBody,
    val lastModifiedEpochMillis: Long,
    val deviceId: String,
    val schemaVersion: Int = BackupFormat.FORMAT_VERSION,
    val checksum: String,
)