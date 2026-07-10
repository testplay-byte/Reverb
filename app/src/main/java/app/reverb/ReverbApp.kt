package app.reverb

import android.app.Application
import app.reverb.adblock.AdBlockInterceptor
import app.reverb.adblock.KotlinRegexMatcher
import app.reverb.core.common.Loggers
import app.reverb.core.common.ReverbLog
import app.reverb.core.network.AndroidCookieJar
import app.reverb.core.network.HttpClientFactory
import app.reverb.extractor.ExtractorManager
import app.reverb.logging.AndroidLogger
import app.reverb.source.universal.UniversalSite
import app.reverb.source.universal.WebViewCloudflareSolver
import okhttp3.OkHttpClient

/**
 * Application — holds the manual DI graph for Phase 1.
 */
class ReverbApp : Application() {

    lateinit var cookieJar: AndroidCookieJar
    lateinit var extractorManager: ExtractorManager
    lateinit var universalSite: UniversalSite
    lateinit var httpClient: OkHttpClient

    override fun onCreate() {
        super.onCreate()
        instance = this

        // ── Logging ──
        Loggers.set(AndroidLogger())
        ReverbLog.i("App", "Reverb starting — Phase 1 MVP")

        // ── Ad-blocker ──
        val adMatcher = KotlinRegexMatcher(KotlinRegexMatcher.STARTER_RULES)
        ReverbLog.d("App", "Ad matcher initialized — ${KotlinRegexMatcher.STARTER_RULES.size} starter rules")

        // ── Cookie jar (bridges OkHttp ↔ WebView for Cloudflare solving) ──
        cookieJar = AndroidCookieJar()

        // ── Cloudflare solver (real WebView cookie-poll) ──
        val cfSolver = WebViewCloudflareSolver(this, cookieJar)
        ReverbLog.d("App", "Cloudflare WebView solver wired")

        // ── HTTP client ──
        httpClient = HttpClientFactory(
            cookieJar = cookieJar,
            cloudflareSolver = cfSolver,
            enableDoH = true,
            enableLogging = true,
        ).build()
            .newBuilder()
            .addInterceptor(AdBlockInterceptor(adMatcher))
            .build()
        ReverbLog.d("App", "HTTP client built — OkHttp + UA + RateLimit + Cloudflare(solver) + DoH + Brotli + AdBlock")

        // ── Extractor ──
        extractorManager = ExtractorManager(this, httpClient, adMatcher)
        universalSite = UniversalSite(extractorManager.extractor)
        ReverbLog.i("App", "Reverb ready — extractor + universal site + CF solver wired")
    }

    companion object {
        lateinit var instance: ReverbApp
            private set
    }
}
