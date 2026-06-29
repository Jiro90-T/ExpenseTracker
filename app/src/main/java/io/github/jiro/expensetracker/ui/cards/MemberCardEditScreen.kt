package io.github.jiro.expensetracker.ui.cards

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jiro.expensetracker.BuildConfig
import io.github.jiro.expensetracker.R
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The 8 emoji glyphs offered for member-card icon selection. Mirrors the
 * visual mockup in the design doc.
 */
private val CARD_ICON_CHOICES = listOf("💳", "🎁", "⭐", "🛒", "✈️", "☕", "🎬", "📚")

/**
 * The 6 preset swatch colors. ARGB ints so they can be applied directly to
 * [Color]. An additional "Auto" choice sets the color to `null` and lets
 * the renderer fall back to the surface variant.
 */
private val CARD_COLOR_CHOICES = listOf(
    0xFF43A047.toInt(), // green
    0xFF1976D2.toInt(), // blue
    0xFFC62828.toInt(), // red
    0xFFF57C00.toInt(), // orange
    0xFF455A64.toInt(), // slate
    0xFF7B1FA2.toInt(), // purple
)

/**
 * Add/Edit screen for a member card.
 *
 * Used for both the Add (no `cardId` arg) and Edit (`cardId` arg) flows.
 * Pops itself via [onSaved] when [MemberCardEditUiState.saveComplete] flips
 * true. Intercepts back navigation when the form is dirty and prompts for
 * confirmation before discarding.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemberCardEditScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: MemberCardEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var showImageSheet by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showDiscardConfirm by remember { mutableStateOf(false) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    // Camera intent: write to a fresh temp file in the app cache so we
    // can hand a real file://-backed content URI to the system camera.
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        val uri = pendingCameraUri
        pendingCameraUri = null
        if (success && uri != null) {
            viewModel.onImagePicked(uri)
        }
    }

    // Gallery picker using the visual-media contract — no runtime permission
    // needed on Android 13+ for PickVisualMedia.
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) viewModel.onImagePicked(uri)
    }

    // Launch the camera whenever the pending URI is set. Using a separate
    // LaunchedEffect (instead of launching inline) avoids holding the
    // launcher reference across recompositions.
    LaunchedEffect(pendingCameraUri) {
        val uri = pendingCameraUri ?: return@LaunchedEffect
        cameraLauncher.launch(uri)
    }

    // One-shot side effects: pop after save, surface errors as snackbar.
    LaunchedEffect(state.saveComplete) {
        if (state.saveComplete) onSaved()
    }
    LaunchedEffect(state.errorMessage) {
        val msg = state.errorMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.onErrorShown()
        }
    }

    // Back button intercepts when dirty — otherwise the user could lose
    // work by accidentally tapping the nav arrow.
    BackHandler(enabled = state.isDirty) {
        showDiscardConfirm = true
    }

    val dateFormatter = remember { DateTimeFormatter.ofPattern("MMM d, yyyy") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cards_edit_title)) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (state.isDirty) showDiscardConfirm = true else onBack()
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
        bottomBar = {
            EditBottomBar(
                onCancel = {
                    if (state.isDirty) showDiscardConfirm = true else onBack()
                },
                onSave = viewModel::save,
                saveEnabled = !state.isSaving &&
                    state.name.isNotBlank() &&
                    state.imageUri != null,
                isSaving = state.isSaving,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(it) } },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Name field.
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::onNameChange,
                label = { Text(stringResource(R.string.cards_field_name)) },
                singleLine = true,
                isError = state.nameError != null,
                supportingText = if (state.nameError is NameError.REQUIRED) {
                    { Text("Required") }
                } else null,
                modifier = Modifier.fillMaxWidth(),
            )

            // Image tile — tappable to open the picker sheet.
            ImageTile(
                imageUri = state.imageUri,
                hasError = state.imageError != null,
                onTap = { showImageSheet = true },
            )

            // Member ID field.
            OutlinedTextField(
                value = state.memberIdText,
                onValueChange = viewModel::onMemberIdChange,
                label = { Text(stringResource(R.string.cards_field_member_id)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // Color picker row.
            ColorPickerRow(
                selected = state.colorHex,
                onChange = viewModel::onColorChange,
            )

            // Icon picker row.
            IconPickerRow(
                selected = state.icon,
                onChange = viewModel::onIconChange,
            )

            // Expiry date row.
            ExpiryRow(
                epochMillis = state.expiresAtEpochMillis,
                formatter = dateFormatter,
                onPickClick = { showDatePicker = true },
                onClearClick = { viewModel.onExpiresChange(null) },
            )

            // Notes field.
            OutlinedTextField(
                value = state.notes,
                onValueChange = viewModel::onNotesChange,
                label = { Text(stringResource(R.string.cards_field_notes)) },
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))
        }
    }

    if (showImageSheet) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = {
                showImageSheet = false
                viewModel.onImagePickerDismissed()
            },
            sheetState = sheetState,
        ) {
            Column {
                TextButton(
                    onClick = {
                        showImageSheet = false
                        viewModel.onImagePickerDismissed()
                        pendingCameraUri = createCameraCaptureUri(context)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                ) { Text(stringResource(R.string.cards_image_take_photo)) }
                TextButton(
                    onClick = {
                        showImageSheet = false
                        viewModel.onImagePickerDismissed()
                        galleryLauncher.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                ) { Text(stringResource(R.string.cards_image_pick_gallery)) }
            }
        }
    }

    if (showDatePicker) {
        val today = remember { LocalDate.now() }
        val initial = state.expiresAtEpochMillis
            ?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
            ?: today
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = initial
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val millis = pickerState.selectedDateMillis
                    if (millis != null) {
                        viewModel.onExpiresChange(millis)
                    }
                    showDatePicker = false
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text(stringResource(R.string.cards_discard_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardConfirm = false
                    onBack()
                }) { Text(stringResource(R.string.cards_discard_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun EditBottomBar(
    onCancel: () -> Unit,
    onSave: () -> Unit,
    saveEnabled: Boolean,
    isSaving: Boolean,
) {
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
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.cards_cancel)) }
        Button(
            onClick = onSave,
            enabled = saveEnabled && !isSaving,
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.cards_save)) }
    }
}

/**
 * Tappable image preview. Shows the currently attached photo (if any) with
 * a "Replace image" overlay, or a dashed placeholder prompting the user
 * to add an image. Tapping anywhere opens the picker sheet.
 *
 * For existing cards, the state holds the absolute file path of the image
 * already on disk; for fresh picks it holds a content:// or file:// URI.
 * Both cases decode via [io.github.jiro.expensetracker.data.local.ImageProcessor].
 */
@Composable
private fun ImageTile(
    imageUri: String?,
    hasError: Boolean,
    onTap: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = if (hasError) 2.dp else 1.dp,
                color = if (hasError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp),
            )
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        if (imageUri != null) {
            AttachedImagePreview(uri = imageUri)
        } else {
            Text(
                text = stringResource(R.string.cards_image_placeholder),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun AttachedImagePreview(uri: String) {
    // Decode asynchronously to keep the main thread responsive even though
    // the picked image is local. We recycle the previous bitmap when the
    // URI changes so navigating away then back doesn't leak the buffer.
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
    DisposableEffect(uri) {
        onDispose {
            bitmap?.takeIf { !it.isRecycled }?.recycle()
        }
    }
    LaunchedEffect(uri) {
        bitmap?.takeIf { !it.isRecycled }?.recycle()
        bitmap = null
        bitmap = withContext(Dispatchers.IO) {
            val file = File(uri)
            val decoded = if (file.isFile) {
                runCatching {
                    io.github.jiro.expensetracker.data.local.ImageProcessor
                        .decodeSampledBitmap(file, maxEdge = 1024)
                }.getOrNull()
            } else {
                runCatching {
                    context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
                        val tempFile = File.createTempFile(
                            "card-preview-", ".bin", context.cacheDir,
                        )
                        tempFile.outputStream().use { out -> input.copyTo(out) }
                        val decoded2 = io.github.jiro.expensetracker.data.local.ImageProcessor
                            .decodeSampledBitmap(tempFile, maxEdge = 1024)
                        tempFile.delete()
                        decoded2
                    }
                }.getOrNull()
            }
            decoded
        }
    }
    val current = bitmap
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (current != null) {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // Replace-image hint overlay (bottom-right).
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = stringResource(R.string.cards_image_replace),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        } else {
            Text(
                text = stringResource(R.string.cards_image_load_error),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ColorPickerRow(
    selected: Int?,
    onChange: (Int?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.cards_field_color),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AutoSwatch(active = selected == null, onClick = { onChange(null) })
            CARD_COLOR_CHOICES.forEach { argb ->
                ColorSwatch(
                    color = Color(argb),
                    selected = selected == argb,
                    onClick = { onChange(argb) },
                )
            }
        }
    }
}

@Composable
private fun ColorSwatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(color)
            .border(
                width = if (selected) 3.dp else 0.dp,
                color = MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick),
    )
}

@Composable
private fun AutoSwatch(active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = if (active) 3.dp else 1.dp,
                color = if (active) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "A",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun IconPickerRow(
    selected: String?,
    onChange: (String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.cards_field_icon),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AutoIcon(active = selected == null, onClick = { onChange(null) })
            CARD_ICON_CHOICES.forEach { emoji ->
                IconSwatch(
                    emoji = emoji,
                    selected = selected == emoji,
                    onClick = { onChange(emoji) },
                )
            }
        }
    }
}

@Composable
private fun IconSwatch(emoji: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else Color.Transparent,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji, style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
private fun AutoIcon(active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(
                width = if (active) 2.dp else 1.dp,
                color = if (active) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "A",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ExpiryRow(
    epochMillis: Long?,
    formatter: DateTimeFormatter,
    onPickClick: () -> Unit,
    onClearClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.cards_field_expiry),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = epochMillis?.let { millis ->
                    val date = Instant.ofEpochMilli(millis)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
                    date.format(formatter)
                } ?: "—",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onPickClick)
                    .padding(vertical = 8.dp),
            )
            if (epochMillis != null) {
                TextButton(onClick = onClearClick) {
                    Text(stringResource(R.string.cards_cancel))
                }
            }
        }
    }
}

/**
 * Helper: build a fresh file under the app cache and return a
 * FileProvider URI for it. The path matches what the FileProvider XML
 * exposes — see `res/xml/file_paths.xml`.
 */
private fun createCameraCaptureUri(context: android.content.Context): Uri {
    val captureDir = File(context.cacheDir, "card-camera").apply { mkdirs() }
    val captureFile = File(captureDir, "${UUID.randomUUID()}.jpg")
    return FileProvider.getUriForFile(
        context,
        "${BuildConfig.APPLICATION_ID}.fileprovider",
        captureFile,
    )
}