package app.reverb.core.common

/** Reverb-wide logging facade. Phase 0 just delegates to android.util.Log via a typealias in android modules. */
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
