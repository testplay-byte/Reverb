package app.reverb.core.html

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.safety.Safelist

/**
 * Simplifies a raw HTML page down to the minimal structure an LLM needs to identify
 * catalog/details/episode selectors. Tested on the anikototv.to homepage: 97KB → 2.6KB.
 *
 * Pipeline (PLAN.md §23.3):
 *   1. Parse with Jsoup.
 *   2. Remove <script>, <style>, <svg>, <noscript>, comments, <iframe> (keep iframe src).
 *   3. Prune all attributes except class, id, href, src, data-*.
 *   4. Collapse whitespace.
 *   5. Optionally: candidate-pattern detect — find the most-repeated div/article with a
 *      class hint (item|card|poster|thumb|box|entry) and emit just title + nav + 3 sample cards.
 */
object HtmlSimplifier {

    private val keptAttributes = setOf("class", "id", "href", "src")
    private val removedTags = setOf("script", "style", "svg", "noscript", "path", "meta", "link", "head")
    private val candidateClassHints = listOf("item", "card", "poster", "thumb", "box", "entry", "movie", "anime", "video")

    /**
     * Simplify [html] down to ~2-4KB of structured markup.
     * If [detectCandidates] is true, also extract the most-likely catalog cards.
     */
    fun simplify(html: String, detectCandidates: Boolean = true): SimplifiedHtml {
        val doc = Jsoup.parse(html)
        doc.outputSettings().prettyPrint(false)

        // 1. Remove noise tags.
        removedTags.forEach { tag ->
            doc.select(tag).remove()
        }
        doc.select("iframe").forEach { iframe ->
            // Replace iframes with a marker preserving the src (useful for the LLM to see embeds).
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                iframe.replaceWith(Element("div").attr("data-iframe-src", src))
            } else {
                iframe.remove()
            }
        }
        doc.select("comment").remove()
        // Also walk the tree to remove comment nodes (Jsoup represents them as comment pseudo-elements in newer versions).
        doc.traverse(object : org.jsoup.select.NodeVisitor {
            override fun head(node: org.jsoup.nodes.Node, depth: Int) {
                if (node is org.jsoup.nodes.Comment) node.remove()
            }
            override fun tail(node: org.jsoup.nodes.Node, depth: Int) {}
        })

        // 2. Prune attributes.
        doc.allElements.forEach { el ->
            val attrs = el.attributes().toList()
            attrs.forEach { attr ->
                val key = attr.key
                if (key !in keptAttributes && !key.startsWith("data-")) {
                    el.removeAttr(key)
                }
            }
        }

        // 3. Collapse whitespace in text nodes.
        doc.body()?.let { body -> collapseWhitespace(body) }

        val fullSimplified = doc.body()?.html()?.trim().orEmpty()

        // 4. Candidate detection (optional).
        val candidates = if (detectCandidates) detectCatalogCandidates(doc) else emptyList()

        // 5. Build the compact payload: title + nav + 3 sample cards.
        val compact = buildCompactPayload(doc, candidates)

        return SimplifiedHtml(
            title = doc.title(),
            compactHtml = compact,
            fullSimplifiedHtml = fullSimplified,
            candidateSelector = candidates.firstOrNull()?.selector,
            candidateCount = candidates.firstOrNull()?.count ?: 0,
            originalSizeBytes = html.length,
            simplifiedSizeBytes = compact.length,
        )
    }

    private fun collapseWhitespace(el: Element) {
        el.textNodes().forEach { node ->
            val text = node.text().trim()
            if (text.isNotEmpty()) {
                val collapsed = text.replace(Regex("\\s+"), " ")
                node.text(collapsed)
            }
        }
        el.children().forEach { collapseWhitespace(it) }
    }

    /**
     * Find the most-repeated div/article element whose class contains a hint like "card"/"poster".
     * Returns candidates sorted by repetition count (descending).
     */
    private fun detectCatalogCandidates(doc: Document): List<Candidate> {
        val counts = HashMap<String, Int>()
        val sampleSelectors = HashMap<String, Element>()

        doc.select("div, article, li").forEach { el ->
            val cls = el.className()
            if (cls.isBlank()) return@forEach
            val lower = cls.lowercase()
            if (candidateClassHints.none { hint -> lower.contains(hint) }) return@forEach

            // Build a selector for this element.
            val tag = el.tagName()
            val firstClass = cls.split(' ').first()
            val selector = "$tag.$firstClass"
            counts[selector] = (counts[selector] ?: 0) + 1
            if (!sampleSelectors.containsKey(selector)) sampleSelectors[selector] = el
        }

        return counts.entries
            .filter { it.value >= 3 } // at least 3 cards to qualify
            .sortedByDescending { it.value }
            .map { (selector, count) ->
                Candidate(selector = selector, count = count, sample = sampleSelectors[selector]!!)
            }
            .take(5)
    }

    private fun buildCompactPayload(doc: Document, candidates: List<Candidate>): String {
        val sb = StringBuilder()
        sb.appendLine("<title>${doc.title()}</title>")
        sb.appendLine("<nav>${doc.selectFirst("nav")?.text()?.take(200).orEmpty()}</nav>")

        if (candidates.isNotEmpty()) {
            val top = candidates.first()
            sb.appendLine("<!-- candidate: ${top.selector} x${top.count} -->")
            // Emit 3 sample cards.
            doc.select(top.selector).take(3).forEach { card ->
                sb.appendLine(card.outerHtml().take(400))
            }
        } else {
            // No candidates found — emit the first 2KB of the body.
            sb.appendLine(doc.body()?.html()?.take(2048).orEmpty())
        }
        return sb.toString()
    }

    data class SimplifiedHtml(
        val title: String,
        val compactHtml: String,
        val fullSimplifiedHtml: String,
        val candidateSelector: String?,
        val candidateCount: Int,
        val originalSizeBytes: Int,
        val simplifiedSizeBytes: Int,
    )

    data class Candidate(
        val selector: String,
        val count: Int,
        val sample: Element,
    )
}
