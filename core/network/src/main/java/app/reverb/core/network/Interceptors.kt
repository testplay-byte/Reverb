package app.reverb.core.network

import okhttp3.Interceptor
import okhttp3.Response
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Rate-limit interceptor — sliding-window per host.
 *
 * Default: 1 request per 2 seconds per host. Matches Aniyomi's pattern.
 * Per-site overrides can be applied by wrapping the client.
 */
class RateLimitInterceptor(
    private val minIntervalMs: Long = 2000L,
) : Interceptor {

    private val lastRequestTimes = ConcurrentHashMap<String, Long>()
    private val inFlight = ConcurrentHashMap<String, AtomicInteger>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val host = chain.request().url.host
        val now = System.currentTimeMillis()

        // Per-host mutual exclusion so we actually respect the interval.
        val counter = inFlight.computeIfAbsent(host) { AtomicInteger(0) }
        synchronized(counter) {
            val last = lastRequestTimes[host] ?: 0L
            val elapsed = now - last
            if (elapsed < minIntervalMs) {
                val sleep = minIntervalMs - elapsed
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
 * Phase 0 uses a single static UA; Phase 1 will fetch a rotating list.
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
 * Cloudflare interceptor — detects CF challenge responses (403/503 with the CF server header)
 * and delegates to a [CloudflareSolver] to obtain a cf_clearance cookie.
 *
 * Phase 0 ships a stub solver that just logs; Phase 1 wires the real WebView cookie-poll solver.
 */
class CloudflareInterceptor(
    private val solver: CloudflareSolver = NoopCloudflareSolver,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (!isCloudflareChallenge(response)) return response

        // Close the challenge response, solve, and retry once.
        response.close()
        val solved = solver.solve(chain.request().url.toString(), chain)
        return if (solved) chain.proceed(chain.request()) else chain.proceed(chain.request())
    }

    private fun isCloudflareChallenge(response: Response): Boolean {
        val server = response.header("Server")?.lowercase(Locale.US) ?: return false
        if (!server.startsWith("cloudflare")) return false
        val code = response.code
        return code == 403 || code == 503 || code == 429
    }
}

/** Contract for solving a Cloudflare challenge. */
fun interface CloudflareSolver {
    /**
     * Solve the CF challenge for [url]. Returns true if cookies were obtained and
     * the retry should proceed. Implementations update the shared CookieJar.
     */
    suspend fun solve(url: String, chain: Interceptor.Chain): Boolean
}

/** Phase-0 no-op solver — logs and returns false. Phase 1 will replace with the WebView solver. */
object NoopCloudflareSolver : CloudflareSolver {
    override suspend fun solve(url: String, chain: Interceptor.Chain): Boolean {
        println("[CloudflareInterceptor] CF challenge detected for $url — no solver wired (Phase 0 stub)")
        return false
    }
}
