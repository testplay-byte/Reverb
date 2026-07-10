package app.reverb.data

import android.content.Context
import app.reverb.core.common.ReverbLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Simple JSON-file-backed storage for Phase 1.
 *
 * Each "store" is a JSON file in the app's internal storage (filesDir/reverb/).
 * Reads are cached in memory after first load; writes are async + flushed to disk.
 *
 * This avoids Room/KSP version coupling for Phase 1. Phase 2 can migrate to Room
 * when data volumes grow. For Phase 1 (history, bookmarks, learned sites, settings)
 * the volumes are small (<1000 entries) and JSON is fast enough.
 *
 * Reference: PLAN.md §5.2 (storage) — pragmatic Phase 1 choice.
 */
class JsonStore(private val context: Context) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val dir: File by lazy {
        File(context.filesDir, "reverb").also { it.mkdirs() }
    }

    private val caches = mutableMapOf<String, Any>()

    /**
     * Load a list of [T] items from a JSON file. Cached in memory after first load.
     * Returns an empty list if the file doesn't exist or can't be parsed.
     */
    @Synchronized
    @Suppress("UNCHECKED_CAST")
    fun <T> loadList(name: String, serializer: kotlinx.serialization.KSerializer<List<T>>): List<T> {
        caches[name]?.let { return it as List<T> }
        val file = File(dir, "$name.json")
        if (!file.exists()) {
            ReverbLog.d("Data", "Store '$name' — no file, starting empty")
            val empty = emptyList<T>()
            caches[name] = empty
            return empty
        }
        return try {
            val content = file.readText()
            val list = json.decodeFromString(serializer, content)
            caches[name] = list
            ReverbLog.d("Data", "Store '$name' — loaded ${list.size} items from ${content.length}B")
            list
        } catch (e: Exception) {
            ReverbLog.e("Data", "Store '$name' — failed to parse: ${e.message}", e)
            emptyList<T>().also { caches[name] = it }
        }
    }

    /**
     * Save a list of [T] items to a JSON file. Also updates the in-memory cache.
     */
    @Synchronized
    fun <T> saveList(name: String, items: List<T>, serializer: kotlinx.serialization.KSerializer<List<T>>) {
        caches[name] = items
        val content = json.encodeToString(serializer, items)
        val file = File(dir, "$name.json")
        file.writeText(content)
        ReverbLog.d("Data", "Store '$name' — saved ${items.size} items (${content.length}B)")
    }

    /**
     * Load a single [T] value from a JSON file. Returns [default] if not found.
     */
    @Synchronized
    @Suppress("UNCHECKED_CAST")
    fun <T> load(name: String, serializer: kotlinx.serialization.KSerializer<T>, default: T): T {
        caches[name]?.let { return it as T }
        val file = File(dir, "$name.json")
        if (!file.exists()) return default.also { caches[name] = it }
        return try {
            val value = json.decodeFromString(serializer, file.readText())
            caches[name] = value
            ReverbLog.d("Data", "Store '$name' — loaded from ${file.length()}B")
            value
        } catch (e: Exception) {
            ReverbLog.e("Data", "Store '$name' — failed to parse: ${e.message}", e)
            default.also { caches[name] = it }
        }
    }

    /**
     * Save a single [T] value to a JSON file.
     */
    @Synchronized
    fun <T> save(name: String, value: T, serializer: kotlinx.serialization.KSerializer<T>) {
        caches[name] = value
        val content = json.encodeToString(serializer, value)
        File(dir, "$name.json").writeText(content)
        ReverbLog.d("Data", "Store '$name' — saved (${content.length}B)")
    }
}
