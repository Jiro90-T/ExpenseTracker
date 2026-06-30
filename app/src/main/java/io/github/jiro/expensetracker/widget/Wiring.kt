// app/src/main/java/io/github/jiro/expensetracker/widget/Wiring.kt
package io.github.jiro.expensetracker.widget

import android.content.Context
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

/** Pure functions and constants shared by the member-card widget classes. */
object Wiring {
    /** DataStore name for the widget's persisted UI state. */
    const val DATASTORE_NAME = "member_card_widget"

    /** Single key — the index of the card currently displayed by the widget. */
    val KEY_CYCLE_INDEX = intPreferencesKey("cycle_index")

    /**
     * Compute the next cycle index.
     *  - `count <= 0` → 0 (no-op: caller is in State A, nothing to cycle).
     *  - `count == 1` → 0 (effectively a no-op: only one card to show).
     *  - otherwise → wrap `(current + 1) % count`.
     */
    fun nextIndex(current: Int, count: Int): Int = when {
        count <= 0 -> 0
        count == 1 -> 0
        else -> (current + 1) % count
    }

    /** Clamp [this] into `[0, max(0, maxExclusive - 1)]`. */
    fun Int.coerceInRange(maxExclusive: Int): Int {
        val safeMax = (maxExclusive - 1).coerceAtLeast(0)
        return this.coerceIn(0, safeMax)
    }
}

/** Process-wide DataStore instance. Lives at top level so callers don't need a Context holder. */
val Context.widgetDataStore by preferencesDataStore(name = Wiring.DATASTORE_NAME)