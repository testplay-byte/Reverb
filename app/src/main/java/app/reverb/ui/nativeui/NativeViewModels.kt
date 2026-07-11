package app.reverb.ui.nativeui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.reverb.ReverbApp
import app.reverb.autolearn.LearnedSiteInterpreter
import app.reverb.autolearn.LlmClient
import app.reverb.autolearn.NoopLlmClient
import app.reverb.autolearn.SiteAnalyzer
import app.reverb.core.common.ReverbLog
import app.reverb.data.LearnedSiteConfig
import app.reverb.source.api.MediaItem
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
)

class NativeCatalogViewModel(
    private val app: ReverbApp,
    private val siteUrl: String,
) : ViewModel() {

    private val _state = MutableStateFlow(CatalogState())
    val state = _state.asStateFlow()

    private val interpreter = LearnedSiteInterpreter(app.httpClient)

    init {
        loadCatalog()
    }

    fun loadCatalog() {
        _state.value = CatalogState(loading = true, statusMessage = "Analyzing site…")
        viewModelScope.launch {
            try {
                // Step 1: Check if we already have a cached config.
                val host = java.net.URI(siteUrl).host
                val cached = host?.let { app.dataRepository.getLearnedSite(it) }

                val config = if (cached != null) {
                    ReverbLog.i("NativeCatalog", "Using cached config for $host")
                    _state.value = _state.value.copy(statusMessage = "Loading catalog…")
                    cached
                } else {
                    // Step 2: Analyze the site with the LLM.
                    val llmClient = app.llmClient
                    if (!llmClient.isConfigured) {
                        _state.value = CatalogState(
                            loading = false,
                            error = "No LLM configured. Go to Settings → LLM to set up Gemini or GLM for automatic site analysis.",
                        )
                        return@launch
                    }
                    _state.value = _state.value.copy(statusMessage = "Analyzing site with ${llmClient.name}…")
                    val analyzer = SiteAnalyzer(app.httpClient, llmClient, app.dataRepository)
                    val analyzed = withContext(Dispatchers.IO) { analyzer.analyzeSite(siteUrl) }
                    if (analyzed == null) {
                        _state.value = CatalogState(
                            loading = false,
                            error = "Site analysis failed. The LLM could not identify the page structure.",
                        )
                        return@launch
                    }
                    analyzed
                }

                // Step 3: Scrape the catalog using the config.
                _state.value = _state.value.copy(config = config, statusMessage = "Loading catalog…", siteName = config.name)
                val items = withContext(Dispatchers.IO) { interpreter.fetchCatalog(config) }
                _state.value = CatalogState(
                    loading = false,
                    items = items,
                    siteName = config.name,
                    config = config,
                )
                ReverbLog.i("NativeCatalog", "Catalog loaded: ${items.size} items")
            } catch (e: Exception) {
                ReverbLog.e("NativeCatalog", "Failed: ${e.message}", e)
                _state.value = CatalogState(loading = false, error = e.message ?: "Unknown error")
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

    init {
        loadDetails()
    }

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
