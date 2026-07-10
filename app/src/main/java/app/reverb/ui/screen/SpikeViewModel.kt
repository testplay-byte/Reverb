package app.reverb.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.reverb.ReverbApp
import app.reverb.source.api.ResolvedStream
import app.reverb.source.api.VideoRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SpikeState(
    val urlInput: String = "",
    val extracting: Boolean = false,
    val stream: ResolvedStream? = null,
    val error: String? = null,
    val adBlocksCount: Int = 0,
)

class SpikeViewModel(
    private val context: Context,
    private val universalSite: app.reverb.source.universal.UniversalSite,
) : ViewModel() {

    private val _state = MutableStateFlow(SpikeState())
    val state: StateFlow<SpikeState> = _state.asStateFlow()

    fun onUrlChange(url: String) {
        _state.update { it.copy(urlInput = url, error = null) }
    }

    fun extract() {
        val raw = _state.value.urlInput.trim()
        if (raw.isBlank()) return
        val url = normalizeUrl(raw)
        _state.update { it.copy(extracting = true, stream = null, error = null, urlInput = url) }

        viewModelScope.launch {
            try {
                val stream = universalSite.resolveVideo(VideoRef(url = url, title = url))
                _state.update { it.copy(extracting = false, stream = stream) }
            } catch (e: Exception) {
                _state.update { it.copy(extracting = false, error = e.message ?: "Unknown error") }
            }
        }
    }

    fun copyUrl(url: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Reverb stream URL", url))
    }

    fun openInBrowser(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun normalizeUrl(input: String): String {
        if (input.startsWith("http://") || input.startsWith("https://")) return input
        if (input.contains('.') && !input.contains(' ')) return "https://$input"
        return "https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(input, "UTF-8")
    }

    companion object {
        fun create(app: ReverbApp): SpikeViewModel =
            SpikeViewModel(app, app.universalSite)
    }
}
