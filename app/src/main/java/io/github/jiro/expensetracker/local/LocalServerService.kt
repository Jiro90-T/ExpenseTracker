package io.github.jiro.expensetracker.local

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.jiro.expensetracker.MainActivity
import io.github.jiro.expensetracker.R
import io.ktor.server.application.call
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.cio.CIO
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Foreground service that hosts the Ktor engine for the local PC browser
 * server. Started by Settings → "Start server"; the persistent
 * notification keeps the process alive while the embedded server is
 * running. The full HTTP surface (dashboard, transactions, etc.) is
 * layered on top of the placeholder route in the next task.
 */
class LocalServerService : Service() {

    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ApplicationEngine? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = intent?.getIntExtra(EXTRA_PORT, LocalServerState.DEFAULT_PORT)
            ?: LocalServerState.DEFAULT_PORT
        startForeground(NOTIFICATION_ID, buildNotification(port))
        if (server == null) {
            server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
                routing {
                    get("/") {
                        call.respondText("placeholder — see Task 6")
                    }
                }
            }.start(wait = false)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        server?.stop(gracePeriodMillis = 0, timeoutMillis = 1000)
        server = null
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
         * Start this foreground service. Uses `ContextCompat.startForegroundService`
         * so the call is correct on every supported API level — project
         * minSdk is 24, but `Context.startForegroundService` was only
         * added in API 26.
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
