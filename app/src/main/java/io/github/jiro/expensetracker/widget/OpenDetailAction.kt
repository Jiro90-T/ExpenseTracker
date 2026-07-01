// app/src/main/java/io/github/jiro/expensetracker/widget/OpenDetailAction.kt
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

/** Extras key carried in the deep-link intent from the widget to MainActivity. */
const val EXTRA_MEMBER_CARD_ID = "member_card_id"

/**
 * Image-tap. Reads the card id from `ActionParameters` and fires a
 * `PendingIntent` to MainActivity, which decodes it via the
 * `EXTRA_MEMBER_CARD_ID` extra.
 */
@Singleton
class OpenDetailAction @Inject constructor() : ActionCallback {

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val cardId = parameters[ActionParamsKeys.CARD_ID] ?: return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_MEMBER_CARD_ID, cardId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            cardId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        pendingIntent.send()
    }
}
