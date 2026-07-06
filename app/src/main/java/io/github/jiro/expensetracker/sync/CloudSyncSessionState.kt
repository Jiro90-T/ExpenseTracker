package io.github.jiro.expensetracker.sync

/**
 * Snapshot of the sync UI state, assembled by `SettingsViewModel` from the
 * router's flows + the local conflict flag. `internal` because it embeds
 * `internal` types ([SyncState]).
 */
internal data class CloudSyncSessionState(
    val providerId: SyncProviderId,
    val state: SyncState,
    val lastSyncedAtEpochMillis: Long?,
    val accountEmail: String?,
    val conflictPending: Boolean,
)