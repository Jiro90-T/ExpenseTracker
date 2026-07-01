// app/src/main/java/io/github/jiro/expensetracker/widget/WidgetRefresh.kt
package io.github.jiro.expensetracker.widget

import android.content.Context
import androidx.glance.appwidget.updateAll

/** Refresh all placed member-card widgets. Safe to call when none are placed. */
suspend fun refreshMemberCardWidgets(context: Context) {
    MemberCardWidget().updateAll(context)
}