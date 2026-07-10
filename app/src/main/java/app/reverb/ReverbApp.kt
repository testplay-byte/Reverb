package app.reverb

import android.app.Application
import app.reverb.adblock.AdBlockInterceptor
import app.reverb.adblock.KotlinRegexMatcher
import app.reverb.core.common.Loggers
import app.reverb.core.common.ReverbLog
import app.reverb.core.network.HttpClientFactory
import app.reverb.extractor.ExtractorManager
import app.reverb.logging.AndroidLogger
import app.reverb.source.universal.UniversalSite
import okhttp3.OkHttpClient

/**
 * Application — holds the manual DI graph for Phase 1.
 * Phase 2 will replace this with Hilt.
 */
class ReverbApp : Application() {

    lateinit var extractorManager: ExtractorManager
    lateinit var universalSite: UniversalSite
    lateinit var httpClient: OkHttpClient

    override fun onCreate() {
        super.onCreate()
        instance = this

        // ── Logging ── wire AndroidLogger as the global logger ──
        Loggers.set(AndroidLogger())
        ReverbLog.i("App", "Reverb starting — Phase 1 MVP")

        val adMatcher = KotlinRegexMatcher(KotlinRegexMatcher.STARTER_RULES)
        ReverbLog.d("App", "Ad matcher initialized — ${KotlinRegexMatcher.STARTER_RULES.size} starter rules")

        httpClient = HttpClientFactory(enableDoH = true, enableLogging = true).build()
            .newBuilder()
            .addInterceptor(AdBlockInterceptor(adMatcher))
            .build()
        ReverbLog.d("App", "HTTP client built — OkHttp 5.4.0 + UA + RateLimit + Cloudflare + DoH + Brotli + AdBlock")

        extractorManager = ExtractorManager(this, httpClient, adMatcher)
        universalSite = UniversalSite(extractorManager.extractor)
        ReverbLog.i("App", "Reverb ready — extractor + universal site wired")
    }

    companion object {
        lateinit var instance: ReverbApp
            private set
    }
}
