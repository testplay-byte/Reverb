package app.reverb.extractor

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import app.reverb.adblock.AdMatcher
import app.reverb.core.common.ReverbLog
import app.reverb.source.universal.EnhancedUniversalExtractor
import okhttp3.OkHttpClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Manages the lifecycle of the headless WebView used by the [EnhancedUniversalExtractor].
 *
 * The WebView is created lazily on first access — NOT during Application.onCreate().
 * This avoids blocking the main thread during app startup.
 */
class ExtractorManager(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val adMatcher: AdMatcher,
) {
    @Volatile private var _extractor: EnhancedUniversalExtractor? = null

    /**
     * Lazily create the extractor on first access.
     * Safe to call from any thread — if called from the main thread, creates directly;
     * if called from a background thread, posts to the main thread and waits.
     */
    val extractor: EnhancedUniversalExtractor
        get() {
            _extractor?.let { return it }
            return synchronized(this) {
                _extractor?.let { return it }
                ReverbLog.i("Extractor", "Creating EnhancedUniversalExtractor (lazy init)")
                val webView = createWebViewOnMainThread(context)
                val ext = EnhancedUniversalExtractor(webView, httpClient, adMatcher)
                _extractor = ext
                ReverbLog.d("Extractor", "Extractor created — WebView ready")
                ext
            }
        }

    fun stop() {
        _extractor?.stop()
    }

    /**
     * Create a WebView on the main thread.
     *
     * If we're ALREADY on the main thread, create directly (common case — called from
     * a coroutine that resumed on Dispatchers.Main).
     *
     * If we're on a background thread, post to the main thread Handler and wait with a latch.
     */
    private fun createWebViewOnMainThread(context: Context): WebView {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // Already on the main thread — create directly.
            ReverbLog.d("Extractor", "Creating WebView (on main thread, direct)")
            return WebView(context)
        }

        // On a background thread — post to main thread and wait.
        ReverbLog.d("Extractor", "Creating WebView (posting to main thread from background)")
        val latch = CountDownLatch(1)
        var result: WebView? = null
        Handler(Looper.getMainLooper()).post {
            try {
                result = WebView(context)
            } catch (e: Exception) {
                ReverbLog.e("Extractor", "Failed to create WebView — ${e.message}", e)
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(10, TimeUnit.SECONDS)) {
            throw IllegalStateException("Timed out waiting for WebView creation on main thread")
        }
        return result ?: throw IllegalStateException("WebView creation returned null")
    }
}
