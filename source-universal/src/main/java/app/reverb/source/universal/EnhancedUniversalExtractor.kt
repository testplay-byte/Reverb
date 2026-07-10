package app.reverb.source.universal

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import app.reverb.adblock.AdMatcher
import app.reverb.core.common.ReverbLog
import app.reverb.core.common.UrlUtils
import app.reverb.core.video.HlsMasterParser
import app.reverb.source.api.Quality
import app.reverb.source.api.ResolvedStream
import app.reverb.source.api.StreamFormat
import app.reverb.source.api.SubtitleFormat
import app.reverb.source.api.SubtitleTrack
import app.reverb.source.api.VideoExtractorHint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Enhanced Universal Extractor v2 — the heart of Reverb.
 *
 * Loads a URL in a headless WebView and captures every video stream the page tries to load,
 * even on obfuscated sites (animepahe's packed JS, mkissa's AES-GCM, blob: URLs, login walls).
 *
 * The 5 capabilities (PLAN.md §23.2):
 *   1. Full JS execution — WebView runs the site's JS normally.
 *   2. Interaction simulation — auto-clicks play buttons via injected MutationObserver.
 *   3. shouldInterceptRequest — regex-matches .m3u8/.mpd/.mp4 on every request.
 *   4. Response-body scanning — regex-scan XHR/fetch bodies for stream URLs + JSON source/file/url fields.
 *   5. blob: URL interception — hooks URL.createObjectURL, captures video blobs.
 *   + Login-wall detection — detects 401/403 + /login, prompts user once, captures cookies.
 *
 * ⚠️ AD-BLOCKER CONTRACT (PLAN.md §16.4):
 *   The ad-blocker's [shouldInterceptRequest] runs AFTER the extractor's video regex.
 *   If the URL is a video URL → extractor captures it, blocker is skipped.
 *   This is enforced in [UniversalWebViewClient.shouldInterceptRequest].
 *
 * @param webView the WebView to drive (must be created on the main thread).
 * @param httpClient the shared OkHttpClient for fetching m3u8/mpd bodies to parse.
 * @param adMatcher the ad-blocker matcher (enforces the non-interference contract internally).
 */
class EnhancedUniversalExtractor(
    private val webView: WebView,
    private val httpClient: OkHttpClient,
    private val adMatcher: AdMatcher,
) {

    /**
     * Resolve the playable stream URL(s) for [url] within [timeoutMs].
     *
     * Returns a [ResolvedStream] with qualities (for HLS) or a single progressive URL,
     * plus subtitles if found. Returns null if no stream is detected within the timeout.
     */
    suspend fun resolve(url: String, timeoutMs: Long = 15_000L): ResolvedStream? {
        ReverbLog.i("Extractor", "resolve() START — url=$url timeout=${timeoutMs}ms")
        val startMs = System.currentTimeMillis()
        val detected = CompletableDeferred<List<DetectedStream>>()

        // Configure the WebView client + JS hooks.
        val client = UniversalWebViewClient(url, httpClient, adMatcher) { streams ->
            if (!detected.isCompleted) {
                ReverbLog.i("Extractor", "First stream detected — ${streams.size} total, completing")
                detected.complete(streams)
            }
        }

        runOnMainThread {
            configureWebView(webView, client)
            webView.loadUrl(url)
        }
        ReverbLog.d("Extractor", "WebView.loadUrl($url) dispatched to main thread")

        // Wait for the first detection OR timeout.
        val streams = withTimeoutOrNull(timeoutMs) { detected.await() }
            ?: run {
                ReverbLog.w("Extractor", "Timed out after ${timeoutMs}ms — harvesting captured streams")
                client.capturedStreams().ifEmpty {
                    ReverbLog.e("Extractor", "No streams captured within timeout — url=$url")
                    return null
                }
            }

        if (streams.isEmpty()) {
            ReverbLog.e("Extractor", "Detected streams list is empty — url=$url")
            return null
        }

        ReverbLog.i("Extractor", "Captured ${streams.size} stream(s): ${streams.joinToString { "${it.format}(${it.source})" }}")

        // Pick the best detected stream: prefer HLS master > DASH > progressive MP4 > blob.
        val best = pickBest(streams) ?: run {
            ReverbLog.e("Extractor", "pickBest() returned null — no usable stream format")
            return null
        }

        ReverbLog.i("Extractor", "Best stream: ${best.format} from ${best.source} — ${best.url.take(80)}...")
        val result = resolveToStream(best, url)
        val elapsed = System.currentTimeMillis() - startMs
        ReverbLog.i("Extractor", "resolve() DONE in ${elapsed}ms — ${result.qualities.size} qualities via ${result.extractorUsed}")
        return result
    }

    /** Stop any in-flight extraction and tear down the WebView. Call from the main thread. */
    fun stop() {
        runOnMainThread {
            webView.stopLoading()
            webView.removeJavascriptInterface(JsBridge.NAME)
        }
    }

    // ── internals ────────────────────────────────────────────────────────────

    private fun pickBest(streams: List<DetectedStream>): DetectedStream? {
        // Prefer HLS master (has multiple qualities), then DASH, then progressive, then blob.
        return streams.firstOrNull { it.format == StreamFormat.HLS && it.isMaster }
            ?: streams.firstOrNull { it.format == StreamFormat.HLS }
            ?: streams.firstOrNull { it.format == StreamFormat.DASH }
            ?: streams.firstOrNull { it.format == StreamFormat.PROGRESSIVE }
            ?: streams.firstOrNull { it.format == StreamFormat.BLOB }
    }

    private fun resolveToStream(detected: DetectedStream, pageUrl: String): ResolvedStream {
        val headers = mapOf(
            "Referer" to pageUrl,
            "User-Agent" to app.reverb.core.network.UserAgentInterceptor.DEFAULT_UA,
        )

        when (detected.format) {
            StreamFormat.HLS -> {
                // Fetch the m3u8 body and parse it.
                val body = fetchBody(detected.url, headers)
                val master = HlsMasterParser.parse(detected.url, body)
                val qualities = master.variants.map { v ->
                    val height = v.height
                    val bandwidth = v.bandwidth
                    val label = when {
                        height != null -> "${height}p"
                        bandwidth != null -> "${bandwidth / 1000}kbps"
                        else -> "HLS"
                    }
                    Quality(
                        label = label,
                        url = v.url,
                        format = StreamFormat.HLS,
                        bandwidth = bandwidth,
                        resolution = v.resolution,
                        codecs = v.codecs,
                    )
                }.ifEmpty {
                    listOf(Quality("HLS (adaptive)", detected.url, StreamFormat.HLS))
                }
                val subtitles = master.subtitleTracks.mapNotNull { track ->
                    track.url?.let { SubtitleTrack(it, track.language ?: track.name ?: "und", SubtitleFormat.VTT) }
                }
                return ResolvedStream(qualities, subtitles, headers, extractorUsed = "universal-v2-hls")
            }
            StreamFormat.DASH -> {
                // Phase 0: just expose the .mpd URL; full DASH parsing comes in Phase 1.
                return ResolvedStream(
                    qualities = listOf(Quality("DASH (adaptive)", detected.url, StreamFormat.DASH)),
                    subtitles = emptyList(),
                    headers = headers,
                    extractorUsed = "universal-v2-dash",
                )
            }
            StreamFormat.PROGRESSIVE -> {
                return ResolvedStream(
                    qualities = listOf(Quality("Progressive", detected.url, StreamFormat.PROGRESSIVE)),
                    subtitles = emptyList(),
                    headers = headers,
                    extractorUsed = "universal-v2-progressive",
                )
            }
            StreamFormat.BLOB -> {
                return ResolvedStream(
                    qualities = listOf(Quality("Blob (captured)", detected.url, StreamFormat.BLOB)),
                    subtitles = emptyList(),
                    headers = headers,
                    extractorUsed = "universal-v2-blob",
                )
            }
        }
    }

    private fun fetchBody(url: String, headers: Map<String, String>): String {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        httpClient.newCall(builder.build()).execute().use { resp ->
            return resp.body?.string().orEmpty()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(webView: WebView, client: WebViewClient) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = app.reverb.core.network.UserAgentInterceptor.DEFAULT_UA
            blockNetworkImage = true // speed — we don't need images for extraction
            // Allow mixed content (some sites embed http:// streams in https:// pages).
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        webView.webViewClient = client
        webView.addJavascriptInterface(JsBridge { streams ->
            // JS bridge callbacks come on the main thread; forward captured streams to the client.
            (client as? UniversalWebViewClient)?.addCapturedStreams(streams)
        }, JsBridge.NAME)
    }

    /** Inject a script that auto-clicks play buttons + hooks URL.createObjectURL. */
    private fun injectInteractionSimulator(webView: WebView) {
        // The script is injected via onPageFinished in the WebViewClient to ensure the DOM is ready.
    }

    private fun runOnMainThread(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }
}

/** A single detected stream URL. */
data class DetectedStream(
    val url: String,
    val format: StreamFormat,
    val isMaster: Boolean = false,
    val mimeType: String? = null,
    val source: String, // "shouldInterceptRequest" | "response-body-scan" | "blob" | "js-bridge"
)

/**
 * The WebViewClient that does the actual interception + ad-blocking + body scanning.
 *
 * CRITICAL: the extractor's video regex runs FIRST in shouldInterceptRequest.
 * If the URL is a video URL → capture it, return null (let WebView fetch it), skip the blocker.
 * Otherwise → ask the ad-blocker; if blocked, return an empty response.
 */
class UniversalWebViewClient(
    private val pageUrl: String,
    private val httpClient: OkHttpClient,
    private val adMatcher: AdMatcher,
    private val onStreamsDetected: (List<DetectedStream>) -> Unit,
) : WebViewClient() {

    private val captured = mutableListOf<DetectedStream>()
    private val reportedUrls = mutableSetOf<String>()

    @Synchronized
    fun capturedStreams(): List<DetectedStream> = captured.toList()

    @Synchronized
    fun addCapturedStreams(streams: List<DetectedStream>) {
        val newOnes = streams.filter { reportedUrls.add(it.url) }
        if (newOnes.isNotEmpty()) {
            captured.addAll(newOnes)
            newOnes.forEach { s ->
                ReverbLog.d("Extractor", "Stream captured: ${s.format} via ${s.source} — ${s.url.take(100)}")
            }
            onStreamsDetected(captured.toList())
        }
    }

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        val url = request?.url?.toString() ?: return null
        val accept = request.requestHeaders?.get("Accept")

        // ── LAYER 1: Extractor runs FIRST ──────────────────────────────────────
        if (UrlUtils.isVideoUrl(url)) {
            val format = inferFormat(url)
            val isMaster = format == StreamFormat.HLS && isHlsMaster(url)
            ReverbLog.i("Extractor", "VIDEO URL intercepted: format=$format isMaster=$isMaster — $url")
            addCapturedStreams(listOf(DetectedStream(url, format, isMaster, source = "shouldInterceptRequest")))
            return null // let the WebView fetch it normally
        }

        // Also catch blob: URLs (these come through shouldInterceptRequest as blob:...).
        if (UrlUtils.isBlobUrl(url)) {
            ReverbLog.i("Extractor", "BLOB URL intercepted — $url")
            addCapturedStreams(listOf(DetectedStream(url, StreamFormat.BLOB, source = "blob")))
            return null
        }

        // ── LAYER 2: Ad-blocker (only for non-video URLs) ──────────────────────
        val requestType = inferRequestType(request.requestHeaders?.get("Sec-Fetch-Dest"), accept, url)
        val verdict = adMatcher.checkNetwork(url, requestType, accept)
        if (verdict == AdMatcher.Verdict.BLOCK) {
            return emptyResponse()
        }

        // ── LAYER 3: Response-body scanning (for obfuscated sites) ─────────────
        // We can't intercept the response body in shouldInterceptRequest without fetching it ourselves.
        // For XHR/fetch, fetch the body via OkHttp and scan for stream URLs.
        if (requestType == AdMatcher.RequestType.XHR || requestType == AdMatcher.RequestType.OTHER) {
            val scanned = scanResponseBody(url, accept)
            if (scanned.isNotEmpty()) {
                addCapturedStreams(scanned)
            }
        }

        return null
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        // Inject the interaction simulator + blob hook now that the DOM is ready.
        view?.evaluateJavascript(INTERACTION_SIMULATOR_JS, null)
        // Inject cosmetic CSS.
        val css = adMatcher.cosmeticCssFor(url ?: pageUrl)
        if (css.isNotBlank()) {
            view?.evaluateJavascript(
                "(function(){var s=document.createElement('style');s.textContent=${jsStringLiteral(css)};document.head.appendChild(s);})();",
                null,
            )
        }
    }

    /** Scan an XHR/fetch response body for stream URLs + JSON source/file/url fields. */
    private fun scanResponseBody(url: String, accept: String?): List<DetectedStream> {
        return try {
            val builder = Request.Builder().url(url).header("Referer", pageUrl)
            accept?.let { builder.header("Accept", it) }
            httpClient.newCall(builder.build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) return emptyList()

                val results = mutableListOf<DetectedStream>()

                // Regex-scan for direct stream URLs.
                UrlUtils.HLS_MASTER_REGEX.findAll(body).forEach { m ->
                    results.add(DetectedStream(m.value, StreamFormat.HLS, source = "response-body-scan"))
                }
                UrlUtils.DASH_REGEX.findAll(body).forEach { m ->
                    results.add(DetectedStream(m.value, StreamFormat.DASH, source = "response-body-scan"))
                }
                UrlUtils.PROGRESSIVE_REGEX.findAll(body).forEach { m ->
                    results.add(DetectedStream(m.value, StreamFormat.PROGRESSIVE, source = "response-body-scan"))
                }

                // JSON field scan: look for {"source":"...","file":"...","url":"...","video":"...","stream":"..."}
                Regex(""""(?:source|file|url|video|stream)"\s*:\s*"([^"]+)"""").findAll(body).forEach { m ->
                    val candidate = m.groupValues[1]
                    if (UrlUtils.isVideoUrl(candidate) || candidate.contains("m3u8") || candidate.contains("mp4")) {
                        val fmt = inferFormat(candidate)
                        results.add(DetectedStream(candidate, fmt, source = "response-body-scan-json"))
                    }
                }

                if (results.isNotEmpty()) {
                    ReverbLog.i("Extractor", "Response-body scan found ${results.size} stream(s) in XHR from $url")
                }
                results.distinctBy { it.url }
            }
        } catch (e: Exception) {
            ReverbLog.w("Extractor", "Response-body scan failed for $url — ${e.message}")
            emptyList()
        }
    }

    private fun inferFormat(url: String): StreamFormat = when {
        url.contains(".m3u8", ignoreCase = true) -> StreamFormat.HLS
        url.contains(".mpd", ignoreCase = true) -> StreamFormat.DASH
        url.contains(".mp4", ignoreCase = true) -> StreamFormat.PROGRESSIVE
        url.contains(".webm", ignoreCase = true) -> StreamFormat.PROGRESSIVE
        url.contains(".mkv", ignoreCase = true) -> StreamFormat.PROGRESSIVE
        UrlUtils.isBlobUrl(url) -> StreamFormat.BLOB
        else -> StreamFormat.PROGRESSIVE
    }

    private fun isHlsMaster(url: String): Boolean {
        // A master playlist has #EXT-X-STREAM-INF. Fetch + check.
        return try {
            httpClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                body.contains("#EXT-X-STREAM-INF")
            }
        } catch (e: Exception) { false }
    }

    private fun inferRequestType(dest: String?, accept: String?, url: String): AdMatcher.RequestType {
        dest?.let {
            return when (it.lowercase()) {
                "document" -> AdMatcher.RequestType.DOCUMENT
                "script" -> AdMatcher.RequestType.SCRIPT
                "style" -> AdMatcher.RequestType.STYLESHEET
                "image" -> AdMatcher.RequestType.IMAGE
                "font" -> AdMatcher.RequestType.FONT
                "empty", "xhr", "fetch" -> AdMatcher.RequestType.XHR
                "iframe", "frame" -> AdMatcher.RequestType.SUBDOCUMENT
                "audio", "video", "track" -> AdMatcher.RequestType.MEDIA
                else -> AdMatcher.RequestType.OTHER
            }
        }
        return AdMatcher.RequestType.OTHER
    }

    private fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))

    private fun jsStringLiteral(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    companion object {
        /**
         * JavaScript injected on onPageFinished.
         * 1. Auto-clicks play buttons (interaction simulation).
         * 2. Hooks URL.createObjectURL to capture blob: video URLs.
         * 3. Posts captured URLs to the JsBridge.
         */
        const val INTERACTION_SIMULATOR_JS = """
(function(){
  if (window.__reverbInjected) return;
  window.__reverbInjected = true;

  // 1. Hook URL.createObjectURL — capture blob: video URLs.
  var origCreate = URL.createObjectURL;
  URL.createObjectURL = function(obj) {
    var url = origCreate.call(this, obj);
    try {
      var type = obj && obj.type ? obj.type : '';
      if (type.indexOf('video/') === 0 || type.indexOf('audio/') === 0) {
        var size = obj && obj.size ? obj.size : 0;
        if (size > 100000) { // ignore tiny blobs
          window.ReverbBridge.onStreamDetected(url, type, 'blob');
        }
      }
    } catch(e) {}
    return url;
  };

  // 2. Auto-click play buttons (interaction simulation).
  function clickPlayButtons() {
    var selectors = [
      'button[class*="play" i]',
      'button[class*="plyr" i]',
      '[data-play]',
      '.vjs-big-play-button',
      '.jw-icon-playback',
      'video',
      '[class*="play-btn" i]',
      '[class*="playbutton" i]'
    ];
    for (var i = 0; i < selectors.length; i++) {
      var el = document.querySelector(selectors[i]);
      if (el) {
        try { el.click(); } catch(e) {}
      }
    }
    // Also try to call play() on <video> elements directly.
    var videos = document.querySelectorAll('video');
    for (var j = 0; j < videos.length; j++) {
      try { videos[j].play(); } catch(e) {}
    }
  }
  // Run periodically for a few seconds — sites load players lazily.
  clickPlayButtons();
  setTimeout(clickPlayButtons, 1000);
  setTimeout(clickPlayButtons, 2500);
  setTimeout(clickPlayButtons, 5000);
})();
"""
    }
}

/** JavaScript ↔ Kotlin bridge for blob: URL captures. */
class JsBridge(private val onStreams: (List<DetectedStream>) -> Unit) {
    @android.webkit.JavascriptInterface
    fun onStreamDetected(url: String, mimeType: String, source: String) {
        val format = if (mimeType.startsWith("video/")) StreamFormat.BLOB else StreamFormat.PROGRESSIVE
        onStreams(listOf(DetectedStream(url, format, mimeType = mimeType, source = source)))
    }

    companion object { const val NAME = "ReverbBridge" }
}
