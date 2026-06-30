# Member Cards Widget — Phase B Design Spec

> **Status:** Draft, pending user review.

**Goal:** Add an Android home-screen widget that surfaces the user's saved member / loyalty cards, with tap-to-cycle and tap-photo-to-open behavior.

**Non-goals (Phase C candidates, not in this phase):**
- Per-widget-instance cycle state (single global index instead).
- Configurable widget (pin a specific card, hide counter).
- Multiple widget sizes (MVP supports one size: 4×2-equivalent).
- QR/barcode display in the widget.
- Lock-screen widget, theme-aware variants, background-color picker.

---

## User-facing behavior

### Placement

Long-press the home screen → Widgets → ExpenseTracker → **Member Card** → drag onto a cell. The widget picker reads `appwidget-provider` metadata + a preview image. Minimum size: `250dp × 110dp` (≈ 4×2 cells), resizable horizontally only.

### States

**A. No cards yet** — empty widget. Centered column:
- Card-front icon.
- Title "No cards yet".
- Subtitle "Add your first loyalty or membership card."
- Inline **Add card** button. Tap → opens the app at the existing Add route (`member_cards/edit`, no `id`).

**B. Single card** — the card renders without a cycle counter; the `[next →]` affordance is absent (cycle is a no-op).

**C. Multiple cards** — populated widget:
```
┌──────────────────────────────────┐
│ ★ Member Card          1/4   ◀▶  │
│  ┌────────────────────────────┐  │
│  │      [card photo]          │  │ ← tap = open Detail
│  └────────────────────────────┘  │
│  COSTCO WHOLESALE        Expired │ ← name + expired pill
│  Expires Dec 1, 2027             │ ← only if expiresAt set
│  [next →]                        │ ← tap = cycle
└──────────────────────────────────┘
```

- **Tap on the photo** → open the app at that card's Detail route (`member_cards/{id}`).
- **Tap anywhere else on the widget body** → advance to the next card (wraps around).
- **Counter** `1/4` in the top-right; updates on cycle tap. Hidden (or shown as "1/1") when only one card exists.
- **Voice-over / TalkBack**: the whole widget has a content description formatted via `cards_widget_widget_content_desc_format` ("Member card 1 of 4: Costco Wholesale. Tap photo to open, tap elsewhere for next card.").

### Visual details

- Padding: 16dp outer, 12dp between blocks.
- Photo area: `ContentScale.Crop`, fills width, height ~140dp. If image file is missing on disk, render the same placeholder the in-app screens use (tinted box + card-front icon + "Image missing" label).
- Card name: single line, ellipsized, `bodyLarge`.
- Expired pill: red `MaterialTheme.colorScheme.error` background, white text, `labelSmall`.
- Header title color: `onSurface`. Counter color: `onSurfaceVariant`. Arrow indicator: subtle (`onSurfaceVariant`).
- `[next →]`: secondary text button style (no fill, accent text color).
- The widget does not animate between cards on cycle — Android performs the layout transition automatically.

### Edge cases

| Case | Behavior |
|---|---|
| No cards | State A. |
| One card | State B. Card visible, no `[next →]`. |
| Multiple cards | State C. Cycle counter `m/n`. |
| Image file missing on disk | "Image missing" placeholder in the photo slot. Image-tap still opens Detail (where the placeholder also renders). |
| Card expired | Photo + name + a small red **Expired** pill next to the name. |
| User deletes the currently-shown card | Cycle index clamps to `[0, newCount-1]` before `update()`. Widget re-renders onto a valid card (no "card 3 of 2" gap). |
| User deletes the last card | Widget transitions back to State A. |
| DataStore holds an out-of-range index (e.g. corrupted, or index=5 when count=3) | `provideGlance` clamps via `index.coerceIn(0, (count - 1).coerceAtLeast(0))`. |
| User taps body rapidly | DataStore writes are serialized; only the latest tap wins. Counter advances exactly once per tap. |
| Multiple widget instances placed | All instances render the same card (single global cycle index, per design). |

---

## Architecture

### Big picture

The widget is a single Glance `GlanceAppWidget`. It reads two pieces of state:
1. The current card list (`WidgetCardSource` hitting the existing `MemberCardRepository`).
2. The current cycle index (`MemberCardWidgetState` backed by a small DataStore).

Three event sources drive re-renders: cycle tap (writes index → `update()`), repository write paths (add/update/delete → `update()`), and Detail screen `ON_RESUME` (catches "edit-and-back" without a write). Initial placement is handled by Glance's default `onUpdate`.

### File layout

**New files:**

| Path | Purpose |
|---|---|
| `widget/MemberCardWidget.kt` | `GlanceAppWidget` subclass. Single `provideGlance` composable that branches on card count and renders State A / B / C. |
| `widget/MemberCardWidgetReceiver.kt` | `GlanceAppWidgetReceiver` subclass. Declared in manifest; ties Android to the widget class. |
| `widget/MemberCardWidgetState.kt` | `object` exposing `getCurrentIndex()`, `setCurrentIndex(i: Int)`. Backed by `preferencesDataStore("member_card_widget")`. |
| `widget/WidgetCardSource.kt` | `suspend fun loadAll(context): List<WidgetCard>`. Reads `repository.observeAll().first()`. Projected on top of the existing repository — no new persistence. |
| `widget/CycleCardsAction.kt` | `ActionCallback`. Body-tap. Reads count, computes next index, writes DataStore, calls `MemberCardWidget().update(context)`. |
| `widget/OpenDetailAction.kt` | `ActionCallback`. Image-tap. Looks up the current card id, fires a `PendingIntent` that deep-links into the existing Compose NavHost at `member_cards/{id}`. |
| `widget/Wiring.kt` | `object Wiring`. `fun nextIndex(current, count): Int` (pure function — the cycle math). `const val DATASTORE_NAME = "member_card_widget"`. |
| `res/xml/member_card_widget_info.xml` | `appwidget-provider` metadata: `minWidth=250dp`, `minHeight=110dp`, `resizeMode=horizontal`, `updatePeriodMillis=0`, `widgetCategory=home_screen`, `previewLayout=@layout/widget_preview`. |
| `res/layout/widget_preview.xml` | Static preview for the widget picker (no Compose — Glance reads an Android layout). |

**Modified files:**

| Path | Change |
|---|---|
| `gradle/libs.versions.toml` | Add `androidx-glance-appwidget` entry under `[libraries]` and a `glance = "1.1.1"` version. |
| `app/build.gradle.kts` | Add `implementation(libs.androidx.glance.appwidget)`. |
| `app/src/main/AndroidManifest.xml` | (a) Declare `MemberCardWidgetReceiver` with the `appwidget-provider` intent filter and `meta-data` pointing to `@xml/member_card_widget_info`. (b) Add `android:launchMode="singleTop"` to `MainActivity` so re-launches deliver to `onNewIntent`. |
| `data/repository/MemberCardRepositoryImpl.kt` | Take a `WidgetRefresher` interface in the constructor (Hilt-provided). Call `widgetRefresher.refresh(context)` after `add` / `update` / `delete` succeed. Delete also clamps the cycle index via `MemberCardWidgetState.setCurrentIndex(clamped)` before refreshing. |
| `ui/cards/MemberCardDetailScreen.kt` | Existing `LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }` block — extend it to also call `MemberCardWidget().update(context)`. Catches "edit-and-back" widget staleness. |
| `MainActivity.kt` | (a) New field `var pendingMemberCardNavId: Long?`. (b) Read `EXTRA_MEMBER_CARD_ID` from `intent` in `onCreate(savedInstanceState)` and `onNewIntent`. (c) Top-level `LaunchedEffect(pendingMemberCardNavId)` in the root composable that calls `navController.navigate("member_cards/$id")` then resets the field. `AppNavHost` exposes the controller via composition local or a ref to the root composable. |
| `di/MemberCardsModule.kt` | New `@Binds` / `@Provides` for `WidgetRefresher`. (Implementation calls `MemberCardWidget().update(context)`. Implementation holds a `@ApplicationContext Context`.) |
| `res/values/strings.xml` | Add the ~10 widget strings (see *Strings* below). |

### Key types

```kotlin
// Glance widget (one class, three branches via state)
class MemberCardWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val cards = WidgetCardSource.loadAll(context)
        val state = MemberCardWidgetState.read(context)
        val index = state.coerceInRange(cards.size)
        provideContent {
            GlanceTheme {
                when {
                    cards.isEmpty() -> EmptyState()                // State A
                    cards.size == 1 -> PopulatedCard(cards[0], index, cards.size)  // State B
                    else            -> PopulatedCard(cards[index], index, cards.size)  // State C
                }
            }
        }
    }
}

data class WidgetCard(
    val id: Long,
    val name: String,
    val imagePath: String?,                  // null when file is missing on disk
    val imageMissing: Boolean,
    val expiresAtEpochMillis: Long?,
    val memberIdText: String?,
    val isExpired: Boolean,                  // computed
)

object MemberCardWidgetState {
    fun read(context: Context): Int          // DataStore read; returns 0 on miss
    suspend fun setCurrentIndex(context: Context, value: Int)
    fun Int.coerceInRange(count: Int): Int   // clamps; pure function
}

object Wiring {
    fun nextIndex(current: Int, count: Int): Int   // pure; see logic below
    const val DATASTORE_NAME = "member_card_widget"
}

// Hilt boundary: kept narrow so the impl stays test-friendly.
fun interface WidgetRefresher {
    suspend fun refresh(context: Context)
}
```

### Cycle math (pure, testable)

```kotlin
fun nextIndex(current: Int, count: Int): Int = when {
    count <= 0 -> 0          // no-op for the caller (State A)
    count == 1 -> 0          // effectively a no-op (State B)
    else -> (current + 1) % count
}
```

### DataStore content

A single `intPreferencesKey("cycle_index")` under `preferencesDataStore("member_card_widget")`. Default 0. No other entries.

### Repository → widget hooks

```kotlin
// MemberCardRepositoryImpl sketch (only the new bits shown):
class MemberCardRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: MemberCardDao,
    private val widgetRefresher: WidgetRefresher,
) : MemberCardRepository {
    override suspend fun add(sourceUri: Uri, form: MemberCardForm): Long {
        val id = /* existing insert logic */ id
        widgetRefresher.refresh(context)
        return id
    }
    override suspend fun update(id: Long, form: MemberCardForm, newImageUri: Uri?) {
        /* existing update logic */
        widgetRefresher.refresh(context)
    }
    override suspend fun delete(id: Long) {
        /* existing delete logic */
        val remaining = dao.observeAll().first()
        val current = MemberCardWidgetState.read(context)
        val clamped = current.coerceInRange(remaining.size)
        if (clamped != current) {
            MemberCardWidgetState.setCurrentIndex(context, clamped)
        }
        widgetRefresher.refresh(context)
    }
}
```

### Deep-link

The image-tap action builds a plain `Intent` addressed by component (no custom URI scheme, no `<data>` entry — avoids collisions with other apps that might want to register the same scheme):

```kotlin
val intent = Intent(context, MainActivity::class.java).apply {
    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_SINGLE_TOP
    putExtra(EXTRA_MEMBER_CARD_ID, cardId)
}
val pendingIntent = PendingIntent.getActivity(
    context,
    /* requestCode = */ cardId.toInt(),  // unique per card; avoids PendingIntent reuse collapsing
    intent,
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
)
```

`MainActivity` extracts `EXTRA_MEMBER_CARD_ID` in `onCreate(savedInstanceState: Bundle?)` and `onNewIntent(intent: Intent?)`. A new field on `MainActivity` (`var pendingMemberCardNavId: Long?`) holds the most recent value. The root composable inside `setContent` observes it via a top-level `LaunchedEffect(pendingMemberCardNavId)` and calls `navController.navigate("member_cards/$id")` once per change, then `pendingMemberCardNavId = null`. `onNewIntent` writes the new extra to the same field; `launchMode="singleTop"` on `MainActivity` (manifest change) guarantees that re-launches while the app is foregrounded deliver to `onNewIntent` rather than recreating the activity.

**Constant.** `const val EXTRA_MEMBER_CARD_ID = "member_card_id"` lives in `OpenDetailAction.kt`. Shared by the `PendingIntent` builder and `MainActivity`'s extra reader — moving it to a top-level `widget/Constants.kt` would be premature until a second consumer appears.

**Manifest changes for the deep-link path.** `MainActivity` gets `android:launchMode="singleTop"`. No new `<intent-filter>` and no `<data>` entry.

---

## Refresh triggers

The widget re-renders in four situations. Every one of them ends with `MemberCardWidget().update(context, glanceId)`.

1. **App repository write paths** — `add` / `update` / `delete` in `MemberCardRepositoryImpl` call `widgetRefresher.refresh(context)` after success. Delete clamps the cycle index first.
2. **Body-tap (cycle)** — `CycleCardsAction.actionRun` reads `WidgetCardSource.loadAll()`, computes `nextIndex(current, count)`, writes the DataStore, calls `MemberCardWidget().update(context)`.
3. **Detail screen `ON_RESUME`** — `MemberCardDetailScreen` already calls `viewModel.refresh()` on resume; we extend the same `LifecycleEventEffect` block to call `MemberCardWidget().update(context)`. Catches edit-and-back widget staleness.
4. **Initial placement** — handled by Glance's default `onUpdate` for each placed instance.

### What we deliberately don't do

- No polling — `updatePeriodMillis=0` is honored; no WorkManager; no system alarm.
- No `Flow` observers inside the Glance widget (would require `GlanceStateDefinition` + repository injection in the composable). Explicit triggers are enough.
- No DataStore observers — DataStore is read on every `update()`; the preference read is cheap.
- No retry on a failed `.update()` — a stale widget until the next trigger is acceptable; the next tap writes an index, the next foreground writes a refresh.

### Failure modes

| Trigger | Fails | Behavior |
|---|---|---|
| Repository write succeeds, then `.update()` throws | Card is updated; widget shows stale state until next trigger | Acceptable. Next foreground / cycle tap corrects it. |
| Cycle-tap DataStore read fails | Default 0; widget re-renders showing card 1 | Acceptable. |
| Widget removed while `.update()` is in flight | Glance drops the glanceId; no-op | Safe. |
| DataStore holds out-of-range index | Coerced via `coerceInRange(count)` in `provideGlance` | Safe. |

---

## Strings (added)

```xml
<string name="cards_widget_label">Member Card</string>
<string name="cards_widget_description">Show a loyalty or membership card on your home screen. Tap the photo to open it, tap anywhere else to cycle cards.</string>
<string name="cards_widget_image_content_desc">Open card details</string>
<string name="cards_widget_cycle_content_desc">Show next card</string>
<string name="cards_widget_empty_title">No cards yet</string>
<string name="cards_widget_empty_subtitle">Add your first loyalty or membership card.</string>
<string name="cards_widget_add_card">Add card</string>
<string name="cards_widget_counter_format">%1$d/%2$d</string>
<string name="cards_widget_next">Next</string>
<string name="cards_widget_widget_content_desc_format">Member card %1$d of %2$d: %3$s. Tap photo to open, tap elsewhere for next card.</string>
```

Reuses existing `cards_expired`, `cards_image_missing`, `nav_cards`.

---

## Testing strategy

### Unit tests (`app/src/test/`, pure Kotlin)

`WiringTest` — covers the pure cycle math:

- `nextIndex_countZero_returnsZero`
- `nextIndex_countOne_returnsZero`
- `nextIndex_countThree_currentTwo_wrapsToZero`
- `nextIndex_countFive_currentZero_returnsOne`
- `nextIndex_negativeCount_returnsZero`

`MemberCardWidgetStateCoercionTest` — covers the index-clamping helper (Glance DataStore read is mocked via its `MutablePreferences` / `PreferenceDataStoreFactory` API; if mocking is awkward, move to `androidTest`):

- `coerceInRange_countZero_returnsZero`
- `coerceInRange_countOne_negativeIndex_returnsZero`
- `coerceInRange_countFive_indexSeven_returnsFour`
- `coerceInRange_countFive_indexFour_returnsFour`

`WidgetCardProjectionTest` — covers the projection from `MemberCardEntity` → `WidgetCard`:

- `cardsWithMissingFiles_haveImageMissingTrue`
- `unexpiredCards_haveIsExpiredFalse`
- `expiredCards_haveIsExpiredTrue`
- `notes_excludedFromWidgetCard` (notes are not rendered in the widget)

### Room in-memory tests (`app/src/androidTest/`)

`MemberCardWidgetRepositoryHookTest` — confirms `WidgetRefresher.refresh` is invoked exactly once per successful `add` / `update` / `delete`. Uses an in-memory DB and a fake `WidgetRefresher` that increments a counter.

### Manual smoke plan (`docs/superpowers/testdata/member-cards-widget.md`)

1. **Empty state placement.** Clear app data. Long-press home screen → Widgets → ExpenseTracker → Member Card → drag onto a cell. Verify State A renders, "Add card" CTA visible.
2. **Empty-state CTA.** Tap "Add card" on the widget → app opens to the Add screen.
3. **Single card.** With 1 card saved, widget shows that card without a `[next →]` affordance.
4. **Cycle.** With 3 cards, widget shows card 1, "1/3" → tap body → "2/3", card 2 visible → tap body → "3/3", card 3 visible → tap body → "1/3", card 1 visible.
5. **Open Detail.** Tap the photo → app opens to the Detail screen for that card.
6. **Edit reflects.** Tap photo → Edit → change name → Save → widget re-renders with new name on return.
7. **Delete reflects (middle).** With 3 cards on "2/3", tap photo → overflow → Delete. Widget re-renders: counter "2/2" (the new card 2 was the old card 3), image updated.
8. **Delete reflects (last).** With 2 cards on "2/2", delete the visible card. Widget transitions to State A.
9. **Add reflects (from empty).** From State A, add a card in the app. Widget transitions to State B (single card, no `[next →]`).
10. **Image missing in widget.** Delete one image file at `<filesDir>/cards/<uuid>.jpg` via `adb shell run-as`. Cycle onto that card. Verify "Image missing" placeholder renders (no crash).
11. **Expired pill.** Edit a card to expire yesterday. Cycle onto it. Verify red "Expired" pill next to the name.
12. **Rapid body-taps.** Hammer the body tap ~10 times in a second. Verify counter advances exactly 10 times (no missed events, no double-advance).
13. **Multi-instance.** Add two Member Card widgets. Tap one to cycle. Verify both advance (single global index).
14. **Persistence.** Cycle to card 2. Kill the app. Relaunch (or trigger `MemberCardWidget().update()` by opening the app). Verify widget still shows card 2.

---

## Out of scope (Phase C candidates)

- Per-widget-instance cycle state (per-glanceId cycle index in DataStore).
- Configurable widget (pin a specific card, hide counter, lock to a category).
- Multiple widget sizes (4×1, 4×3, 2×2).
- Theme-aware variants or background-color picker.
- QR/barcode display in widget.
- Lock-screen widget, always-on-display tile.

---

## Open questions

None at design time. Resolved through brainstorming:
- Widget content: cycle through cards.
- Tap model: body = cycle, image = open Detail.
- State model: single global cycle index.
- Build tech: Jetpack Glance.
- Empty state: inline "Add your first card" CTA.
- Refresh model: repository writes call `update()` + Detail `ON_RESUME`.
