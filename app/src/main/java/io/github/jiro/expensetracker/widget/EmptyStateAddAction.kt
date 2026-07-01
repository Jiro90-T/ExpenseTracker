// app/src/main/java/io/github/jiro/expensetracker/widget/EmptyStateAddAction.kt
package io.github.jiro.expensetracker.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import io.github.jiro.expensetracker.MainActivity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Action on the empty-state "Add card" chip. Fires the same kind of
 * `PendingIntent` as the open-detail path, but with a sentinel `cardId = 0`
 * which the MainActivity treats as a navigation to the Add screen.
 */
@Singleton
class EmptyStateAddAction @Inject constructor() : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_MEMBER_CARD_ID, 0L)  // 0 = "open Add screen"
        }
        val pi = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        pi.send()
    }
}
