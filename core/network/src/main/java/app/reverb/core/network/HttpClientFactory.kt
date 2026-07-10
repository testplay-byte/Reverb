package app.reverb.core.network

import okhttp3.Cache
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Builds the shared OkHttpClient used by every Reverb extractor + scraper.
 *
 * The interceptor chain (in order):
 *   UserAgent → RateLimit → Cloudflare → (Brotli auto by OkHttp) → DoH → network
 *
 * Reference: PLAN.md §6.4
 */
class HttpClientFactory(
    private val cookieJar: CookieJar = CookieJar.NO_COOKIES,
    private val cache: Cache? = null,
    private val cloudflareSolver: CloudflareSolver = NoopCloudflareSolver,
    private val enableDoH: Boolean = true,
    private val enableLogging: Boolean = true,
) {

    fun build(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)

        cache?.let { builder.cache(it) }

        // Interceptor chain — order matters.
        builder.addInterceptor(UserAgentInterceptor())
        builder.addInterceptor(RateLimitInterceptor())

        if (enableLogging) {
            builder.addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                }
            )
        }

        builder.addInterceptor(CloudflareInterceptor(cloudflareSolver))

        // Brotli decompression (OkHttp 5 supports it via the brotli module).
        // The okhttp-brotli artifact auto-registers via ServiceLoader; no explicit interceptor needed.

        if (enableDoH) {
            builder.dns(cloudflareDoH(builder.build()))
        }

        return builder.build()
    }

    /**
     * Cloudflare DNS-over-HTTPS (1.1.1.1).
     * Phase 1 will make the provider user-selectable (Google, Quad9, AdGuard, NextDNS, ...).
     */
    private fun cloudflareDoH(bootstrapClient: OkHttpClient): Dns =
        DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url("https://cloudflare-dns.com/dns-query".toHttpUrlOrEmpty())
            .includeIPv6(true)
            .resolvePrivateAddresses(true)
            .build()

    private fun String.toHttpUrlOrEmpty() =
        runCatching { okhttp3.HttpUrl.get(this) }.getOrElse { error("invalid DoH URL: $this") }
}

/** A no-op Dns that just resolves via the platform resolver — used when DoH is disabled. */
object PlatformDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> = Dns.SYSTEM.lookup(hostname)
}
