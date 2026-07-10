package app.reverb.core.html

import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlSimplifierTest {

    @Test
    fun `simplify strips scripts and styles`() {
        val html = """
            <html><head><title>Test Page</title>
            <style>.ad { color: red; }</style>
            <script>var x = 1; fetch('/api/track');</script>
            </head><body>
            <div class="nav"><a href="/">Home</a></div>
            <div class="movie-card"><img src="/a.jpg"><a href="/movie/1">Movie 1</a></div>
            <div class="movie-card"><img src="/b.jpg"><a href="/movie/2">Movie 2</a></div>
            <div class="movie-card"><img src="/c.jpg"><a href="/movie/3">Movie 3</a></div>
            </body></html>
        """.trimIndent()

        val result = HtmlSimplifier.simplify(html)
        assertTrue("Title should be preserved", result.title == "Test Page")
        assertTrue("Scripts should be removed", !result.compactHtml.contains("<script"))
        assertTrue("Styles should be removed", !result.compactHtml.contains("<style"))
        assertTrue("Candidate selector should be detected", result.candidateSelector == "div.movie-card")
        assertTrue("Should find 3 cards", result.candidateCount == 3)
        assertTrue("Simplified should be smaller than original", result.simplifiedSizeBytes < result.originalSizeBytes)
    }

    @Test
    fun `simplify preserves iframe src as data attribute`() {
        val html = """
            <html><body>
            <iframe src="https://vidstream.example/embed/123"></iframe>
            </body></html>
        """.trimIndent()

        val result = HtmlSimplifier.simplify(html, detectCandidates = false)
        assertTrue("iframe src should be preserved as data-iframe-src",
            result.fullSimplifiedHtml.contains("data-iframe-src=\"https://vidstream.example/embed/123\""))
    }

    @Test
    fun `simplify prunes non-essential attributes`() {
        val html = """
            <html><body>
            <div class="card" style="color:red" onclick="track()" data-id="42">
              <a href="/x" title="hover" target="_blank">link</a>
            </div>
            </body></html>
        """.trimIndent()

        val result = HtmlSimplifier.simplify(html, detectCandidates = false)
        assertTrue("class should be kept", result.fullSimplifiedHtml.contains("class=\"card\""))
        assertTrue("href should be kept", result.fullSimplifiedHtml.contains("href=\"/x\""))
        assertTrue("data-id should be kept", result.fullSimplifiedHtml.contains("data-id=\"42\""))
        assertTrue("style should be removed", !result.fullSimplifiedHtml.contains("style="))
        assertTrue("onclick should be removed", !result.fullSimplifiedHtml.contains("onclick="))
        assertTrue("title should be removed", !result.fullSimplifiedHtml.contains("title="))
        assertTrue("target should be removed", !result.fullSimplifiedHtml.contains("target="))
    }
}
