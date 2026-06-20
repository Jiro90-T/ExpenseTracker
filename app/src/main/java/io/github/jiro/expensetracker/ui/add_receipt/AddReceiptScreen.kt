package io.github.jiro.expensetracker.ui.add_receipt

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.jiro.expensetracker.R
import io.github.jiro.expensetracker.domain.model.TransactionType
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddReceiptScreen(
    onBack: () -> Unit,
    viewModel: AddReceiptViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var cameraDenied by remember { mutableStateOf(false) }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        val uri = pendingCameraUri
        pendingCameraUri = null
        if (success && uri != null) {
            viewModel.onPhotoCaptured(uri)
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            cameraDenied = false
            pendingCameraUri = createCameraCaptureUri(context)
        } else {
            cameraDenied = true
        }
    }

    LaunchedEffect(pendingCameraUri) {
        val uri = pendingCameraUri ?: return@LaunchedEffect
        cameraLauncher.launch(uri)
    }

    LaunchedEffect(state.saveComplete) {
        if (state.saveComplete) onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.add_receipt_title)) })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            when (state.mode) {
                AddReceiptMode.Idle -> IdleView(
                    cameraDenied = cameraDenied,
                    onTakePhoto = {
                        if (ContextCompat.checkSelfPermission(
                                context, android.Manifest.permission.CAMERA,
                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            pendingCameraUri = createCameraCaptureUri(context)
                        } else {
                            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                        }
                    },
                )
                AddReceiptMode.OcrInProgress -> OcrProgressView()
                AddReceiptMode.Review -> ReviewForm(
                    state = state,
                    onTitleChange = viewModel::onTitleChange,
                    onAmountChange = viewModel::onAmountChange,
                    onDateChange = viewModel::onDateChange,
                    onTypeChange = viewModel::onTypeChange,
                    onCategoryChange = viewModel::onCategoryChange,
                    onCurrencyChange = viewModel::onCurrencyChange,
                    onNoteChange = viewModel::onNoteChange,
                    onSave = viewModel::onSave,
                    onCancel = onBack,
                )
            }
        }
    }
}

@Composable
private fun IdleView(
    cameraDenied: Boolean,
    onTakePhoto: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.PhotoCamera,
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.add_receipt_idle_prompt),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onTakePhoto,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.add_receipt_take_photo))
        }
        if (cameraDenied) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.add_receipt_camera_denied),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun OcrProgressView() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.add_receipt_ocr_in_progress))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewForm(
    state: AddReceiptUiState,
    onTitleChange: (String) -> Unit,
    onAmountChange: (String) -> Unit,
    onDateChange: (Long) -> Unit,
    onTypeChange: (TransactionType) -> Unit,
    onCategoryChange: (Long) -> Unit,
    onCurrencyChange: (String) -> Unit,
    onNoteChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll),
    ) {
        Text(
            text = stringResource(R.string.add_receipt_review_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = state.title,
            onValueChange = onTitleChange,
            label = { Text("Title") },
            isError = state.error == AddReceiptError.TITLE_REQUIRED,
            supportingText = if (state.error == AddReceiptError.TITLE_REQUIRED) {
                { Text(stringResource(R.string.add_receipt_error_title_required)) }
            } else null,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = state.amountInput,
            onValueChange = onAmountChange,
            label = { Text("Amount") },
            isError = state.error == AddReceiptError.AMOUNT_INVALID,
            supportingText = if (state.error == AddReceiptError.AMOUNT_INVALID) {
                { Text(stringResource(R.string.add_receipt_error_amount_invalid)) }
            } else null,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        // Date: display only for MVP. (Could be a DatePicker later.)
        Text(
            text = "Date: ${DateFormat.getDateInstance().format(Date(state.occurredAtEpochMillis))}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))

        // Type dropdown
        var typeExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = typeExpanded,
            onExpandedChange = { typeExpanded = it },
        ) {
            OutlinedTextField(
                value = state.type.name,
                onValueChange = {},
                readOnly = true,
                label = { Text("Type") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth(),
            )
            ExposedDropdownMenu(
                expanded = typeExpanded,
                onDismissRequest = { typeExpanded = false },
            ) {
                TransactionType.entries.forEach { t ->
                    DropdownMenuItem(
                        text = { Text(t.name) },
                        onClick = {
                            onTypeChange(t)
                            typeExpanded = false
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        // Category dropdown
        var catExpanded by remember { mutableStateOf(false) }
        val selectedCat = state.categoriesForType.firstOrNull { it.id == state.selectedCategoryId }
        ExposedDropdownMenuBox(
            expanded = catExpanded,
            onExpandedChange = { catExpanded = it },
        ) {
            OutlinedTextField(
                value = selectedCat?.name.orEmpty(),
                onValueChange = {},
                readOnly = true,
                label = { Text("Category") },
                isError = state.error == AddReceiptError.CATEGORY_REQUIRED,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = catExpanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth(),
            )
            ExposedDropdownMenu(
                expanded = catExpanded,
                onDismissRequest = { catExpanded = false },
            ) {
                state.categoriesForType.forEach { c ->
                    DropdownMenuItem(
                        text = { Text(c.name) },
                        onClick = {
                            onCategoryChange(c.id)
                            catExpanded = false
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        // Currency text field (free-form to match AddEditTransaction)
        OutlinedTextField(
            value = state.currency,
            onValueChange = onCurrencyChange,
            label = { Text("Currency") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = state.note,
            onValueChange = onNoteChange,
            label = { Text("Note") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onSave,
            enabled = !state.isSaving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.isSaving) stringResource(R.string.add_receipt_saving) else stringResource(R.string.add_receipt_save))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.add_receipt_cancel))
        }
    }
}

private fun createCameraCaptureUri(context: Context): Uri {
    val captureDir = File(context.filesDir, "receipts/.capture").apply { mkdirs() }
    val captureFile = File(captureDir, "${UUID.randomUUID()}.jpg")
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        captureFile,
    )
}