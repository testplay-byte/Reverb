package app.reverb.data

import kotlinx.serialization.Serializable

// ── History ────────────────────────────────────────────────────────────────

@Serializable
data class HistoryEntry(
    val url: String,
    val title: String,
    val thumbnailUrl: String? = null,
    val visitedAt: Long,
)

// ── Bookmarks ───────────────────────────────────────────────────────────────

@Serializable
data class Bookmark(
    val url: String,
    val title: String,
    val thumbnailUrl: String? = null,
    val siteId: String,
    val addedAt: Long,
)

// ── Downloaded items ────────────────────────────────────────────────────────

@Serializable
data class DownloadedItem(
    val url: String,
    val title: String,
    val filePath: String,
    val fileSizeBytes: Long,
    val format: String,       // "HLS" / "DASH" / "PROGRESSIVE"
    val quality: String,      // "1080p" / "720p" / etc.
    val downloadedAt: Long,
    val thumbnailUrl: String? = null,
)

// ── Download queue ──────────────────────────────────────────────────────────

@Serializable
data class DownloadTask(
    val id: String,
    val url: String,
    val title: String,
    val quality: String,
    val format: String,
    val status: DownloadStatus,
    val progress: Float = 0f,     // 0.0 → 1.0
    val error: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
enum class DownloadStatus {
    QUEUED, FETCHING_INFO, RUNNING, COMPLETED, CANCELED, ERROR
}

// ── Learned sites (for the LLM analyzer / Learn Mode — Phase 2) ─────────────

@Serializable
data class LearnedSiteConfig(
    val id: String,              // generated from baseUrl host
    val baseUrl: String,
    val name: String,
    val catalogSelector: String? = null,
    val cardTitleSelector: String? = null,
    val cardThumbnailSelector: String? = null,
    val cardUrlSelector: String? = null,
    val detailsUrlPattern: String? = null,
    val detailsPosterSelector: String? = null,
    val detailsSynopsisSelector: String? = null,
    val episodeListSelector: String? = null,
    val episodeUrlPattern: String? = null,
    val videoExtractorHint: String = "universal",
    val lastValidatedAt: Long = 0,
)

// ── App settings ────────────────────────────────────────────────────────────

@Serializable
data class AppSettings(
    val adBlockEnabled: Boolean = true,
    val dohEnabled: Boolean = true,
    val translationEnabled: Boolean = false,
    val autoTranslate: Boolean = false,
    val targetLanguage: String = "en",
    val defaultQuality: String = "auto",
    val maxConcurrentDownloads: Int = 3,
    val wifiOnlyDownloads: Boolean = false,
    val cfSolverEnabled: Boolean = true,
)

// ── LLM configuration ───────────────────────────────────────────────────────

@Serializable
data class LlmConfig(
    val provider: LlmProvider = LlmProvider.NONE,
    val apiKey: String = "",
    val endpoint: String = "https://api.z.ai/api/paas/v4/chat/completions",
    val model: String = "",
)

@Serializable
enum class LlmProvider {
    NONE,
    GEMINI,
    OPENAI_COMPATIBLE,
}
