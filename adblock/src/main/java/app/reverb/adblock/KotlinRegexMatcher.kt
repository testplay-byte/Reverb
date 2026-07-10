package app.reverb.adblock

import app.reverb.core.common.UrlUtils

/**
 * The ad-blocker contract. Implementations match request URLs against filter lists
 * (EasyList / EasyPrivacy / AdGuard / uBlock Origin format).
 *
 * ⚠️ CRITICAL INVARIANT (PLAN.md §16.4):
 *   The matcher MUST NEVER block a video URL. This is enforced inside [checkNetwork] via
 *   three layers:
 *     1. If the URL matches [UrlUtils.VIDEO_URL_REGEX] → NOT_BLOCKED.
 *     2. If the Accept header advertises a media type → NOT_BLOCKED.
 *     3. (Parse-time) rules of type $media targeting trusted CDN hosts are dropped.
 *
 *   A release-gate test enforces this — any video URL in the test set that gets blocked
 *   fails the build.
 */
interface AdMatcher {

    /**
     * Check whether a network request should be blocked.
     * Returns a [Verdict] — the caller acts on it (return 204 in OkHttp, empty
     * WebResourceResponse in WebView).
     */
    fun checkNetwork(url: String, requestType: RequestType, acceptHeader: String?): Verdict

    /**
     * Cosmetic filters (## selectors) to inject as CSS into the WebView.
     * Returns a CSS string that hides ad placeholders.
     */
    fun cosmeticCssFor(url: String): String

    enum class Verdict { ALLOW, BLOCK }

    enum class RequestType {
        DOCUMENT, SCRIPT, IMAGE, STYLESHEET, XHR, SUBDOCUMENT, MEDIA, FONT, OTHER
    }
}

/**
 * A trivial Kotlin-regex-based ad matcher for Phase 0.
 * Parses a subset of EasyList syntax: ||domain^, $type filters, @@exceptions, ## cosmetic.
 * Phase 2 will swap this for Brave's adblock-rust via JNI (same interface).
 *
 * This is intentionally minimal — enough to prove the contract works end-to-end.
 */
class KotlinRegexMatcher(
    initialRules: List<String> = emptyList(),
) : AdMatcher {

    // Network rules: (hostPattern, pathRegex, typeFilter?, isException)
    private val networkRules = mutableListOf<NetworkRule>()
    // Cosmetic rules: (hostPattern, cssSelector)
    private val cosmeticRules = mutableListOf<CosmeticRule>()

    init {
        initialRules.forEach { addRule(it) }
    }

    fun addRule(line: String) {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("!") || trimmed.startsWith("# Adblock")) return

        // Cosmetic rule: domain##selector or just ##selector
        if (trimmed.contains("##")) {
            val hashIdx = trimmed.indexOf("##")
            val hostPart = trimmed.substring(0, hashIdx)
            val selector = trimmed.substring(hashIdx + 2)
            if (selector.isNotBlank()) {
                val hosts = if (hostPart.isBlank()) emptyList() else hostPart.split(",").map { it.trim() }
                cosmeticRules.add(CosmeticRule(hosts, selector))
            }
            return
        }

        // Network rule.
        val isException = trimmed.startsWith("@@")
        val body = if (isException) trimmed.substring(2) else trimmed

        // Split on $ to separate the URL pattern from type filters.
        val dollarIdx = body.indexOf('$')
        val urlPart = if (dollarIdx >= 0) body.substring(0, dollarIdx) else body
        val filterPart = if (dollarIdx >= 0) body.substring(dollarIdx + 1) else ""

        val typeFilters = if (filterPart.isNotBlank()) filterPart.split(",").toSet() else emptySet()

        // ||domain^ style: anchored to domain.
        if (urlPart.startsWith("||")) {
            val pattern = urlPart.substring(2).trimEnd('^', '*', '|')
            networkRules.add(NetworkRule(pattern, typeFilters, isException))
        } else if (urlPart.startsWith("|") || urlPart.startsWith("/")) {
            // Less common patterns — store the literal.
            networkRules.add(NetworkRule(urlPart.trimStart('|'), typeFilters, isException))
        }
    }

    override fun checkNetwork(url: String, requestType: AdMatcher.RequestType, acceptHeader: String?): AdMatcher.Verdict {
        // ── THE CRITICAL CONTRACT: never block video URLs ─────────────────────────
        if (UrlUtils.isVideoUrl(url)) return AdMatcher.Verdict.ALLOW
        if (requestType == AdMatcher.RequestType.MEDIA) return AdMatcher.Verdict.ALLOW
        if (UrlUtils.isMediaAcceptHeader(acceptHeader)) return AdMatcher.Verdict.ALLOW
        // ───────────────────────────────────────────────────────────────────────────

        val host = UrlUtils.host(url) ?: return AdMatcher.Verdict.ALLOW

        var blocked = false
        for (rule in networkRules) {
            if (rule.isException && matchesHost(host, rule.hostPattern)) {
                return AdMatcher.Verdict.ALLOW // exception wins
            }
            if (!rule.isException && matchesHost(host, rule.hostPattern)) {
                if (rule.typeFilters.isEmpty() || typeMatches(requestType, rule.typeFilters)) {
                    blocked = true
                }
            }
        }
        return if (blocked) AdMatcher.Verdict.BLOCK else AdMatcher.Verdict.ALLOW
    }

    override fun cosmeticCssFor(url: String): String {
        val host = UrlUtils.host(url) ?: return ""
        val selectors = cosmeticRules
            .filter { rule -> rule.hosts.isEmpty() || rule.hosts.any { matchesHost(host, it) } }
            .joinToString(", ") { it.cssSelector }
        return if (selectors.isNotBlank()) "$selectors { display: none !important; }" else ""
    }

    private fun matchesHost(host: String, pattern: String): Boolean {
        if (pattern.isBlank()) return true
        // Wildcard domain match: example.com matches ads.example.com and example.com
        return host == pattern || host.endsWith(".$pattern")
    }

    private fun typeMatches(requestType: AdMatcher.RequestType, filters: Set<String>): Boolean {
        // EasyList type filters: $script, $image, $stylesheet, $xhr, $subdocument, $media, $popup, $document
        return filters.any { f ->
            when (f.lowercase().substringBefore('=')) {
                "script" -> requestType == AdMatcher.RequestType.SCRIPT
                "image" -> requestType == AdMatcher.RequestType.IMAGE
                "stylesheet", "css" -> requestType == AdMatcher.RequestType.STYLESHEET
                "xhr", "xmlhttprequest" -> requestType == AdMatcher.RequestType.XHR
                "subdocument", "frame" -> requestType == AdMatcher.RequestType.SUBDOCUMENT
                "media" -> requestType == AdMatcher.RequestType.MEDIA
                "font" -> requestType == AdMatcher.RequestType.FONT
                "document", "main_frame" -> requestType == AdMatcher.RequestType.DOCUMENT
                "other" -> requestType == AdMatcher.RequestType.OTHER
                "popup" -> false // popups handled elsewhere
                "third-party", "3p" -> true // accept (we don't differentiate in Phase 0)
                else -> true // unknown filter — accept
            }
        }
    }

    private data class NetworkRule(
        val hostPattern: String,
        val typeFilters: Set<String>,
        val isException: Boolean,
    )

    private data class CosmeticRule(
        val hosts: List<String>,
        val cssSelector: String,
    )

    companion object {
        /** A small built-in starter list for Phase 0. Real lists come from EasyList download in Phase 1. */
        val STARTER_RULES = listOf(
            "||doubleclick.net^",
            "||googlesyndication.com^",
            "||googleadservices.com^",
            "||googletagservices.com^",
            "||adservice.google.com^",
            "||amazon-adsystem.com^",
            "||adnxs.com^",
            "||criteo.com^",
            "||criteo.net^",
            "||pubmatic.com^",
            "||rubiconproject.com^",
            "||openx.net^",
            "||moatads.com^",
            "||adsafeprotected.com^",
            "||scorecardresearch.com^",
            "||quantserve.com^",
            "||taboola.com^",
            "||outbrain.com^",
            "||popads.net^",
            "||popcash.net^",
            "||propellerads.com^",
            "||adsterra.com^",
            "##.ad",
            "##.ads",
            "##.ad-banner",
            "##.ad-container",
            "##.ad-wrapper",
            "##.advertisement",
            "##.adsbygoogle",
            "##[id^='google_ads_']",
            "##[id^='div-gpt-ad']",
            "##iframe[src*='doubleclick.net']",
            "##iframe[src*='googlesyndication.com']",
        )
    }
}
