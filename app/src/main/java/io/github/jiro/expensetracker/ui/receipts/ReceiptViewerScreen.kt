package io.github.jiro.expensetracker.ui.receipts

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.jiro.expensetracker.R
import io.github.jiro.expensetracker.data.repository.ReceiptRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptViewerScreen(
    receiptPath: String,
    receiptRepository: ReceiptRepository,
    onBack: () -> Unit,
) {
    val isPdf = remember(receiptPath) { receiptPath.endsWith(".pdf", ignoreCase = true) }
    var pages by remember(receiptPath) { mutableStateOf<List<android.graphics.Bitmap>>(emptyList()) }
    var missing by remember(receiptPath) { mutableStateOf(false) }

    LaunchedEffect(receiptPath) {
        if (!receiptRepository.exists(receiptPath)) {
            missing = true
            return@LaunchedEffect
        }
        pages = withContext(Dispatchers.IO) {
            if (isPdf) {
                val count = runCatching { receiptRepository.openPdfPageCount(receiptPath) }.getOrDefault(0)
                if (count == 0) emptyList()
                else (0 until count).map {
                    runCatching { receiptRepository.renderPdfPage(receiptPath, it) }.getOrNull()
                }.filterNotNull()
            } else {
                val bmp = runCatching {
                    io.github.jiro.expensetracker.data.local.ImageProcessor
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
            )
        },
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
                    val pagerState = rememberPagerState(pageCount = { pages.size })
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                    ) { pageIndex ->
                        Image(
                            bitmap = pages[pageIndex].asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
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
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
