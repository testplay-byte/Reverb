package app.reverb.core.video

import app.reverb.core.common.UrlUtils

/**
 * HLS master playlist parser.
 *
 * Parses #EXT-X-STREAM-INF (variant playlists) and #EXT-X-MEDIA (audio/subtitle tracks).
 * Reference: PLAN.md §17 / lib/playlistutils from anime-extensions.
 */
object HlsMasterParser {

    private val STREAM_INF_REGEX = Regex("""#EXT-X-STREAM-INF:([^\n]*)""")
    private val RESOLUTION_REGEX = Regex("""RESOLUTION=(\d+)x(\d+)""")
    private val BANDWIDTH_REGEX = Regex("""BANDWIDTH=(\d+)""")
    private val CODECS_REGEX = Regex("""CODECS="([^"]+)"""")
    private val FRAME_RATE_REGEX = Regex("""FRAME-RATE=([\d.]+)""")
    private val MEDIA_REGEX = Regex("""#EXT-X-MEDIA:TYPE=([^,]+),([^\n]*)""")
    private val NAME_REGEX = Regex("""NAME="([^"]+)"""")
    private val LANGUAGE_REGEX = Regex("""LANGUAGE="([^"]+)"""")

    data class HlsMaster(
        val variants: List<Variant>,
        val audioTracks: List<MediaTrack>,
        val subtitleTracks: List<MediaTrack>,
    )

    data class Variant(
        val url: String,
        val bandwidth: Long?,
        val resolution: String?,
        val width: Int?,
        val height: Int?,
        val codecs: String?,
        val frameRate: Double?,
    )

    data class MediaTrack(
        val type: String,  // AUDIO, SUBTITLES, CLOSED-CAPTIONS
        val url: String?,
        val name: String?,
        val language: String?,
        val default: Boolean,
    )

    /** Parse an HLS master playlist. [masterUrl] is used to resolve relative variant URLs. */
    fun parse(masterUrl: String, masterPlaylist: String): HlsMaster {
        val variants = mutableListOf<Variant>()
        val audio = mutableListOf<MediaTrack>()
        val subtitles = mutableListOf<MediaTrack>()

        val lines = masterPlaylist.lines()
        for (i in lines.indices) {
            val line = lines[i].trim()

            STREAM_INF_REGEX.find(line)?.let { match ->
                val attrs = match.groupValues[1]
                // The URL is on the next non-empty line.
                val url = nextNonEmptyLine(lines, i + 1) ?: return@let
                val resolved = resolve(masterUrl, url)
                if (resolved != null) {
                    variants.add(
                        Variant(
                            url = resolved,
                            bandwidth = BANDWIDTH_REGEX.find(attrs)?.groupValues?.get(1)?.toLongOrNull(),
                            resolution = RESOLUTION_REGEX.find(attrs)?.let { "${it.groupValues[1]}x${it.groupValues[2]}" },
                            width = RESOLUTION_REGEX.find(attrs)?.groupValues?.get(1)?.toIntOrNull(),
                            height = RESOLUTION_REGEX.find(attrs)?.groupValues?.get(2)?.toIntOrNull(),
                            codecs = CODECS_REGEX.find(attrs)?.groupValues?.get(1),
                            frameRate = FRAME_RATE_REGEX.find(attrs)?.groupValues?.get(1)?.toDoubleOrNull(),
                        )
                    )
                }
            }

            MEDIA_REGEX.find(line)?.let { match ->
                val type = match.groupValues[1]
                val attrs = match.groupValues[2]
                val uri = Regex("""URI="([^"]+)"""").find(attrs)?.groupValues?.get(1)
                val name = NAME_REGEX.find(attrs)?.groupValues?.get(1)
                val language = LANGUAGE_REGEX.find(attrs)?.groupValues?.get(1)
                val isDefault = attrs.contains("DEFAULT=YES", ignoreCase = true)
                val resolvedUri = uri?.let { resolve(masterUrl, it) }
                val track = MediaTrack(type, resolvedUri, name, language, isDefault)
                when (type.uppercase()) {
                    "AUDIO" -> audio.add(track)
                    "SUBTITLES" -> subtitles.add(track)
                }
            }
        }

        // Sort variants by resolution descending (best quality first).
        val sorted = variants.sortedByDescending { it.height ?: 0 }
        return HlsMaster(sorted, audio, subtitles)
    }

    /** Convert parsed variants into a list of [Quality]-like labels for the UI. */
    fun toQualityLabels(master: HlsMaster): List<String> =
        master.variants.map { v ->
            when {
                v.height != null -> "${v.height}p"
                v.bandwidth != null -> "${v.bandwidth / 1000}kbps"
                else -> "adaptive"
            }
        }.ifEmpty { listOf("HLS (adaptive)") }

    private fun nextNonEmptyLine(lines: List<String>, start: Int): String? {
        var i = start
        while (i < lines.size) {
            val l = lines[i].trim()
            if (l.isNotEmpty() && !l.startsWith("#")) return l
            i++
        }
        return null
    }

    private fun resolve(baseUrl: String, relative: String): String? = runCatching {
        java.net.URI(baseUrl).resolve(relative).toString()
    }.getOrNull()
}
