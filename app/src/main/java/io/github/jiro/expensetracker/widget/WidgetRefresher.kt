package io.github.jiro.expensetracker.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridge between the repository (which can't reach Glance directly without
 * dragging the widget stack into core) and the widget UI. The repository
 * impl calls [refresh] after every successful write; the impl triggers
 * a re-render of every placed widget.
 *
 * `refresh` is a suspend function so the repository can `await` it; the
 * default impl does a synchronous `updateAll`.
 */
fun interface WidgetRefresher {
    suspend fun refresh(context: Context)
}

@Singleton
class WidgetRefresherImpl @Inject constructor() : WidgetRefresher {
    override suspend fun refresh(context: Context) {
        MemberCardWidget().updateAll(context)
    }
}