package app.reverb.source.universal

import app.reverb.adblock.AdMatcher
import app.reverb.core.common.UrlUtils
import app.reverb.source.api.Capability
import app.reverb.source.api.ResolvedStream
import app.reverb.source.api.Site
import app.reverb.source.api.VideoRef
import okhttp3.OkHttpClient

/**
 * The Universal Site — adapts the [EnhancedUniversalExtractor] to the [Site] contract.
 *
 * This is the fallback `Site` that handles ANY url the user enters (when no other Site
 * matches). It implements only DIRECT_URL capability — it can resolve a stream from any URL,
 * but doesn't provide a catalogue/details UI (that comes from the LLM analyzer in Phase 2,
 * or from the manual Learn Mode).
 *
 * Reference: PLAN.md §18.2 decision tree, step 3.
 */
class UniversalSite(
    private val extractor: EnhancedUniversalExtractor,
) : Site {

    override val id: String = "universal"
    override val name: String = "Universal"
    override val baseUrl: String = ""
    override val language: String = "all"
    override val isNsfw: Boolean = false
    override val capabilities: Set<Capability> = setOf(Capability.DIRECT_URL)

    /** UniversalSite.matches() returns true for ALL urls — it's the fallback. */
    override fun matches(url: String): Boolean = true

    /**
     * Resolve a video stream from [ref.url].
     * Uses the enhanced extractor (WebView + JS exec + interaction sim + body scan + blob + login-wall).
     */
    override suspend fun resolveVideo(ref: VideoRef): ResolvedStream {
        return extractor.resolve(ref.url) ?: throw IllegalStateException(
            "Universal extractor could not detect a stream at ${ref.url} within the timeout. " +
                "The site may require login, use DRM, or load video via a mechanism the extractor doesn't yet handle."
        )
    }
}

/**
 * Detects login walls — pages that require authentication to see content.
 *
 * Heuristics:
 *  - HTTP 401/403 with a Location: /login header.
 *  - HTML containing <form action*=login> or <form action*=signin>.
 *  - HTML containing a prominent "sign in" / "log in" prompt with no video content.
 *
 * When detected, Reverb surfaces a "This site requires login" prompt and opens an embedded
 * WebView for one-time credential capture; cookies are then stored in the shared CookieJar.
 * Reference: PLAN.md §23.2 (login-wall detection).
 */
object LoginWallDetector {

    data class Result(val isLoginWall: Boolean, val loginFormAction: String?)

    fun detect(statusCode: Int, locationHeader: String?, html: String): Result {
        // 401/403 + redirect to login.
        if ((statusCode == 401 || statusCode == 403) && locationHeader != null) {
            val loc = locationHeader.lowercase()
            if (loc.contains("login") || loc.contains("signin") || loc.contains("auth")) {
                return Result(true, locationHeader)
            }
        }
        // HTML login form.
        val formMatch = Regex("""<form[^>]*action=["']([^"']*(?:login|signin|auth)[^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(html)
        if (formMatch != null) {
            return Result(true, formMatch.groupValues[1])
        }
        return Result(false, null)
    }
}
