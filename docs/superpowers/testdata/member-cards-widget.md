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