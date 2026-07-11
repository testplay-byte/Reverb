package app.reverb.ui.nativeui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.reverb.ReverbApp
import app.reverb.autolearn.LearnedSiteInterpreter
import app.reverb.autolearn.SiteAnalyzer
import app.reverb.core.common.ReverbLog
import app.reverb.data.LearnedSiteConfig
import app.reverb.source.api.MediaItem
import app.reverb.source.api.ResolvedStream
import app.reverb.source.api.VideoRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CatalogState(
    val loading: Boolean = true,
    val items: List<MediaItem> = emptyList(),
    val siteName: String? = null,
    val config: LearnedSiteConfig? = null,
    val error: String? = null,
    val statusMessage: String = "Analyzing site…",
    val isSearchResults: Boolean = false,
)

class NativeCatalogViewModel(
    private val app: ReverbApp,
    private val siteUrl: String,
) : ViewModel() {

    private val _state = MutableStateFlow(CatalogState())
    val state = _state.asStateFlow()

    private val interpreter = LearnedSiteInterpreter(app.httpClient)

    init { loadCatalog() }

    fun loadCatalog() {
        _state.value = CatalogState(loading = true, statusMessage = "Analyzing site…")
        viewModelScope.launch {
            try {
                val host = runCatching { java.net.URI(siteUrl).host }.getOrNull()
                val cached = host?.let { app.dataRepository.getLearnedSite(it) }

                val config = if (cached != null) {
                    ReverbLog.i("NativeCatalog", "Using cached config for $host")
                    _state.value = _state.value.copy(statusMessage = "Loading catalog…")
                    cached
                } else {
                    val llmClient = app.llmClient
                    if (!llmClient.isConfigured) {
                        _state.value = CatalogState(loading = false, error = "No LLM configured. Go to Settings → LLM.")
                        return@launch
                    }
                    _state.value = _state.value.copy(statusMessage = "Analyzing site with ${llmClient.name}…")
                    val analyzer = SiteAnalyzer(app.httpClient, llmClient, app.dataRepository)
                    val analyzed = withContext(Dispatchers.IO) { analyzer.analyzeSite(siteUrl) }
                    if (analyzed == null) {
                        _state.value = CatalogState(loading = false, error = "Site analysis failed. Try the WebView browser.")
                        return@launch
                    }
                    analyzed
                }

                _state.value = _state.value.copy(config = config, statusMessage = "Loading catalog…", siteName = config.name)
                val items = withContext(Dispatchers.IO) { interpreter.fetchCatalog(config) }
                _state.value = CatalogState(loading = false, items = items, siteName = config.name, config = config)
                ReverbLog.i("NativeCatalog", "Catalog loaded: ${items.size} items")
            } catch (e: Exception) {
                ReverbLog.e("NativeCatalog", "Failed: ${e.message}", e)
                _state.value = CatalogState(loading = false, error = e.message ?: "Unknown error")
            }
        }
    }

    fun search(query: String) {
        val config = _state.value.config ?: return
        _state.value = _state.value.copy(loading = true, statusMessage = "Searching for '$query'…")
        viewModelScope.launch {
            try {
                val results = withContext(Dispatchers.IO) { interpreter.search(config, query) }
                _state.value = _state.value.copy(loading = false, items = results, isSearchResults = true)
                ReverbLog.i("NativeCatalog", "Search results: ${results.size} items for '$query'")
            } catch (e: Exception) {
                ReverbLog.e("NativeCatalog", "Search failed: ${e.message}", e)
                _state.value = _state.value.copy(loading = false, error = "Search failed: ${e.message}")
            }
        }
    }
}

data class DetailsState(
    val loading: Boolean = true,
    val details: app.reverb.source.api.MediaDetails? = null,
)

class NativeDetailsViewModel(
    private val app: ReverbApp,
    private val item: MediaItem,
    private val config: LearnedSiteConfig?,
) : ViewModel() {

    private val _state = MutableStateFlow(DetailsState())
    val state = _state.asStateFlow()

    private val interpreter = LearnedSiteInterpreter(app.httpClient)

    init { loadDetails() }

    private fun loadDetails() {
        viewModelScope.launch {
            try {
                if (config == null) {
                    _state.value = DetailsState(loading = false)
                    return@launch
                }
                val details = withContext(Dispatchers.IO) { interpreter.fetchDetails(config, item) }
                _state.value = DetailsState(loading = false, details = details)
            } catch (e: Exception) {
                ReverbLog.e("NativeDetails", "Failed: ${e.message}", e)
                _state.value = DetailsState(loading = false)
            }
        }
    }
}

// ── Episode playback ViewModel ──────────────────────────────────────────────

data class EpisodePlayerState(
    val loading: Boolean = false,
    val stream: ResolvedStream? = null,
    val error: String? = null,
)

class EpisodePlayerViewModel(
    private val app: ReverbApp,
) : ViewModel() {

    private val _state = MutableStateFlow(EpisodePlayerState())
    val state = _state.asStateFlow()

    fun playEpisode(episode: VideoRef) {
        _state.value = EpisodePlayerState(loading = true)
        viewModelScope.launch {
            try {
                ReverbLog.i("EpisodePlayer", "Extracting video from ${episode.url}")
                val stream = withContext(Dispatchers.IO) {
                    app.universalSite.resolveVideo(episode)
                }
                _state.value = EpisodePlayerState(loading = false, stream = stream)

                // Auto-play the best quality.
                val bestQuality = stream.qualities.firstOrNull()
                if (bestQuality != null) {
                    ReverbLog.i("EpisodePlayer", "Playing: ${bestQuality.label} ${bestQuality.format}")
                    app.player.play(stream, bestQuality, title = episode.title)
                }
            } catch (e: Exception) {
                ReverbLog.e("EpisodePlayer", "Failed: ${e.message}", e)
                _state.value = EpisodePlayerState(loading = false, error = e.message)
            }
        }
    }
}
