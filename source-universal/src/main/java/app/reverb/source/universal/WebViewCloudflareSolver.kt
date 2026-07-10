package app.reverb.source.universal

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import app.reverb.core.common.ReverbLog
import app.reverb.core.network.AndroidCookieJar
import app.reverb.core.network.CloudflareSolver
import okhttp3.Interceptor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Real Cloudflare solver — loads the URL in a WebView, waits for the cf_clearance cookie,
 * and copies cookies into the shared [AndroidCookieJar].
 *
 * How it works (PLAN.md §16 + anime-extensions/lib/cloudflareinterceptor):
 *   1. Create a WebView on the main thread.
 *   2. Enable JS + cookies.
 *   3. Load the challenge URL — the WebView will execute CF's JS challenge / show Turnstile.
 *   4. Poll CookieManager.getCookie(url) for "cf_clearance=" every 500ms, up to 30s.
 *   5. Once cf_clearance appears, flush cookies to the AndroidCookieJar + return true.
 *   6. The CloudflareInterceptor retries the original OkHttp request — now with cookies.
 *
 * This is SYNCHRONOUS (called from a blocking OkHttp interceptor). It uses a CountDownLatch
 * to bridge the main-thread WebView callbacks with the calling thread.
 *
 * @param context Android context (needed to create WebView)
 * @param cookieJar the shared AndroidCookieJar — solved cookies are written here
 * @param timeoutMs max time to wait for the challenge to solve (default 30s)
 */
class WebViewCloudflareSolver(
    private val context: Context,
    private val cookieJar: AndroidCookieJar,
    private val timeoutMs: Long = 30_000L,
) : CloudflareSolver {

    override fun solve(url: String, chain: Interceptor.Chain): Boolean {
        ReverbLog.i("CfSolver", "Solving CF challenge for $url (timeout=${timeoutMs}ms)")
        val startMs = System.currentTimeMillis()

        // Fast path: check if we already have a clearance cookie (from a previous solve).
        if (cookieJar.hasClearanceCookie(url)) {
            ReverbLog.d("CfSolver", "Clearance cookie already present — skipping WebView solve")
            return true
        }

        val latch = CountDownLatch(1)
        var solved = false

        // WebView must be created on the main thread.
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                val webView = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = chain.request().header("User-Agent")
                        ?: app.reverb.core.network.UserAgentInterceptor.DEFAULT_UA
                    CookieManager.getInstance().setAcceptCookie(true)
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, pageUrl: String?) {
                        super.onPageFinished(view, pageUrl)
                        ReverbLog.d("CfSolver", "Page finished loading: $pageUrl")
                        checkForClearance(url)
                    }

                    private fun checkForClearance(targetUrl: String) {
                        val cookies = CookieManager.getInstance().getCookie(targetUrl) ?: ""
                        if (cookies.contains("cf_clearance=")) {
                            solved = true
                            cookieJar.flush()
                            ReverbLog.i("CfSolver", "cf_clearance cookie obtained!")
                            latch.countDown()
                        }
                    }
                }

                ReverbLog.d("CfSolver", "Loading URL in WebView: $url")
                webView.loadUrl(url)

                // Poll for cf_clearance cookie every 500ms (in case onPageFinished doesn't fire
                // or the cookie is set via JS without a page navigation).
                Thread {
                    while (!solved && System.currentTimeMillis() - startMs < timeoutMs) {
                        Thread.sleep(500)
                        val cookies = CookieManager.getInstance().getCookie(url) ?: ""
                        if (cookies.contains("cf_clearance=")) {
                            solved = true
                            cookieJar.flush()
                            ReverbLog.i("CfSolver", "cf_clearance cookie detected via polling!")
                            latch.countDown()
                            break
                        }
                    }
                    if (!solved) latch.countDown() // timeout
                }.start()
            } catch (e: Exception) {
                ReverbLog.e("CfSolver", "Failed to create WebView for solving — ${e.message}", e)
                latch.countDown()
            }
        }

        // Block the OkHttp thread until solved or timeout.
        val solvedInTime = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        val elapsed = System.currentTimeMillis() - startMs

        return if (solved && solvedInTime) {
            ReverbLog.i("CfSolver", "Challenge SOLVED in ${elapsed}ms — retrying request")
            true
        } else {
            ReverbLog.w("CfSolver", "Challenge NOT solved in ${elapsed}ms — giving up")
            false
        }
    }
}
