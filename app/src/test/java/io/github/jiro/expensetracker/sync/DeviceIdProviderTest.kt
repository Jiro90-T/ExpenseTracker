package io.github.jiro.expensetracker.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DeviceIdProviderTest {

    @Before
    fun clearSyncPrefs() {
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private fun newProvider(): DefaultDeviceIdProvider =
        DefaultDeviceIdProvider(ApplicationProvider.getApplicationContext())

    @Test
    fun getOrCreate_returnsSameIdOnSecondCall() {
        val provider = newProvider()
        val first = provider.getOrCreate()
        val second = provider.getOrCreate()
        assertEquals(first, second)
    }

    @Test
    fun getOrCreate_persistsAcrossInstances() {
        val first = newProvider().getOrCreate()
        val second = newProvider().getOrCreate()
        assertEquals(first, second)
    }

    @Test
    fun getOrCreate_generatesUuidFormat() {
        val id = newProvider().getOrCreate()
        // 8-4-4-4-12 hex layout
        assertTrue(id.matches(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")))
    }
}
