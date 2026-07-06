package io.github.jiro.expensetracker.sync

import android.content.Intent
import io.github.jiro.expensetracker.backup.BackupManager
import io.github.jiro.expensetracker.preferences.SettingsRepository
import io.github.jiro.expensetracker.sync.dropbox.DropboxCloudSyncRepository
import io.github.jiro.expensetracker.sync.google.GoogleDriveCloudSyncRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow

/**
 * Switches between [GoogleDriveCloudSyncRepository] and
 * [DropboxCloudSyncRepository] based on the user's selected provider
 * (`SettingsRepository.syncProvider`). All other methods delegate to the
 * active repo.
 *
 * Hilt binds this as `CloudSyncRepository`. The two concrete repos remain
 * directly injectable (their `@Inject` constructors) so the router can
 * depend on the concrete types and avoid `@Qualifier` gymnastics.
 */
@Singleton
internal class RoutingCloudSyncRepository @Inject constructor(
    private val googleDriveRepo: GoogleDriveCloudSyncRepository,
    private val dropboxRepo: DropboxCloudSyncRepository,
    private val backupManager: BackupManager,
    private val settings: SettingsRepository,
) : CloudSyncRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val mirror = RoutingCloudSyncRepositoryMirror(
        googleDriveRepo = googleDriveRepo,
        dropboxRepo = dropboxRepo,
        providerFlow = settings.syncProvider,
        scope = scope,
    )

    override val state: StateFlow<SyncState> = mirror.state
    override val lastSyncedAtEpochMillis: StateFlow<Long?> = mirror.lastSyncedAtEpochMillis
    override val isSignedIn: StateFlow<Boolean> = mirror.isSignedIn

    private val activeRepo: CloudSyncRepository
        get() = when (settings.syncProvider.value) {
            SyncProviderId.GOOGLE_DRIVE -> googleDriveRepo
            SyncProviderId.DROPBOX -> dropboxRepo
        }

    override val signInIntent: Intent get() = activeRepo.signInIntent
    override suspend fun handleSignInResult(data: Intent?): SignInResult = activeRepo.handleSignInResult(data)
    override suspend fun signIn(): SignInResult = activeRepo.signIn()
    override suspend fun signOut() = activeRepo.signOut()
    override suspend fun push(snapshot: SyncSnapshot): PushResult = activeRepo.push(snapshot)
    override suspend fun pull(): PullResult<SyncSnapshot> = activeRepo.pull()

    override suspend fun syncOnce(): SyncResult {
        val result = activeRepo.syncOnce()
        if (result is SyncResult.Pulled) {
            backupManager.applyBackupBodyToDb(result.snapshot.body)
        }
        return result
    }
}