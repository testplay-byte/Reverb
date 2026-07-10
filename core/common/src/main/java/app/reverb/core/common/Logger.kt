package app.reverb.core.common

/**
 * Logging facade for Reverb.
 *
 * Implementations:
 * - [NoopLogger] — for JVM unit tests (no output).
 * - [AndroidLogger] — delegates to android.util.Log (in the :app module).
 *
 * Usage throughout the codebase:
 *   ReverbLog.d("Network", "Fetching $url")
 *   ReverbLog.e("Extractor", "Failed to resolve video", exception)
 *
 * The global logger is set once in ReverbApp.onCreate() via [Loggers.set].
 * Reference: PLAN.md — user requested proper logcat debugging for each process/task/error.
 */
interface Logger {
    fun d(tag: String, msg: String, throwable: Throwable? = null)
    fun i(tag: String, msg: String, throwable: Throwable? = null)
    fun w(tag: String, msg: String, throwable: Throwable? = null)
    fun e(tag: String, msg: String, throwable: Throwable? = null)
}

/** No-op logger for JVM tests. */
object NoopLogger : Logger {
    override fun d(tag: String, msg: String, throwable: Throwable?) {}
    override fun i(tag: String, msg: String, throwable: Throwable?) {}
    override fun w(tag: String, msg: String, throwable: Throwable?) {}
    override fun e(tag: String, msg: String, throwable: Throwable?) {}
}

/**
 * Global logger holder. Modules call [ReverbLog] which delegates to [Loggers.current].
 * Set once at app startup: `Loggers.set(AndroidLogger())`.
 */
object Loggers {
    @Volatile
    private var current: Logger = NoopLogger

    fun set(logger: Logger) {
        current = logger
    }

    fun get(): Logger = current
}

/**
 * The main logging entry point for all Reverb modules.
 * Automatically prefixes tags with "Reverb/" for easy logcat filtering: `adb logcat -s Reverb/*`
 *
 * Example output:
 *   D/Reverb﹕ Network: GET https://anidb.app/home → 200 (234ms)
 *   E/Reverb﹕ Extractor: Failed to resolve video — timeout after 15000ms
 */
object ReverbLog {
    private const val PREFIX = "Reverb"

    fun d(tag: String, msg: String, throwable: Throwable? = null) =
        Loggers.get().d("$PREFIX/$tag", msg, throwable)

    fun i(tag: String, msg: String, throwable: Throwable? = null) =
        Loggers.get().i("$PREFIX/$tag", msg, throwable)

    fun w(tag: String, msg: String, throwable: Throwable? = null) =
        Loggers.get().w("$PREFIX/$tag", msg, throwable)

    fun e(tag: String, msg: String, throwable: Throwable? = null) =
        Loggers.get().e("$PREFIX/$tag", msg, throwable)
}
