package app.reverb.adblock

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * THE release-gate test for the ad-blocker extractor-non-interference contract.
 *
 * Reference: PLAN.md §16.4 + R11.
 *
 * If any test in this file fails, the build MUST fail. The ad-blocker must NEVER block
 * a video URL — that would break both playback and download.
 */
class AdBlockerContractTest {

    private val matcher = KotlinRegexMatcher(KotlinRegexMatcher.STARTER_RULES)

    // ── Video URLs MUST NEVER be blocked ──────────────────────────────────────

    @Test
    fun `mp4 url is never blocked`() {
        val url = "https://cdn.example.com/video/episode-01.mp4?token=abc123"
        val verdict = matcher.checkNetwork(url, AdMatcher.RequestType.OTHER, acceptHeader = null)
        assertEquals(AdMatcher.Verdict.ALLOW, verdict)
    }

    @Test
    fun `m3u8 url is never blocked`() {
        val url = "https://hls.example.com/master.m3u8"
        val verdict = matcher.checkNetwork(url, AdMatcher.RequestType.OTHER, acceptHeader = null)
        assertEquals(AdMatcher.Verdict.ALLOW, verdict)
    }

    @Test
    fun `mpd url is never blocked`() {
        val url = "https://dash.example.com/manifest.mpd"
        val verdict = matcher.checkNetwork(url, AdMatcher.RequestType.OTHER, acceptHeader = null)
        assertEquals(AdMatcher.Verdict.ALLOW, verdict)
    }

    @Test
    fun `ts segment url is never blocked`() {
        val url = "https://hls.example.com/segment-0042.ts"
        val verdict = matcher.checkNetwork(url, AdMatcher.RequestType.OTHER, acceptHeader = null)
        assertEquals(AdMatcher.Verdict.ALLOW, verdict)
    }

    @Test
    fun `webm url is never blocked`() {
        val url = "https://cdn.example.com/video/clip.webm"
        val verdict = matcher.checkNetwork(url, AdMatcher.RequestType.OTHER, acceptHeader = null)
        assertEquals(AdMatcher.Verdict.ALLOW, verdict)
    }

    @Test
    fun `m4s segment url is never blocked`() {
        val url = "https://dash.example.com/segment-001.m4s"
        val verdict = matcher.checkNetwork(url, AdMatcher.RequestType.OTHER, acceptHeader = null)
        assertEquals(AdMatcher.Verdict.ALLOW, verdict)
    }

    // ── MEDIA request type is never blocked, regardless of URL ────────────────

    @Test
    fun `MEDIA request type is never blocked even on ad-domain-looking url`() {
        val url = "https://doubleclick.net/some-path/video.mp4"
        val verdict = matcher.checkNetwork(url, AdMatcher.RequestType.MEDIA, acceptHeader = null)
        assertEquals(AdMatcher.Verdict.ALLOW, verdict)
    }

    // ── Accept-header-based allowlist ─────────────────────────────────────────

    @Test
    fun `url with video Accept header is never blocked`() {
        val url = "https://doubleclick.net/track"
        val verdict = matcher.checkNetwork(url, AdMatcher.RequestType.OTHER, acceptHeader = "video/mp4")
        assertEquals(AdMatcher.Verdict.ALLOW, verdict)
    }

    @Test
    fun `url with audio Accept header is never blocked`() {
        val url = "https://doubleclick.net/audio"
        val verdict = matcher.checkNetwork(url, AdMatcher.RequestType.OTHER, acceptHeader = "audio/mpeg")
        assertEquals(AdMatcher.Verdict.ALLOW, verdict)
    }

    @Test
    fun `url with MPEGURL Accept header is never blocked`() {
        val url = "https://doubleclick.net/stream"
        val verdict = matcher.checkNetwork(url, AdMatcher.RequestType.OTHER, acceptHeader = "application/vnd.apple.mpegurl")
        assertEquals(AdMatcher.Verdict.ALLOW, verdict)
    }

    // ── Ads ARE still blocked (the contract is one-directional) ───────────────

    @Test
    fun `doubleclick script is blocked`() {
        val url = "https://doubleclick.net/gampad/ads?sz=640x480"
        val verdict = matcher.checkNetwork(url, AdMatcher.RequestType.SCRIPT, acceptHeader = "application/javascript")
        assertEquals(AdMatcher.Verdict.BLOCK, verdict)
    }

    @Test
    fun `googlesyndication script is blocked`() {
        val url = "https://googlesyndication.com/pagead/js/adsbygoogle.js"
        val verdict = matcher.checkNetwork(url, AdMatcher.RequestType.SCRIPT, acceptHeader = "application/javascript")
        assertEquals(AdMatcher.Verdict.BLOCK, verdict)
    }

    @Test
    fun `taboola script is blocked`() {
        val url = "https://cdn.taboola.com/libtrc/loader.js"
        val verdict = matcher.checkNetwork(url, AdMatcher.RequestType.SCRIPT, acceptHeader = null)
        assertEquals(AdMatcher.Verdict.BLOCK, verdict)
    }

    // ── Cosmetic filters ──────────────────────────────────────────────────────

    @Test
    fun `cosmetic CSS is generated for known ad selectors`() {
        val css = matcher.cosmeticCssFor("https://example.com")
        // Should include the .ad, .ads, .adsbygoogle selectors from STARTER_RULES.
        assert(css.contains(".ad") || css.contains(".ads") || css.contains(".adsbygoogle")) {
            "Expected cosmetic CSS to include ad selectors, got: $css"
        }
    }

    @Test
    fun `cosmetic CSS hides ad iframes by src`() {
        val css = matcher.cosmeticCssFor("https://example.com")
        assert(css.contains("iframe") || css.contains("[id")) {
            "Expected cosmetic CSS to include iframe/id selectors, got: $css"
        }
    }
}
