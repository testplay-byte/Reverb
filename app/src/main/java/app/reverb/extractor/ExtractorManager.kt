package app.reverb.extractor

import android.content.Context
import android.webkit.WebView
import app.reverb.adblock.AdMatcher
import app.reverb.source.universal.EnhancedUniversalExtractor
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the lifecycle of the headless WebView used by the [EnhancedUniversalExtractor].
 *
 * The WebView must be created on the main thread; this class marshals creation + access.
 * Phase 0 uses a single shared WebView instance; Phase 1 will pool them per-host.
 */
@Singleton
class ExtractorManager @Inject constructor(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val adMatcher: AdMatcher,
) {
    @Volatile private var extractor: EnhancedUniversalExtractor? = null

    /** Lazily create the extractor (on the main thread). */
    fun getOrCreateExtractor(): EnhancedUniversalExtractor = synchronized(this) {
        extractor?.let { return it }
        val webView = android.os.Handler(android.os.Looper.getMainLooper()).let {
            val latch = java.util.concurrent.CountDownLatch(1)
            var result: WebView? = null
            it.post {
                result = WebView(context).apply {
                    // Headless-ish: we don't attach it to a window, but it still runs.
                }
                latch.countDown()
            }
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            result!!
        }
        val ext = EnhancedUniversalExtractor(webView, httpClient, adMatcher)
        extractor = ext
        ext
    }

    fun stop() {
        extractor?.stop()
    }
}
