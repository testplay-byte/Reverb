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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
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
import androidx.compose.runtime.collectAsState
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
import app.reverb.data.DownloadStatus
import app.reverb.data.DownloadTask
import app.reverb.data.HistoryEntry
import app.reverb.ui.browser.WebsiteBrowser

/**
 * Browse screen — the main screen of Phase 2.
 *
 * Two modes:
 *   1. Home (default): address bar + bookmarks + recent history. User enters a URL → opens the WebsiteBrowser.
 *   2. Browser (when browsingUrl != null): the in-app WebView browser with ad-blocking + video detection.
 *
 * This is the core UX the user requested: "extract a web page and recreate it so
 * the user can click buttons and navigate, but the UI will be simplified and made good."
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BrowseScreen(
    app: ReverbApp,
    modifier: Modifier = Modifier,
) {
    var browsingUrl by remember { mutableStateOf<String?>(null) }

    // When the user enters a URL, switch to browser mode.
    val onNavigate: (String) -> Unit = { url ->
        browsingUrl = url
    }

    if (browsingUrl != null) {
        // Browser mode — show the actual website with ad-blocking + video detection.
        WebsiteBrowser(
            app = app,
            initialUrl = browsingUrl!!,
            onBack = { browsingUrl = null },
            onPlayStream = { stream, quality ->
                app.player.play(stream, quality, title = quality.label)
            },
            onDownloadStream = { stream, quality ->
                // Phase 2: create a download task in the queue.
                val url = browsingUrl ?: ""
                val task = DownloadTask(
                    id = java.util.UUID.randomUUID().toString(),
                    url = quality.url,
                    title = url,
                    quality = quality.label,
                    format = quality.format.name,
                    status = DownloadStatus.QUEUED,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                )
                app.dataRepository.updateDownloadTask(task)
            },
            modifier = modifier,
        )
    } else {
        // Home mode — address bar + bookmarks + recent.
        HomeContent(app, modifier, onNavigate)
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

    LazyColumn(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Hero.
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("Reverb", style = MaterialTheme.typography.displaySmall)
                Text(
                    "Enter a website to browse — Reverb will block ads and detect videos.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
