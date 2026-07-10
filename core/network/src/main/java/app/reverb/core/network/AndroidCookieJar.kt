package app.reverb.core.network

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import app.reverb.core.common.ReverbLog

/**
 * Bridges OkHttp's [CookieJar] with Android's [CookieManager].
 *
 * This is the critical glue for the Cloudflare solver: when the WebView solves a CF challenge
 * and obtains a cf_clearance cookie, it's stored in CookieManager. This jar makes it visible
 * to OkHttp so subsequent requests are pre-authenticated.
 *
 * Reference: PLAN.md §16 (Cloudflare bypass) + aniyomi's AndroidCookieJar pattern.
 */
class AndroidCookieJar : CookieJar {

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val cookieManager = CookieManager.getInstance()
        cookies.forEach { cookie ->
            val cookieHeader = buildString {
                append("${cookie.name}=${cookie.value}")
                if (cookie.persistent) {
                    append("; Max-Age=${cookie.expiresAt - System.currentTimeMillis() / 1000}")
                }
                if (cookie.secure) append("; Secure")
                if (cookie.httpOnly) append("; HttpOnly")
                cookie.path?.let { append("; Path=$it") }
                cookie.domain?.let { append("; Domain=$it") }
            }
            cookieManager.setCookie(url.toString(), cookieHeader)
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val cookieManager = CookieManager.getInstance()
        val cookieHeader = cookieManager.getCookie(url.toString()) ?: return emptyList()
        return cookieHeader.split(";")
            .map { it.trim() }
            .filter { it.contains("=") }
            .mapNotNull { pair ->
                val idx = pair.indexOf("=")
                val name = pair.substring(0, idx).trim()
                val value = pair.substring(idx + 1).trim()
                try {
                    Cookie.Builder()
                        .domain(url.host)
                        .name(name)
                        .value(value)
                        .build()
                } catch (e: Exception) {
                    ReverbLog.w("CookieJar", "Failed to parse cookie '$name' — ${e.message}")
                    null
                }
            }
    }

    /** Check if a cf_clearance cookie exists for the given URL — used by the CF solver. */
    fun hasClearanceCookie(url: String): Boolean {
        val cookies = CookieManager.getInstance().getCookie(url) ?: return false
        return cookies.contains("cf_clearance=")
    }

    /** Force-flush cookies to persistent storage (call after solving a CF challenge). */
    fun flush() {
        CookieManager.getInstance().flush()
    }
}
