package app.reverb.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.reverb.ReverbApp
import app.reverb.core.common.ReverbLog
import app.reverb.data.HistoryEntry
import app.reverb.data.DownloadTask
import app.reverb.data.DownloadStatus
import app.reverb.source.api.Quality
import app.reverb.source.api.ResolvedStream
import app.reverb.source.api.VideoRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class BrowseState(
    val urlInput: String = "",
    val extracting: Boolean = false,
    val stream: ResolvedStream? = null,
    val error: String? = null,
    val playing: Boolean = false,
    val selectedQuality: Quality? = null,
    val history: List<HistoryEntry> = emptyList(),
)

class BrowseViewModel(private val app: ReverbApp) : ViewModel() {

    private val _state = MutableStateFlow(BrowseState(history = app.dataRepository.getHistory()))
    val state: StateFlow<BrowseState> = _state.asStateFlow()

    fun onUrlChange(url: String) {
        _state.update { it.copy(urlInput = url, error = null) }
    }

    fun extract() {
        val raw = _state.value.urlInput.trim()
        if (raw.isBlank()) return
        val url = normalizeUrl(raw)
        ReverbLog.i("Browse", "Extracting: $url")
        _state.update { it.copy(extracting = true, stream = null, error = null, urlInput = url, playing = false, selectedQuality = null) }

        viewModelScope.launch {
            try {
                // Run extraction on IO dispatcher — the WebView creation + JS execution
                // should not block the main thread. The extractor internally posts WebView
                // operations to the main thread via Handler.
                val stream = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    app.universalSite.resolveVideo(VideoRef(url = url, title = url))
                }
                _state.update { it.copy(extracting = false, stream = stream) }
                // Add to history.
                app.dataRepository.addHistory(HistoryEntry(
                    url = url, title = url, visitedAt = System.currentTimeMillis()
                ))
                _state.update { it.copy(history = app.dataRepository.getHistory()) }
            } catch (e: Exception) {
                ReverbLog.e("Browse", "Extraction failed: ${e.message}", e)
                _state.update { it.copy(extracting = false, error = e.message ?: "Unknown error") }
            }
        }
    }

    fun play(quality: Quality) {
        val stream = _state.value.stream ?: return
        ReverbLog.i("Browse", "Playing: ${quality.label} ${quality.format}")
        try {
            app.player.play(stream, quality, title = quality.label)
            _state.update { it.copy(playing = true, selectedQuality = quality) }
        } catch (e: Exception) {
            ReverbLog.e("Browse", "Playback failed: ${e.message}", e)
            _state.update { it.copy(error = "Playback failed: ${e.message}") }
        }
    }

    fun download(quality: Quality) {
        val stream = _state.value.stream ?: return
        val url = _state.value.urlInput
        ReverbLog.i("Browse", "Download requested: ${quality.label} ${quality.format} from $url")
        // Phase 1: create a download task in the queue. Actual download execution comes in Phase 2.
        val task = DownloadTask(
            id = UUID.randomUUID().toString(),
            url = quality.url,
            title = url,
            quality = quality.label,
            format = quality.format.name,
            status = DownloadStatus.QUEUED,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        app.dataRepository.updateDownloadTask(task)
        ReverbLog.i("Browse", "Download queued: ${task.id}")
    }

    fun copyUrl(url: String) {
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Reverb stream URL", url))
    }

    private fun normalizeUrl(input: String): String {
        if (input.startsWith("http://") || input.startsWith("https://")) return input
        if (input.contains('.') && !input.contains(' ')) return "https://$input"
        return "https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(input, "UTF-8")
    }
}
