package io.github.jiro.expensetracker

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.github.jiro.expensetracker.data.local.CategorySeeder
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class ExpenseTrackerApp : Application() {

    @Inject lateinit var categorySeeder: CategorySeeder

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        appScope.launch { categorySeeder.seedIfEmpty() }
    }
}
