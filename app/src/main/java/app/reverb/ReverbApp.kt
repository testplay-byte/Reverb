package app.reverb

import android.app.Application
import app.reverb.adblock.AdBlockInterceptor
import app.reverb.adblock.KotlinRegexMatcher
import app.reverb.core.network.HttpClientFactory
import app.reverb.extractor.ExtractorManager
import app.reverb.source.universal.UniversalSite
import okhttp3.OkHttpClient

/**
 * Application — holds the manual DI graph for Phase 0.
 * Phase 1 will replace this with Hilt.
 */
class ReverbApp : Application() {

    lateinit var extractorManager: ExtractorManager
    lateinit var universalSite: UniversalSite
    lateinit var httpClient: OkHttpClient

    override fun onCreate() {
        super.onCreate()
        instance = this
        val adMatcher = KotlinRegexMatcher(KotlinRegexMatcher.STARTER_RULES)
        httpClient = HttpClientFactory(enableDoH = true, enableLogging = true).build()
            .newBuilder()
            .addInterceptor(AdBlockInterceptor(adMatcher))
            .build()
        extractorManager = ExtractorManager(this, httpClient, adMatcher)
        universalSite = UniversalSite(extractorManager.extractor)
    }

    companion object {
        lateinit var instance: ReverbApp
            private set
    }
}
