package io.github.jiro.expensetracker.ui.cards

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.jiro.expensetracker.R
import io.github.jiro.expensetracker.data.local.ImageProcessor
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Crop screen for member-card images. After a camera capture or gallery pick,
 * the user is sent here to position the crop rectangle over the image.
 *
 * MVP scope (intentionally limited):
 *  - No zoom/pan of the image itself; image is rendered at [ContentScale.Fit].
 *  - Free aspect ratio; rectangle starts at the image's full bounds.
 *  - Drag-to-reposition only (no resize handles).
 *  - Single Compose screen, no separate Activity.
 *
 * The cropped bitmap is JPEG-encoded (q=90) and written to
 * `<cacheDir>/cards/cropped/<uuid>.jpg`; the absolute path is returned via
 * [onCropped] so the caller can hand it to the repository as if it were
 * the original pick.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemberCardCropScreen(
    sourceUri: String,
    onCancel: () -> Unit,
    onCropped: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var loadError by remember { mutableStateOf(false) }
    var isCropping by remember { mutableStateOf(false) }

    // Bitmap is decoded off the main thread on entry. A content:// URI is
    // first copied to a temp file because [ImageProcessor.decodeSampledBitmap]
    // expects a real file path.
    LaunchedEffect(sourceUri) {
        loadError = false
        val decoded: Bitmap? = withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(sourceUri)
                val file = if (uri.scheme == "content") {
                    val tempFile = File.createTempFile(
                        "card-crop-src-", ".bin", context.cacheDir,
                    )
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { out -> input.copyTo(out) }
                    } ?: return@runCatching null
                    tempFile
                } else {
                    val path = uri.path ?: sourceUri
                    File(path)
                }
                if (!file.isFile) return@runCatching null
                ImageProcessor.decodeSampledBitmap(file, maxEdge = 2048)
            }.getOrNull()
        }
        if (decoded == null) {
            loadError = true
        } else {
            sourceBitmap = decoded
        }
    }

    // Recycle the source bitmap on dispose so navigating away frees memory.
    DisposableEffect(Unit) {
        onDispose {
            sourceBitmap?.takeIf { !it.isRecycled }?.recycle()
            sourceBitmap = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cards_crop_title)) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 4.dp),
                ) { Text(stringResource(R.string.cards_crop_cancel)) }
                Button(
                    onClick = {
                        val bmp = sourceBitmap ?: return@Button
                        if (isCropping) return@Button
                        isCropping = true
                        scope.launch {
                            val path = withContext(Dispatchers.IO) {
                                runCatching {
                                    val (rect, layout) = cropState
                                    if (rect == null || layout == null) return@runCatching null
                                    cropAndEncode(
                                        source = bmp,
                                        layout = layout,
                                        cropRect = rect,
                                        cacheDir = context.cacheDir,
                                    )
                                }.getOrNull()
                            }
                            isCropping = false
                            if (path != null) onCropped(path)
                        }
                    },
                    enabled = sourceBitmap != null && !loadError && !isCropping,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 4.dp),
                ) { Text(stringResource(R.string.cards_crop_confirm)) }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.Black),
        ) {
            when {
                loadError -> ErrorMessage(
                    text = stringResource(R.string.cards_crop_load_error),
                )
                sourceBitmap == null -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = Color.White) }
                else -> CropBody(
                    bitmap = sourceBitmap!!,
                    onCropStateChange = { rect, layout -> cropState = rect to layout },
                )
            }
        }
    }
}

// Holds the latest crop rect + image layout in screen-pixel coordinates.
// Module-level on purpose: CropBody writes here on every change, and the
// Crop button reads it when the user taps. This is intentionally simple
// (and tightly scoped to a single screen lifetime) — the screen always
// exists exactly once per navigation entry.
private var cropState: Pair<Rect?, CropLayout?> = null to null

/**
 * The crop body — image + dimmed cutout overlay + drag handling.
 */
@Composable
private fun CropBody(
    bitmap: Bitmap,
    onCropStateChange: (Rect?, CropLayout?) -> Unit,
) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { boxSize = it },
        contentAlignment = Alignment.Center,
    ) {
        val boxW = boxSize.width.toFloat()
        val boxH = boxSize.height.toFloat()
        val bmpW = bitmap.width.toFloat()
        val bmpH = bitmap.height.toFloat()

        if (boxW <= 0f || boxH <= 0f || bmpW <= 0f || bmpH <= 0f) return@BoxWithConstraints

        // ContentScale.Fit math — the bitmap is uniformly scaled to fit
        // inside the box and centered.
        val scale = min(boxW / bmpW, boxH / bmpH)
        val displayedW = bmpW * scale
        val displayedH = bmpH * scale
        val displayedLeft = (boxW - displayedW) / 2f
        val displayedTop = (boxH - displayedH) / 2f

        val imageBounds = Rect(
            offset = Offset(displayedLeft, displayedTop),
            size = Size(displayedW, displayedH),
        )

        val layout = CropLayout(
            displayedLeft = displayedLeft,
            displayedTop = displayedTop,
            scale = scale,
        )

        // Initial crop rect = the full displayed image bounds. Keyed on
        // the bitmap identity so a new source resets to full bounds.
        var cropRect by remember(bitmap) {
            mutableStateOf(imageBounds)
        }

        // Clamp helper — keeps the crop rect inside the image bounds.
        // Note: for MVP, the rectangle keeps its full size; the user only
        // drags it around. Resize handles are intentionally out of scope.
        fun clampRect(r: Rect): Rect {
            val w = r.width
            val h = r.height
            val maxLeft = imageBounds.right - w
            val maxTop = imageBounds.bottom - h
            val newLeft = r.left.coerceIn(imageBounds.left, maxLeft)
            val newTop = r.top.coerceIn(imageBounds.top, maxTop)
            return Rect(newLeft, newTop, newLeft + w, newTop + h)
        }

        // Publish the current state upward on every change.
        LaunchedEffect(cropRect, layout) {
            onCropStateChange(cropRect, layout)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(bitmap) {
                    detectDragGestures(
                        onDragStart = { /* offset is computed per-event */ },
                    ) { change, dragAmount ->
                        change.consume()
                        val candidate = Rect(
                            offset = cropRect.topLeft + dragAmount,
                            size = cropRect.size,
                        )
                        cropRect = clampRect(candidate)
                    }
                },
        ) {
            // Underlying image.
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            // Dimmed overlay with a cutout that exposes the un-dimmed image
            // inside the crop rect. Drawn as four rectangles around the crop
            // area, plus a thin border to mark the crop edge.
            Canvas(modifier = Modifier.fillMaxSize()) {
                val overlay = Color.Black.copy(alpha = 0.5f)
                drawRect(
                    color = overlay,
                    topLeft = Offset(0f, 0f),
                    size = Size(size.width, cropRect.top),
                )
                drawRect(
                    color = overlay,
                    topLeft = Offset(0f, cropRect.bottom),
                    size = Size(size.width, size.height - cropRect.bottom),
                )
                drawRect(
                    color = overlay,
                    topLeft = Offset(0f, cropRect.top),
                    size = Size(cropRect.left, cropRect.height),
                )
                drawRect(
                    color = overlay,
                    topLeft = Offset(cropRect.right, cropRect.top),
                    size = Size(size.width - cropRect.right, cropRect.height),
                )
                drawRect(
                    color = Color.White,
                    topLeft = cropRect.topLeft,
                    size = cropRect.size,
                    style = Stroke(width = 2f),
                )
            }
        }
    }
}

@Composable
private fun ErrorMessage(text: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}

private data class CropLayout(
    val displayedLeft: Float,
    val displayedTop: Float,
    val scale: Float,
)

/**
 * Map the on-screen crop rect to image-pixel coordinates, slice the source
 * bitmap, JPEG-encode at quality 90, and write to
 * `<cacheDir>/cards/cropped/<uuid>.jpg`. Returns the absolute path.
 *
 * The new (cropped) bitmap is recycled after encoding. The source bitmap
 * is left intact — the caller still owns it and is responsible for
 * recycling.
 */
private fun cropAndEncode(
    source: Bitmap,
    layout: CropLayout,
    cropRect: Rect,
    cacheDir: File,
): String? {
    val scale = layout.scale
    if (scale <= 0f) return null
    val imageX = ((cropRect.left - layout.displayedLeft) / scale).toInt().coerceAtLeast(0)
    val imageY = ((cropRect.top - layout.displayedTop) / scale).toInt().coerceAtLeast(0)
    val imageW = (cropRect.width / scale).toInt().coerceAtLeast(1)
    val imageH = (cropRect.height / scale).toInt().coerceAtLeast(1)
    val safeX = min(imageX, source.width - 1)
    val safeY = min(imageY, source.height - 1)
    val safeW = min(imageW, source.width - safeX)
    val safeH = min(imageH, source.height - safeY)
    if (safeW <= 0 || safeH <= 0) return null
    val cropped: Bitmap = Bitmap.createBitmap(source, safeX, safeY, safeW, safeH)
        ?: return null
    return try {
        val outDir = File(cacheDir, "cards/cropped").apply { mkdirs() }
        val outFile = File(outDir, "${UUID.randomUUID()}.jpg")
        FileOutputStream(outFile).use { out ->
            cropped.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        outFile.absolutePath
    } finally {
        if (!cropped.isRecycled) cropped.recycle()
    }
}