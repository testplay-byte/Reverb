package app.reverb.core.network

import android.util.Log
import app.reverb.core.common.Logger
import app.reverb.core.common.NoopLogger
import app.reverb.core.common.ReverbLog
import okhttp3.Interceptor
import okhttp3.Response
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Rate-limit interceptor — sliding-window per host.
 * Default: 1 request per 2 seconds per host. Matches Aniyomi's pattern.
 */
class RateLimitInterceptor(
    private val minIntervalMs: Long = 2000L,
) : Interceptor {

    private val lastRequestTimes = ConcurrentHashMap<String, Long>()
    private val inFlight = ConcurrentHashMap<String, AtomicInteger>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val host = chain.request().url.host
        val now = System.currentTimeMillis()

        val counter = inFlight.computeIfAbsent(host) { AtomicInteger(0) }
        synchronized(counter) {
            val last = lastRequestTimes[host] ?: 0L
            val elapsed = now - last
            if (elapsed < minIntervalMs) {
                val sleep = minIntervalMs - elapsed
                ReverbLog.d("RateLimit", "Waiting ${sleep}ms for host $host (rate limit)")
                try { Thread.sleep(sleep) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            lastRequestTimes[host] = System.currentTimeMillis()
        }

        return chain.proceed(chain.request())
    }
}

/**
 * User-Agent interceptor — picks a realistic desktop Chrome UA.
 */
class UserAgentInterceptor(
    private val userAgent: String = DEFAULT_UA,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val uaHeader = request.header("User-Agent")
        val newRequest = if (uaHeader.isNullOrBlank() || uaHeader.startsWith("okhttp", ignoreCase = true)) {
            request.newBuilder().header("User-Agent", userAgent).build()
        } else request
        return chain.proceed(newRequest)
    }

    companion object {
        const val DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}

/**
 * Request-logging interceptor — logs every HTTP request with method, URL, response code, and timing.
 */
class RequestLoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val method = request.method
        val url = request.url.toString()
        val startMs = System.currentTimeMillis()

        return try {
            val response = chain.proceed(request)
            val elapsed = System.currentTimeMillis() - startMs
            ReverbLog.d("Network", "$method $url → ${response.code} (${elapsed}ms)")
            response
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startMs
            ReverbLog.e("Network", "$method $url FAILED after ${elapsed}ms — ${e.message}", e)
            throw e
        }
    }
}

/**
 * Cloudflare interceptor — detects CF challenge responses (403/503 with the CF server header)
 * and delegates to a [CloudflareSolver] to obtain a cf_clearance cookie.
 */
class CloudflareInterceptor(
    private val solver: CloudflareSolver = NoopCloudflareSolver,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (!isCloudflareChallenge(response)) return response

        val url = chain.request().url.toString()
        ReverbLog.w("Cloudflare", "Challenge detected for $url (HTTP ${response.code}) — invoking solver")
        response.close()

        val solved = solver.solve(url, chain)
        if (solved) {
            ReverbLog.i("Cloudflare", "Challenge solved for $url — retrying request")
            return chain.proceed(chain.request())
        } else {
            ReverbLog.e("Cloudflare", "Failed to solve challenge for $url — returning unsolved response")
            return chain.proceed(chain.request())
        }
    }

    private fun isCloudflareChallenge(response: Response): Boolean {
        val server = response.header("Server")?.lowercase(Locale.US) ?: return false
        if (!server.startsWith("cloudflare")) return false
        val code = response.code
        return code == 403 || code == 503 || code == 429
    }
}

/**
 * Contract for solving a Cloudflare challenge.
 * Synchronous because OkHttp interceptors are blocking.
 * The WebView-based solver uses runBlocking internally to bridge the coroutine.
 */
fun interface CloudflareSolver {
    fun solve(url: String, chain: Interceptor.Chain): Boolean
}

/** Phase-0 no-op solver — logs and returns false. Phase 1 replaces with the WebView solver. */
object NoopCloudflareSolver : CloudflareSolver {
    override fun solve(url: String, chain: Interceptor.Chain): Boolean {
        ReverbLog.w("Cloudflare", "No solver wired (Phase 0 stub) — cannot solve challenge for $url")
        return false
    }
}
