package app.reverb.autolearn

import app.reverb.core.common.ReverbLog
import app.reverb.data.LearnedSiteConfig
import app.reverb.source.api.MediaDetails
import app.reverb.source.api.MediaItem
import app.reverb.source.api.VideoRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI

/**
 * The LearnedSite interpreter — scrapes a site using a [LearnedSiteConfig].
 *
 * Uses Jsoup to fetch + parse pages, extracts structured data using CSS selectors.
 * Handles lazy-loaded images (data-src, data-original), relative URL resolution,
 * and multiple fallback strategies for titles/thumbnails/links.
 */
class LearnedSiteInterpreter(
    private val httpClient: OkHttpClient,
) {

    /** Fetch the catalog (homepage grid) for a site. */
    suspend fun fetchCatalog(config: LearnedSiteConfig, page: Int = 1): List<MediaItem> = withContext(Dispatchers.IO) {
        val url = config.baseUrl
        ReverbLog.i("LearnedSite", "Fetching catalog from $url")
        val doc = fetchDocument(url) ?: return@withContext emptyList()

        val selector = config.catalogSelector ?: return@withContext emptyList()
        val cards = doc.select(selector)
        ReverbLog.d("LearnedSite", "Found ${cards.size} cards with selector '$selector'")

        val items = cards.mapNotNull { card -> parseCard(card, config, url) }
        ReverbLog.i("LearnedSite", "Catalog: ${items.size} items parsed")
        items
    }

    /** Search the site for [query]. Uses the searchUrlPattern from the config. */
    suspend fun search(config: LearnedSiteConfig, query: String): List<MediaItem> = withContext(Dispatchers.IO) {
        // Try the searchUrlPattern from the config.
        val searchUrl = buildSearchUrl(config, query)
        if (searchUrl == null) {
            ReverbLog.w("LearnedSite", "No searchUrlPattern in config — can't search")
            return@withContext emptyList()
        }

        ReverbLog.i("LearnedSite", "Searching: $searchUrl")
        val doc = fetchDocument(searchUrl) ?: return@withContext emptyList()

        val selector = config.catalogSelector ?: return@withContext emptyList()
        val cards = doc.select(selector)
        ReverbLog.d("LearnedSite", "Search found ${cards.size} cards")

        cards.mapNotNull { card -> parseCard(card, config, searchUrl) }
    }

    /** Fetch details for a specific media item. */
    suspend fun fetchDetails(config: LearnedSiteConfig, item: MediaItem): MediaDetails = withContext(Dispatchers.IO) {
        ReverbLog.i("LearnedSite", "Fetching details from ${item.url}")
        val doc = fetchDocument(item.url) ?: return@withContext MediaDetails(
            url = item.url, title = item.title, description = "Failed to load page"
        )

        val poster = config.detailsPosterSelector?.let { extractImageUrl(doc.selectFirst(it)) }
        val synopsis = config.detailsSynopsisSelector?.let { doc.selectFirst(it)?.text() }

        // Fetch episodes using the episodeListSelector.
        val episodes = config.episodeListSelector?.let { epSelector ->
            if (epSelector.isNotBlank()) {
                val epElements = doc.select(epSelector)
                ReverbLog.d("LearnedSite", "Episode selector '$epSelector' found ${epElements.size} elements")
                epElements.mapNotNull { epEl ->
                    val href = epEl.absUrl("href").ifBlank { epEl.attr("href") }
                    val title = epEl.text().trim().ifBlank { epEl.attr("title").ifBlank { "Episode" } }
                    if (href.isNotBlank()) {
                        val fullUrl = resolveUrl(item.url, href)
                        VideoRef(url = fullUrl, title = title)
                    } else null
                }
            } else emptyList()
        } ?: emptyList()

        ReverbLog.i("LearnedSite", "Details: ${episodes.size} episodes found for ${item.title}")
        MediaDetails(
            url = item.url,
            title = item.title,
            description = synopsis,
            posterUrl = poster ?: item.thumbnailUrl,
            thumbnailUrl = item.thumbnailUrl,
            episodes = episodes,
        )
    }

    /** Parse a catalog card element into a MediaItem. */
    private fun parseCard(card: Element, config: LearnedSiteConfig, baseUrl: String): MediaItem? {
        // Title — try the configured selector, then fallbacks.
        val title = config.cardTitleSelector?.let { card.selectFirst(it)?.text()?.trim() }
            ?: card.selectFirst("a[title]")?.attr("title")?.trim()
            ?: card.selectFirst("img")?.attr("alt")?.trim()
            ?: card.selectFirst("a")?.text()?.trim()
            ?: card.text()?.trim()?.take(100)
            ?: "Unknown"

        // Thumbnail — try the configured selector, then fallbacks for lazy-loaded images.
        val thumbnail = config.cardThumbnailSelector?.let { extractImageUrl(card.selectFirst(it)) }
            ?: extractImageUrl(card.selectFirst("img"))
            ?: card.selectFirst("[style*='background']")?.attr("style")?.let { extractBgUrl(it) }

        // URL — try the configured selector, then fallback. MUST be a details page link.
        val href = config.cardUrlSelector?.let { card.selectFirst(it)?.absUrl("href")?.ifBlank { card.selectFirst(it)?.attr("href") } }
            ?: card.selectFirst("a")?.absUrl("href")?.ifBlank { card.selectFirst("a")?.attr("href") }
            ?: card.absUrl("href").ifBlank { card.attr("href") }

        if (href.isNullOrBlank()) return null
        val fullUrl = resolveUrl(baseUrl, href)
        return MediaItem(
            url = fullUrl,
            title = title,
            thumbnailUrl = thumbnail,
        )
    }

    /** Extract image URL from an element, handling lazy-loading attributes. */
    private fun extractImageUrl(el: Element?): String? {
        if (el == null) return null
        // Try src first, then lazy-load attributes.
        return el.absUrl("src").ifBlank { el.attr("src") }.ifBlank {
            el.absUrl("data-src").ifBlank { el.attr("data-src") }.ifBlank {
                el.absUrl("data-original").ifBlank { el.attr("data-original") }.ifBlank {
                    el.attr("data-lazy-src").ifBlank {
                        el.attr("data-cfsrc")
                    }
                }
            }
        }.takeIf { it.isNotBlank() && !it.startsWith("data:") }
    }

    /** Extract URL from a CSS background-image: url(...) style. */
    private fun extractBgUrl(style: String): String? {
        val match = Regex("""url\(['"]?([^'")]+)['"]?\)""").find(style)
        return match?.groupValues?.get(1)
    }

    /** Build a search URL from the config's searchUrlPattern. */
    private fun buildSearchUrl(config: LearnedSiteConfig, query: String): String? {
        val pattern = config.searchUrlPattern
        if (pattern.isNullOrBlank()) {
            // Fallback: try common search URL patterns.
            val base = config.baseUrl.removeSuffix("/")
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            return "$base/?s=$encoded"
        }
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        return pattern.replace("{query}", encoded)
    }

    private fun fetchDocument(url: String): org.jsoup.nodes.Document? = try {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", app.reverb.core.network.UserAgentInterceptor.DEFAULT_UA)
            .build()
        httpClient.newCall(request).execute().use { resp ->
            val html = resp.body?.string() ?: return null
            Jsoup.parse(html, url)
        }
    } catch (e: Exception) {
        ReverbLog.e("LearnedSite", "Failed to fetch $url: ${e.message}", e)
        null
    }

    private fun resolveUrl(base: String, relative: String): String {
        if (relative.startsWith("http://") || relative.startsWith("https://")) return relative
        return try { URI(base).resolve(relative).toString() } catch (e: Exception) { relative }
    }
}
