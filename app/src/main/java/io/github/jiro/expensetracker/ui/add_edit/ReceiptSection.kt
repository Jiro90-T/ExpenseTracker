package io.github.jiro.expensetracker.ui.add_edit

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import io.github.jiro.expensetracker.R
import io.github.jiro.expensetracker.data.local.ImageProcessor
import io.github.jiro.expensetracker.data.repository.ReceiptRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * The "Receipt" section of the AddEdit form. Renders a thumbnail (or a
 * dashed "Attach receipt" placeholder) and an action row that lets the user
 * pick from the camera or the gallery.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptSection(
    receiptPath: String?,
    onAttached: (Uri) -> Unit,
    onRemoved: () -> Unit,
    onOpen: () -> Unit,
    receiptRepository: ReceiptRepository,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState()
    var showPicker by remember { mutableStateOf(false) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        if (success) {
            pendingCameraUri?.let { uri -> onAttached(uri) }
        }
        pendingCameraUri = null
    }

    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) onAttached(uri)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            // Re-create the camera URI now that we have permission.
            pendingCameraUri = createCameraCaptureUri(context)
        } else {
            // Show the denial string via the existing onOcrSnackbar (or a TODO).
            // For MVP: just log. The user can still pick from gallery.
            android.util.Log.w("ReceiptSection", "Camera permission denied")
        }
    }

    LaunchedEffect(pendingCameraUri) {
        val uri = pendingCameraUri ?: return@LaunchedEffect
        cameraLauncher.launch(uri)
    }

    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.receipt_section_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        if (receiptPath == null) {
            AttachPlaceholder(onClick = { showPicker = true })
        } else {
            ReceiptThumbnail(
                receiptPath = receiptPath,
                repository = receiptRepository,
                onOpen = onOpen,
                onReplace = { showPicker = true },
                onRemove = onRemoved,
            )
        }
    }

    if (showPicker) {
        ModalBottomSheet(
            onDismissRequest = { showPicker = false },
            sheetState = sheetState,
        ) {
            Column {
                TextButton(
                    onClick = {
                        showPicker = false
                        if (ContextCompat.checkSelfPermission(
                                context, android.Manifest.permission.CAMERA,
                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            pendingCameraUri = createCameraCaptureUri(context)
                        } else {
                            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                ) { Text(stringResource(R.string.receipt_take_photo)) }
                TextButton(
                    onClick = {
                        showPicker = false
                        fileLauncher.launch(arrayOf("image/*", "application/pdf"))
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                ) { Text(stringResource(R.string.receipt_choose)) }
            }
        }
    }
}

@Composable
private fun AttachPlaceholder(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.AddPhotoAlternate,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.receipt_attach),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ReceiptThumbnail(
    receiptPath: String,
    repository: ReceiptRepository,
    onOpen: () -> Unit,
    onReplace: () -> Unit,
    onRemove: () -> Unit,
) {
    var bitmap by remember(receiptPath) { mutableStateOf<Bitmap?>(null) }
    val isPdf = remember(receiptPath) { receiptPath.endsWith(".pdf", ignoreCase = true) }
    var missing by remember(receiptPath) { mutableStateOf(false) }

    LaunchedEffect(receiptPath) {
        if (!repository.exists(receiptPath)) {
            missing = true
            return@LaunchedEffect
        }
        missing = false
        bitmap = withContext(Dispatchers.IO) {
            if (isPdf) {
                runCatching { repository.renderPdfPage(receiptPath, 0) }.getOrNull()
            } else {
                runCatching {
                    ImageProcessor
                        .decodeSampledBitmap(repository.absolutePath(receiptPath), maxEdge = 512)
                }.getOrNull()
            }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onOpen),
            contentAlignment = Alignment.Center,
        ) {
            when {
                missing -> Text(
                    text = stringResource(R.string.receipt_missing),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                bitmap != null -> {
                    val bmp = bitmap!!
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(96.dp).clip(RoundedCornerShape(12.dp)),
                    )
                    if (isPdf) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.receipt_pdf_badge),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                else -> Icon(
                    imageVector = if (isPdf) Icons.Filled.PictureAsPdf else Icons.Filled.AddPhotoAlternate,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
        Spacer(Modifier.size(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onReplace) {
                Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(4.dp))
                Text(stringResource(R.string.receipt_replace))
            }
            TextButton(onClick = onRemove) {
                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(4.dp))
                Text(stringResource(R.string.receipt_remove))
            }
        }
    }
}

// Helper extracted for clarity. Create the camera output URI under
// <filesDir>/receipts/.capture/. Caller is responsible for moving the file
// into the live receipts dir on success.
private fun createCameraCaptureUri(context: android.content.Context): Uri {
    val captureDir = File(context.filesDir, "receipts/.capture").apply { mkdirs() }
    val captureFile = File(captureDir, "${UUID.randomUUID()}.jpg")
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        captureFile,
    )
}
