package io.github.jiro.expensetracker.ui.cards

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ImageNotSupported
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.jiro.expensetracker.R
import io.github.jiro.expensetracker.data.local.ImageProcessor
import io.github.jiro.expensetracker.data.repository.MemberCardRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Renders a member-card image from a [MemberCardRepository]-stored path.
 *
 * States:
 * - **Loading:** centered spinner (transient, no string)
 * - **Missing on disk** (repository.absolutePath returns null): centered
 *   [Icons.Filled.ImageNotSupported] icon + `R.string.cards_image_missing`
 *   label, both tinted with `colorScheme.error`.
 * - **Decode failure:** same UI as missing (the spec treats both as
 *   "image missing" — no separate error path on this composable).
 * - **Loaded:** the bitmap rendered with the caller-supplied [contentScale].
 *
 * Tap behavior is NOT the composable's responsibility — callers wrap in
 * their own `Modifier.clickable` for replace/full-screen.
 *
 * The bitmap is recycled when [relativePath] changes or this composable
 * leaves the composition, to avoid leaks across navigation. We track the
 * decoded bitmap in a holder keyed on the path; on relaunch we explicitly
 * `recycle()` the previous holder before overwriting so large bitmaps
 * don't pile up while a user browses many cards.
 */
@Composable
fun MemberCardImage(
    relativePath: String,
    repository: MemberCardRepository,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    // `holder` is a small container we mutate from the LaunchedEffect when a new
    // path resolves. We keep `previousBitmap` so we can recycle it when the
    // composable leaves the composition (DisposableEffect below).
    var bitmap by remember(relativePath) { mutableStateOf<Bitmap?>(null) }
    var isMissingOrFailed by remember(relativePath) { mutableStateOf(false) }
    var isLoading by remember(relativePath) { mutableStateOf(true) }

    LaunchedEffect(relativePath) {
        isLoading = true
        isMissingOrFailed = false
        // Recycle the previous decode (if any) before attempting a new one —
        // member cards can be large and a user may navigate between several
        // before the old one is GC'd, so be explicit about releasing.
        bitmap?.takeIf { !it.isRecycled }?.recycle()
        bitmap = null

        val file = repository.absolutePath(relativePath)
        if (file == null) {
            isMissingOrFailed = true
            isLoading = false
            return@LaunchedEffect
        }

        val decoded = withContext(Dispatchers.IO) {
            runCatching { ImageProcessor.decodeSampledBitmap(file, maxEdge = 1024) }.getOrNull()
        }
        if (decoded == null) {
            isMissingOrFailed = true
            isLoading = false
            return@LaunchedEffect
        }
        bitmap = decoded
        isLoading = false
    }

    // When the composable leaves the composition entirely (e.g. navigation
    // away from Detail screen) make sure the bitmap is freed promptly.
    DisposableEffect(relativePath) {
        onDispose {
            bitmap?.takeIf { !it.isRecycled }?.recycle()
            bitmap = null
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when {
            bitmap != null -> {
                val bmp = bitmap!!
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = contentScale,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            isMissingOrFailed -> MissingImagePlaceholder(modifier = Modifier.fillMaxSize())
            else -> LoadingPlaceholder(modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun MissingImagePlaceholder(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.ImageNotSupported,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = stringResource(R.string.cards_image_missing),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun LoadingPlaceholder(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            strokeWidth = 2.dp,
            modifier = Modifier.size(32.dp),
        )
    }
}
