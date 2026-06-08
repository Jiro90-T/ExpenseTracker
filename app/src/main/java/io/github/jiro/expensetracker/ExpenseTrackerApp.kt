package io.github.jiro.expensetracker

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.HiltAndroidApp
import io.github.jiro.expensetracker.data.local.CategorySeeder
import io.github.jiro.expensetracker.work.RecurringTransactionWorker
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class ExpenseTrackerApp : Application(), Configuration.Provider {

    @Inject lateinit var categorySeeder: CategorySeeder
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Run the seeder on a background scope so first-launch doesn't block.
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        appScope.launch { categorySeeder.seedIfEmpty() }
        scheduleRecurringTransactionJob()
    }

    /**
     * One daily run is enough — the worker only fires on rows whose
     * `recurrenceNextAt <= now`, so a missed day catches up next run.
     * KEEP policy: if the user updates the app while the job is scheduled,
     * we keep the existing schedule rather than resetting the period.
     */
    private fun scheduleRecurringTransactionJob() {
        val request = PeriodicWorkRequestBuilder<RecurringTransactionWorker>(
            repeatInterval = 1, repeatIntervalTimeUnit = TimeUnit.DAYS,
        ).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            RecurringTransactionJobName,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    companion object {
        const val RecurringTransactionJobName = "recurring-transaction-worker"
    }
}
