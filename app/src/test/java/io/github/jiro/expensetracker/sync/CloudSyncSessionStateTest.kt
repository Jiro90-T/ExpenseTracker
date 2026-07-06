package io.github.jiro.expensetracker.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CloudSyncSessionStateTest {

    @Test
    fun combine_producesExpectedSnapshot() = runTest {
        val state = MutableStateFlow<SyncState>(SyncState.SignedIn("dropbox"))
        val lastSynced = MutableStateFlow<Long?>(123L)
        val provider = MutableStateFlow(SyncProviderId.DROPBOX)
        val email = MutableStateFlow("user@example.com")
        val conflict = MutableStateFlow(false)

        val combined = combine(state, lastSynced, provider, email, conflict) { s, ls, p, e, c ->
            CloudSyncSessionState(
                providerId = p,
                state = s,
                lastSyncedAtEpochMillis = ls,
                accountEmail = e,
                conflictPending = c,
            )
        }.first()

        assertEquals(SyncProviderId.DROPBOX, combined.providerId)
        assertEquals(SyncState.SignedIn("dropbox"), combined.state)
        assertEquals(123L, combined.lastSyncedAtEpochMillis)
        assertEquals("user@example.com", combined.accountEmail)
        assertEquals(false, combined.conflictPending)
    }

    @Test
    fun conflictFlag_flipSurfacedInCombined() = runTest {
        val state = MutableStateFlow<SyncState>(SyncState.SignedOut)
        val lastSynced = MutableStateFlow<Long?>(null)
        val provider = MutableStateFlow(SyncProviderId.GOOGLE_DRIVE)
        val email = MutableStateFlow<String?>(null)
        val conflict = MutableStateFlow(true)

        val combined = combine(state, lastSynced, provider, email, conflict) { s, ls, p, e, c ->
            CloudSyncSessionState(p, s, ls, e, c)
        }.first()

        assertEquals(true, combined.conflictPending)
        assertEquals(SyncProviderId.GOOGLE_DRIVE, combined.providerId)
    }
}