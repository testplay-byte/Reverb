package app.reverb.adblock

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * OkHttp interceptor that blocks ad requests using an [AdMatcher].
 *
 * The matcher itself enforces the extractor-non-interference contract (never block video URLs),
 * so this interceptor just delegates. Reference: PLAN.md §16.
 */
class AdBlockInterceptor(
    private val matcher: AdMatcher,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()

        val accept = request.header("Accept")
        val requestType = inferRequestType(request.header("Sec-Fetch-Dest"), accept, url)

        val verdict = matcher.checkNetwork(url, requestType, accept)
        if (verdict == AdMatcher.Verdict.BLOCK) {
            // Return an empty 204 — the caller sees a successful-but-empty response.
            return Response.Builder()
                .request(request)
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(204)
                .message("Blocked by Reverb ad-blocker")
                .body(ByteArray(0).toResponseBody())
                .build()
        }
        return chain.proceed(request)
    }

    /** Map Sec-Fetch-Dest / Accept / extension → AdMatcher.RequestType. */
    private fun inferRequestType(dest: String?, accept: String?, url: String): AdMatcher.RequestType {
        dest?.let {
            return when (it.lowercase()) {
                "document" -> AdMatcher.RequestType.DOCUMENT
                "script" -> AdMatcher.RequestType.SCRIPT
                "style" -> AdMatcher.RequestType.STYLESHEET
                "image" -> AdMatcher.RequestType.IMAGE
                "font" -> AdMatcher.RequestType.FONT
                "empty", "xhr", "fetch" -> AdMatcher.RequestType.XHR
                "iframe", "frame" -> AdMatcher.RequestType.SUBDOCUMENT
                "audio", "video", "track" -> AdMatcher.RequestType.MEDIA
                else -> AdMatcher.RequestType.OTHER
            }
        }
        if (accept != null) {
            if (accept.contains("text/css", ignoreCase = true)) return AdMatcher.RequestType.STYLESHEET
            if (accept.contains("image/", ignoreCase = true)) return AdMatcher.RequestType.IMAGE
            if (accept.contains("video/", ignoreCase = true) || accept.contains("audio/", ignoreCase = true)) return AdMatcher.RequestType.MEDIA
            if (accept.contains("font/", ignoreCase = true)) return AdMatcher.RequestType.FONT
        }
        val ext = url.substringBefore('?').substringAfterLast('.', "").lowercase()
        return when (ext) {
            "js" -> AdMatcher.RequestType.SCRIPT
            "css" -> AdMatcher.RequestType.STYLESHEET
            "png", "jpg", "jpeg", "gif", "webp", "svg" -> AdMatcher.RequestType.IMAGE
            "woff", "woff2", "ttf" -> AdMatcher.RequestType.FONT
            "mp4", "m3u8", "mpd", "ts", "webm", "m4s" -> AdMatcher.RequestType.MEDIA
            else -> AdMatcher.RequestType.OTHER
        }
    }
}
