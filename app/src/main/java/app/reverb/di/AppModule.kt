package app.reverb.di

import android.content.Context
import android.webkit.WebView
import app.reverb.adblock.AdBlockInterceptor
import app.reverb.adblock.AdMatcher
import app.reverb.adblock.KotlinRegexMatcher
import app.reverb.core.network.HttpClientFactory
import app.reverb.extractor.ExtractorManager
import app.reverb.source.universal.EnhancedUniversalExtractor
import app.reverb.source.universal.UniversalSite
import app.reverb.source.api.Site
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideAdMatcher(): AdMatcher = KotlinRegexMatcher(KotlinRegexMatcher.STARTER_RULES)

    @Provides @Singleton
    fun provideOkHttpClient(adMatcher: AdMatcher): OkHttpClient =
        HttpClientFactory(enableDoH = true, enableLogging = true).build()
            .newBuilder()
            .addInterceptor(AdBlockInterceptor(adMatcher))
            .build()

    @Provides @Singleton
    fun provideExtractorManager(
        @ApplicationContext context: Context,
        httpClient: OkHttpClient,
        adMatcher: AdMatcher,
    ): ExtractorManager = ExtractorManager(context, httpClient, adMatcher)

    @Provides @Singleton
    fun provideUniversalSite(extractorManager: ExtractorManager): UniversalSite =
        UniversalSite(extractorManager.extractor)

    @Provides @Singleton
    fun provideSites(universal: UniversalSite): List<Site> = listOf(universal)
}
