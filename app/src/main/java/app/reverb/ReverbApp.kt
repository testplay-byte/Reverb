package app.reverb

import android.app.Application
import app.reverb.adblock.AdBlockInterceptor
import app.reverb.adblock.KotlinRegexMatcher
import app.reverb.core.common.Loggers
import app.reverb.core.common.ReverbLog
import app.reverb.core.network.AndroidCookieJar
import app.reverb.core.network.HttpClientFactory
import app.reverb.data.DataRepository
import app.reverb.extractor.ExtractorManager
import app.reverb.logging.AndroidLogger
import app.reverb.player.ReverbPlayer
import app.reverb.source.universal.UniversalSite
import app.reverb.source.universal.WebViewCloudflareSolver
import okhttp3.OkHttpClient

class ReverbApp : Application() {

    lateinit var cookieJar: AndroidCookieJar
    lateinit var dataRepository: DataRepository
    lateinit var extractorManager: ExtractorManager
    lateinit var universalSite: UniversalSite
    lateinit var player: ReverbPlayer
    lateinit var httpClient: OkHttpClient

    override fun onCreate() {
        super.onCreate()
        instance = this

        // ── Logging ──
        Loggers.set(AndroidLogger())
        ReverbLog.i("App", "Reverb starting — Phase 1 MVP")

        // ── Data ──
        dataRepository = DataRepository(this)
        ReverbLog.d("App", "Data repository initialized")

        // ── Ad-blocker ──
        val adMatcher = KotlinRegexMatcher(KotlinRegexMatcher.STARTER_RULES)

        // ── Cookie jar + CF solver ──
        cookieJar = AndroidCookieJar()
        val cfSolver = WebViewCloudflareSolver(this, cookieJar)

        // ── HTTP client ──
        httpClient = HttpClientFactory(
            cookieJar = cookieJar,
            cloudflareSolver = cfSolver,
            enableDoH = dataRepository.getSettings().dohEnabled,
            enableLogging = true,
        ).build()
            .newBuilder()
            .addInterceptor(AdBlockInterceptor(adMatcher))
            .build()
        ReverbLog.d("App", "HTTP client built — OkHttp + UA + RateLimit + Cloudflare(solver) + DoH + Brotli + AdBlock")

        // ── Extractor (lazy — WebView created on first use, NOT during app startup) ──
        extractorManager = ExtractorManager(this, httpClient, adMatcher)
        universalSite = UniversalSite { extractorManager.extractor }
        ReverbLog.d("App", "Extractor + universal site wired (lazy — WebView created on first resolve)")

        // ── Player ──
        player = ReverbPlayer(this, httpClient)
        ReverbLog.i("App", "Reverb ready — Phase 1 MVP wired")
    }

    companion object {
        lateinit var instance: ReverbApp
            private set
    }
}
