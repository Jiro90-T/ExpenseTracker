package io.github.jiro.expensetracker.local

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import io.github.jiro.expensetracker.MainActivity
import io.github.jiro.expensetracker.R
import io.github.jiro.expensetracker.data.repository.AccountRepository
import io.github.jiro.expensetracker.data.repository.BudgetRepository
import io.github.jiro.expensetracker.data.repository.CategoryRepository
import io.github.jiro.expensetracker.data.repository.TransactionRepository
import io.github.jiro.expensetracker.local.auth.SessionTokenGenerator
import io.github.jiro.expensetracker.preferences.SettingsRepository
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@AndroidEntryPoint
class LocalServerService : Service() {

    @Inject lateinit var transactionRepository: TransactionRepository
    @Inject lateinit var accountRepository: AccountRepository
    @Inject lateinit var categoryRepository: CategoryRepository
    @Inject lateinit var budgetRepository: BudgetRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var sessionTokenGenerator: SessionTokenGenerator

    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ApplicationEngine? = null
    private var currentToken: String? = null

    fun activeToken(): String? = currentToken

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = intent?.getIntExtra(EXTRA_PORT, LocalServerState.DEFAULT_PORT)
            ?: LocalServerState.DEFAULT_PORT
        startForeground(NOTIFICATION_ID, buildNotification(port))
        scope.launch {
            if (server == null) {
                val token = sessionTokenGenerator.generate()
                currentToken = token
                val localServer = LocalServer(
                    transactionRepository,
                    accountRepository,
                    categoryRepository,
                    budgetRepository,
                    settingsRepository,
                    token,
                )
                server = embeddedServer(CIO, port, "0.0.0.0") {
                    with(localServer) { module() }
                }.start(wait = false)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        server?.stop(gracePeriodMillis = 0, timeoutMillis = 1000)
        server = null
        currentToken = null
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(port: Int): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.local_server_notification_title))
            .setContentText(getString(R.string.local_server_notification_text, port))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "local_server_channel"
        const val NOTIFICATION_ID = 7301
        const val EXTRA_PORT = "extra.port"

        /**
         * `ContextCompat.startForegroundService` keeps the call correct on
         * every supported API level — minSdk is 24, but
         * `Context.startForegroundService` only exists from API 26.
         */
        fun start(ctx: Context, port: Int) {
            val intent = Intent(ctx, LocalServerService::class.java)
                .putExtra(EXTRA_PORT, port)
            ContextCompat.startForegroundService(ctx, intent)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, LocalServerService::class.java))
        }
    }
}
