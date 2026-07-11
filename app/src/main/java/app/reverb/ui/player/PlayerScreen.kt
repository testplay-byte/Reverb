package app.reverb.ui.player

import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import app.reverb.ReverbApp
import app.reverb.core.common.ReverbLog
import app.reverb.data.DownloadStatus
import app.reverb.data.DownloadTask
import app.reverb.source.api.Quality
import app.reverb.source.api.ResolvedStream
import app.reverb.source.api.VideoRef
import app.reverb.ui.nativeui.EpisodePlayerViewModel
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    app: ReverbApp,
    episode: VideoRef,
    episodeList: List<VideoRef>,
    onBack: () -> Unit,
) {
    val viewModel = remember { EpisodePlayerViewModel(app) }
    val state by viewModel.state.collectAsState()

    LaunchedEffect(episode.url) {
        viewModel.playEpisode(episode)
    }

    val currentIdx = episodeList.indexOfFirst { it.url == episode.url }
    val hasPrev = currentIdx > 0
    val hasNext = currentIdx >= 0 && currentIdx < episodeList.size - 1

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(episode.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } },
                actions = {
                    IconButton(onClick = { if (hasPrev) viewModel.playEpisode(episodeList[currentIdx - 1]) }, enabled = hasPrev) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = "Previous episode")
                    }
                    IconButton(onClick = { if (hasNext) viewModel.playEpisode(episodeList[currentIdx + 1]) }, enabled = hasNext) {
                        Icon(Icons.Default.SkipNext, contentDescription = "Next episode")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentAlignment = Alignment.Center) {
                when {
                    state.loading -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Text("Extracting video stream…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
                        }
                    }
                    state.error != null -> {
                        Text("⚠ ${state.error}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                    state.stream != null -> {
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                                    player = app.player.exoPlayer
                                    useController = true
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            if (state.stream != null) {
                StreamInfoCard(stream = state.stream!!, onDownload = { quality ->
                    val task = DownloadTask(
                        id = UUID.randomUUID().toString(), url = quality.url, title = episode.title,
                        quality = quality.label, format = quality.format.name,
                        status = DownloadStatus.QUEUED,
                        createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(),
                    )
                    app.dataRepository.updateDownloadTask(task)
                    ReverbLog.i("PlayerScreen", "Download queued: ${episode.title} ${quality.label}")
                })
            }
        }
    }
}

@Composable
private fun StreamInfoCard(stream: ResolvedStream, onDownload: (Quality) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Stream info", style = MaterialTheme.typography.titleSmall)
            Text("${stream.qualities.size} qualities • via ${stream.extractorUsed}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            Row(modifier = Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                stream.qualities.firstOrNull()?.let { FilledTonalButton(onClick = { onDownload(it) }) { Icon(Icons.Default.Download, contentDescription = null); Text("Download ${it.label}") } }
            }
        }
    }
}
