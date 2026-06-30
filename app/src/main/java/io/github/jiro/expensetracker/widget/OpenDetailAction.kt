// app/src/main/java/io/github/jiro/expensetracker/widget/OpenDetailAction.kt
package io.github.jiro.expensetracker.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import javax.inject.Inject
import javax.inject.Singleton

/** Extras key carried in the deep-link intent from the widget to MainActivity. */
const val EXTRA_MEMBER_CARD_ID = "member_card_id"

/**
 * Image-tap. The real implementation (Task 13) reads the card id from
 * `ActionParameters` and fires a `PendingIntent` to MainActivity. This
 * scaffold is a no-op so the widget compiles.
 */
@Singleton
class OpenDetailAction @Inject constructor() : ActionCallback {

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        // Intentionally empty — Task 13 replaces this body with the
        // PendingIntent fire. The composable will only attach this action
        // once Task 7's composable wires `ActionParamsKeys.CARD_ID`.
        return
    }
}
