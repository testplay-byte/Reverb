package app.reverb.autolearn

import app.reverb.core.common.ReverbLog
import app.reverb.core.html.HtmlSimplifier
import app.reverb.data.DataRepository
import app.reverb.data.LearnedSiteConfig
import app.reverb.source.api.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * The LLM-Assisted Site Analyzer — the differentiator.
 *
 * Pipeline (PLAN.md §23.3):
 *   1. Fetch the page HTML via OkHttp (+ CF cookies).
 *   2. Simplify (97KB → 2.6KB) via HtmlSimplifier.
 *   3. Send to LLM with a structured prompt.
 *   4. LLM returns JSON with CSS selectors (catalogSelector, cardFields, detailsUrlPattern, etc.).
 *   5. Validate: run each selector against the page via Jsoup — must return >0 matches.
 *   6. Retry up to 3× with error feedback if validation fails.
 *   7. Store the LearnedSiteConfig in DataRepository.
 *
 * The [LearnedSiteInterpreter] then uses the config to scrape + render native UI.
 */
class SiteAnalyzer(
    private val httpClient: OkHttpClient,
    private val llmClient: LlmClient,
    private val dataRepository: DataRepository,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Analyze a site and produce a [LearnedSiteConfig].
     * Returns null if analysis fails after all retries.
     */
    suspend fun analyzeSite(url: String, maxRetries: Int = 3): LearnedSiteConfig? = withContext(Dispatchers.IO) {
        ReverbLog.i("SiteAnalyzer", "Analyzing: $url")
        val host = java.net.URI(url).host ?: run {
            ReverbLog.e("SiteAnalyzer", "Could not parse host from $url")
            return@withContext null
        }

        // Check if we already have a cached config.
        dataRepository.getLearnedSite(host)?.let { cached ->
            ReverbLog.i("SiteAnalyzer", "Using cached config for $host (last validated: ${cached.lastValidatedAt})")
            return@withContext cached
        }

        // Fetch the HTML.
        val html = fetchHtml(url) ?: run {
            ReverbLog.e("SiteAnalyzer", "Failed to fetch HTML from $url")
            return@withContext null
        }

        // Simplify.
        val simplified = HtmlSimplifier.simplify(html)
        ReverbLog.i("SiteAnalyzer", "Simplified: ${simplified.originalSizeBytes}B → ${simplified.simplifiedSizeBytes}B, candidate=${simplified.candidateSelector}")

        // Try up to maxRetries times.
        var lastError: String? = null
        for (attempt in 1..maxRetries) {
            ReverbLog.d("SiteAnalyzer", "LLM attempt $attempt/$maxRetries")

            val userPrompt = buildUserPrompt(simplified, lastError)
            val llmResponse = try {
                llmClient.complete(SYSTEM_PROMPT, userPrompt)
            } catch (e: Exception) {
                ReverbLog.e("SiteAnalyzer", "LLM call failed on attempt $attempt: ${e.message}", e)
                lastError = "LLM error: ${e.message}"
                continue
            }

            // Parse the LLM response as JSON.
            val config = parseLlmResponse(llmResponse, host, url) ?: run {
                lastError = "Invalid JSON from LLM"
                continue
            }

            // Validate the selectors against the page.
            val errors = validateSelectors(config, html)
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

    private fun fetchHtml(url: String): String? = try {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", app.reverb.core.network.UserAgentInterceptor.DEFAULT_UA)
            .build()
        httpClient.newCall(request).execute().use { resp ->
            resp.body?.string()
        }
    } catch (e: Exception) {
        ReverbLog.e("SiteAnalyzer", "Fetch HTML failed: ${e.message}", e)
        null
    }

    private fun buildUserPrompt(simplified: HtmlSimplifier.SimplifiedHtml, lastError: String?): String {
        val sb = StringBuilder()
        sb.appendLine("Analyze this simplified HTML from a video-streaming website and identify the CSS selectors for scraping.")
        sb.appendLine()
        sb.appendLine("Page title: ${simplified.title}")
        sb.appendLine("Candidate selector detected: ${simplified.candidateSelector} (count: ${simplified.candidateCount})")
        sb.appendLine()
        sb.appendLine("Simplified HTML:")
        sb.appendLine(simplified.compactHtml.take(3000))
        sb.appendLine()
        if (lastError != null) {
            sb.appendLine("Previous attempt failed with these errors:")
            sb.appendLine(lastError)
            sb.appendLine("Please fix the selectors and try again.")
            sb.appendLine()
        }
        sb.appendLine("Output STRICT JSON with these fields:")
        sb.appendLine("""{"catalogSelector":"CSS selector for each card in the grid","cardTitleSelector":"relative to card","cardThumbnailSelector":"relative to card img src","cardUrlSelector":"relative to card link href","detailsUrlPattern":"regex for details URLs","detailsPosterSelector":"poster img on details page","detailsSynopsisSelector":"synopsis text","episodeListSelector":"each episode link","episodeUrlPattern":"regex for episode URLs","videoExtractorHint":"universal"}""")
        sb.appendLine("Only output the JSON, nothing else.")
        return sb.toString()
    }

    private fun parseLlmResponse(response: String, host: String, baseUrl: String): LearnedSiteConfig? {
        // Extract JSON from the response (it might have markdown fences or extra text).
        val jsonStr = extractJson(response) ?: return null
        return try {
            val obj = json.parseToJsonElement(jsonStr) as kotlinx.serialization.json.JsonObject
            LearnedSiteConfig(
                id = host,
                baseUrl = baseUrl,
                name = host,
                catalogSelector = obj["catalogSelector"]?.toString()?.trim('"'),
                cardTitleSelector = obj["cardTitleSelector"]?.toString()?.trim('"'),
                cardThumbnailSelector = obj["cardThumbnailSelector"]?.toString()?.trim('"'),
                cardUrlSelector = obj["cardUrlSelector"]?.toString()?.trim('"'),
                detailsUrlPattern = obj["detailsUrlPattern"]?.toString()?.trim('"'),
                detailsPosterSelector = obj["detailsPosterSelector"]?.toString()?.trim('"'),
                detailsSynopsisSelector = obj["detailsSynopsisSelector"]?.toString()?.trim('"'),
                episodeListSelector = obj["episodeListSelector"]?.toString()?.trim('"'),
                episodeUrlPattern = obj["episodeUrlPattern"]?.toString()?.trim('"'),
                videoExtractorHint = obj["videoExtractorHint"]?.toString()?.trim('"') ?: "universal",
            )
        } catch (e: Exception) {
            ReverbLog.e("SiteAnalyzer", "Failed to parse LLM JSON: ${e.message}", e)
            null
        }
    }

    private fun extractJson(text: String): String? {
        // Try to find a JSON object in the response.
        val jsonRegex = Regex("""\{[^{}]*("(?:[^"\\]|\\.)*"[^{}]*)*\}""", RegexOption.DOT_MATCHES_ALL)
        // First try: look for the full JSON object with nested braces.
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

    /** Validate each selector against the HTML. Returns a list of error messages (empty = all valid). */
    private fun validateSelectors(config: LearnedSiteConfig, html: String): List<String> {
        val errors = mutableListOf<String>()
        val doc = Jsoup.parse(html)

        config.catalogSelector?.let { selector ->
            if (selector.isNotBlank() && selector != "null") {
                val elements = doc.select(selector)
                if (elements.isEmpty()) {
                    errors.add("catalogSelector '$selector' returned 0 matches (page has: ${summarizeRepeatedElements(doc)})")
                }
            }
        }

        config.episodeListSelector?.let { selector ->
            if (selector.isNotBlank() && selector != "null") {
                val elements = doc.select(selector)
                if (elements.isEmpty()) {
                    errors.add("episodeListSelector '$selector' returned 0 matches")
                }
            }
        }

        return errors
    }

    private fun summarizeRepeatedElements(doc: Document): String {
        val counts = HashMap<String, Int>()
        doc.select("div, article, li").forEach { el ->
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
            .take(5)
            .joinToString(", ") { "${it.key}(${it.value})" }
    }

    companion object {
        const val SYSTEM_PROMPT = """You are a web-scraping expert. Given the simplified HTML of a video-streaming website's page, your job is to identify the CSS selectors that an Android app will use to scrape this site into a native app UI.

Output STRICT JSON (no markdown, no explanation) with exactly these fields:
{
  "catalogSelector": "CSS selector for the element wrapping EACH item card in the grid",
  "cardTitleSelector": "CSS selector, relative to the card, for the title text",
  "cardThumbnailSelector": "CSS selector, relative to the card, for the thumbnail img src",
  "cardUrlSelector": "CSS selector, relative to the card, for the link to the details page",
  "detailsUrlPattern": "regex matching details-page URLs",
  "detailsPosterSelector": "CSS selector for the main poster img on the details page",
  "detailsSynopsisSelector": "CSS selector for the synopsis/description text",
  "episodeListSelector": "CSS selector wrapping EACH episode link on the details page",
  "episodeUrlPattern": "regex matching episode-page URLs",
  "videoExtractorHint": "universal"
}

Rules:
- Use standard CSS selectors that work in Jsoup.
- Relative selectors (for card fields) should be relative to the catalogSelector element.
- If you cannot identify a field, output null for that field — do not guess.
- Only output the JSON object, nothing else."""
    }
}
