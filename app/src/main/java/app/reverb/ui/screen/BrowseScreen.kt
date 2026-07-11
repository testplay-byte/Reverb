package app.reverb.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.reverb.ReverbApp
import app.reverb.data.HistoryEntry
import app.reverb.source.api.MediaItem
import app.reverb.ui.nativeui.NativeCatalogScreen
import app.reverb.ui.nativeui.NativeDetailsScreen

/**
 * Browse screen — Phase 2.
 *
 * Two modes:
 *   1. Home (default): address bar + quick sites + recent history.
 *   2. Native catalog: when the user enters a URL, the LLM analyzes the site and
 *      renders a NATIVE Compose UI grid (not a WebView).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BrowseScreen(
    app: ReverbApp,
    modifier: Modifier = Modifier,
) {
    var nativeCatalogUrl by remember { mutableStateOf<String?>(null) }
    var nativeDetailItem by remember { mutableStateOf<MediaItem?>(null) }

    val onNavigate: (String) -> Unit = { url ->
        nativeCatalogUrl = url
    }

    when {
        // Details screen (when an item is tapped in the catalog).
        nativeDetailItem != null && nativeCatalogUrl != null -> {
            val config = app.dataRepository.getLearnedSite(
                java.net.URI(nativeCatalogUrl!!).host ?: ""
            )
            NativeDetailsScreen(
                app = app,
                item = nativeDetailItem!!,
                config = config,
                onBack = { nativeDetailItem = null },
                onEpisodeClick = { episode ->
                    // Open the browser for the episode page (video detection + playback).
                    nativeDetailItem = null
                    nativeCatalogUrl = episode.url
                },
            )
        }
        // Native catalog screen (when a URL is entered).
        nativeCatalogUrl != null -> {
            NativeCatalogScreen(
                app = app,
                siteUrl = nativeCatalogUrl!!,
                onBack = { nativeCatalogUrl = null },
                onItemClick = { item -> nativeDetailItem = item },
            )
        }
        // Home screen.
        else -> {
            HomeContent(app, modifier, onNavigate)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun HomeContent(
    app: ReverbApp,
    modifier: Modifier,
    onNavigate: (String) -> Unit,
) {
    var urlInput by remember { mutableStateOf("") }
    val history = remember { app.dataRepository.getHistory() }
    val llmConfigured = app.llmClient.isConfigured

    LazyColumn(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Hero.
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("Reverb", style = MaterialTheme.typography.displaySmall)
                Text(
                    "Enter a website to rebuild it as a native app UI.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // LLM status warning.
        if (!llmConfigured) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "⚠ No LLM configured",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            "Go to Settings → LLM to add your Google Gemini API key (free) or GLM endpoint. The LLM analyzes each site's HTML and generates the selectors for the native UI.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }

        // Address bar.
        item {
            OutlinedTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Enter a URL or search…") },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    if (urlInput.isNotBlank()) {
                        onNavigate(normalizeUrl(urlInput))
                    }
                }),
                trailingIcon = {
                    if (urlInput.isNotBlank()) {
                        IconButton(onClick = { onNavigate(normalizeUrl(urlInput)) }) {
                            Icon(Icons.Default.Explore, contentDescription = "Browse")
                        }
                    }
                },
            )
        }

        // Quick sites.
        item {
            Text("Quick sites", style = MaterialTheme.typography.titleMedium)
        }
        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    "https://anidb.app/home",
                    "https://anizone.to/",
                    "https://anikototv.to/home",
                    "https://animepahe.pw/",
                    "https://www.miruro.to/",
                    "https://donghuastream.org/",
                ).forEach { site ->
                    Surface(
                        onClick = { onNavigate(site) },
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Text(
                            site.removePrefix("https://").removePrefix("www.").removeSuffix("/"),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }

        // Recent history.
        if (history.isNotEmpty()) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Icon(Icons.Default.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Recent", style = MaterialTheme.typography.titleMedium)
                }
            }
            items(history.take(15)) { entry ->
                HistoryCard(title = entry.title, url = entry.url, onClick = {
                    onNavigate(entry.url)
                })
            }
        }
    }
}

@Composable
private fun HistoryCard(title: String, url: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun normalizeUrl(input: String): String {
    if (input.startsWith("http://") || input.startsWith("https://")) return input
    if (input.contains('.') && !input.contains(' ')) return "https://$input"
    return "https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(input, "UTF-8")
}
