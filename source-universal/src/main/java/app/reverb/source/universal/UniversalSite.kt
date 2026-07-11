package app.reverb.source.universal

import app.reverb.core.common.ReverbLog
import app.reverb.source.api.Capability
import app.reverb.source.api.ResolvedStream
import app.reverb.source.api.Site
import app.reverb.source.api.VideoRef

/**
 * The Universal Site — adapts the [EnhancedUniversalExtractor] to the [Site] contract.
 *
 * Takes a LAZY provider [extractorProvider] so the WebView is NOT created during app startup.
 * The WebView is only created when [resolveVideo] is actually called (from a background coroutine
 * when the user enters a URL).
 *
 * Reference: PLAN.md §18.2 decision tree, step 3.
 */
class UniversalSite(
    private val extractorProvider: () -> EnhancedUniversalExtractor,
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
     * The extractor is created lazily on first call via [extractorProvider].
     */
    override suspend fun resolveVideo(ref: VideoRef): ResolvedStream {
        val extractor = try {
            extractorProvider()
        } catch (e: Exception) {
            ReverbLog.e("UniversalSite", "Failed to create extractor — ${e.message}", e)
            throw IllegalStateException("Could not initialize the video extractor: ${e.message}", e)
        }
        ReverbLog.i("UniversalSite", "Resolving video for ${ref.url}")
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
 *
 * Reference: PLAN.md §23.2 (login-wall detection).
 */
object LoginWallDetector {

    data class Result(val isLoginWall: Boolean, val loginFormAction: String?)

    fun detect(statusCode: Int, locationHeader: String?, html: String): Result {
        if ((statusCode == 401 || statusCode == 403) && locationHeader != null) {
            val loc = locationHeader.lowercase()
            if (loc.contains("login") || loc.contains("signin") || loc.contains("auth")) {
                return Result(true, locationHeader)
            }
        }
        val formMatch = Regex("""<form[^>]*action=["']([^"']*(?:login|signin|auth)[^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(html)
        if (formMatch != null) {
            return Result(true, formMatch.groupValues[1])
        }
        return Result(false, null)
    }
}
