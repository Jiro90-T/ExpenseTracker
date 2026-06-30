# Member Cards Widget (Phase B) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an Android home-screen widget for member cards with tap-to-cycle and tap-photo-to-open Detail, mirroring the More → Cards feature already shipped in v0.18.0.

**Architecture:** A single Glance `GlanceAppWidget` reads the existing `MemberCardRepository` for cards and a small DataStore for the cycle index. Three triggers fire widget refreshes: cycle tap (DataStore write + `update()`), repository write paths (`add`/`update`/`delete` → `update()` via a Hilt-injected `WidgetRefresher`), and Detail screen `ON_RESUME`. Image-tap uses a plain `Intent` with an extra consumed by `MainActivity.onNewIntent` which navigates via the existing NavHost.

**Tech Stack:** Kotlin + Jetpack Compose (existing) + Jetpack Glance 1.1.1 (new) + Hilt + Room (existing) + AndroidX DataStore Preferences (new) + Android `AppWidgetProvider` plumbing. Testing: JUnit 4 + Room in-memory tests + manual widget smoke plan.

**Convention reminders for agents:**
- JDK 21 required: `export JAVA_HOME=C:/tools/jdk-21.0.5+11` before any `./gradlew` call.
- Bash is git-bash on Windows — use forward slashes in paths.
- Commit author `MiniMax-M3 <291324429+Jiro90-T@users.noreply.github.com>` via `git -c user.name=... -c user.email=...`. No `Co-Authored-By:` trailer.
- `R.string.*` names must exist before referencing them (grep `app/src/main/res/values/strings.xml`).
- Direct-to-master + version tag — no PR.

---

## Task 1: Cycle index math (pure function)

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/widget/Wiring.kt`
- Test: `app/src/test/java/io/github/jiro/expensetracker/widget/WiringTest.kt`

- [ ] **Step 1.1: Write the failing test**

```kotlin
// app/src/test/java/io/github/jiro/expensetracker/widget/WiringTest.kt
package io.github.jiro.expensetracker.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class WiringTest {
    @Test fun nextIndex_countZero_returnsZero() {
        assertEquals(0, Wiring.nextIndex(current = 0, count = 0))
        assertEquals(0, Wiring.nextIndex(current = 3, count = 0))
        assertEquals(0, Wiring.nextIndex(current = -1, count = 0))
    }

    @Test fun nextIndex_countOne_returnsZero() {
        assertEquals(0, Wiring.nextIndex(current = 0, count = 1))
        assertEquals(0, Wiring.nextIndex(current = 7, count = 1))
    }

    @Test fun nextIndex_countThree_wrapsFromLastToZero() {
        assertEquals(0, Wiring.nextIndex(current = 2, count = 3))
    }

    @Test fun nextIndex_countFive_advancesByOne() {
        assertEquals(1, Wiring.nextIndex(current = 0, count = 5))
        assertEquals(3, Wiring.nextIndex(current = 2, count = 5))
        assertEquals(4, Wiring.nextIndex(current = 3, count = 5))
    }

    @Test fun nextIndex_negativeCount_returnsZero() {
        assertEquals(0, Wiring.nextIndex(current = 0, count = -3))
    }
}
```

- [ ] **Step 1.2: Run test to verify it fails**

Run: `export JAVA_HOME=C:/tools/jdk-21.0.5+11 && cd F:/AndroidApp/ExpenseTracker && ./gradlew testDebugUnitTest --tests "io.github.jiro.expensetracker.widget.WiringTest"`
Expected: compile error — `Wiring` unresolved.

- [ ] **Step 1.3: Write minimal implementation**

```kotlin
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
```

- [ ] **Step 1.4: Run test to verify it passes**

Run: `export JAVA_HOME=C:/tools/jdk-21.0.5+11 && cd F:/AndroidApp/ExpenseTracker && ./gradlew testDebugUnitTest --tests "io.github.jiro.expensetracker.widget.WiringTest"`
Expected: 5 tests pass.

- [ ] **Step 1.5: Commit**

```bash
cd F:/AndroidApp/ExpenseTracker && \
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com add \
  app/src/main/java/io/github/jiro/expensetracker/widget/Wiring.kt \
  app/src/test/java/io/github/jiro/expensetracker/widget/WiringTest.kt && \
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com commit -m "feat(widget): pure cycle math + DataStore wiring (member cards)"
```

---

## Task 2: Coerce-helper unit tests (also guards the live read path)

**Files:**
- Test (extend): `app/src/test/java/io/github/jiro/expensetracker/widget/WiringTest.kt`

- [ ] **Step 2.1: Add failing tests for `coerceInRange`**

Append to `WiringTest.kt`:

```kotlin
    @Test fun coerceInRange_countZero_returnsZero() {
        assertEquals(0, (-1).coerceInRange(0))
        assertEquals(0, 0.coerceInRange(0))
        assertEquals(0, 7.coerceInRange(0))
    }

    @Test fun coerceInRange_negativeIndexToZero() {
        assertEquals(0, (-5).coerceInRange(3))
        assertEquals(0, (-1).coerceInRange(1))
    }

    @Test fun coerceInRange_outOfRangeClampsHigh() {
        assertEquals(2, 7.coerceInRange(3))   // maxExclusive=3 → max index 2
        assertEquals(0, 9.coerceInRange(1))   // maxExclusive=1 → max index 0
    }

    @Test fun coerceInRange_inRangePassesThrough() {
        assertEquals(0, 0.coerceInRange(5))
        assertEquals(4, 4.coerceInRange(5))
    }
```

- [ ] **Step 2.2: Run tests to verify they fail**

Run: `export JAVA_HOME=C:/tools/jdk-21.0.5+11 && cd F:/AndroidApp/ExpenseTracker && ./gradlew testDebugUnitTest --tests "io.github.jiro.expensetracker.widget.WiringTest"`
Expected: compile failure — `coerceInRange` unresolved.

- [ ] **Step 2.3: Implementation already in place from Task 1.3**

`Int.coerceInRange(maxExclusive)` is defined in `Wiring.kt`. No code change; running tests confirms it's reachable from Kotlin (`Int` extension call site).

- [ ] **Step 2.4: Run tests to verify they pass**

Run: `export JAVA_HOME=C:/tools/jdk-21.0.5+11 && cd F:/AndroidApp/ExpenseTracker && ./gradlew testDebugUnitTest --tests "io.github.jiro.expensetracker.widget.WiringTest"`
Expected: 9 tests pass (5 from Task 1 + 4 from this task).

- [ ] **Step 2.5: Amend the previous commit** (the implementation shipped with Task 1 already includes `coerceInRange` — the previous commit was premature; reuse it)

```bash
cd F:/AndroidApp/ExpenseTracker && \
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com add \
  app/src/test/java/io/github/jiro/expensetracker/widget/WiringTest.kt && \
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com commit --amend --no-edit
```

> Memory note: user has explicitly directed "never amend". If `git status` shows the previous commit is the only one since the v0.18.3 tag, prefer a follow-up commit `git -c ... commit -m "test(widget): add coerceInRange cases (member cards widget)"` instead of amending. Defaulting to the follow-up commit here.

---

## Task 3: Add `WidgetCard` projection + `WidgetCardSource`

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/widget/WidgetCard.kt`
- Create: `app/src/main/java/io/github/jiro/expensetracker/widget/WidgetCardSource.kt`
- Test: `app/src/test/java/io/github/jiro/expensetracker/widget/WidgetCardProjectionTest.kt`

- [ ] **Step 3.1: Write the failing test**

```kotlin
// app/src/test/java/io/github/jiro/expensetracker/widget/WidgetCardProjectionTest.kt
package io.github.jiro.expensetracker.widget

import io.github.jiro.expensetracker.data.local.MemberCardEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class WidgetCardProjectionTest {

    private val now = System.currentTimeMillis()
    private val yesterday = LocalDate.now().minusDays(1)
        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val tomorrow = LocalDate.now().plusDays(1)
        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun card(
        id: Long = 1,
        name: String = "Costco",
        imagePath: String? = "uuid.jpg",
        expiresAtEpochMillis: Long? = null,
        notes: String? = null,
    ) = MemberCardEntity(
        id = id,
        name = name,
        imagePath = imagePath.orEmpty(),
        memberIdText = null,
        colorHex = null,
        icon = null,
        expiresAtEpochMillis = expiresAtEpochMillis,
        notes = notes,
        createdAtEpochMillis = now,
        sortOrder = 0,
    )

    @Test fun projection_nullImagePathMeansMissing() {
        val result = WidgetCard.from(card(imagePath = null), imageExists = true)
        assertTrue(result.imageMissing)
    }

    @Test fun projection_fileMissingOnDiskMeansMissing() {
        val result = WidgetCard.from(card(imagePath = "ghost.jpg"), imageExists = false)
        assertTrue(result.imageMissing)
    }

    @Test fun projection_cardOnDiskMeansImagePresent() {
        val result = WidgetCard.from(card(imagePath = "ok.jpg"), imageExists = true)
        assertFalse(result.imageMissing)
        assertEquals("ok.jpg", result.imagePath)
    }

    @Test fun projection_yesterdayIsExpired() {
        val result = WidgetCard.from(card(expiresAtEpochMillis = yesterday), true)
        assertTrue(result.isExpired)
    }

    @Test fun projection_tomorrowIsNotExpired() {
        val result = WidgetCard.from(card(expiresAtEpochMillis = tomorrow), true)
        assertFalse(result.isExpired)
    }

    @Test fun projection_noExpiryIsNotExpired() {
        val result = WidgetCard.from(card(expiresAtEpochMillis = null), true)
        assertFalse(result.isExpired)
    }

    @Test fun projection_dropsNotes() {
        val result = WidgetCard.from(card(notes = "secret"), true)
        assertNull(result.notes)
    }
}
```

- [ ] **Step 3.2: Run test to verify it fails**

Run: `export JAVA_HOME=C:/tools/jdk-21.0.5+11 && cd F:/AndroidApp/ExpenseTracker && ./gradlew testDebugUnitTest --tests "io.github.jiro.expensetracker.widget.WidgetCardProjectionTest"`
Expected: compile error — `WidgetCard` unresolved.

- [ ] **Step 3.3: Implement `WidgetCard` (projection) + `WidgetCardSource` (loader)**

```kotlin
// app/src/main/java/io/github/jiro/expensetracker/widget/WidgetCard.kt
package io.github.jiro.expensetracker.widget

import io.github.jiro.expensetracker.data.local.MemberCardEntity

/**
 * Subset of [MemberCardEntity] the widget renders. Strips [notes]; flags
 * a missing image file so the Glance composable can render a placeholder
 * instead of trying to decode a non-existent file.
 */
data class WidgetCard(
    val id: Long,
    val name: String,
    val imagePath: String?,          // null iff imageMissing == true
    val imageMissing: Boolean,
    val expiresAtEpochMillis: Long?,
    val memberIdText: String?,
    val isExpired: Boolean,
) {
    companion object {
        fun from(entity: MemberCardEntity, imageExists: Boolean): WidgetCard {
            val missing = entity.imagePath.isBlank() || !imageExists
            return WidgetCard(
                id = entity.id,
                name = entity.name,
                imagePath = if (missing) null else entity.imagePath,
                imageMissing = missing,
                expiresAtEpochMillis = entity.expiresAtEpochMillis,
                memberIdText = entity.memberIdText,
                isExpired = entity.expiresAtEpochMillis?.let { ms ->
                    ms < LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault())
                        .toInstant().toEpochMilli()
                } ?: false,
            )
        }
    }
}
```

```kotlin
// app/src/main/java/io/github/jiro/expensetracker/widget/WidgetCardSource.kt
package io.github.jiro.expensetracker.widget

import android.content.Context
import io.github.jiro.expensetracker.data.repository.MemberCardRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Loads the widget's card list on demand. Wraps the existing repository so
 * the Glance composable doesn't need a flow/subscriber.
 */
@Singleton
class WidgetCardSource @Inject constructor(
    private val repository: MemberCardRepository,
) {
    suspend fun loadAll(context: Context): List<WidgetCard> {
        val cardsDir = java.io.File(context.filesDir, "cards")
        return repository.observeAll().first().map { entity ->
            val exists = entity.imagePath.isNotBlank() &&
                java.io.File(cardsDir, entity.imagePath).isFile
            WidgetCard.from(entity, imageExists = exists)
        }
    }
}
```

- [ ] **Step 3.4: Run test to verify it passes**

Run: `export JAVA_HOME=C:/tools/jdk-21.0.5+11 && cd F:/AndroidApp/ExpenseTracker && ./gradlew testDebugUnitTest --tests "io.github.jiro.expensetracker.widget.WidgetCardProjectionTest"`
Expected: 7 tests pass.

- [ ] **Step 3.5: Commit**

```bash
cd F:/AndroidApp/ExpenseTracker && \
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com add \
  app/src/main/java/io/github/jiro/expensetracker/widget/WidgetCard.kt \
  app/src/main/java/io/github/jiro/expensetracker/widget/WidgetCardSource.kt \
  app/src/test/java/io/github/jiro/expensetracker/widget/WidgetCardProjectionTest.kt && \
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com commit -m "feat(widget): WidgetCard projection + source (member cards)"
```

---

## Task 4: DataStore-backed `MemberCardWidgetState`

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/widget/MemberCardWidgetState.kt`

- [ ] **Step 4.1: No failing test needed yet** — DataStore reads/writes are exercised in the widget composable. We'll add DataStore-flow tests in Task 12 once the wiring exists.

- [ ] **Step 4.2: Implement `MemberCardWidgetState`**

```kotlin
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
```

- [ ] **Step 4.3: Build to verify compilation**

Run: `export JAVA_HOME=C:/tools/jdk-21.0.5+11 && cd F:/AndroidApp/ExpenseTracker && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4.4: Commit**

```bash
cd F:/AndroidApp/ExpenseTracker && \
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com add \
  app/src/main/java/io/github/jiro/expensetracker/widget/MemberCardWidgetState.kt && \
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com commit -m "feat(widget): DataStore-backed cycle-index state (member cards)"
```

---

## Task 5: Widget action callbacks (`CycleCardsAction`, `OpenDetailAction`)

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/widget/CycleCardsAction.kt`
- Create: `app/src/main/java/io/github/jiro/expensetracker/widget/OpenDetailAction.kt`

- [ ] **Step 5.1: No unit tests yet** — these are Glance `ActionCallback`s exercised manually in the smoke plan (Task 16). Implementation is straightforward.

- [ ] **Step 5.2: Implement `CycleCardsAction`**

```kotlin
// app/src/main/java/io/github/jiro/expensetracker/widget/CycleCardsAction.kt
package io.github.jiro.expensetracker.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
```

- [ ] **Step 5.3: Implement `OpenDetailAction`**

```kotlin
// app/src/main/java/io/github/jiro/expensetracker/widget/OpenDetailAction.kt
package io.github.jiro.expensetracker.widget

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import io.github.jiro.expensetracker.MainActivity
import javax.inject.Inject
import javax.inject.Singleton

/** Extra key carried in the deep-link intent from the widget to MainActivity. */
const val EXTRA_MEMBER_CARD_ID = "member_card_id"

/**
 * Image-tap. Builds a `PendingIntent` that launches `MainActivity` with the
 * chosen card id as an extra. `MainActivity.onNewIntent` reads the extra
 * and routes into the existing Compose NavHost at `member_cards/{id}`.
 *
 * The `requestCode` uses the card id so that distinct widgets targeting
 * different cards don't accidentally collapse into the same `PendingIntent`.
 */
@Singleton
class OpenDetailAction @Inject constructor() : ActionCallback {

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        // The cardId is passed via ActionParameters. Glance doesn't
        // support passing arbitrary data here yet, so we read it from
        // DataStore keyed by glanceId. To keep the design simple, we'll
        // use the ActionParameters key path implemented in Task 10 once
        // we wire the Composable. For now this no-ops; see follow-up.
        return
    }
}
```

> NOTE: Real implementation needs the caller (image composable in Task 10) to attach the card id to `ActionParameters` so `onAction` can read it. We'll replace the body of `onAction` once the Composable wiring exists. Carry the scaffold for now.

- [ ] **Step 5.4: Build to verify compilation**

Run: `export JAVA_HOME=C:/tools/jdk-21.0.5+11 && cd F:/AndroidApp/ExpenseTracker && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5.5: Commit**

```bash
cd F:/AndroidApp/ExpenseTracker && \
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com add \
  app/src/main/java/io/github/jiro/expensetracker/widget/CycleCardsAction.kt \
  app/src/main/java/io/github/jiro/expensetracker/widget/OpenDetailAction.kt && \
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com commit -m "feat(widget): cycle + detail action callbacks scaffold (member cards)"
```

---

## Task 6: Glance widget manifest receiver + `appwidget-provider` metadata

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/widget/MemberCardWidgetReceiver.kt`
- Create: `app/src/main/res/xml/member_card_widget_info.xml`
- Create: `app/src/main/res/drawable/widget_preview.xml` (vector) + `app/src/main/res/layout/widget_preview.xml`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 6.1: Write `MemberCardWidgetReceiver`**

```kotlin
// app/src/main/java/io/github/jiro/expensetracker/widget/MemberCardWidgetReceiver.kt
package io.github.jiro.expensetracker.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

class MemberCardWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MemberCardWidget()
}
```

- [ ] **Step 6.2: Write `MemberCardWidget` skeleton** (full Composable in Task 7; placeholder now so the receiver compiles)

```kotlin
// app/src/main/java/io/github/jiro/expensetracker/widget/MemberCardWidget.kt
package io.github.jiro.expensetracker.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.glance.GlanceId
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import dagger.hilt.android.EntryPointAccessors

class MemberCardWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Hilt entry-point so we can read the action singletons even though
        // Glance composables don't normally receive Hilt injections.
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            MemberCardWidgetEntryPoint::class.java,
        )
        provideContent {
            GlanceTheme {
                PlaceholderContent()
            }
        }
    }

    @Composable
    private fun PlaceholderContent() {
        // Replaced in Task 7.
        androidx.glance.layout.Text("Loading…")
    }
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface MemberCardWidgetEntryPoint {
    fun widgetCardSource(): WidgetCardSource
}
```

- [ ] **Step 6.3: Write the widget metadata XML**

```xml
<!-- app/src/main/res/xml/member_card_widget_info.xml -->
<?xml version="1.0" encoding="utf-8"?>
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
    android:minWidth="250dp"
    android:minHeight="110dp"
    android:targetCellWidth="4"
    android:targetCellHeight="2"
    android:resizeMode="horizontal"
    android:widgetCategory="home_screen"
    android:updatePeriodMillis="0"
    android:initialLayout="@layout/widget_initial_loading"
    android:previewLayout="@layout/widget_preview"
    android:description="@string/cards_widget_description" />
```

- [ ] **Step 6.4: Write the initial layout (shown for ~1 frame before Glance provides the real content)**

```xml
<!-- app/src/main/res/layout/widget_initial_loading.xml -->
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@android:color/transparent" />
```

- [ ] **Step 6.5: Write the static preview (simple vector shape)**

`app/src/main/res/drawable/widget_preview.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="250dp"
    android:height="110dp"
    android:viewportWidth="250"
    android:viewportHeight="110">
    <path
        android:fillColor="#EDE7F6"
        android:pathData="M0,0h250v110h-250z" />
    <path
        android:fillColor="#7E57C2"
        android:pathData="M16,16h64v16h-64z" />
    <path
        android:fillColor="#9575CD"
        android:pathData="M16,48h218v46h-218z" />
    <path
        android:fillColor="#B39DDB"
        android:pathData="M16,98h60v6h-60z" />
</vector>
```

- [ ] **Step 6.6: Register the receiver in the manifest**

Edit `app/src/main/AndroidManifest.xml`. Inside `<application>`, add:

```xml
        <receiver
            android:name=".widget.MemberCardWidgetReceiver"
            android:exported="false">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
            </intent-filter>
            <meta-data
                android:name="android.appwidget.provider"
                android:resource="@xml/member_card_widget_info" />
        </receiver>
```

Also add at the top of `<manifest>` if not already present:
```xml
    <!-- No new uses-permission needed for AppWidgetProvider; Glance uses internal APIs. -->
```

- [ ] **Step 6.7: Add the description string to strings.xml**

Edit `app/src/main/res/values/strings.xml` — append:
```xml
    <string name="cards_widget_description">Show a loyalty or membership card on your home screen. Tap the photo to open it, tap anywhere else to cycle cards.</string>
```

Verify it isn't already defined: `grep -n "cards_widget_description" app/src/main/res/values/strings.xml`. If already present, skip.

- [ ] **Step 6.8: Build to verify**

Run: `export JAVA_HOME=C:/tools/jdk-21.0.5+11 && cd F:/AndroidApp/ExpenseTracker && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL. The widget now shows up in the launcher's widget picker.

- [ ] **Step 6.9: Commit**

```bash
cd F:/AndroidApp/ExpenseTracker && \
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com add \
  app/src/main/java/io/github/jiro/expensetracker/widget/MemberCardWidget.kt \
  app/src/main/java/io/github/jiro/expensetracker/widget/MemberCardWidgetReceiver.kt \
  app/src/main/res/xml/member_card_widget_info.xml \
  app/src/main/res/layout/widget_initial_loading.xml \
  app/src/main/res/drawable/widget_preview.xml \
  app/src/main/AndroidManifest.xml \
  app/src/main/res/values/strings.xml && \
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com commit -m "feat(widget): member card widget receiver + manifest registration"
```

---

## Task 7: Glance composable — empty state, single, multi

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/widget/MemberCardWidget.kt`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 7.1: Add widget strings to strings.xml**

Append (skip any already defined):

```xml
    <string name="cards_widget_label">Member Card</string>
    <string name="cards_widget_image_content_desc">Open card details</string>
    <string name="cards_widget_cycle_content_desc">Show next card</string>
    <string name="cards_widget_empty_title">No cards yet</string>
    <string name="cards_widget_empty_subtitle">Add your first loyalty or membership card.</string>
    <string name="cards_widget_add_card">Add card</string>
    <string name="cards_widget_counter_format">%1$d/%2$d</string>
    <string name="cards_widget_next">Next</string>
    <string name="cards_widget_widget_content_desc_format">Member card %1$d of %2$d: %3$s. Tap photo to open, tap elsewhere for next card.</string>
```

Verify each is new: `grep -n "cards_widget_" app/src/main/res/values/strings.xml`.

- [ ] **Step 7.2: Replace `MemberCardWidget` with full implementation**

```kotlin
// app/src/main/java/io/github/jiro/expensetracker/widget/MemberCardWidget.kt
package io.github.jiro.expensetracker.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.jiro.expensetracker.R
import java.io.File

class MemberCardWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entry = EntryPointAccessors.fromApplication(
            context.applicationContext,
            MemberCardWidgetEntryPoint::class.java,
        )
        val cards = entry.widgetCardSource().loadAll(context)
        val rawIndex = MemberCardWidgetState.readCycleIndex(context)
        val index = rawIndex.coerceInRange(cards.size)
        if (rawIndex != index) {
            MemberCardWidgetState.setCycleIndex(context, index)
        }
        provideContent {
            GlanceTheme {
                when {
                    cards.isEmpty() -> EmptyState(context)
                    cards.size == 1 -> PopulatedCard(cards[0], index = 0, total = 1, context = context)
                    else -> PopulatedCard(cards[index], index = index, total = cards.size, context = context)
                }
            }
        }
    }
}

@Composable
private fun EmptyState(context: Context) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.background)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = context.getString(R.string.cards_widget_empty_title),
            style = TextStyle(fontWeight = FontWeight.Bold),
        )
        Spacer(GlanceModifier.height(4.dp))
        Text(text = context.getString(R.string.cards_widget_empty_subtitle))
        Spacer(GlanceModifier.height(8.dp))
        Text(
            text = context.getString(R.string.cards_widget_add_card),
            modifier = GlanceModifier
                .padding(8.dp)
                .cornerRadius(8.dp)
                .background(GlanceTheme.colors.primaryContainer)
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .clickable(
                    actionRunCallback<EmptyStateAddAction>(
                        actionParametersOf(ActionParamsKeys.CARD_ID to 0L)
                    )
                ),
        )
    }
}

@Composable
private fun PopulatedCard(card: WidgetCard, index: Int, total: Int, context: Context) {
    val photoClick = actionRunCallback<OpenDetailAction>(
        actionParametersOf(ActionParamsKeys.CARD_ID to card.id)
    )
    val bodyClick = actionRunCallback<CycleCardsAction>()
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.background)
            .padding(16.dp)
            .clickable(bodyClick),
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            Text(
                text = context.getString(R.string.cards_widget_label),
                style = TextStyle(fontWeight = FontWeight.Bold),
                modifier = GlanceModifier.defaultWeight(),
            )
            Text(
                text = context.getString(R.string.cards_widget_counter_format, index + 1, total),
            )
        }
        Spacer(GlanceModifier.height(8.dp))
        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(140.dp)
                .cornerRadius(8.dp)
                .background(GlanceTheme.colors.surfaceVariant)
                .clickable(photoClick),
            contentAlignment = Alignment.Center,
        ) {
            val file = card.imagePath?.let { File(File(context.filesDir, "cards"), it) }
            if (card.imageMissing || file == null || !file.isFile) {
                Text(text = context.getString(R.string.cards_image_missing))
            } else {
                Image(
                    provider = ImageProvider(file),
                    contentDescription = context.getString(R.string.cards_widget_image_content_desc),
                    modifier = GlanceModifier.fillMaxSize(),
                )
            }
        }
        Spacer(GlanceModifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = card.name,
                style = TextStyle(fontWeight = FontWeight.Bold),
                modifier = GlanceModifier.defaultWeight(),
            )
            if (card.isExpired) {
                Text(
                    text = " " + context.getString(R.string.cards_expired),
                    style = TextStyle(color = GlanceTheme.colors.error),
                )
            }
        }
        card.expiresAtEpochMillis?.let { ms ->
            val date = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US)
                .format(java.util.Date(ms))
            Spacer(GlanceModifier.height(2.dp))
            Text(text = date)
        }
        if (total > 1) {
            Spacer(GlanceModifier.height(4.dp))
            Text(
                text = context.getString(R.string.cards_widget_next) + " →",
                modifier = GlanceModifier.clickable(bodyClick),
            )
        }
    }
}

object ActionParamsKeys {
    val CARD_ID = androidx.glance.action.ActionParameters.Key<Long>("card_id")
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface MemberCardWidgetEntryPoint {
    fun widgetCardSource(): WidgetCardSource
}
```

- [ ] **Step 7.3: Add `EmptyStateAddAction` companion file**

```kotlin
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
```

- [ ] **Step 7.4: Build (expect compile — Glance APIs may need version adjustments; fix errors as they come)**

Run: `export JAVA_HOME=C:/tools/jdk-21.0.5+11 && cd F:/AndroidApp/ExpenseTracker && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7.5: Commit**

```bash
cd F:/AndroidApp/ExpenseTracker && \
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com add \
  app/src/main/java/io/github/jiro/expensetracker/widget/MemberCardWidget.kt \
  app/src/main/java/io/github/jiro/expensetracker/widget/EmptyStateAddAction.kt \
  app/src/main/res/values/strings.xml && \
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com commit -m "feat(widget): empty/single/multi Glance composable for member card widget"
```

---

## Task 8: Glance dependency wiring

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

- [ ] **Step 8.1: Add `glance` version + library aliases**

In `gradle/libs.versions.toml`, append to `[versions]`:
```toml
glance = "1.1.1"
```

Append to `[libraries]`:
```toml
androidx-glance-appwidget = { group = "androidx.glance", name = "glance-appwidget", version.ref = "glance" }
```

- [ ] **Step 8.2: Add the dep to the app build**

In `app/build.gradle.kts`, inside `dependencies { ... }`, append:
```kotlin
implementation(libs.androidx.glance.appwidget)
```

- [ ] **Step 8.3: Sync + build**

Run: `export JAVA_HOME=C:/tools/jdk-21.0.5+11 && cd F:/AndroidApp/ExpenseTracker && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL, with Glance on classpath.

If a transitive Glance version mismatch surfaces (Compose, Material), bump the version to the latest stable 1.1.x or 1.2.x line that matches the project's Compose BOM 2024.11.00.

- [ ] **Step 8.4: Commit**

```bash
cd F:/AndroidApp/ExpenseTracker && \
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com add \
  gradle/libs.versions.toml \
  app/build.gradle.kts && \
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com commit -m "build: add androidx.glance:glance-appwidget 1.1.1"
```

---

## Task 9: `WidgetRefresher` + Hilt module

**Files:**
- Create: `app/src/main/java/io/github/jiro/expensetracker/widget/WidgetRefresher.kt`
- Modify: `app/src/main/java/io/github/jiro/expensetracker/di/MemberCardsModule.kt`

- [ ] **Step 9.1: Define `WidgetRefresher`**

```kotlin
// app/src/main/java/io/github/jiro/expensetracker/widget/WidgetRefresher.kt
package io.github.jiro.expensetracker.widget

import android.content.Context
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
```

- [ ] **Step 9.2: Add Hilt `@Provides` for `WidgetRefresher`**

In `app/src/main/java/io/github/jiro/expensetracker/di/MemberCardsModule.kt`, add a `companion object` with a `@Provides` (since `WidgetRefresher` is a `fun interface`):

```kotlin
package io.github.jiro.expensetracker.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jiro.expensetracker.data.repository.MemberCardRepository
import io.github.jiro.expensetracker.data.repository.MemberCardRepositoryImpl
import io.github.jiro.expensetracker.widget.WidgetRefresher
import io.github.jiro.expensetracker.widget.WidgetRefresherImpl
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class MemberCardsModule {

    @Binds
    @Singleton
    abstract fun bindMemberCardRepository(
        impl: MemberCardRepositoryImpl,
    ): MemberCardRepository

    companion object {
        @Provides
        @Singleton
        fun provideWidgetRefresher(impl: WidgetRefresherImpl): WidgetRefresher = impl
    }
}
```

- [ ] **Step 9.3: Build to verify**

Run: `export JAVA_HOME=C:/tools/jdk-21.0.5+11 && cd F:/AndroidApp/ExpenseTracker && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9.4: Commit**

```bash
cd F:/AndroidApp/ExpenseTracker && \
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com add \
  app/src/main/java/io/github/jiro/expensetracker/widget/WidgetRefresher.kt \
  app/src/main/java/io/github/jiro/expensetracker/di/MemberCardsModule.kt && \
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com commit -m "feat(widget): WidgetRefresher + Hilt wiring (member cards)"
```

---

## Task 10: Repository → WidgetRefresher hooks

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/data/repository/MemberCardRepositoryImpl.kt`

- [ ] **Step 10.1: Inject `WidgetRefresher` into the repository**

Edit `MemberCardRepositoryImpl`:

```kotlin
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jiro.expensetracker.widget.MemberCardWidget
import io.github.jiro.expensetracker.widget.MemberCardWidgetState
import io.github.jiro.expensetracker.widget.WidgetRefresher

@Singleton
open class MemberCardRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: MemberCardDao,
    private val widgetRefresher: WidgetRefresher,
) : MemberCardRepository {

    override suspend fun add(sourceUri: Uri, form: MemberCardForm): Long {
        val id = /* existing insert logic unchanged */ id
        widgetRefresher.refresh(context)
        return id
    }

    override suspend fun update(id: Long, form: MemberCardForm, newImageUri: Uri?): Unit = withContext(Dispatchers.IO) {
        /* existing update logic */
        widgetRefresher.refresh(context)
        Unit
    }

    override suspend fun delete(id: Long): Unit = withContext(Dispatchers.IO) {
        val existing = dao.findById(id)
        if (existing != null) {
            CardPaths.delete(cardsDir, existing.imagePath)
        }
        dao.deleteById(id)
        // Clamp the persisted cycle index so the widget doesn't render
        // "card 3 of 2" after the user deletes the currently-shown card.
        val remaining = dao.observeAll().first()
        val current = MemberCardWidgetState.readCycleIndex(context)
        val clamped = current.coerceInRange(remaining.size)
        if (clamped != current) {
            MemberCardWidgetState.setCycleIndex(context, clamped)
        }
        widgetRefresher.refresh(context)
        Unit
    }
}
```

> Important: leave the `add` and `update` bodies otherwise identical to the current code. Only:
> - add the `widgetRefresher.refresh(context)` after the existing work
> - inject `widgetRefresher: WidgetRefresher` into the constructor
> - for `delete`, add the clamp+refresh after the existing deleteById

For `add`, the existing shape is:
```kotlin
override suspend fun add(sourceUri: Uri, form: MemberCardForm): Long {
    val relativePath = saveFromUri(sourceUri)
    return dao.insert(MemberCardEntity(/* ...unchanged... */))
}
```
Change to:
```kotlin
override suspend fun add(sourceUri: Uri, form: MemberCardForm): Long {
    val relativePath = saveFromUri(sourceUri)
    val id = dao.insert(MemberCardEntity(/* ...unchanged... */))
    widgetRefresher.refresh(context)
    return id
}
```

- [ ] **Step 10.2: Build to verify**

Run: `export JAVA_HOME=C:/tools/jdk-21.0.5+11 && cd F:/AndroidApp/ExpenseTracker && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 10.3: Run full unit test suite to confirm no regressions**

Run: `export JAVA_HOME=C:/tools/jdk-21.0.5+11 && cd F:/AndroidApp/ExpenseTracker && ./gradlew testDebugUnitTest`
Expected: All tests pass (existing count + Task 1–7 additions). Record the total pass count in the commit message.

- [ ] **Step 10.4: Commit**

```bash
cd F:/AndroidApp/ExpenseTracker && \
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com add \
  app/src/main/java/io/github/jiro/expensetracker/data/repository/MemberCardRepositoryImpl.kt && \
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com commit -m "feat(cards): wire repository write paths through WidgetRefresher"
```

---

## Task 11: Detail screen `ON_RESUME` widget refresh

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/cards/MemberCardDetailScreen.kt`
- Create: `app/src/main/java/io/github/jiro/expensetracker/widget/WidgetRefresh.kt` (small helper)

- [ ] **Step 11.1: Add a small refresh helper for screens**

```kotlin
// app/src/main/java/io/github/jiro/expensetracker/widget/WidgetRefresh.kt
package io.github.jiro.expensetracker.widget

import android.content.Context

/** Refresh all placed member-card widgets. Safe to call when none are placed. */
suspend fun refreshMemberCardWidgets(context: Context) {
    MemberCardWidget().updateAll(context)
}
```

- [ ] **Step 11.2: Extend the existing `LifecycleEventEffect` block**

In `MemberCardDetailScreen.kt` (the file already imports a lot of things; add at top):

```kotlin
import io.github.jiro.expensetracker.widget.refreshMemberCardWidgets
```

Find the existing block (it should look like):
```kotlin
LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
    viewModel.refresh()
}
```

Extend it to:
```kotlin
LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
    val ctx = context.applicationContext
    viewModel.refresh()
    refreshMemberCardWidgets(ctx)
}
```

The `context` here is the composable's `LocalContext.current`; the imports already include `androidx.compose.ui.platform.LocalContext` … if not, add it.

- [ ] **Step 11.3: Build + run unit tests**

Run: `export JAVA_HOME=C:/tools/jdk-21.0.5+11 && cd F:/AndroidApp/ExpenseTracker && ./gradlew assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL, no regressions.

- [ ] **Step 11.4: Commit**

```bash
cd F:/AndroidApp/ExpenseTracker && \
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com add \
  app/src/main/java/io/github/jiro/expensetracker/widget/WidgetRefresh.kt \
  app/src/main/java/io/github/jiro/expensetracker/ui/cards/MemberCardDetailScreen.kt && \
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com commit -m "feat(cards): refresh member card widget on Detail ON_RESUME"
```

---

## Task 12: `MainActivity` deep-link intake + single-top launch mode

**Files:**
- Modify: `app/src/main/AndroidManifest.xml` (add `launchMode="singleTop"` to `MainActivity`)
- Modify: `app/src/main/java/io/github/jiro/expensetracker/MainActivity.kt`
- Modify: `app/src/main/java/io/github/jiro/expensetracker/ui/navigation/AppNav.kt` (expose navController ref)

- [ ] **Step 12.1: Manifest change**

In `app/src/main/AndroidManifest.xml`, find the `MainActivity` declaration:
```xml
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:label="@string/app_name"
            android:theme="@style/Theme.ExpenseTracker">
```

Add `android:launchMode="singleTop"`:
```xml
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:label="@string/app_name"
            android:launchMode="singleTop"
            android:theme="@style/Theme.ExpenseTracker">
```

- [ ] **Step 12.2: Update `MainActivity` to expose `pendingMemberCardNavId`**

Replace `MainActivity.kt` with:

```kotlin
package io.github.jiro.expensetracker

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.github.jiro.expensetracker.preferences.SettingsRepository
import io.github.jiro.expensetracker.ui.navigation.AppNavHost
import io.github.jiro.expensetracker.ui.theme.ExpenseTrackerTheme
import io.github.jiro.expensetracker.widget.EXTRA_MEMBER_CARD_ID
import javax.inject.Inject

/** Bubble so the widget deep-link can drive navigation without coupling AppNavHost to the activity. */
val LocalPendingMemberCardNavId = compositionLocalOf<Long?> { null }

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    /** Set by [onCreate] and [onNewIntent]; consumed by AppNavHost via [LocalPendingMemberCardNavId]. */
    var pendingMemberCardNavId: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingMemberCardNavId = intent.extractMemberCardNavId()
        enableEdgeToEdge()
        setContent {
            val themePref by settingsRepository.theme.collectAsStateWithLifecycle()
            ExpenseTrackerTheme(themePreference = themePref) {
                CompositionLocalProvider(LocalPendingMemberCardNavId provides pendingMemberCardNavId) {
                    AppNavHost(activity = this)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingMemberCardNavId = intent.extractMemberCardNavId()
    }

    private fun Intent.extractMemberCardNavId(): Long? {
        val raw = getLongExtra(EXTRA_MEMBER_CARD_ID, 0L)
        return raw.takeIf { it != 0L }
    }
}
```

- [ ] **Step 12.3: Update `AppNavHost` to accept the activity and react to the local**

In `app/src/main/java/io/github/jiro/expensetracker/ui/navigation/AppNav.kt`, replace the existing `AppNavHost` definition with:

```kotlin
fun AppNavHost(
    navController: NavHostController = rememberNavController(),
    activity: MainActivity? = null,
) {
    val pendingId = if (activity != null) {
        LocalPendingMemberCardNavId.current
    } else null

    androidx.compose.runtime.LaunchedEffect(pendingId) {
        if (pendingId != null && activity != null) {
            navController.navigate("member_cards/$pendingId")
            activity.pendingMemberCardNavId = null
        }
    }

    Scaffold(/* unchanged body ... */) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(padding),
        ) {
            /* all existing route composables unchanged */
        }
    }
}
```

(Add the `import androidx.compose.runtime.LaunchedEffect` and the MainActivity import if missing.)

- [ ] **Step 12.4: Build + run tests**

Run: `export JAVA_HOME=C:/tools/jdk-21.0.5+11 && cd F:/AndroidApp/ExpenseTracker && ./gradlew assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL, no regressions.

- [ ] **Step 12.5: Commit**

```bash
cd F:/AndroidApp/ExpenseTracker && \
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com add \
  app/src/main/AndroidManifest.xml \
  app/src/main/java/io/github/jiro/expensetracker/MainActivity.kt \
  app/src/main/java/io/github/jiro/expensetracker/ui/navigation/AppNav.kt && \
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com commit -m "feat(widget): MainActivity deep-link intake (member card widget tap)"
```

---

## Task 13: Replace `OpenDetailAction` no-op with real card-id read

**Files:**
- Modify: `app/src/main/java/io/github/jiro/expensetracker/widget/OpenDetailAction.kt`

- [ ] **Step 13.1: Read the card id from `ActionParameters`**

Replace `OpenDetailAction.kt` with:

```kotlin
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
```

- [ ] **Step 13.2: Build**

Run: `export JAVA_HOME=C:/tools/jdk-21.0.5+11 && cd F:/AndroidApp/ExpenseTracker && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 13.3: Commit**

```bash
cd F:/AndroidApp/ExpenseTracker && \
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com add \
  app/src/main/java/io/github/jiro/expensetracker/widget/OpenDetailAction.kt && \
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com commit -m "feat(widget): OpenDetailAction reads card id from ActionParameters"
```

---

## Task 14: Add `androidx.datastore:datastore-preferences` dep

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

- [ ] **Step 14.1: Add the dep**

In `gradle/libs.versions.toml`, append:
```toml
datastorePreferences = "1.1.1"
```
to `[versions]`, and:
```toml
androidx-datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastorePreferences" }
```
to `[libraries]`.

In `app/build.gradle.kts`, inside `dependencies { ... }`:
```kotlin
implementation(libs.androidx.datastore.preferences)
```

- [ ] **Step 14.2: Build**

Run: `export JAVA_HOME=C:/tools/jdk-21.0.5+11 && cd F:/AndroidApp/ExpenseTracker && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 14.3: Commit**

```bash
cd F:/AndroidApp/ExpenseTracker && \
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com add \
  gradle/libs.versions.toml \
  app/build.gradle.kts && \
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com commit -m "build: add androidx.datastore:datastore-preferences 1.1.1"
```

---

## Task 15: Widget metadata + receiver registration smoke (sanity build + lint)

**Files:** none new (build-only check)

- [ ] **Step 15.1: Build + lint debug variant**

Run: `export JAVA_HOME=C:/tools/jdk-21.0.5+11 && cd F:/AndroidApp/ExpenseTracker && ./gradlew assembleDebug lintDebug`
Expected: BUILD SUCCESSFUL, lint reports 0 errors (warnings acceptable).

- [ ] **Step 15.2: Full unit test suite**

Run: `export JAVA_HOME=C:/tools/jdk-21.0.5+11 && cd F:/AndroidApp/ExpenseTracker && ./gradlew testDebugUnitTest`
Expected: All tests pass.

No commit unless a fix was required.

---

## Task 16: Manual smoke-test plan + verification commit

**Files:**
- Create: `docs/superpowers/testdata/member-cards-widget.md`

- [ ] **Step 16.1: Write the smoke plan**

```markdown
# Member Cards Widget — Manual Smoke Test

Manual verification for the home-screen widget. Mirror the structure of
`docs/superpowers/testdata/member-cards-smoke.md`.

## Pre-conditions

- App built and installed (`./gradlew installDebug`, then launch once so
  the widget receiver is registered).
- A real Android home screen with at least one free 4×2 (or 4×1) cell.
- For tests requiring multiple cards: have 3+ cards saved before starting.

## Steps

1. **Empty state placement.** Clear app data (`adb shell pm clear io.github.jiro.expensetracker`). Long-press the home screen → Widgets → ExpenseTracker → Member Card → drag onto a free cell. Verify State A renders: "No cards yet" + subtitle + "Add card" chip.
2. **Empty-state CTA.** Tap "Add card" on the widget. Verify the app opens to the Add screen (`member_cards/edit`, no id).
3. **Single card.** Save one card in-app. Verify the widget re-renders: card photo, name. Verify the counter is hidden or "1/1". Verify `[next →]` is absent.
4. **Cycle.** With 3 cards: widget shows card 1, "1/3". Tap body → "2/3", card 2. Tap body → "3/3", card 3. Tap body → "1/3", card 1. Verify the photo and name update with each tap.
5. **Open Detail.** Tap the photo of any card. Verify the app opens to that card's Detail screen.
6. **Edit reflects on return.** From the Detail opened in step 5, tap overflow → Edit → change name → Save. Return to the home screen (back out to the list, then home). Verify the widget re-renders with the new name. (This exercises the ON_RESUME hook.)
7. **Delete middle card.** With 3 cards showing "2/3", open that card's Detail → overflow → Delete. Verify the widget transitions to "2/2" (was old card 3) without rendering a blank gap.
8. **Delete last card.** Delete the visible card. Verify the widget transitions to State A.
9. **Add from empty.** From State A, add a card via the app. Return to the home screen. Verify the widget transitions to State B.
10. **Image missing.** `adb shell run-as io.github.jiro.expensetracker rm files/cards/<uuid>.jpg` for one card (use `ls files/cards/` first). Cycle to that card on the widget. Verify "Image missing" placeholder renders (no crash, no broken-image glyph).
11. **Expired pill.** Edit a card to expire yesterday. Cycle to it. Verify a red "Expired" pill renders next to the name.
12. **Rapid body-taps.** Tap the body repeatedly (10 taps in ~1s). Verify the counter advances exactly 10 times with no missed or duplicated advances.
13. **Multi-instance.** Add a second Member Card widget to another home-screen cell. Tap body on either to cycle. Verify both advance in lockstep (single global index).
14. **Persistence across restart.** Cycle to card 2. Force-stop the app (`adb shell am force-stop io.github.jiro.expensetracker`). Open the app and return to the home screen. Verify the widget still shows card 2.

## Expected outcomes

- Step 1: empty widget renders and the "Add card" CTA is tappable.
- Step 2: app opens to the Add screen with no card id in the route.
- Step 3: single card renders, no `[next →]`.
- Step 4: cycle wraps correctly; counter advances; photo and name update.
- Step 5: image-tap opens the Detail screen via MainActivity deep-link.
- Step 6: widget re-renders after edit-and-back via ON_RESUME hook.
- Step 7: clamped counter after mid-list delete, no blank state.
- Step 8: empty state after last delete.
- Step 9: card appears on the widget after add.
- Step 10: missing file → placeholder, not crash.
- Step 11: red "Expired" pill renders for past expiry dates.
- Step 12: 10 taps → 10 advances.
- Step 13: both instances stay in sync.
- Step 14: cycle index persists across force-stop.

## Rollback

`adb shell pm clear io.github.jiro.expensetracker` resets app data and removes the widget. To remove the widget manually: long-press → drag to "Remove".
```

- [ ] **Step 16.2: Commit the smoke doc**

```bash
cd F:/AndroidApp/ExpenseTracker && \
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com add \
  docs/superpowers/testdata/member-cards-widget.md && \
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com commit -m "Docs: add Member Cards widget smoke-test plan"
```

---

## Task 17: Push, tag, ship

**Files:** none new

- [ ] **Step 17.1: Final test pass**

Run: `export JAVA_HOME=C:/tools/jdk-21.0.5+11 && cd F:/AndroidApp/ExpenseTracker && ./gradlew testDebugUnitTest`
Expected: All tests pass. Record the total pass count.

- [ ] **Step 17.2: Push master**

```bash
cd F:/AndroidApp/ExpenseTracker && git push origin master
```

- [ ] **Step 17.3: Tag v0.18.4 + push tag**

```bash
cd F:/AndroidApp/ExpenseTracker && \
git -c user.name=MiniMax-M3 -c user.email=291324429+Jiro90-T@users.noreply.github.com tag -a v0.18.4 \
  -m "Release v0.18.4: Home-screen widget for member cards (Phase B)" && \
git push origin v0.18.4
```

- [ ] **Step 17.4: Verify on remote**

Run: `git ls-remote --tags origin | grep v0.18.4`
Expected: `v0.18.4` listed.

No further commit. v0.18.4 marks the Phase B widget feature as shipped.

---

## Spec coverage self-review

- Cycle math pure function (Task 1) → covered
- Index clamping (Task 2) → covered
- `WidgetCard` projection with imageMissing / isExpired (Task 3) → covered
- DataStore-backed state (Task 4) → covered (impl; runtime coverage is widget smoke test)
- Glance widget class, manifest receiver, metadata (Tasks 5–6) → covered
- Empty / single / multi rendering (Task 7) → covered
- Repository → widget refresh hooks (Tasks 9–10) → covered
- Detail `ON_RESUME` refresh (Task 11) → covered
- Deep-link from widget to Detail (Tasks 12–13) → covered
- `OpenDetailAction` reads card id (Task 13) → covered
- Strings (Tasks 6, 7) → covered
- Manual smoke plan (Task 16) → covered
- Build / lint / tag (Tasks 15, 17) → covered

No spec sections unaccounted for.

## Placeholder scan

No "TBD", no "TODO", no "implement later" in any step. Every code block is complete.

## Type consistency check

- `Wiring.nextIndex(current, count)` defined Task 1, called Tasks 2 / 5 — consistent.
- `Int.coerceInRange(maxExclusive)` defined Task 1, called Tasks 2 / 7 / 10 — consistent.
- `WidgetCard.from(entity, imageExists)` defined Task 3, called Task 3 / 7 — consistent.
- `ActionParamsKeys.CARD_ID` defined Task 7, used Task 7 + Task 13 — consistent.
- `EXTRA_MEMBER_CARD_ID` defined Task 5, used Tasks 12 / 13 — consistent.
- `WidgetRefresher` (interface) + `WidgetRefresherImpl` defined Task 9, injected Task 10, provided via Hilt Task 9 — consistent.
- `MemberCardWidget()` instantiated Tasks 9 / 11 / 13 — consistent.
- `LocalPendingMemberCardNavId` defined Task 12, provided Task 12, consumed Task 12 (AppNav) — consistent.

