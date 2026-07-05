package io.github.jiro.expensetracker.preferences

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jiro.expensetracker.sync.SyncProviderId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsRepositoryTest {

    private lateinit var repo: SettingsRepository

    @Before
    fun setUp() {
        // Use a fresh repo per test so persisted state doesn't leak.
        // `ApplicationProvider.getApplicationContext` returns the same
        // Application across tests, but we can clear the prefs file first.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences(SettingsRepository.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        repo = SettingsRepository(context)
    }

    @Test
    fun balanceHidden_defaultsToFalse() = runTest {
        assertFalse(repo.balanceHidden.first())
    }

    @Test
    fun setBalanceHidden_true_observableAsTrue() = runTest {
        repo.setBalanceHidden(true)
        assertTrue(repo.balanceHidden.first())
    }

    @Test
    fun setBalanceHidden_false_afterTrue_observableAsFalse() = runTest {
        repo.setBalanceHidden(true)
        repo.setBalanceHidden(false)
        assertFalse(repo.balanceHidden.first())
    }

    @Test
    fun balanceHidden_persistsAcrossRepoInstances() = runTest {
        // First repo: write the flag.
        repo.setBalanceHidden(true)
        // Second repo reading the same prefs file should observe the stored value.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val other = SettingsRepository(context)
        assertTrue(other.balanceHidden.first())
    }

    // ---- Phase 4d: cloud-sync provider selection ----

    @Test
    fun syncProvider_isDropbox_byDefault() = runTest {
        assertEquals(SyncProviderId.DROPBOX, repo.syncProvider.first())
    }

    @Test
    fun setSyncProvider_persistsAndUpdatesFlow() = runTest {
        repo.setSyncProvider(SyncProviderId.GOOGLE_DRIVE)
        assertEquals(SyncProviderId.GOOGLE_DRIVE, repo.syncProvider.first())
    }

    @Test
    fun settings_constructedTwice_sharePrefsKey_returnsSameProvider() = runTest {
        repo.setSyncProvider(SyncProviderId.GOOGLE_DRIVE)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val second = SettingsRepository(context)
        assertEquals(SyncProviderId.GOOGLE_DRIVE, second.syncProvider.first())
    }
}