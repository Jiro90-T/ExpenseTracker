# Member Cards — Phase A Design Spec

> **Status:** Draft, pending user review.

**Goal:** Let the user store, browse, and edit loyalty / membership card photos in one place inside the ExpenseTracker app. (Widget, JSON backup, and manual reorder are explicitly out of scope for this phase — covered by a separate Phase B brainstorm later.)

**Non-goals:**
- Home-screen widget (Phase B).
- QR/barcode scanning or generation.
- JSON backup/restore of cards.
- Manual drag-to-reorder of cards (`sortOrder` column reserved but not exposed).
- Card categories or tags.
- Multi-user / cloud sync.

---

## User-facing behavior

### Entry point

**More** tab → **Cards** entry, placed between **Accounts** and **Categories** (alphabetical placement is acceptable too; the spec writes "Cards" as a top-level domain object parallel to Accounts/Categories).

### List screen (`MemberCardListScreen`)

- Top: search bar (single-line `OutlinedTextField`, magnifying-glass icon, placeholder "Search cards").
- Below: lazy column of card tiles. Each tile shows a small thumbnail (or color swatch + icon if image is missing) + card name.
- **Empty state (no cards):** centered text "No cards yet" + subtitle "Add your first loyalty or membership card." + primary "Add card" button.
- **Empty state (search no match):** "No cards match \"foo\"" line; clear-search button.
- **FAB (bottom-right):** opens the Edit screen for a new card.
- Tap a card tile → Detail screen.

### Detail screen (`MemberCardDetailScreen`)

- Top bar: card name as title, overflow menu with **Edit** and **Delete**.
- Body: large hero image of the card, `ContentScale.Fit`, fills available space below the bar.
- Below the image: member ID text (if present, monospace + copy-to-clipboard icon), expiry date (if present), color/icon (if set), notes (if present).
- Tap on the image → full-screen view (a `Dialog` containing the image with `ContentScale.Fit`; tap anywhere to dismiss).
- **Delete:** confirmation dialog "Delete {name}? This can't be undone." → deletes DB row + image file → pops back to list.

### Edit screen (`MemberCardEditScreen`)

Used for both add (no `id` argument) and edit (`id` argument).

Fields, top-to-bottom:

1. **Name** — required, single-line text field.
2. **Image** — a card-style placeholder tile. If empty, shows "Tap to add image" with a + icon. If filled, shows the current image with a small "Replace" label on tap.
   - Tap → `ModalBottomSheet` with two options: **Take photo** / **Pick from gallery**.
3. **Member ID** — optional, single-line text field.
4. **Color** — optional, 6 preset swatches + "Auto" (first option, picks icon-default).
5. **Icon** — optional, 8 emoji choices + "Auto".
6. **Expires** — optional, date picker (Material 3). Shows formatted date when set; "Clear" affordance.
7. **Notes** — optional, multi-line text field (3 lines visible, scrollable).

Bottom bar: **Cancel** (left) and **Save** (right). Save is disabled until **name** and **image** are both filled.

On Save:
- Copy the picked image into internal storage (see *Repository implementation notes* below).
- Insert (or update) the DB row.
- Pop back to the previous screen.

On Cancel / system back:
- If form has unsaved changes, show "Discard changes?" confirmation. Otherwise pop immediately.
  - "Unsaved" = any field differs from the snapshot captured when the form was hydrated (existing card) or from the empty initial state (new card). The VM keeps a `MemberCardForm baseline` alongside the live state and compares on `onBackPressed()`.

### Edge cases

| Case | Behavior |
|---|---|
| Empty card list | List screen shows the empty state with the "Add card" button. |
| Search returns no results | "No cards match \"{query}\"" line; clear-search button. |
| Image file missing on disk (e.g., cleared app data) | Image tile shows a placeholder icon + "Image missing" label. Tap-to-replace still works. |
| Card expired | List tile shows an "Expired" badge (red). Detail header shows "Expired {date}". Card remains usable. |
| Two cards with the same name | Allowed — disambiguate visually by image. |
| Delete from Detail | Confirmation dialog → DB row + image file deleted → pop back to list. |
| Edit, then back without saving | "Discard changes?" confirmation if any field has been modified; otherwise pop immediately. |
| Image decode failure | Snackbar "Couldn't load image"; user can retry. |
| No camera app installed (Take Photo path) | Activity result returns `resultCode != RESULT_OK`; show snackbar "No camera available"; fall back to gallery option. |

---

## Architecture

### File layout

**New files:**

| Path | Purpose |
|---|---|
| `data/local/MemberCardEntity.kt` | Room `@Entity` for the `member_cards` table. |
| `data/local/MemberCardDao.kt` | Room `@Dao` with CRUD + search. |
| `data/repository/MemberCardRepository.kt` | Interface — delegate to DAO; own image file copy/delete. |
| `data/repository/MemberCardRepositoryImpl.kt` | Implementation; takes `Context` (Hilt-injected via `@ApplicationContext`). |
| `di/MemberCardsModule.kt` | Hilt `@Binds` for `MemberCardRepository`. |
| `ui/cards/MemberCardListScreen.kt` | List screen with search. |
| `ui/cards/MemberCardListViewModel.kt` | List state + search query. |
| `ui/cards/MemberCardDetailScreen.kt` | Detail screen. |
| `ui/cards/MemberCardDetailViewModel.kt` | Detail state + delete handler. |
| `ui/cards/MemberCardEditScreen.kt` | Add/Edit screen. |
| `ui/cards/MemberCardEditViewModel.kt` | Form state + save handler + image capture flow. |
| `ui/cards/MemberCardForm.kt` | Form-state data class shared by Add and Edit (mirrors the pattern in `AddEditAccountViewModel`). |
| `ui/cards/MemberCardImage.kt` | Reusable composable: `MemberCardImage(path, modifier)` — handles the "image missing" placeholder. |
| `app/src/test/java/.../data/repository/MemberCardRepositoryTest.kt` | Unit tests for filename generation + sort (no Android deps). |
| `app/src/androidTest/java/.../data/local/MemberCardDaoTest.kt` | Room in-memory CRUD + search tests. |

**Modified files:**

| Path | Change |
|---|---|
| `data/local/AppDatabase.kt` | Add `MemberCardEntity` to the entities list + bump schema version. |
| `ui/more/MoreScreen.kt` (or equivalent) | Add a Cards entry row. |
| `ui/navigation/AppNav.kt` (or equivalent) | Add `cards`, `cards/{id}`, `cards/edit?id={id?}` routes. |
| `res/values/strings.xml` | Add the ~22 strings (see *Strings* below). |

### Key types

```kotlin
@Entity(tableName = "member_cards")
data class MemberCardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val imagePath: String,                    // relative path under filesDir/cards/
    val memberIdText: String? = null,
    val colorHex: Int? = null,                // ARGB
    val icon: String? = null,                 // emoji glyph
    val expiresAtEpochMillis: Long? = null,
    val notes: String? = null,
    val createdAtEpochMillis: Long,
    val sortOrder: Int = 0,                   // reserved for future reorder
)

@Dao
interface MemberCardDao {
    @Query("SELECT * FROM member_cards ORDER BY LOWER(name) ASC, id ASC")
    fun observeAll(): Flow<List<MemberCardEntity>>

    @Query("""
        SELECT * FROM member_cards
        WHERE LOWER(name) LIKE '%' || LOWER(:query) || '%'
        ORDER BY LOWER(name) ASC, id ASC
    """)
    fun searchByName(query: String): Flow<List<MemberCardEntity>>

    @Query("SELECT * FROM member_cards WHERE id = :id")
    suspend fun findById(id: Long): MemberCardEntity?

    @Insert
    suspend fun insert(entity: MemberCardEntity): Long

    @Update
    suspend fun update(entity: MemberCardEntity)

    @Query("DELETE FROM member_cards WHERE id = :id")
    suspend fun deleteById(id: Long)
}

interface MemberCardRepository {
    fun observeAll(): Flow<List<MemberCardEntity>>
    fun search(query: String): Flow<List<MemberCardEntity>>
    suspend fun getById(id: Long): MemberCardEntity?
    /** Copy [sourceUri] into internal storage and create a new card. Returns the new id. */
    suspend fun add(sourceUri: Uri, form: MemberCardForm): Long
    /** Update an existing card. If [newImageUri] is non-null, replace the image file. */
    suspend fun update(id: Long, form: MemberCardForm, newImageUri: Uri? = null)
    /** Delete the card row + image file. */
    suspend fun delete(id: Long)
}

data class MemberCardForm(
    val name: String,
    val memberIdText: String? = null,
    val colorHex: Int? = null,
    val icon: String? = null,
    val expiresAtEpochMillis: Long? = null,
    val notes: String? = null,
)
// The image URI is passed separately to the repository (see add/update signatures).
// Keeping it out of the form avoids two sources of truth for the same value.
```

### Repository implementation notes

- `add(sourceUri, form)`:
  1. Resolve the content URI via `ContentResolver` (or take the existing absolute path for an edit).
  2. Generate `<filesDir>/cards/${UUID.randomUUID()}.jpg`.
  3. Decode with `BitmapFactory.Options(inJustDecodeBounds=true)` first, compute `inSampleSize` so the longest side ≤ 1024 px, decode the full bitmap, re-encode JPEG quality 85 to the target file.
  4. Insert the `MemberCardEntity` with `imagePath = "${uuid}.jpg"` (relative), `createdAtEpochMillis = System.currentTimeMillis()`, `sortOrder = 0`.
- `update(id, form, newImageUri)`:
  1. Look up the existing row.
  2. If `newImageUri != null`, copy to a new file under `cards/`, update `imagePath`, and delete the old image file.
  3. Update the row with the new form values.
- `delete(id)`:
  1. Look up the row.
  2. Delete `<filesDir>/cards/${row.imagePath}` (best-effort; ignore missing-file errors).
  3. Delete the row.

### DAO wire-up

`AppDatabase` includes `MemberCardEntity::class` in its `@Database(entities = [...])` list. Bump the database version (1 → 2). Provide a `Migration` from v1 → v2 that creates `member_cards` with no rows.

```sql
CREATE TABLE IF NOT EXISTS `member_cards` (
  `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  `name` TEXT NOT NULL,
  `imagePath` TEXT NOT NULL,
  `memberIdText` TEXT,
  `colorHex` INTEGER,
  `icon` TEXT,
  `expiresAtEpochMillis` INTEGER,
  `notes` TEXT,
  `createdAtEpochMillis` INTEGER NOT NULL,
  `sortOrder` INTEGER NOT NULL
);
```

### ViewModel flow (Edit)

```kotlin
data class MemberCardEditUiState(
    val isEdit: Boolean = false,
    val name: String = "",
    val imageUri: String? = null,            // absolute path or content://
    val memberIdText: String = "",
    val colorHex: Int? = null,
    val icon: String? = null,
    val expiresAtEpochMillis: Long? = null,
    val notes: String = "",
    val isLoaded: Boolean = false,
    val isSaving: Boolean = false,
    val saveComplete: Boolean = false,
    val nameError: NameError? = null,         // NAME_REQUIRED, NAME_DUPLICATE
    val imageError: ImageError? = null,       // IMAGE_REQUIRED, IMAGE_LOAD_FAILED
    /** Snapshot of the form taken when hydration finished; used to detect dirty state on back. */
    val baseline: MemberCardForm? = null,
)

fun onNameChange(value: String)
fun onMemberIdChange(value: String)
fun onColorChange(value: Int?)
fun onIconChange(value: String?)
fun onExpiresChange(value: Long?)
fun onNotesChange(value: String)
fun onImagePicked(uri: Uri)         // camera or gallery result
fun onImagePickerDismissed()        // user canceled the bottom sheet
fun save()                          // validates + delegates to repository
fun onBackPressed(): Boolean        // returns true if we handled back (discard prompt)
```

Save validation:
- `name.trim().isNotEmpty()` — else `nameError = NAME_REQUIRED`.
- `imageUri != null` — else `imageError = IMAGE_REQUIRED`.

If both valid, call `repository.add(uri, form)` or `repository.update(id, form, uri)`, set `saveComplete = true`.

### Capture flow (Edit screen)

The screen owns two `rememberLauncherForActivityResult` instances:
- `cameraLauncher = rememberLauncherForActivityResult(TakePicture())` — the destination URI is created up front via `FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", tempFile)`. App declares the `FileProvider` in the manifest with `paths.xml` pointing at `files-path` named `cards`.
- `galleryLauncher = rememberLauncherForActivityResult(PickVisualMedia())` — uses `PickVisualMediaRequest(ImageOnly)`.

A `ModalBottomSheet` shows the two options. Tap → launches the corresponding intent. Result callback calls `viewModel.onImagePicked(uri)`.

On result:
- `cameraLauncher` returns `Boolean` (success). If true, the URI passed to it is the captured photo. Pass that URI to `onImagePicked`.
- `galleryLauncher` returns `Uri?`. Pass to `onImagePicked` if non-null.

The bottom sheet is dismissed after launch (don't wait for result).

---

## Strings (added)

```xml
<string name="nav_cards">Cards</string>
<string name="cards_search_hint">Search cards</string>
<string name="cards_empty_title">No cards yet</string>
<string name="cards_empty_subtitle">Add your first loyalty or membership card.</string>
<string name="cards_empty_search">No cards match \"%1$s\"</string>
<string name="cards_add">Add card</string>
<string name="cards_edit_title">Edit card</string>
<string name="cards_field_name">Card name</string>
<string name="cards_field_member_id">Member ID</string>
<string name="cards_field_notes">Notes</string>
<string name="cards_field_expiry">Expires</string>
<string name="cards_field_color">Color</string>
<string name="cards_field_icon">Icon</string>
<string name="cards_image_take_photo">Take photo</string>
<string name="cards_image_pick_gallery">Pick from gallery</string>
<string name="cards_image_replace">Replace image</string>
<string name="cards_image_placeholder">Tap to add image</string>
<string name="cards_image_missing">Image missing</string>
<string name="cards_delete_confirm">Delete %1$s? This can\'t be undone.</string>
<string name="cards_delete">Delete</string>
<string name="cards_discard_confirm">Discard unsaved changes?</string>
<string name="cards_expired">Expired</string>
<string name="cards_save">Save</string>
<string name="cards_cancel">Cancel</string>
<string name="cards_no_camera">No camera available</string>
<string name="cards_image_load_error">Couldn\'t load image</string>
```

---

## Testing strategy

### Pure-Kotlin unit tests (`app/src/test/`)

`MemberCardRepositoryTest` — covers filename generation logic in isolation by mocking the `Context` / `ContentResolver` boundary. Tests:

- `generateFilename_isUnique` — two calls produce distinct UUIDs.
- `computeInSampleSize_capsLongSideAt1024` — feed a 4000×3000 bitmap's bounds, expect `inSampleSize >= 4`.

(No real image decoding in unit tests — that's an androidTest concern.)

### Room in-memory tests (`app/src/androidTest/`)

`MemberCardDaoTest` — covers CRUD + search on the in-memory database:

- `insert_thenFindById_returnsRow`
- `insert_duplicateName_allowed` (no unique constraint on name)
- `observeAll_ordersByNameAscending_caseInsensitive`
- `searchByName_matchesSubstring_caseInsensitive`
- `deleteById_removesRow`
- `update_changesAllFields`

### Manual smoke plan

1. Add a card: tap **Add card** → take photo of a real loyalty card → enter name "Test Card" → tap Save → confirm appears in list.
2. Edit: tap card → overflow → Edit → change name to "Test Card 2" → Save → confirm in list.
3. Search: type "test" → confirm filter works; clear → confirm full list returns.
4. Delete: tap card → overflow → Delete → confirm dialog → tap Delete → confirm card is gone, image file is gone (`adb shell run-as` check).
5. Image missing: manually delete `<filesDir>/cards/<uuid>.jpg` (or clear app data and re-open) → confirm "Image missing" placeholder shows on the tile and detail screen.
6. Expired: edit a card → set expiry to yesterday → confirm "Expired" badge on tile.
7. Cancel-with-changes: open Edit → change name → tap Cancel → confirm "Discard unsaved changes?" prompt.
8. Take-photo fallback: on a device without a camera (or with permission denied) → confirm the gallery option still works and a snackbar shows for the camera path.

---

## Open questions

None at design time. Resolved through brainstorming:
- Card model: photo + name + optional fields (text/color/icon/expiry/notes).
- Capture method: photo (camera + gallery picker).
- Widget: deferred to Phase B (separate brainstorm).
- App entry point: More tab.
- Sort: alphabetical, case-insensitive.
- Color/icon presets: 6 colors + 8 emoji icons, both optional.

---

## Out of scope (Phase B brainstorm, separate)

- Home-screen widget provider, RemoteViews layout, tap-to-cycle behavior, long-press-to-open-app, DataStore for current-card-id.
- JSON backup of cards in the existing backup/restore format.
- Manual drag-to-reorder using the `sortOrder` column.
- Card categories / tags.
- QR/barcode scanning or generation.