package app.reverb.ui.nativeui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.reverb.ReverbApp
import app.reverb.autolearn.LearnedSiteInterpreter
import app.reverb.autolearn.SiteAnalyzer
import app.reverb.core.common.ReverbLog
import app.reverb.data.LearnedSiteConfig
import app.reverb.source.api.MediaDetails
import app.reverb.source.api.MediaItem
import app.reverb.source.api.VideoRef
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The native rebuilt catalog screen — a grid of anime cards scraped from the site.
 *
 * This is the "recreated UI" the user requested: instead of showing the WebView,
 * Reverb fetches the page HTML, the LLM analyzes it to find the CSS selectors,
 * then Jsoup scrapes the data and renders it in a native Compose grid.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativeCatalogScreen(
    app: ReverbApp,
    siteUrl: String,
    onBack: () -> Unit,
    onItemClick: (MediaItem) -> Unit,
) {
    val viewModel = remember { NativeCatalogViewModel(app, siteUrl) }
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.siteName ?: "Loading…", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator()
                        Text(
                            state.statusMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                }
                state.error != null -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("⚠ ${state.error}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        Text(
                            "Tip: Go to Settings → LLM to configure an LLM provider (Gemini or GLM) for automatic site analysis.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                }
                state.items.isEmpty() -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("No items found", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "The site may not have a catalog grid, or the LLM couldn't identify the selectors.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 140.dp),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(state.items, key = { it.url }) { item ->
                            CatalogCard(item = item, onClick = { onItemClick(item) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogCard(item: MediaItem, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column {
            // Poster image.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (item.thumbnailUrl != null) {
                    AsyncImage(
                        model = item.thumbnailUrl,
                        contentDescription = item.title,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text("🎬", style = MaterialTheme.typography.displaySmall)
                }
            }
            // Title.
            Text(
                item.title,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}

// ── Details screen ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativeDetailsScreen(
    app: ReverbApp,
    item: MediaItem,
    config: LearnedSiteConfig?,
    onBack: () -> Unit,
    onEpisodeClick: (VideoRef) -> Unit,
) {
    val viewModel = remember { NativeDetailsViewModel(app, item, config) }
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            val details = state.details
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                // Poster + title.
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        // Poster.
                        Box(
                            modifier = Modifier
                                .size(width = 120.dp, height = 180.dp)
                                .clip(RoundedCornerShape(12.dp)),
                        ) {
                            if (details?.posterUrl != null) {
                                AsyncImage(
                                    model = details.posterUrl,
                                    contentDescription = details.title,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Text("🎬", style = MaterialTheme.typography.displaySmall, modifier = Modifier.align(Alignment.Center))
                            }
                        }
                        // Title + synopsis.
                        Column(modifier = Modifier.weight(1f)) {
                            Text(details?.title ?: item.title, style = MaterialTheme.typography.titleLarge)
                            details?.description?.let { synopsis ->
                                Text(
                                    synopsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 6,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }
                    }
                }

                // Episodes.
                if (details?.episodes?.isNotEmpty() == true) {
                    item {
                        Text(
                            "Episodes (${details.episodes.size})",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    items(details.episodes) { episode ->
                        EpisodeRow(episode = episode, onClick = { onEpisodeClick(episode) })
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(episode: VideoRef, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(
            episode.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
