package io.github.jiro.expensetracker.sync

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

interface DeviceIdProvider {
    fun getOrCreate(): String
}

@Singleton
internal class DefaultDeviceIdProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : DeviceIdProvider {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)

    override fun getOrCreate(): String {
        prefs.getString(KEY, null)?.let { return it }
        val newId = UUID.randomUUID().toString()
        prefs.edit().putString(KEY, newId).apply()
        return newId
    }

    private companion object { const val KEY = "device_id" }
}
