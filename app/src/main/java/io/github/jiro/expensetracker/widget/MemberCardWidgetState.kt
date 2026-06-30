// app/src/main/java/io/github/jiro/expensetracker/widget/MemberCardWidgetState.kt
package io.github.jiro.expensetracker.widget

import android.content.Context
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first

/**
 * Persists the widget's UI state — just the current cycle index, for now.
 * Backed by the DataStore declared in [Wiring.DATASTORE_NAME].
 */
object MemberCardWidgetState {

    /** Read the persisted cycle index. Returns 0 if unset / parse error. */
    suspend fun readCycleIndex(context: Context): Int =
        context.widgetDataStore.data.first()[Wiring.KEY_CYCLE_INDEX] ?: 0

    /** Persist the cycle index. */
    suspend fun setCycleIndex(context: Context, value: Int) {
        context.widgetDataStore.edit { it[Wiring.KEY_CYCLE_INDEX] = value }
    }
}
