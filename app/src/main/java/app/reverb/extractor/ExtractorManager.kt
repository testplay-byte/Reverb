package app.reverb.extractor

import android.content.Context
import android.webkit.WebView
import app.reverb.adblock.AdMatcher
import app.reverb.source.universal.EnhancedUniversalExtractor
import okhttp3.OkHttpClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Manages the lifecycle of the headless WebView used by the [EnhancedUniversalExtractor].
 * Phase 0 uses a single shared WebView instance.
 */
class ExtractorManager(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val adMatcher: AdMatcher,
) {
    @Volatile private var _extractor: EnhancedUniversalExtractor? = null

    /** Lazily create the extractor (WebView must be created on the main thread). */
    val extractor: EnhancedUniversalExtractor
        get() {
            _extractor?.let { return it }
            return synchronized(this) {
                _extractor?.let { return it }
                val webView = createWebViewOnMainThread(context)
                val ext = EnhancedUniversalExtractor(webView, httpClient, adMatcher)
                _extractor = ext
                ext
            }
        }

    fun stop() {
        _extractor?.stop()
    }

    private fun createWebViewOnMainThread(context: Context): WebView {
        val latch = CountDownLatch(1)
        var result: WebView? = null
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            result = WebView(context)
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
        return result!!
    }
}
