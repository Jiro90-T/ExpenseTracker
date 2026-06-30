// app/src/main/java/io/github/jiro/expensetracker/widget/CycleCardsAction.kt
package io.github.jiro.expensetracker.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Body-tap. Reads the current card list, advances the cycle index (with
 * wrap), persists it, and asks Glance to re-render every widget instance.
 */
@Singleton
class CycleCardsAction @Inject constructor(
    private val source: WidgetCardSource,
) : ActionCallback {

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val cards = source.loadAll(context)
        if (cards.isEmpty()) return
        val current = MemberCardWidgetState.readCycleIndex(context)
        val next = Wiring.nextIndex(current, cards.size)
        MemberCardWidgetState.setCycleIndex(context, next)
        MemberCardWidget().updateAll(context)
    }
}
