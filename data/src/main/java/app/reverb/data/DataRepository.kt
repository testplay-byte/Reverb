package app.reverb.data

import android.content.Context
import app.reverb.core.common.ReverbLog
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Central repository manager — holds all the repositories + provides async access.
 *
 * Usage from app:
 *   val repo = ReverbApp.instance.dataRepository
 *   repo.addHistory(HistoryEntry(...))
 *   val history = repo.getHistory()
 */
class DataRepository(context: Context) {

    private val store = JsonStore(context)

    // ── History ──────────────────────────────────────────────────────────────
    private val historySerializer = ListSerializer(HistoryEntry.serializer())

    fun getHistory(): List<HistoryEntry> = store.loadList("history", historySerializer)
        .sortedByDescending { it.visitedAt }

    fun addHistory(entry: HistoryEntry) {
        val current = store.loadList("history", historySerializer).toMutableList()
        // Remove existing entry with same URL, then prepend.
        current.removeAll { it.url == entry.url }
        current.add(0, entry)
        // Keep last 500 entries.
        val trimmed = current.take(500)
        store.saveList("history", trimmed, historySerializer)
        ReverbLog.d("Repo", "History added: ${entry.title} (${entry.url})")
    }

    fun clearHistory() {
        store.saveList("history", emptyList(), historySerializer)
    }

    // ── Bookmarks ─────────────────────────────────────────────────────────────
    private val bookmarkSerializer = ListSerializer(Bookmark.serializer())

    fun getBookmarks(): List<Bookmark> = store.loadList("bookmarks", bookmarkSerializer)
        .sortedByDescending { it.addedAt }

    fun addBookmark(bookmark: Bookmark) {
        val current = store.loadList("bookmarks", bookmarkSerializer).toMutableList()
        if (current.none { it.url == bookmark.url }) {
            current.add(bookmark)
            store.saveList("bookmarks", current, bookmarkSerializer)
            ReverbLog.d("Repo", "Bookmark added: ${bookmark.title}")
        }
    }

    fun removeBookmark(url: String) {
        val current = store.loadList("bookmarks", bookmarkSerializer).toMutableList()
        current.removeAll { it.url == url }
        store.saveList("bookmarks", current, bookmarkSerializer)
    }

    fun isBookmarked(url: String): Boolean =
        store.loadList("bookmarks", bookmarkSerializer).any { it.url == url }

    // ── Downloads ─────────────────────────────────────────────────────────────
    private val downloadedSerializer = ListSerializer(DownloadedItem.serializer())
    private val queueSerializer = ListSerializer(DownloadTask.serializer())

    fun getDownloaded(): List<DownloadedItem> =
        store.loadList("downloaded", downloadedSerializer).sortedByDescending { it.downloadedAt }

    fun addDownloaded(item: DownloadedItem) {
        val current = store.loadList("downloaded", downloadedSerializer).toMutableList()
        current.add(item)
        store.saveList("downloaded", current, downloadedSerializer)
    }

    fun getDownloadQueue(): List<DownloadTask> =
        store.loadList("queue", queueSerializer).sortedBy { it.createdAt }

    fun updateDownloadTask(task: DownloadTask) {
        val current = store.loadList("queue", queueSerializer).toMutableList()
        val idx = current.indexOfFirst { it.id == task.id }
        if (idx >= 0) current[idx] = task else current.add(task)
        store.saveList("queue", current, queueSerializer)
    }

    fun removeDownloadTask(id: String) {
        val current = store.loadList("queue", queueSerializer).toMutableList()
        current.removeAll { it.id == id }
        store.saveList("queue", current, queueSerializer)
    }

    // ── Learned sites ────────────────────────────────────────────────────────
    private val learnedSiteSerializer = ListSerializer(LearnedSiteConfig.serializer())

    fun getLearnedSites(): List<LearnedSiteConfig> =
        store.loadList("learned_sites", learnedSiteSerializer)

    fun getLearnedSite(host: String): LearnedSiteConfig? =
        store.loadList("learned_sites", learnedSiteSerializer).find { it.id == host }

    fun saveLearnedSite(config: LearnedSiteConfig) {
        val current = store.loadList("learned_sites", learnedSiteSerializer).toMutableList()
        val idx = current.indexOfFirst { it.id == config.id }
        if (idx >= 0) current[idx] = config else current.add(config)
        store.saveList("learned_sites", current, learnedSiteSerializer)
        ReverbLog.i("Repo", "Learned site saved: ${config.name} (${config.baseUrl})")
    }

    // ── Settings ─────────────────────────────────────────────────────────────
    fun getSettings(): AppSettings =
        store.load("settings", AppSettings.serializer(), AppSettings())

    fun saveSettings(settings: AppSettings) {
        store.save("settings", settings, AppSettings.serializer())
        ReverbLog.i("Repo", "Settings saved — adBlock=${settings.adBlockEnabled} doh=${settings.dohEnabled} translation=${settings.translationEnabled}")
    }

    // ── LLM config ───────────────────────────────────────────────────────────
    fun getLlmConfig(): LlmConfig =
        store.load("llm_config", LlmConfig.serializer(), LlmConfig())

    fun saveLlmConfig(config: LlmConfig) {
        store.save("llm_config", config, LlmConfig.serializer())
        ReverbLog.i("Repo", "LLM config saved — provider=${config.provider} model=${config.model}")
    }
}
