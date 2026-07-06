package io.github.jiro.expensetracker.sync

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.github.jiro.expensetracker.preferences.SettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RoutingCloudSyncRepositoryTest {

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.getSharedPreferences(SettingsRepository.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun mirror_state_flips_whenProviderChanges() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = SettingsRepository(ctx)
        settings.setSyncProvider(SyncProviderId.DROPBOX)

        val dropboxRepo = object : CloudSyncRepository {
            override val state = kotlinx.coroutines.flow.MutableStateFlow<SyncState>(SyncState.SignedIn("dropbox"))
            override val lastSyncedAtEpochMillis = kotlinx.coroutines.flow.MutableStateFlow<Long?>(1L)
            override val isSignedIn = kotlinx.coroutines.flow.MutableStateFlow(true)
            override val signInIntent: Intent = Intent("FAKE_DROPBOX")
            override suspend fun signIn(): SignInResult = SignInResult.Success
            override suspend fun handleSignInResult(data: Intent?): SignInResult = SignInResult.Success
            override suspend fun signOut() {}
            override suspend fun push(snapshot: SyncSnapshot): PushResult = PushResult.Pushed(1L)
            override suspend fun pull(): PullResult<SyncSnapshot> = PullResult.NoRemoteSnapshot
            override suspend fun syncOnce(): SyncResult = SyncResult.NoRemoteSnapshot
        }
        val driveRepo = object : CloudSyncRepository {
            override val state = kotlinx.coroutines.flow.MutableStateFlow<SyncState>(SyncState.SignedOut)
            override val lastSyncedAtEpochMillis = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)
            override val isSignedIn = kotlinx.coroutines.flow.MutableStateFlow(false)
            override val signInIntent: Intent = Intent("FAKE_DRIVE")
            override suspend fun signIn(): SignInResult = SignInResult.Success
            override suspend fun handleSignInResult(data: Intent?): SignInResult = SignInResult.Success
            override suspend fun signOut() {}
            override suspend fun push(snapshot: SyncSnapshot): PushResult = PushResult.Pushed(1L)
            override suspend fun pull(): PullResult<SyncSnapshot> = PullResult.NoRemoteSnapshot
            override suspend fun syncOnce(): SyncResult = SyncResult.NoRemoteSnapshot
        }

        val mirror = RoutingCloudSyncRepositoryMirror(
            googleDriveRepo = driveRepo,
            dropboxRepo = dropboxRepo,
            providerFlow = settings.syncProvider,
        )
        // Wait for mirror to subscribe and propagate the initial value
        delay(50)
        assertEquals(SyncState.SignedIn("dropbox"), mirror.state.value)
        assertEquals(true, mirror.isSignedIn.value)

        // Flip provider — mirror should switch to driveRepo
        settings.setSyncProvider(SyncProviderId.GOOGLE_DRIVE)
        delay(50)
        assertEquals(SyncState.SignedOut, mirror.state.value)
        assertFalse(mirror.isSignedIn.value)
    }
}