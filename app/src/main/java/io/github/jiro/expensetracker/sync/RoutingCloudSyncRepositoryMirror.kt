package io.github.jiro.expensetracker.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

/**
 * Tracks which concrete [CloudSyncRepository] is "active" based on
 * [providerFlow] (a flow of the selected [SyncProviderId]) and exposes
 * StateFlows that mirror the active repo's flows. The mirror runs in
 * [scope] — typically the router's own `SupervisorJob` scope.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class RoutingCloudSyncRepositoryMirror(
    private val googleDriveRepo: CloudSyncRepository,
    private val dropboxRepo: CloudSyncRepository,
    providerFlow: StateFlow<SyncProviderId>,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
) {

    val state: StateFlow<SyncState> = providerFlow
        .flatMapLatest { provider -> if (provider == SyncProviderId.GOOGLE_DRIVE) googleDriveRepo.state else dropboxRepo.state }
        .stateIn(scope, SharingStarted.Eagerly, SyncState.SignedOut)

    val lastSyncedAtEpochMillis: StateFlow<Long?> = providerFlow
        .flatMapLatest { provider -> if (provider == SyncProviderId.GOOGLE_DRIVE) googleDriveRepo.lastSyncedAtEpochMillis else dropboxRepo.lastSyncedAtEpochMillis }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val isSignedIn: StateFlow<Boolean> = providerFlow
        .flatMapLatest { provider -> if (provider == SyncProviderId.GOOGLE_DRIVE) googleDriveRepo.isSignedIn else dropboxRepo.isSignedIn }
        .stateIn(scope, SharingStarted.Eagerly, false)
}