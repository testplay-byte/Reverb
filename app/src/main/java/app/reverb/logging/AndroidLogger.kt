package app.reverb.logging

import android.util.Log
import app.reverb.core.common.Logger

/**
 * Android Logger implementation — delegates to android.util.Log.
 *
 * Set as the global logger in ReverbApp.onCreate():
 *   Loggers.set(AndroidLogger())
 *
 * All Reverb log lines appear in logcat under the "Reverb/" tag prefix.
 * Filter with: adb logcat -s Reverb/Network:* Reverb/Extractor:* Reverb/AdBlock:*
 */
class AndroidLogger : Logger {
    override fun d(tag: String, msg: String, throwable: Throwable?) {
        if (throwable != null) Log.d(tag, msg, throwable)
        else Log.d(tag, msg)
    }

    override fun i(tag: String, msg: String, throwable: Throwable?) {
        if (throwable != null) Log.i(tag, msg, throwable)
        else Log.i(tag, msg)
    }

    override fun w(tag: String, msg: String, throwable: Throwable?) {
        if (throwable != null) Log.w(tag, msg, throwable)
        else Log.w(tag, msg)
    }

    override fun e(tag: String, msg: String, throwable: Throwable?) {
        if (throwable != null) Log.e(tag, msg, throwable)
        else Log.e(tag, msg)
    }
}
