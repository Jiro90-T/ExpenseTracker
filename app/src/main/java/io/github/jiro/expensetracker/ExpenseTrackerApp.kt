package io.github.jiro.expensetracker

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.content.getSystemService
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.HiltAndroidApp
import io.github.jiro.expensetracker.data.local.AccountSeeder
import io.github.jiro.expensetracker.data.local.CategorySeeder
import io.github.jiro.expensetracker.local.LocalServerService
import io.github.jiro.expensetracker.sync.CloudSyncRepository
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
    @Inject lateinit var accountSeeder: AccountSeeder
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject internal lateinit var cloudSyncRepository: CloudSyncRepository

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        createLocalServerNotificationChannel()
        // Run the seeder on a background scope so first-launch doesn't block.
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        appScope.launch {
            categorySeeder.seedIfEmpty()
            accountSeeder.syncDefaultCurrency()
        }
        appScope.launch {
            if (cloudSyncRepository.isSignedIn.value) {
                runCatching { cloudSyncRepository.syncOnce() }
                    .onFailure { android.util.Log.w(TAG, "Launch sync failed", it) }
            }
        }
        scheduleRecurringTransactionJob()
        triggerRecurringTransactionCheckOnLaunch()
    }

    private fun createLocalServerNotificationChannel() {
        // NotificationChannel requires API 26; project minSdk is 24.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        val nm = getSystemService<NotificationManager>() ?: return
        val channel = NotificationChannel(
            LocalServerService.CHANNEL_ID,
            getString(R.string.local_server_foreground_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.local_server_foreground_channel_description)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
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

    /**
     * Fire the worker once on every app launch in addition to the daily
     * cadence. Without this, a user who creates a recurring transaction
     * and reopens the app wouldn't see the next instance materialise until
     * the daily timer next ticks (up to 24h later). REPLACE policy: any
     * in-flight one-time job is cancelled and a fresh one is enqueued, so
     * the worker always runs at launch.
     */
    private fun triggerRecurringTransactionCheckOnLaunch() {
        val request = OneTimeWorkRequestBuilder<RecurringTransactionWorker>().build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            RecurringTransactionCheckOnLaunchJobName,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    companion object {
        const val RecurringTransactionJobName = "recurring-transaction-worker"
        const val RecurringTransactionCheckOnLaunchJobName = "recurring-transaction-check-on-launch"
        private const val TAG = "ExpenseTrackerApp"
    }
}
