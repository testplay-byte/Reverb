package app.reverb.ui.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.reverb.ReverbApp
import app.reverb.adblock.AdMatcher
import app.reverb.core.common.ReverbLog
import app.reverb.core.common.UrlUtils
import app.reverb.core.network.UserAgentInterceptor
import app.reverb.source.api.Quality
import app.reverb.source.api.ResolvedStream
import app.reverb.source.api.StreamFormat
import kotlinx.coroutines.launch

/**
 * The in-app website browser — shows the actual webpage (with ad-blocking + cosmetic CSS)
 * so the user can navigate: home → anime details → episode page.
 *
 * When the user lands on a page that has video, a "Detected streams" FAB appears.
 * Tapping it opens a bottom sheet with the detected stream qualities, and the user
 * can play or download.
 *
 * This is the core UX the user requested: "extract a web page and recreate it so
 * the user can click buttons and navigate, but the UI will be simplified and made good."
 *
 * The WebView runs the site's real JS + renders its real HTML (with ads blocked),
 * so navigation works exactly as on the site. The "rebuilt native UI" (Phase 2 LLM
 * analyzer) is an enhancement on top of this, not a replacement.
 *
 * Reference: PLAN.md §1.1 + §23 (the user's core request).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebsiteBrowser(
    app: ReverbApp,
    initialUrl: String,
    onBack: () -> Unit,
    onPlayStream: (ResolvedStream, Quality) -> Unit,
    onDownloadStream: (ResolvedStream, Quality) -> Unit,
    modifier: Modifier = Modifier,
) {
    val browserViewModel = remember { WebsiteBrowserViewModel(app) }
    val state by browserViewModel.state.collectAsState()

    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableStateOf(0) }
    var showStreamsSheet by remember { mutableStateOf(false) }

    LaunchedEffect(initialUrl) {
        if (state.currentUrl != initialUrl) {
            browserViewModel.loadUrl(initialUrl)
        }
    }

    androidx.compose.material3.Scaffold(
        modifier = modifier,
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = {
                    Text(
                        text = state.pageTitle ?: state.currentUrl ?: "Loading…",
                        style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Close, contentDescription = "Close browser")
                    }
                },
                actions = {
                    IconButton(onClick = { webView?.goBack() }, enabled = webView?.canGoBack() == true) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    IconButton(onClick = { webView?.goForward() }, enabled = webView?.canGoForward() == true) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward")
                    }
                    IconButton(onClick = { webView?.reload() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reload")
                    }
                },
            )
        },
        bottomBar = {
            BottomAppBar(
                actions = {
                    Text(
                        text = "${state.adBlocksCount} ads blocked",
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                },
                floatingActionButton = {
                    if (state.detectedStreams.isNotEmpty()) {
                        androidx.compose.material3.ExtendedFloatingActionButton(
                            onClick = { showStreamsSheet = true },
                            icon = { Icon(Icons.Default.VideoLibrary, contentDescription = null) },
                            text = { Text("Play (${state.detectedStreams.size})") },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                factory = { ctx ->
                    ReverbLog.i("Browser", "Creating WebView for $initialUrl")
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            mediaPlaybackRequiresUserGesture = false
                            userAgentString = UserAgentInterceptor.DEFAULT_UA
                            blockNetworkImage = false
                            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        }

                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                                val url = request?.url?.toString() ?: return null
                                val accept = request.requestHeaders?.get("Accept")

                                // ── Extractor: capture video URLs ──
                                if (UrlUtils.isVideoUrl(url)) {
                                    ReverbLog.i("Browser", "Video URL detected on page: $url")
                                    browserViewModel.onStreamDetected(url, inferFormat(url))
                                    return null
                                }

                                // ── Ad-blocker: block ads (but never video) ──
                                val requestType = inferRequestType(request.requestHeaders?.get("Sec-Fetch-Dest"), accept, url)
                                val verdict = app.adMatcher.checkNetwork(url, requestType, accept)
                                if (verdict == AdMatcher.Verdict.BLOCK) {
                                    browserViewModel.onAdBlocked()
                                    return WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                                }
                                return null
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                ReverbLog.d("Browser", "Page started: $url")
                                progress = 0
                                browserViewModel.onUrlChanged(url ?: "")
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                ReverbLog.d("Browser", "Page finished: $url")
                                progress = 100
                                browserViewModel.onPageFinished(url ?: "")
                                // Inject cosmetic CSS to hide ad placeholders.
                                val css = app.adMatcher.cosmeticCssFor(url ?: "")
                                if (css.isNotBlank()) {
                                    view?.evaluateJavascript(
                                        "(function(){var s=document.createElement('style');s.textContent=${jsLiteral(css)};document.head.appendChild(s);})();",
                                        null,
                                    )
                                }
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress
                            }
                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                browserViewModel.onTitleChanged(title ?: "")
                            }
                        }

                        webView = this
                        loadUrl(initialUrl)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            // Progress bar.
            if (progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxSize().align(Alignment.TopCenter),
                )
            }
        }
    }

    // Detected streams bottom sheet.
    if (showStreamsSheet && state.detectedStreams.isNotEmpty()) {
        val stream = state.detectedStreamsResolved
        if (stream != null) {
            DetectedStreamsSheet(
                stream = stream,
                onDismiss = { showStreamsSheet = false },
                onPlay = { quality ->
                    showStreamsSheet = false
                    onPlayStream(stream, quality)
                },
                onDownload = { quality ->
                    showStreamsSheet = false
                    onDownloadStream(stream, quality)
                },
            )
        } else {
            // Resolving — show a loading sheet.
            androidx.compose.material3.ModalBottomSheet(
                onDismissRequest = { showStreamsSheet = false },
            ) {
                Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("Resolving stream…")
                }
            }
        }
    }
}

private fun inferFormat(url: String): StreamFormat = when {
    url.contains(".m3u8", ignoreCase = true) -> StreamFormat.HLS
    url.contains(".mpd", ignoreCase = true) -> StreamFormat.DASH
    url.contains(".mp4", ignoreCase = true) -> StreamFormat.PROGRESSIVE
    else -> StreamFormat.PROGRESSIVE
}

private fun inferRequestType(dest: String?, accept: String?, url: String): AdMatcher.RequestType {
    dest?.let {
        return when (it.lowercase()) {
            "script" -> AdMatcher.RequestType.SCRIPT
            "style" -> AdMatcher.RequestType.STYLESHEET
            "image" -> AdMatcher.RequestType.IMAGE
            "font" -> AdMatcher.RequestType.FONT
            "iframe", "frame" -> AdMatcher.RequestType.SUBDOCUMENT
            "audio", "video" -> AdMatcher.RequestType.MEDIA
            else -> AdMatcher.RequestType.OTHER
        }
    }
    return AdMatcher.RequestType.OTHER
}

private fun jsLiteral(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
