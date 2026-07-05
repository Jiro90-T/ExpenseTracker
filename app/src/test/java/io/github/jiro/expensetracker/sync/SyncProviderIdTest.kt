package io.github.jiro.expensetracker.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncProviderIdTest {

    @Test
    fun fromKey_returnsDropbox_default() {
        assertEquals(SyncProviderId.DROPBOX, SyncProviderId.fromKey(null))
    }

    @Test
    fun fromKey_returnsDropbox_whenKeyIsDropbox() {
        assertEquals(SyncProviderId.DROPBOX, SyncProviderId.fromKey("dropbox"))
    }

    @Test
    fun fromKey_returnsGoogleDrive_whenKeyIsGoogleDrive() {
        assertEquals(SyncProviderId.GOOGLE_DRIVE, SyncProviderId.fromKey("google_drive"))
    }

    @Test
    fun fromKey_returnsDropbox_whenKeyIsUnknown() {
        assertEquals(SyncProviderId.DROPBOX, SyncProviderId.fromKey("not-a-real-provider"))
    }
}