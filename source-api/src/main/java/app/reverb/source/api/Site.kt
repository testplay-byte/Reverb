package app.reverb.source.api

/**
 * The core contract every Reverb source implements.
 *
 * This interface lives in a pure-Kotlin (KMP-ready) module with ZERO Android dependencies,
 * so scrapers are testable in plain JVM. Hand-written SiteModules, theme modules, the
 * NewPipe adapter, AND the universal extractor all implement this.
 *
 * Reference: PLAN.md §6.1, §23
 */
interface Site {
    /** Stable identifier, e.g. "youtube", "kisskh", "universal", "learned:anidb.app". */
    val id: String

    /** Display name. */
    val name: String

    /** Base URL of the site. */
    val baseUrl: String

    /** ISO-639-1 language code, or "all" for multilingual. */
    val language: String

    /** Whether this site hosts adult content. */
    val isNsfw: Boolean

    /** What this Site can do. Drives which UI affordances to show. */
    val capabilities: Set<Capability>

    /**
     * True if this Site can handle the given URL.
     * Called on EVERY url the user enters — the URL Router uses firstMatch().
     */
    fun matches(url: String): Boolean

    /** Browse the homepage / popular feed. Null if !CATALOGUE. */
    suspend fun fetchPopular(page: Int): CataloguePage = throw UnsupportedOperationException()

    /** Search the site. Null if !SEARCH. */
    suspend fun search(query: String, page: Int, filters: FilterList = FilterList.Empty): CataloguePage =
        throw UnsupportedOperationException()

    /** Fetch details (title, description, thumbnail, episode list) for one item. */
    suspend fun fetchDetails(item: MediaItem): MediaDetails = throw UnsupportedOperationException()

    /** Given a details page (or episode), list the playable video entries. */
    suspend fun fetchVideoList(item: MediaItem): List<VideoRef> = throw UnsupportedOperationException()

    /**
     * Resolve a VideoRef to actual stream URLs (HLS/DASH/MP4) + qualities + subtitles.
     * May be lazy — called when the user hits play.
     */
    suspend fun resolveVideo(ref: VideoRef): ResolvedStream = throw UnsupportedOperationException()
}

/** What a Site can do. */
enum class Capability {
    CATALOGUE,
    SEARCH,
    DETAILS,
    EPISODES,
    VIDEO_LIST,
    DIRECT_URL,
}

/** A page of catalogue results. */
data class CataloguePage(
    val items: List<MediaItem>,
    val hasNextPage: Boolean,
)

/** A single item in a catalogue grid (anime, show, movie, etc.). */
data class MediaItem(
    val url: String,
    val title: String,
    val thumbnailUrl: String? = null,
    val episodeCount: Int? = null,
    val rating: Double? = null,
    val year: Int? = null,
    val language: String? = null,
    val isNsfw: Boolean = false,
)

/** Full details for a MediaItem. */
data class MediaDetails(
    val url: String,
    val title: String,
    val description: String? = null,
    val posterUrl: String? = null,
    val thumbnailUrl: String? = null,
    val genres: List<String> = emptyList(),
    val year: Int? = null,
    val status: String? = null,
    val studio: String? = null,
    val rating: Double? = null,
    val episodes: List<VideoRef> = emptyList(),
    val related: List<MediaItem> = emptyList(),
)

/** A reference to a playable video (an episode, a movie, a clip). */
data class VideoRef(
    val url: String,
    val title: String,
    val thumbnailUrl: String? = null,
    val episodeNumber: Int? = null,
    val durationMs: Long? = null,
    val scanDate: Long? = null,
)

/** The resolved stream — what the player + downloader actually consume. */
data class ResolvedStream(
    val qualities: List<Quality>,
    val subtitles: List<SubtitleTrack>,
    /** Headers needed for playback (Referer, Origin, cookies). */
    val headers: Map<String, String> = emptyMap(),
    val durationMs: Long? = null,
    /** Which extractor produced this — for analytics + debugging. */
    val extractorUsed: String,
)

/** A single quality option of a stream. */
data class Quality(
    val label: String,           // "1080p", "720p", "DASH (adaptive)"
    val url: String,
    val format: StreamFormat,
    val bandwidth: Long? = null,
    val resolution: String? = null,
    val codecs: String? = null,
    /** For DASH adaptive: separate audio URL. */
    val audioUrl: String? = null,
)

enum class StreamFormat {
    HLS,        // .m3u8
    DASH,       // .mpd
    PROGRESSIVE, // .mp4 / .webm direct
    BLOB,       // captured from URL.createObjectURL
}

/** A subtitle track. */
data class SubtitleTrack(
    val url: String,
    val language: String,
    val format: SubtitleFormat,
)

enum class SubtitleFormat { SRT, VTT, TTML }

/** Search filters (genres, sort, year). Site-specific. */
data class FilterList(val filters: List<Filter>) {
    companion object { val Empty = FilterList(emptyList()) }
}

interface Filter {
    val name: String
}

data class GenreFilter(override val name: String, val genres: List<String>) : Filter
data class SortFilter(override val name: String, val options: List<String>) : Filter
data class YearFilter(override val name: String, val years: List<Int>) : Filter

/** A hint about which video extractor pattern worked — used by LearnedSiteConfig. */
enum class VideoExtractorHint {
    UNIVERSAL,
    ANIMESTREAM_MIRROR,
    KWIK,
    GOGO,
    DOODSTREAM,
    MP4UPLOAD,
    STREAMTAPE,
    DIRECT_M3U8,
    UNKNOWN,
}
