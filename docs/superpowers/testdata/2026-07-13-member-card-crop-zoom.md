# Phase 2.16 — Member-Card Crop Zoom & Pan — Smoke Test

## Scope

Adds pinch-to-zoom and pan to the member-card crop UI while preserving today's drag-the-rect behavior at 1.0x zoom. Single file change (`MemberCardCropScreen.kt`) + two new test files.

## Automated verification

```bash
export JAVA_HOME=C:/tools/jdk-21.0.5+11
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest --tests "*ImageTransformTest*" --tests "*ComputeBitmapCropRectTest*"
```

Expected: BUILD SUCCESSFUL; 13 new tests PASS (8 in `ImageTransformTest`, 5 in `ComputeBitmapCropRectTest`).

## Manual verification

### Prerequisites

A device or emulator with the app installed (`./gradlew :app:installDebug`).

### Steps

- [ ] Open the Add/Edit Member Card flow and select (or capture) an image with small text/details to crop.
- [ ] At 1.0x, verify the crop rect still drags to reposition over the fit-scaled image (existing behavior).
- [ ] Pinch outward on the image — verify the image scales up smoothly around your pinch focal point (your fingers stay over the same content pixels).
- [ ] At >1.0x, drag the image — verify the image pans under the screen-fixed crop rect.
- [ ] Pinch outward past ~3.0x — verify the image stops at 3.0x (no further magnification).
- [ ] Drag an image edge past the crop rect edge — verify the clamp prevents the image from leaving any crop rect edge uncovered (no transparent corners).
- [ ] Tap Crop. Verify the saved JPEG crops the zoomed-and-panned region accurately (open the JPEG; expect the area visible through the screen-fixed crop rect).
- [ ] Re-open the crop screen with a different image. Verify zoom resets to 1.0x (because `remember(bitmap)` keys the state on bitmap identity).

## What this phase did NOT add

- Double-tap to reset zoom
- +/- buttons or zoom slider
- Persistence of zoom across screen re-entries
- Animation on the Crop button press
- Per-row diff vs the saved crop
