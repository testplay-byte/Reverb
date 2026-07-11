package app.reverb.autolearn

import app.reverb.core.common.ReverbLog
import app.reverb.core.html.HtmlSimplifier
import app.reverb.data.DataRepository
import app.reverb.data.LearnedSiteConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * The LLM-Assisted Site Analyzer — the differentiator.
 *
 * MULTI-PAGE ANALYSIS (v2):
 *   1. Fetch the homepage HTML → find the catalog cards.
 *   2. Follow one card's link → fetch the DETAILS page.
 *   3. Follow one episode link (if found) → fetch the EPISODE page.
 *   4. Send all 3 simplified pages to the LLM in ONE prompt.
 *   5. LLM returns JSON with selectors for all 3 page types.
 *   6. Validate each selector against the corresponding page.
 *   7. Retry up to 3× with error feedback.
 *   8. Store the LearnedSiteConfig.
 *
 * This is critical because a single homepage doesn't contain enough info for the
 * LLM to distinguish between detail-page links and episode-page links.
 */
class SiteAnalyzer(
    private val httpClient: OkHttpClient,
    private val llmClient: LlmClient,
    private val dataRepository: DataRepository,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun analyzeSite(url: String, maxRetries: Int = 3): LearnedSiteConfig? = withContext(Dispatchers.IO) {
        ReverbLog.i("SiteAnalyzer", "Analyzing: $url")
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: run {
            ReverbLog.e("SiteAnalyzer", "Could not parse host from $url")
            return@withContext null
        }

        // Check cache first.
        dataRepository.getLearnedSite(host)?.let { cached ->
            ReverbLog.i("SiteAnalyzer", "Using cached config for $host")
            return@withContext cached
        }

        // ── Step 1: Fetch the homepage ──
        val homeHtml = fetchHtml(url) ?: run {
            ReverbLog.e("SiteAnalyzer", "Failed to fetch homepage from $url")
            return@withContext null
        }
        val homeSimplified = HtmlSimplifier.simplify(homeHtml)
        ReverbLog.i("SiteAnalyzer", "Homepage simplified: ${homeSimplified.originalSizeBytes}B → ${homeSimplified.simplifiedSizeBytes}B, candidate=${homeSimplified.candidateSelector}")

        // ── Step 2: Find a detail-page link from the homepage ──
        val candidateDetailUrl = findFirstCardLink(homeHtml, url, homeSimplified.candidateSelector)
        ReverbLog.i("SiteAnalyzer", "Candidate detail URL: $candidateDetailUrl")

        // ── Step 3: Fetch the detail page (if found) ──
        var detailSimplified: HtmlSimplifier.SimplifiedHtml? = null
        var detailHtml: String? = null
        if (candidateDetailUrl != null) {
            detailHtml = fetchHtml(candidateDetailUrl)
            if (detailHtml != null) {
                detailSimplified = HtmlSimplifier.simplify(detailHtml, detectCandidates = true)
                ReverbLog.i("SiteAnalyzer", "Detail page simplified: ${detailSimplified.simplifiedSizeBytes}B, candidate=${detailSimplified.candidateSelector}")
            }
        }

        // ── Step 4: Find an episode link from the detail page ──
        var episodeSimplified: HtmlSimplifier.SimplifiedHtml? = null
        if (detailHtml != null && detailSimplified != null) {
            val episodeUrl = findFirstEpisodeLink(detailHtml, candidateDetailUrl!!, detailSimplified.candidateSelector)
            if (episodeUrl != null) {
                ReverbLog.i("SiteAnalyzer", "Candidate episode URL: $episodeUrl")
                val episodeHtml = fetchHtml(episodeUrl)
                if (episodeHtml != null) {
                    episodeSimplified = HtmlSimplifier.simplify(episodeHtml, detectCandidates = false)
                    ReverbLog.i("SiteAnalyzer", "Episode page simplified: ${episodeSimplified.simplifiedSizeBytes}B")
                }
            }
        }

        // ── Step 5: Send all pages to the LLM ──
        var lastError: String? = null
        for (attempt in 1..maxRetries) {
            ReverbLog.d("SiteAnalyzer", "LLM attempt $attempt/$maxRetries")

            val userPrompt = buildMultiPagePrompt(url, homeSimplified, detailSimplified, episodeSimplified, lastError)
            val llmResponse = try {
                llmClient.complete(SYSTEM_PROMPT, userPrompt)
            } catch (e: Exception) {
                ReverbLog.e("SiteAnalyzer", "LLM call failed on attempt $attempt: ${e.message}", e)
                lastError = "LLM error: ${e.message}"
                continue
            }

            val config = parseLlmResponse(llmResponse, host, url) ?: run {
                lastError = "Invalid JSON from LLM"
                continue
            }

            // Validate selectors against the pages we fetched.
            val errors = validateSelectorsMultiPage(config, homeHtml, detailHtml)
            if (errors.isEmpty()) {
                ReverbLog.i("SiteAnalyzer", "Analysis SUCCESS on attempt $attempt — config saved for $host")
                val finalConfig = config.copy(lastValidatedAt = System.currentTimeMillis())
                dataRepository.saveLearnedSite(finalConfig)
                return@withContext finalConfig
            } else {
                lastError = errors.joinToString("; ")
                ReverbLog.w("SiteAnalyzer", "Validation failed on attempt $attempt: $lastError")
            }
        }

        ReverbLog.e("SiteAnalyzer", "Analysis FAILED after $maxRetries attempts for $url")
        null
    }

    /** Find the first link inside a catalog card — this is likely the detail page URL. */
    private fun findFirstCardLink(html: String, baseUrl: String, cardSelector: String?): String? {
        val doc = Jsoup.parse(html, baseUrl)
        val selector = cardSelector ?: run {
            // Try common card selectors.
            val candidates = listOf("div.poster", "div.ani.poster", "div.movie-card", "article", "div.card", "div.item")
            candidates.firstOrNull { doc.select(it).isNotEmpty() } ?: return null
        }
        val firstCard = doc.selectFirst(selector) ?: return null
        val link = firstCard.selectFirst("a") ?: firstCard.tagName("a")
        val href = link.absUrl("href").ifBlank { link.attr("href") }
        return resolveUrl(baseUrl, href).takeIf { it.isNotBlank() }
    }

    /** Find the first episode link on a detail page. */
    private fun findFirstEpisodeLink(html: String, baseUrl: String, episodeSelector: String?): String? {
        val doc = Jsoup.parse(html, baseUrl)
        // Try the candidate selector first, then common episode selectors.
        val selectors = listOfNotNull(
            episodeSelector,
            "a[href*=episode]", "a[href*=ep-]", "a[href*=watch]", "a[href*=play]",
            "li a", "div.ep a", "div.epl a", "ul.episodes a",
        )
        for (selector in selectors) {
            if (selector.isNullOrBlank()) continue
            val elements = doc.select(selector)
            if (elements.isNotEmpty()) {
                val firstEl = elements.first() ?: continue
                val href = firstEl.absUrl("href").ifBlank { firstEl.attr("href") }
                if (href.isNotBlank()) {
                    return resolveUrl(baseUrl, href)
                }
            }
        }
        return null
    }

    private fun buildMultiPagePrompt(
        baseUrl: String,
        home: HtmlSimplifier.SimplifiedHtml,
        detail: HtmlSimplifier.SimplifiedHtml?,
        episode: HtmlSimplifier.SimplifiedHtml?,
        lastError: String?,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("Analyze this video-streaming website and identify CSS selectors for scraping it into a native Android app.")
        sb.appendLine()
        sb.appendLine("Base URL: $baseUrl")
        sb.appendLine()
        sb.appendLine("=== PAGE 1: HOMEPAGE (catalog grid) ===")
        sb.appendLine("Title: ${home.title}")
        sb.appendLine("Candidate card selector: ${home.candidateSelector} (count: ${home.candidateCount})")
        sb.appendLine("Simplified HTML:")
        sb.appendLine(home.compactHtml.take(2500))
        sb.appendLine()

        if (detail != null) {
            sb.appendLine("=== PAGE 2: DETAILS PAGE (anime/show info + episode list) ===")
            sb.appendLine("Title: ${detail.title}")
            sb.appendLine("Candidate episode selector: ${detail.candidateSelector} (count: ${detail.candidateCount})")
            sb.appendLine("Simplified HTML:")
            sb.appendLine(detail.compactHtml.take(2500))
            sb.appendLine()
        }

        if (episode != null) {
            sb.appendLine("=== PAGE 3: EPISODE/WATCH PAGE (video player) ===")
            sb.appendLine("Title: ${episode.title}")
            sb.appendLine("Simplified HTML:")
            sb.appendLine(episode.compactHtml.take(1500))
            sb.appendLine()
        }

        if (lastError != null) {
            sb.appendLine("Previous attempt failed with these errors:")
            sb.appendLine(lastError)
            sb.appendLine("Please fix the selectors and try again.")
            sb.appendLine()
        }

        sb.appendLine("Output STRICT JSON with these fields:")
        sb.appendLine("""{"catalogSelector":"CSS selector for each card on the HOMEPAGE grid","cardTitleSelector":"relative to card, for the title text","cardThumbnailSelector":"relative to card, for the thumbnail img (use img src)","cardUrlSelector":"relative to card, for the link to the DETAILS PAGE (not episode page!)","detailsUrlPattern":"regex matching details-page URLs","detailsPosterSelector":"poster img on details page","detailsSynopsisSelector":"synopsis/description text on details page","episodeListSelector":"each episode link on the DETAILS PAGE","episodeUrlPattern":"regex matching episode/watch URLs","searchUrlPattern":"URL pattern for search (use {query} as placeholder, e.g. https://site.com/search?q={query})","videoExtractorHint":"universal"}""")
        sb.appendLine()
        sb.appendLine("IMPORTANT:")
        sb.appendLine("- cardUrlSelector must link to the DETAILS page (with episode list), NOT directly to a watch/episode page")
        sb.appendLine("- Details pages typically have a URL like /anime/{slug} or /title/{id}")
        sb.appendLine("- Episode/watch pages typically have a URL like /watch/{slug}/{ep} or /play/{id}")
        sb.appendLine("- If the site has search, set searchUrlPattern with {query} as the placeholder")
        sb.appendLine("- Only output the JSON, nothing else")
        return sb.toString()
    }

    private fun parseLlmResponse(response: String, host: String, baseUrl: String): LearnedSiteConfig? {
        val jsonStr = extractJson(response) ?: run {
            ReverbLog.e("SiteAnalyzer", "Could not extract JSON from LLM response: ${response.take(200)}")
            return null
        }
        return try {
            val obj = json.parseToJsonElement(jsonStr) as kotlinx.serialization.json.JsonObject
            LearnedSiteConfig(
                id = host,
                baseUrl = baseUrl,
                name = host,
                catalogSelector = obj["catalogSelector"]?.toString()?.trim('"')?.takeIf { it.isNotBlank() && it != "null" },
                cardTitleSelector = obj["cardTitleSelector"]?.toString()?.trim('"')?.takeIf { it.isNotBlank() && it != "null" },
                cardThumbnailSelector = obj["cardThumbnailSelector"]?.toString()?.trim('"')?.takeIf { it.isNotBlank() && it != "null" },
                cardUrlSelector = obj["cardUrlSelector"]?.toString()?.trim('"')?.takeIf { it.isNotBlank() && it != "null" },
                detailsUrlPattern = obj["detailsUrlPattern"]?.toString()?.trim('"')?.takeIf { it.isNotBlank() && it != "null" },
                detailsPosterSelector = obj["detailsPosterSelector"]?.toString()?.trim('"')?.takeIf { it.isNotBlank() && it != "null" },
                detailsSynopsisSelector = obj["detailsSynopsisSelector"]?.toString()?.trim('"')?.takeIf { it.isNotBlank() && it != "null" },
                episodeListSelector = obj["episodeListSelector"]?.toString()?.trim('"')?.takeIf { it.isNotBlank() && it != "null" },
                episodeUrlPattern = obj["episodeUrlPattern"]?.toString()?.trim('"')?.takeIf { it.isNotBlank() && it != "null" },
                searchUrlPattern = obj["searchUrlPattern"]?.toString()?.trim('"')?.takeIf { it.isNotBlank() && it != "null" },
                videoExtractorHint = obj["videoExtractorHint"]?.toString()?.trim('"') ?: "universal",
            )
        } catch (e: Exception) {
            ReverbLog.e("SiteAnalyzer", "Failed to parse LLM JSON: ${e.message}", e)
            null
        }
    }

    private fun extractJson(text: String): String? {
        var depth = 0
        var start = -1
        for (i in text.indices) {
            when (text[i]) {
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> { depth--; if (depth == 0 && start >= 0) return text.substring(start, i + 1) }
            }
        }
        return null
    }

    private fun validateSelectorsMultiPage(config: LearnedSiteConfig, homeHtml: String, detailHtml: String?): List<String> {
        val errors = mutableListOf<String>()
        val homeDoc = Jsoup.parse(homeHtml)

        config.catalogSelector?.let { selector ->
            if (selector.isNotBlank()) {
                val elements = homeDoc.select(selector)
                if (elements.isEmpty()) {
                    errors.add("catalogSelector '$selector' returned 0 matches on homepage (available: ${summarizeRepeatedElements(homeDoc)})")
                } else {
                    ReverbLog.d("SiteAnalyzer", "Validation: catalogSelector '$selector' → ${elements.size} matches ✓")
                }
            }
        }

        // Validate episode selector against the detail page (if we fetched one).
        if (detailHtml != null) {
            val detailDoc = Jsoup.parse(detailHtml)
            config.episodeListSelector?.let { selector ->
                if (selector.isNotBlank()) {
                    val elements = detailDoc.select(selector)
                    if (elements.isEmpty()) {
                        errors.add("episodeListSelector '$selector' returned 0 matches on detail page (available: ${summarizeRepeatedElements(detailDoc)})")
                    } else {
                        ReverbLog.d("SiteAnalyzer", "Validation: episodeListSelector '$selector' → ${elements.size} matches ✓")
                    }
                }
            }
        }

        return errors
    }

    private fun summarizeRepeatedElements(doc: Document): String {
        val counts = HashMap<String, Int>()
        doc.select("div, article, li, a").forEach { el ->
            val cls = el.className()
            if (cls.isNotBlank()) {
                val tag = el.tagName()
                val firstClass = cls.split(' ').first()
                val key = "$tag.$firstClass"
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
        return counts.entries
            .filter { it.value >= 3 }
            .sortedByDescending { it.value }
            .take(8)
            .joinToString(", ") { "${it.key}(${it.value})" }
    }

    private fun fetchHtml(url: String): String? = try {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", app.reverb.core.network.UserAgentInterceptor.DEFAULT_UA)
            .build()
        httpClient.newCall(request).execute().use { resp ->
            resp.body?.string()
        }
    } catch (e: Exception) {
        ReverbLog.e("SiteAnalyzer", "Fetch HTML failed for $url: ${e.message}", e)
        null
    }

    private fun resolveUrl(base: String, relative: String): String {
        if (relative.startsWith("http://") || relative.startsWith("https://")) return relative
        return try { java.net.URI(base).resolve(relative).toString() } catch (e: Exception) { relative }
    }

    companion object {
        const val SYSTEM_PROMPT = """You are a web-scraping expert. Given the simplified HTML of multiple page types from a video-streaming website, identify CSS selectors for scraping it into a native Android app UI.

The app needs to:
1. Show a catalog grid (homepage) → user taps a card → opens details page
2. Show details page (poster, synopsis, episode list) → user taps an episode → plays video
3. Search for content

Output STRICT JSON (no markdown, no explanation) with these fields:
{
  "catalogSelector": "CSS selector for each card on the HOMEPAGE grid",
  "cardTitleSelector": "CSS selector relative to card, for the title text",
  "cardThumbnailSelector": "CSS selector relative to card, for the thumbnail img",
  "cardUrlSelector": "CSS selector relative to card, for the link to the DETAILS PAGE (not episode page)",
  "detailsUrlPattern": "regex matching details-page URLs",
  "detailsPosterSelector": "CSS selector for poster img on details page",
  "detailsSynopsisSelector": "CSS selector for synopsis text on details page",
  "episodeListSelector": "CSS selector for each episode link on the DETAILS PAGE",
  "episodeUrlPattern": "regex matching episode/watch URLs",
  "searchUrlPattern": "URL pattern for search with {query} placeholder",
  "videoExtractorHint": "universal"
}

CRITICAL RULES:
- cardUrlSelector MUST link to the DETAILS page (which has the episode list), NOT to a watch/episode page
- Details pages usually have URLs like /anime/{slug} or /title/{id}
- Episode/watch pages usually have URLs like /watch/{slug}/{ep} or /play/{id}
- Use standard CSS selectors that work in Jsoup
- Relative selectors (card fields) should be relative to the catalogSelector element
- If you cannot identify a field, output null — do not guess
- Only output the JSON object"""
    }
}
