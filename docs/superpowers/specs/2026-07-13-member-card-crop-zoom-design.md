# Member-Card Crop — Pinch-to-Zoom & Pan — Design

> **For engineers:** REQUIRED SUB-SKILL: Use superpowers:writing-plans to author the implementation plan before touching code.

**Goal:** Add pinch-to-zoom and pan to the member-card crop UI while preserving today's drag-the-rect behavior at 1.0x zoom.

**Architecture:** Replace the existing `detectDragGestures` with `detectTransformGestures`, introduce an `ImageTransform` state (scale + offset), render the image through a `graphicsLayer`, and un-project the screen-fixed crop rect through both fit-scale and image transform when computing source-bitmap crop coords.

**Tech Stack:** Jetpack Compose, `detectTransformGestures`, `Modifier.graphicsLayer`, existing `androidx.compose.ui.geometry.Rect`. No new dependencies.

---

## 1. Background

The Member Card crop UI (`app/src/main/java/io/github/jiro/expensetracker/ui/cards/MemberCardCropScreen.kt`) lets the user select a region of a member-card image to save as a JPEG. Today it is **drag-only**: a screen-fixed crop rect (initialised at 80% of the displayed image, centered) can be repositioned over a fit-scaled source bitmap. Pinch-to-zoom is explicitly out of scope by design ("No zoom/pan of the image itself").

Users want to crop small details (e.g., a member number on a loyalty card). Today they cannot zoom in to position the rect accurately. This change adds pinch zoom + pan to the image while preserving drag-the-rect at 1.0x.

## 2. Goals & Non-Goals

**Goals**
- Pinch zooms the image 1.0x → 3.0x around the user's pinch focal point
- Drag pans the zoomed image at scale > 1.0x
- Drag still moves the crop rect at scale = 1.0x (preserve current behavior)
- The crop rect never shows transparent or empty content (clamping guarantee)
- Output bitmap crop coords remain in source-bitmap pixels, computed by inverting both the fit-scale and the live image transform

**Non-goals**
- No double-tap to reset zoom
- No +/- buttons or zoom slider (gesture-only, per user preference)
- No persistence of zoom across screen re-entries (each entry starts at 1.0x)
- No animation on Crop button press (result snaps)
- No change to the JPEG output pipeline (`cropAndEncode`, q=90)
- No change to DAO/schema (`MemberCardDao`, `MemberCardEntity`)

## 3. State Model

### New: `ImageTransform` data class

```kotlin
internal data class ImageTransform(
    val scale: Float = 1f,        // ratio of source-to-screen pixels times graphicsLayer.scaleX
    val offsetX: Float = 0f,      // screen-pixel translation applied after centering + scaling
    val offsetY: Float = 0f,
)
```

### Existing state (unchanged)

- `cropRect: Rect` — screen-pixel crop rect (initialised at 80% centered on the displayed image)
- `CropLayout(displayedLeft, displayedTop, scale)` — fit-scale info from `Layout` derivation
- Module-level `cropState: Pair<Rect?, CropLayout?>` — bridge to the Crop button
- `BitmapCropRect(x, y, width, height)` — output shape

### New local state in `CropBody`

```kotlin
var imageTransform by remember(bitmap) { mutableStateOf(ImageTransform()) }
```

Keyed on `bitmap` identity so a new source resets transform (mirrors the existing `cropRect` reset on the same key).

## 4. Composition

### Image becomes a zoomable layer

```kotlin
Image(
    bitmap = bitmap.asImageBitmap(),
    contentDescription = null,
    modifier = Modifier
        .fillMaxSize()
        .graphicsLayer {
            scaleX = imageTransform.scale
            scaleY = imageTransform.scale
            translationX = imageTransform.offsetX
            translationY = imageTransform.offsetY
        },
)
```

The crop rect overlay stays screen-fixed (no `graphicsLayer` on the rect Box). The crop window's visual position does not change with zoom.

### Gesture detector swap

Replace `detectDragGestures(...)` on the image Box with `detectTransformGestures { centroid, pan, zoom, _ -> }` and branch on user intent:

```kotlin
.pointerInput(bitmap) {
    detectTransformGestures { centroid, pan, zoom, _ ->
        if (zoom != 1f) {
            // pinch — apply scale + offset with focal point preserved
            imageTransform = applyZoomAround(centroid, zoom, imageTransform, boxSize, sourceBitmapSize)
        } else if (imageTransform.scale == 1f) {
            // drag at 1.0x — moves the crop rect (current behavior)
            val candidate = Rect(cropRect.topLeft + pan, cropRect.size)
            cropRect = clampRect(candidate)  // existing helper, unchanged
        } else {
            // drag at >1.0x — pans the zoomed image
            imageTransform = applyPan(pan, imageTransform, boxSize)
        }
        imageTransform = clampTransform(
            imageTransform,
            boxSize = boxSize,
            sourceBitmapSize = sourceBitmapSize,
            cropRectInScreen = cropRect,
        )
    }
}
```

Notes:
- The scale-conditional branch enforces the user's chosen UX: at 1.0x pinch is ignored (`zoom==1` path goes to elif), drag moves the rect; at >1.0x pinch zooms, drag pans.
- `clampTransform` is called after both pinch and pan to keep the crop rect covered with image content.
- The existing `clampRect(...)` helper for rect-drag is reused, unchanged.

## 5. Transform Math

Three pure top-level functions, unit-testable without Compose.

### 5.1 `applyZoomAround(centroid, zoomFactor, transform, boxSize, sourceBitmapSize): ImageTransform`

Zooms the image centered on the user's pinch focal point.

**Math:**
- `newScale = (transform.scale * zoomFactor).coerceIn(MIN_SCALE, MAX_SCALE)`
- The image is centered in `boxSize`. At identity, the image's drawn topLeft = `(boxSize.center - sourceBitmapSize.toFloat() / 2)`.
- The scale-1 image-content center coincides with the screen-center of the image's drawn area.
- Pinch focal `centroid` (in screen coords) must keep pointing to the same image-content pixel after zoom.
- Algebra:

  ```text
  // Pre-pinch: drawnCenter = boxSize.center + transform.offset
  // Image-content point under centroid at old scale:
  //   P = ((centroid - drawnCenter_old) / oldScale) + imageContentCenter
  // After newScale and newOffset, drawnCenter_new = boxSize.center + newOffset.
  // We need: (P - imageContentCenter) * newScale + drawnCenter_new == centroid
  // Substituting P:
  //   (centroid - drawnCenter_old) * (newScale / oldScale) + drawnCenter_new == centroid
  //   drawnCenter_new = centroid - (centroid - drawnCenter_old) * (newScale / oldScale)
  //   newOffset = drawnCenter_new - boxSize.center
  ```

- Implementation:

  ```kotlin
  internal fun applyZoomAround(
      centroid: Offset,
      zoomFactor: Float,
      transform: ImageTransform,
      boxSize: IntSize,
      sourceBitmapSize: IntSize,
  ): ImageTransform {
      val oldScale = transform.scale
      val newScale = (oldScale * zoomFactor).coerceIn(MIN_SCALE, MAX_SCALE)
      val boxCenter = Offset(boxSize.width / 2f, boxSize.height / 2f)
      val drawnCenterOld = boxCenter + Offset(transform.offsetX, transform.offsetY)
      val scaleRatio = newScale / oldScale
      val drawnCenterNew = centroid - (centroid - drawnCenterOld) * scaleRatio
      val newOffsetX = drawnCenterNew.x - boxCenter.x
      val newOffsetY = drawnCenterNew.y - boxCenter.y
      return transform.copy(scale = newScale, offsetX = newOffsetX, offsetY = newOffsetY)
  }
  ```

- Edge cases (covered by unit tests):
  - `zoomFactor < 1/MIN_SCALE` clamps to `MIN_SCALE = 1.0` (no zoom out)
  - `zoomFactor > MAX_SCALE/oldScale` clamps to `MAX_SCALE = 3.0`
  - Inverse-invariance: `applyZoomAround(p, z, applyZoomAround(p, 1/z, t)) == t` (within float epsilon)
  - Invariant: the screen point `centroid` after zoom maps to the same image-content pixel as before (asserted via the `computeBitmapCropRect` test pair)

### 5.2 `applyPan(panDelta, transform, boxSize): ImageTransform`

```kotlin
internal fun applyPan(
    panDelta: Offset,
    transform: ImageTransform,
    boxSize: IntSize,
): ImageTransform {
    if (transform.scale == 1f) return transform  // panning disallowed at 1.0x
    return transform.copy(
        offsetX = transform.offsetX + panDelta.x,
        offsetY = transform.offsetY + panDelta.y,
    )
}
```

Pan is only called when `transform.scale > 1f` (gesture branch guarantees this), but the function defensively short-circuits anyway.

### 5.3 `clampTransform(transform, boxSize, sourceBitmapSize, cropRectInScreen): ImageTransform`

Ensures the screen-fixed `cropRect` always has image content beneath it.

**Steps:**
1. Compute image's drawn topLeft in screen coords (after scale + offset):
   ```kotlin
   val drawnCenterX = boxSize.width / 2f + transform.offsetX
   val drawnCenterY = boxSize.height / 2f + transform.offsetY
   val drawnW = sourceBitmapSize.width * transform.scale
   val drawnH = sourceBitmapSize.height * transform.scale
   val drawnLeft = drawnCenterX - drawnW / 2f
   val drawnTop = drawnCenterY - drawnH / 2f
   val drawnRight = drawnLeft + drawnW
   val drawnBottom = drawnTop + drawnH
   ```
2. Compute the crop rect's left/right/top/bottom — already in screen coords.
3. Clamp horizontal so the crop rect stays inside the image's drawn rect:
   - **Want:** `drawnLeft ≤ cropRectLeft` AND `drawnRight ≥ cropRectRight`
   - **Shift required:** `dx = (cropRectLeft - drawnLeft).coerceAtLeast(cropRectRight - drawnRight)` — the more restrictive bound wins.
4. Same clamp for vertical: `dy = (cropRectTop - drawnTop).coerceAtLeast(cropRectBottom - drawnBottom)`.
5. Apply: `transform.copy(offsetX = transform.offsetX + dx, offsetY = transform.offsetY + dy)`.

At `transform.scale == 1f` the image exactly fills its drawn bounding box inside `boxSize`, which already contains the crop rect, so the clamp produces a no-op (verified by unit test).

### 5.4 Updated `computeBitmapCropRect`

```kotlin
internal fun computeBitmapCropRect(
    boxSize: IntSize,
    cropRectInScreen: Rect,
    layout: CropLayout,
    sourceBitmapSize: IntSize,
    imageTransform: ImageTransform,
): BitmapCropRect
```

Three un-projections to recover source-bitmap coords:
1. **screen → displayedBox**: subtract the image's drawn topLeft (computed as in §5.3)
2. **displayedBox → fit-scaled content**: divide by `layout.scale` (the fit factor)
3. **fit-scaled content → source**: multiply by `(sourceBitmapSize.width / displayedW, sourceBitmapSize.height / displayedH)` — i.e., the source-to-displayed ratio

```kotlin
val drawnLeft = (boxSize.width / 2f + imageTransform.offsetX) -
    sourceBitmapSize.width * imageTransform.scale / 2f
val drawnTop = (boxSize.height / 2f + imageTransform.offsetY) -
    sourceBitmapSize.height * imageTransform.scale / 2f

val cropLeftInDisplayed = (cropRectInScreen.left - drawnLeft) / imageTransform.scale
val cropTopInDisplayed = (cropRectInScreen.top - drawnTop) / imageTransform.scale
val cropRightInDisplayed = (cropRectInScreen.right - drawnLeft) / imageTransform.scale
val cropBottomInDisplayed = (cropRectInScreen.bottom - drawnTop) / imageTransform.scale

val srcWPerDispW = sourceBitmapSize.width.toFloat() / (sourceBitmapSize.width * layout.scale * imageTransform.scale)
val srcHPerDispH = sourceBitmapSize.height.toFloat() / (sourceBitmapSize.height * layout.scale * imageTransform.scale)
val srcLeft = (cropLeftInDisplayed * srcWPerDispW).toInt().coerceIn(0, sourceBitmapSize.width)
val srcTop = (cropTopInDisplayed * srcHPerDispH).toInt().coerceIn(0, sourceBitmapSize.height)
val srcRight = (cropRightInDisplayed * srcWPerDispW).toInt().coerceIn(0, sourceBitmapSize.width)
val srcBottom = (cropBottomInDisplayed * srcHPerDispH).toInt().coerceIn(0, sourceBitmapSize.height)
val width = (srcRight - srcLeft).coerceAtLeast(1)
val height = (srcBottom - srcTop).coerceAtLeast(1)
return BitmapCropRect(x = srcLeft, y = srcTop, width = width, height = height)
```

The trailing `coerceIn` is defense-in-depth; the clamp guarantee from §5.3 should already keep these inside source bounds.

**Backward compatibility:** with `imageTransform = ImageTransform()` (scale=1, offset=0) and the same fit scale as before, this returns the exact same `BitmapCropRect` as today's implementation — verified by unit test `identityTransform_returnsFitScaledCrop`.

## 6. Testing Strategy

Three new test classes, all pure unit tests (no Robolectric, no Compose):

### 6.1 `ImageTransformTest`
- `applyZoomAround_centroidInvariant_zoomedInOutReturnsToIdentity`
- `applyZoomAround_clampsToMin` (zoom factor 0.1x → scale stays 1.0)
- `applyZoomAround_clampsToMax` (zoom factor 5x → scale clamped to 3.0)
- `applyZoomAround_focalPointMapsToSameContentPixel` (the screen point `centroid` after zoom un-projects to the same source-bitmap pixel before and after)
- `applyPan_atScaleOne_isNoop`
- `applyPan_atScaleAboveOne_offsetsByDelta`
- `clampTransform_atScaleOne_isNoop`
- `clampTransform_keepsDrawnRectCoveringCropRect` (pan beyond edge → drawn rect still covers crop rect)

### 6.2 `ComputeBitmapCropRectTest`
- `identityTransform_matchesExistingFitScaledOutput`
- `zoomIn_returnsSmallSourceRect`
- `panAndZoom_returnsTranslatedSourceRect`
- `clampsToSourceBounds` (defense-in-depth; passes extreme values directly)

### 6.3 No new `MemberCardCropScreenTest`
Skip if Compose-test infrastructure would need scaffolding. Pure-function tests cover the gesture → output chain.

### Existing tests (unchanged)
- `MemberCardDaoTest` (androidTest): DAO CRUD/search
- `MemberCardMigrationTest` (androidTest): schema migration

## 7. Implementation Plan

Tasks (each TDD where applicable):

1. **`ImageTransform` data class** in `MemberCardCropScreen.kt`
2. **Pure helpers + unit tests** (`ImageTransformTest`)
3. **Updated `computeBitmapCropRect` + unit tests** (`ComputeBitmapCropRectTest`)
4. **Wire `imageTransform` state** in `CropBody` composable
5. **Add `graphicsLayer`** on the `Image` composable
6. **Swap gesture detector** to `detectTransformGestures` with scale-conditional branch
7. **Smoke test** on emulator (build APK, open the member-card crop flow, pinch and drag)
8. **Commit + tag as `v0.18.18`** on master (Phase 4d fix is `v0.18.17`; this is the next minor version). Push only after explicit user approval.

## 8. Open Questions / Future Work

- **Animated reset on zoom**: if user wants double-tap to reset to 1.0x, add as a follow-up. Out of scope per user.
- **Persistence**: zoom state could persist across screen re-entries via `SavedStateHandle`. Out of scope per user.
- **Hysteresis / zoom momentum**: Android's default gesture detector already smooths pan/zoom somewhat. Custom `Animatable` for spring physics would be nicer for production polish. Out of scope.

---

## File Changes Summary

- **Modify:** `app/src/main/java/io/github/jiro/expensetracker/ui/cards/MemberCardCropScreen.kt`
- **Create:** `app/src/test/java/io/github/jiro/expensetracker/ui/cards/ImageTransformTest.kt`
- **Create:** `app/src/test/java/io/github/jiro/expensetracker/ui/cards/ComputeBitmapCropRectTest.kt`

## Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| Zoom-around-focal-point algebra is wrong (image "jumps" on pinch) | Unit tests assert centroid invariance and inverse-invariance |
| Clamp math is wrong (image goes outside crop rect → transparent corners) | Unit tests assert drawn rect covers crop rect |
| `computeBitmapCropRect` regression at identity transform | Backward-compat test: identity transform produces same output as old implementation |
| Performance: re-rasterizing on every zoom frame | `graphicsLayer` + Compose already handles this; no manual invalidation needed |
| Crop output dimensions shrink too much at 3.0x zoom | Acceptable: cropping small detail is the goal. Output quality is the user's choice. |
