# Member Cards — Manual Smoke Test

Smoke test for the More → Member Cards feature. Covers add, edit, search, delete, missing-image placeholder, expired badge, cancel-with-changes, and the camera fallback when the device has no camera (or permission is denied).

## Pre-conditions

- App installed on a device or emulator (`io.github.jiro.expensetracker`).
- For a clean baseline: open More → Member Cards and delete any existing cards (tap card → overflow → Delete).
- Camera permission granted on a camera-equipped device. To exercise the no-camera fallback use an emulator AVD without a `Camera` feature, or `adb shell pm revoke io.github.jiro.expensetracker android.permission.CAMERA`.
- One real-world loyalty card in front of you (any printed card with a barcode/number) for the photo step. Have at least one gallery image ready on the device for the fallback test.

## Steps

1. Open More → Member Cards. Verify the empty state shows "No cards yet" and an **Add card** button.
2. Tap **Add card**. Verify the capture screen offers both **Take photo** and **Pick from gallery**.
3. Take-photo (happy path): choose **Take photo**, grant the permission prompt on first run, capture the loyalty card, confirm the preview.
4. Enter name "Test Card", leave color/icon/expiry/notes at their defaults, tap **Save**.
5. Verify the card appears in the list with the photo, the name "Test Card", and the current date as the added-on date.
6. Search: tap the search icon, type "test". Verify the list filters to just "Test Card". Clear the query and verify the full list returns (no other cards expected on clean baseline).
7. Edit: tap the card tile → tap the overflow menu → **Edit**. Change the name to "Test Card 2", tap **Save**. Verify the tile now shows "Test Card 2".
8. Cancel-with-changes: open the same card → **Edit** → change the name to "Discarded Name" → tap **Cancel**. Verify the **Discard unsaved changes?** dialog appears. Tap **Discard**. Re-open the card and confirm the name is still "Test Card 2".
9. Expired badge: open the same card → **Edit** → set the expiry date to yesterday's date → **Save**. Verify the tile shows an **Expired** badge (and the detail header still shows the original expiry date, since only date and not time is stored, the card shows expired all day on the expiry date).
10. Delete: tap the card tile → overflow → **Delete** → confirm the **Delete card?** dialog → tap **Delete**.
11. Verify the card is gone from the list. Then verify the on-disk image file is also gone:
    `adb shell run-as io.github.jiro.expensetracker ls files/cards/` should return empty (or just `No such file or directory` if the directory was removed).
12. Image-missing placeholder: delete one card's image directly with
    `adb shell run-as io.github.jiro.expensetracker rm files/cards/<uuid>.jpg`
    (use the UUID returned by `ls files/cards/` first). Open the list. Verify the affected tile shows a generic **Image missing** placeholder. Tap into the detail screen and verify the header also shows the **Image missing** placeholder (not a broken-image icon or a crash).
13. Take-photo fallback: on an emulator without a back camera, or after revoking camera permission, open the capture screen. Verify **Take photo** still appears, and when tapped the system shows the camera-unavailable or permission-denied state, then a snackbar reports the failure. Confirm **Pick from gallery** is still selectable; pick an image, complete the form, save, and verify the card appears with that gallery image.

## Expected outcomes

- Step 1: empty state copy and **Add card** CTA render correctly.
- Step 2: capture screen exposes both options.
- Step 3: photo captures and returns to the form with the image preview populated.
- Step 4: form validates required name only; save completes without error.
- Step 5: tile renders the saved photo, name, and today's added-on date.
- Step 6: search is case-insensitive substring on name; clearing restores the full list.
- Step 7: edited name persists across navigation back and re-open.
- Step 8: dirty-form guard fires before dismissing the screen.
- Step 9: **Expired** badge appears on any card whose expiry date is on or before today; saving and reopening reflects the change.
- Step 10: delete confirmation requires an explicit Delete tap.
- Step 11: list updates immediately and the corresponding `<uuid>.jpg` is removed from `<filesDir>/cards/`.
- Step 12: missing file renders a uniform **Image missing** placeholder on both the tile and the detail screen — no crash, no broken-image glyph.
- Step 13: camera path fails gracefully with a snackbar; gallery path still creates a valid card.

## Rollback

If you want to start over, open More → Member Cards and delete each card (tap card → overflow → Delete). To fully reset app state run `adb shell pm clear io.github.jiro.expensetracker` and re-grant the camera permission when prompted. Re-run from Step 1.
