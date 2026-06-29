package io.github.jiro.expensetracker.ui.cards

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jiro.expensetracker.R
import io.github.jiro.expensetracker.data.local.ImageProcessor
import io.github.jiro.expensetracker.data.local.MemberCardEntity
import io.github.jiro.expensetracker.data.repository.MemberCardRepository
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemberCardListScreen(
    onBack: () -> Unit,
    onAddCard: () -> Unit,
    onCardClick: (Long) -> Unit,
    viewModel: MemberCardListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Local mirror of the query so the text field stays responsive.
    // Re-sync when the VM's query is cleared externally (e.g. by the
    // "Clear search" affordance in the no-matches empty state).
    var searchInput by remember(state.query) { mutableStateOf(state.query) }

    // Hoist "start of today" so every card in the list doesn't recompute it
    // on every recomposition. Recomputed only when this composable enters
    // composition (or its parent restarts it).
    val startOfTodayMillis = remember {
        LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_cards)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddCard) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = stringResource(R.string.cards_add),
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            OutlinedTextField(
                value = searchInput,
                onValueChange = {
                    searchInput = it
                    viewModel.onQueryChange(it)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.cards_search_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = if (searchInput.isNotEmpty()) {
                    {
                        IconButton(onClick = {
                            searchInput = ""
                            viewModel.clearQuery()
                        }) {
                            Icon(
                                Icons.Filled.Clear,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    }
                } else null,
                singleLine = true,
            )

            when {
                state.isTrulyEmpty -> {
                    EmptyNoCards(
                        modifier = Modifier.fillMaxSize(),
                        onAddCard = onAddCard,
                    )
                }
                state.cards.isEmpty() -> {
                    EmptyNoMatches(
                        query = state.query,
                        onClearQuery = {
                            searchInput = ""
                            viewModel.clearQuery()
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.cards, key = { it.id }) { card ->
                            CardTile(
                                card = card,
                                repository = viewModel.repository,
                                startOfTodayMillis = startOfTodayMillis,
                                onClick = { onCardClick(card.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CardTile(
    card: MemberCardEntity,
    repository: MemberCardRepository,
    startOfTodayMillis: Long,
    onClick: () -> Unit,
) {
    val expired = card.expiresAtEpochMillis != null && card.expiresAtEpochMillis!! < startOfTodayMillis
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CardThumbnail(card = card, repository = repository)
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = card.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
            )
            if (card.memberIdText != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = card.memberIdText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (expired) {
            Spacer(Modifier.size(8.dp))
            ExpiredBadge()
        }
    }
}

@Composable
private fun CardThumbnail(card: MemberCardEntity, repository: MemberCardRepository) {
    val backdrop: Color = card.colorHex?.let { Color(it) }
        ?: MaterialTheme.colorScheme.surfaceVariant

    var bitmap by remember(card.imagePath) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(card.imagePath) {
        // Release the previous decode before starting a new one.
        bitmap?.takeIf { !it.isRecycled }?.recycle()
        bitmap = null
        bitmap = withContext(Dispatchers.IO) {
            val file = repository.absolutePath(card.imagePath) ?: return@withContext null
            runCatching { ImageProcessor.decodeSampledBitmap(file, maxEdge = 256) }
                .getOrNull()
        }
    }
    // Recycle when this tile leaves the composition entirely (e.g. filtered out).
    DisposableEffect(Unit) {
        onDispose {
            bitmap?.takeIf { !it.isRecycled }?.recycle()
        }
    }

    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(backdrop),
    ) {
        bitmap?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            return@Box
        }
        // No image loaded: show the emoji glyph centered (when present).
        if (!card.icon.isNullOrEmpty()) {
            Text(
                text = card.icon,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun ExpiredBadge() {
    Surface(
        color = MaterialTheme.colorScheme.error,
        contentColor = Color.White,
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(
            text = stringResource(R.string.cards_expired),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun EmptyNoCards(modifier: Modifier = Modifier, onAddCard: () -> Unit) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.cards_empty_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.cards_empty_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onAddCard) {
            Text(stringResource(R.string.cards_add))
        }
    }
}

@Composable
private fun EmptyNoMatches(
    query: String,
    onClearQuery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.cards_empty_search, query),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onClearQuery) {
            Text(stringResource(R.string.cards_clear_search))
        }
    }
}
