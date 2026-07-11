package app.reverb.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.reverb.ReverbApp
import app.reverb.core.common.ReverbLog
import app.reverb.source.api.Quality
import app.reverb.source.api.ResolvedStream
import app.reverb.source.api.StreamFormat
import app.reverb.source.api.VideoRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BrowserState(
    val currentUrl: String = "",
    val pageTitle: String? = null,
    val detectedStreams: List<DetectedVideo> = emptyList(),
    val detectedStreamsResolved: ResolvedStream? = null,
    val adBlocksCount: Int = 0,
    val resolving: Boolean = false,
)

data class DetectedVideo(
    val url: String,
    val format: StreamFormat,
)

class WebsiteBrowserViewModel(
    private val app: ReverbApp,
) : ViewModel() {

    private val _state = MutableStateFlow(BrowserState())
    val state: StateFlow<BrowserState> = _state.asStateFlow()

    private val seenUrls = mutableSetOf<String>()

    fun loadUrl(url: String) {
        ReverbLog.i("BrowserVM", "loadUrl: $url")
        _state.update { it.copy(currentUrl = url) }
    }

    fun onUrlChanged(url: String) {
        _state.update { it.copy(currentUrl = url) }
    }

    fun onPageFinished(url: String) {
        ReverbLog.i("BrowserVM", "Page finished: $url — ${_state.value.detectedStreams.size} streams detected")
        // Record this visit in history.
        app.dataRepository.addHistory(
            app.reverb.data.HistoryEntry(
                url = url,
                title = _state.value.pageTitle ?: url,
                visitedAt = System.currentTimeMillis(),
            )
        )
        // If streams were detected on this page, resolve them.
        if (_state.value.detectedStreams.isNotEmpty() && _state.value.detectedStreamsResolved == null) {
            resolveStreams()
        }
    }

    fun onTitleChanged(title: String) {
        _state.update { it.copy(pageTitle = title) }
    }

    fun onStreamDetected(url: String, format: StreamFormat) {
        if (seenUrls.add(url)) {
            ReverbLog.i("BrowserVM", "Stream detected: $format — $url")
            _state.update { it.copy(detectedStreams = it.detectedStreams + DetectedVideo(url, format)) }
        }
    }

    fun onAdBlocked() {
        _state.update { it.copy(adBlocksCount = it.adBlocksCount + 1) }
    }

    private fun resolveStreams() {
        val streams = _state.value.detectedStreams
        if (streams.isEmpty()) return

        _state.update { it.copy(resolving = true) }
        viewModelScope.launch {
            try {
                // Pick the best detected stream (prefer HLS > DASH > progressive).
                val best = streams.firstOrNull { it.format == StreamFormat.HLS }
                    ?: streams.firstOrNull { it.format == StreamFormat.DASH }
                    ?: streams.firstOrNull()
                    ?: return@launch

                ReverbLog.i("BrowserVM", "Resolving best stream: ${best.format} — ${best.url.take(80)}")

                // Use the universal extractor to resolve (it fetches the m3u8 body + parses qualities).
                val resolved = withContext(Dispatchers.IO) {
                    app.universalSite.resolveVideo(VideoRef(url = best.url, title = _state.value.pageTitle ?: best.url))
                }
                _state.update { it.copy(detectedStreamsResolved = resolved, resolving = false) }
                ReverbLog.i("BrowserVM", "Resolved: ${resolved.qualities.size} qualities via ${resolved.extractorUsed}")
            } catch (e: Exception) {
                ReverbLog.e("BrowserVM", "Resolve failed: ${e.message}", e)
                // Fallback: create a simple ResolvedStream from the raw URL.
                val best = streams.first()
                val resolved = ResolvedStream(
                    qualities = listOf(Quality(
                        label = best.format.name,
                        url = best.url,
                        format = best.format,
                    )),
                    subtitles = emptyList(),
                    headers = mapOf("Referer" to (_state.value.currentUrl)),
                    extractorUsed = "browser-detected",
                )
                _state.update { it.copy(detectedStreamsResolved = resolved, resolving = false) }
            }
        }
    }
}
