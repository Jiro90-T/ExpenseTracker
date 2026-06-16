package io.github.jiro.expensetracker.ui.receipts

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.jiro.expensetracker.R
import io.github.jiro.expensetracker.data.local.ImageProcessor
import io.github.jiro.expensetracker.data.repository.ReceiptRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptViewerScreen(
    receiptPath: String,
    receiptRepository: ReceiptRepository,
    onBack: () -> Unit,
    viewModel: ReceiptViewerViewModel = hiltViewModel(),
) {
    val isPdf = remember(receiptPath) { receiptPath.endsWith(".pdf", ignoreCase = true) }
    var pages by remember(receiptPath) { mutableStateOf<List<Bitmap>>(emptyList()) }
    var missing by remember(receiptPath) { mutableStateOf(false) }
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var scale by remember(receiptPath) { mutableFloatStateOf(1f) }
    var offset by remember(receiptPath) { mutableStateOf(Offset.Zero) }
    val zoomModifier = Modifier
        .pointerInput(receiptPath) {
            detectTransformGestures { _, pan, zoom, _ ->
                scale = (scale * zoom).coerceIn(1f, 5f)
                if (scale > 1f) offset += pan
            }
        }
        .pointerInput(receiptPath) {
            detectTapGestures(onDoubleTap = {
                scale = 1f
                offset = Offset.Zero
            })
        }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            translationX = offset.x
            translationY = offset.y
        }
    val shareSuccessMsg = stringResource(R.string.receipt_save_success)
    val saveFailedFmt = stringResource(R.string.receipt_save_failed)

    LaunchedEffect(receiptPath) {
        if (!receiptRepository.exists(receiptPath)) {
            missing = true
            return@LaunchedEffect
        }
        // TODO Phase 2.4 MVP: eagerly decode all pages. For multi-page PDFs
        // (50+ pages) this can OOM. Follow-up: switch to per-page decoding
        // using `produceState` keyed on the current pager page. Most receipts
        // are 1-2 pages so this isn't blocking.
        pages = withContext(Dispatchers.IO) {
            if (isPdf) {
                val count = runCatching { receiptRepository.openPdfPageCount(receiptPath) }.getOrDefault(0)
                if (count == 0) emptyList()
                else (0 until count).map {
                    runCatching { receiptRepository.renderPdfPage(receiptPath, it) }.getOrNull()
                }.filterNotNull()
            } else {
                val bmp = runCatching {
                    ImageProcessor
                        .decodeSampledBitmap(receiptRepository.absolutePath(receiptPath), maxEdge = 4096)
                }.getOrNull()
                if (bmp != null) listOf(bmp) else emptyList()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.receipt_viewer_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    val hasReceipt = receiptPath.isNotEmpty() && pages.isNotEmpty()
                    IconButton(
                        enabled = hasReceipt,
                        onClick = {
                            scope.launch {
                                val intent = viewModel.buildShareIntent(receiptPath) ?: return@launch
                                val chooser = Intent.createChooser(intent, "Share receipt").apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                runCatching { context.startActivity(chooser) }
                                    .onFailure { android.util.Log.w("ReceiptVM", "startActivity failed: ${it.message}") }
                            }
                        },
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.receipt_action_share))
                    }
                    IconButton(
                        enabled = hasReceipt,
                        onClick = {
                            scope.launch {
                                val displayName = receiptPath.substringAfterLast('/').ifEmpty { "receipt.jpg" }
                                val result = viewModel.saveToPhotos(receiptPath, displayName)
                                val message = when (result) {
                                    is SaveResult.Success -> shareSuccessMsg
                                    is SaveResult.Failure -> String.format(saveFailedFmt, result.message)
                                }
                                snackbarHostState.showSnackbar(message)
                            }
                        },
                    ) {
                        Icon(Icons.Filled.PhotoLibrary, contentDescription = stringResource(R.string.receipt_action_save_to_photos))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            when {
                missing -> Text(
                    text = stringResource(if (isPdf) R.string.receipt_viewer_pdf_missing else R.string.receipt_viewer_image_missing),
                    color = Color.White,
                )
                pages.isEmpty() -> Text(
                    text = stringResource(if (isPdf) R.string.receipt_viewer_pdf_missing else R.string.receipt_viewer_image_missing),
                    color = Color.White,
                )
                isPdf -> {
                    key(pagerState.currentPage) {
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize().then(zoomModifier),
                        ) { pageIndex ->
                            Image(
                                bitmap = pages[pageIndex].asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Text(
                        text = "${pagerState.currentPage + 1} / ${pages.size}",
                        color = Color.White,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    )
                }
                else -> Image(
                    bitmap = pages.first().asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().then(zoomModifier),
                )
            }
        }
    }
}
