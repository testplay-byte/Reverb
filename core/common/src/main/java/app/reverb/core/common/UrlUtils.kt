package app.reverb.core.common

import java.util.Locale

/** URL helpers shared across the network, html, and extractor layers. */
object UrlUtils {

    /** Regex matching video-stream URLs — the universal extractor's primary trigger. */
    val VIDEO_URL_REGEX: Regex = Regex(
        """.*\.(mp4|m3u8|mpd|ts|m4s|aac|webm|mkv|flv)(\?.*)?$""",
        RegexOption.IGNORE_CASE,
    )

    /** Regex matching HLS master playlist URLs specifically. */
    val HLS_MASTER_REGEX: Regex = Regex(
        """https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""",
        RegexOption.IGNORE_CASE,
    )

    /** Regex matching DASH manifest URLs. */
    val DASH_REGEX: Regex = Regex(
        """https?://[^\s"'<>]+\.mpd[^\s"'<>]*""",
        RegexOption.IGNORE_CASE,
    )

    /** Regex matching progressive video URLs. */
    val PROGRESSIVE_REGEX: Regex = Regex(
        """https?://[^\s"'<>]+\.(mp4|webm|mkv|flv)[^\s"'<>]*""",
        RegexOption.IGNORE_CASE,
    )

    /** Returns true if the URL looks like a video stream URL. */
    fun isVideoUrl(url: String): Boolean = VIDEO_URL_REGEX.matches(url)

    /** Returns true if the Accept header advertises a media type we must never block. */
    fun isMediaAcceptHeader(accept: String?): Boolean {
        if (accept.isNullOrBlank()) return false
        val lower = accept.lowercase(Locale.US)
        return lower.contains("video/") ||
            lower.contains("audio/") ||
            lower.contains("mpegurl") ||
            lower.contains("dash") ||
            lower.contains("application/vnd.apple.mpegurl") ||
            lower.contains("application/x-mpegurl")
    }

    /** Extract the file extension from a URL (before any query string), lowercased. */
    fun extension(url: String): String? {
        val noQuery = url.substringBefore("?")
        val dot = noQuery.lastIndexOf('.')
        return if (dot >= 0 && dot < noQuery.length - 1) {
            noQuery.substring(dot + 1).lowercase(Locale.US)
        } else null
    }

    /** Returns the host of a URL, or null if unparseable. */
    fun host(url: String): String? = runCatching {
        java.net.URI(url).host?.lowercase(Locale.US)
    }.getOrNull()

    /** True if the URL is a blob: URL (created by URL.createObjectURL). */
    fun isBlobUrl(url: String): Boolean = url.startsWith("blob:", ignoreCase = true)

    /** True if the URL is a data: URL. */
    fun isDataUrl(url: String): Boolean = url.startsWith("data:", ignoreCase = true)
}

/** A simple LRU cache for small things (resolved streams, simplified HTML). */
class LruCache<K, V>(private val maxSize: Int) {
    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean =
            size > maxSize
    }

    @Synchronized
    fun get(key: K): V? = map[key]

    @Synchronized
    fun put(key: K, value: V) { map[key] = value }

    @Synchronized
    fun clear() = map.clear()

    @Synchronized
    fun size(): Int = map.size
}
