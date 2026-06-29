package io.github.jiro.expensetracker.ui.cards

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jiro.expensetracker.R
import io.github.jiro.expensetracker.data.local.MemberCardEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemberCardDetailScreen(
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    viewModel: MemberCardDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val memberIdCopiedMessage = stringResource(R.string.cards_member_id_copied)
    var menuOpen by remember { mutableStateOf(false) }
    var showFullImage by remember { mutableStateOf(false) }

    // Refresh when returning from Edit (ON_RESUME fires every time the
    // screen comes back to the foreground, including after Edit pops).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refresh()
    }

    // One-shot side effects: pop on delete, surface DB errors as a snackbar.
    LaunchedEffect(state.deleted) {
        if (state.deleted) onBack()
    }
    LaunchedEffect(state.errorMessage) {
        val msg = state.errorMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.onErrorShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.card?.name.orEmpty()) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_edit)) },
                            leadingIcon = {
                                Icon(Icons.Filled.Edit, contentDescription = null)
                            },
                            enabled = state.card != null,
                            onClick = {
                                menuOpen = false
                                state.card?.id?.let(onEdit)
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.cards_delete),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            enabled = state.card != null,
                            onClick = {
                                menuOpen = false
                                viewModel.onDeleteClick()
                            },
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(it) } },
    ) { padding ->
        val card = state.card
        when {
            card != null -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    HeroImage(
                        card = card,
                        repository = viewModel.repository,
                        onTap = { showFullImage = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                    DetailRows(
                        card = card,
                        onCopyMemberId = { id ->
                            clipboardManager.setText(AnnotatedString(id))
                            snackbarScope.launch {
                                snackbarHostState.showSnackbar(memberIdCopiedMessage)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    )
                }
            }
            // Loading and notFound both render an empty body; the title is
            // empty too. There's no separate error UI here — the spec keeps
            // the Detail screen simple and lets the back arrow exit.
            else -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                )
            }
        }
    }

    if (state.showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = viewModel::onDeleteDismiss,
            title = { Text(stringResource(R.string.cards_delete)) },
            text = {
                Text(stringResource(R.string.cards_delete_confirm, state.card?.name.orEmpty()))
            },
            confirmButton = {
                TextButton(onClick = viewModel::onDeleteConfirm) {
                    Text(
                        stringResource(R.string.cards_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::onDeleteDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showFullImage) {
        val fullCard = state.card
        if (fullCard != null) {
            Dialog(onDismissRequest = { showFullImage = false }) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .clickable { showFullImage = false },
                    contentAlignment = Alignment.Center,
                ) {
                    MemberCardImage(
                        relativePath = fullCard.imagePath,
                        repository = viewModel.repository,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroImage(
    card: MemberCardEntity,
    repository: io.github.jiro.expensetracker.data.repository.MemberCardRepository,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MemberCardImage(
        relativePath = card.imagePath,
        repository = repository,
        modifier = modifier.clickable(onClick = onTap),
        contentScale = ContentScale.Fit,
    )
}

@Composable
private fun DetailRows(
    card: MemberCardEntity,
    onCopyMemberId: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dateFormatter = remember { DateTimeFormatter.ofPattern("MMM d, yyyy") }
    val today = remember {
        LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        card.memberIdText?.takeIf { it.isNotBlank() }?.let { memberId ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LabelColumn(stringResource(R.string.cards_field_member_id))
                Spacer(Modifier.size(12.dp))
                Text(
                    text = memberId,
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onCopyMemberId(memberId) }) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.cards_action_copy),
                    )
                }
            }
        }
        card.expiresAtEpochMillis?.let { epochMillis ->
            val date = LocalDate.ofInstant(
                Instant.ofEpochMilli(epochMillis),
                ZoneId.systemDefault(),
            )
            val expired = epochMillis < today
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LabelColumn(stringResource(R.string.cards_field_expiry))
                Spacer(Modifier.size(12.dp))
                Text(
                    text = buildString {
                        append(date.format(dateFormatter))
                        if (expired) {
                            append(" (")
                            append(stringResource(R.string.cards_expired))
                            append(")")
                        }
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        card.colorHex?.let { argb ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LabelColumn(stringResource(R.string.cards_field_color))
                Spacer(Modifier.size(12.dp))
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .background(Color(argb), RoundedCornerShape(2.dp)),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "#%08X".format(argb),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        card.icon?.takeIf { it.isNotBlank() }?.let { icon ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LabelColumn(stringResource(R.string.cards_field_icon))
                Spacer(Modifier.size(12.dp))
                Text(
                    text = icon,
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = stringResource(R.string.cards_field_icon_value, icon),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        card.notes?.takeIf { it.isNotBlank() }?.let { notes ->
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.cards_field_notes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = notes,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Composable
private fun LabelColumn(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.width(110.dp),
    )
}