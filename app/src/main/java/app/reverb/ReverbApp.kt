package app.reverb

import android.app.Application
import app.reverb.adblock.AdBlockInterceptor
import app.reverb.adblock.AdMatcher
import app.reverb.adblock.KotlinRegexMatcher
import app.reverb.autolearn.LlmClient
import app.reverb.autolearn.LlmClientFactory
import app.reverb.autolearn.NoopLlmClient
import app.reverb.core.common.Loggers
import app.reverb.core.common.ReverbLog
import app.reverb.core.network.AndroidCookieJar
import app.reverb.core.network.HttpClientFactory
import app.reverb.data.DataRepository
import app.reverb.data.LlmConfig
import app.reverb.data.LlmProvider
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
    lateinit var adMatcher: AdMatcher
    var llmClient: LlmClient = NoopLlmClient
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // ── Logging ──
        Loggers.set(AndroidLogger())
        ReverbLog.i("App", "Reverb starting — Phase 2")

        // ── Data ──
        dataRepository = DataRepository(this)

        // ── Ad-blocker ──
        adMatcher = KotlinRegexMatcher(KotlinRegexMatcher.STARTER_RULES)

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
        ReverbLog.d("App", "HTTP client built")

        // ── Extractor (lazy) ──
        extractorManager = ExtractorManager(this, httpClient, adMatcher)
        universalSite = UniversalSite { extractorManager.extractor }

        // ── Player ──
        player = ReverbPlayer(this, httpClient)

        // ── LLM client ──
        refreshLlmClient()

        ReverbLog.i("App", "Reverb ready — Phase 2 (native UI + LLM analyzer)")
    }

    /** Re-create the LLM client from current settings. Call after settings change. */
    fun refreshLlmClient() {
        val config = dataRepository.getLlmConfig()
        llmClient = LlmClientFactory.create(httpClient, config)
        ReverbLog.i("App", "LLM client: ${llmClient.name} (configured=${llmClient.isConfigured})")
    }

    companion object {
        lateinit var instance: ReverbApp
            private set
    }
}
