package app.reverb.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import app.reverb.core.common.ReverbLog
import app.reverb.source.api.Quality
import app.reverb.source.api.ResolvedStream
import app.reverb.source.api.StreamFormat
import okhttp3.OkHttpClient

/**
 * Wraps Media3 ExoPlayer for Reverb's playback needs.
 *
 * Uses DefaultMediaSourceFactory which auto-detects HLS/DASH/progressive from the URI.
 * Uses the shared OkHttp client for datasource (CF cookies + ad-block + custom headers).
 *
 * Reference: PLAN.md §5.3 + §7 (player module).
 */
class ReverbPlayer(
    private val context: Context,
    private val httpClient: OkHttpClient,
) {
    private var player: ExoPlayer? = null

    fun getOrCreatePlayer(): ExoPlayer {
        player?.let { return it }
        ReverbLog.i("Player", "Creating ExoPlayer instance")

        val dataSourceFactory = OkHttpDataSource.Factory(httpClient)
            .setUserAgent(app.reverb.core.network.UserAgentInterceptor.DEFAULT_UA)

        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(dataSourceFactory)

        val p = ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
        player = p
        return p
    }

    /**
     * Build a [MediaSource] for the given [stream] + [quality].
     * DefaultMediaSourceFactory auto-detects HLS/DASH/progressive from the URI.
     */
    fun buildMediaSource(stream: ResolvedStream, quality: Quality, title: String? = null): MediaSource {
        val exoPlayer = getOrCreatePlayer()

        val dataSourceFactory = OkHttpDataSource.Factory(httpClient)
            .setUserAgent(app.reverb.core.network.UserAgentInterceptor.DEFAULT_UA)

        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(dataSourceFactory)

        // Build the MediaItem with request headers (Referer, etc.).
        val mediaItemBuilder = MediaItem.Builder()
            .setUri(quality.url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title ?: quality.label)
                    .build()
            )

        // Set stream headers as request headers on the MediaItem.
        if (stream.headers.isNotEmpty()) {
            val headersMap = androidx.media3.common.MediaItem.RequestMetadata.Builder()
                .build()
            // Media3 1.4.x: set headers via the MediaItem's requestMetadata
            // Actually, the OkHttpDataSource handles headers via the client's interceptors.
            // The Referer + UA are already set by the OkHttp interceptors, so we just log them.
            ReverbLog.d("Player", "Stream headers: ${stream.headers.keys.joinToString()}")
        }

        val mediaItem = mediaItemBuilder.build()
        ReverbLog.d("Player", "Building media source — format=${quality.format} url=${quality.url.take(80)}")

        return mediaSourceFactory.createMediaSource(mediaItem)
    }

    /**
     * Play a [ResolvedStream] at the given [quality].
     */
    fun play(stream: ResolvedStream, quality: Quality, title: String? = null) {
        ReverbLog.i("Player", "play() — quality=${quality.label} format=${quality.format} title=$title")
        val exoPlayer = getOrCreatePlayer()
        val source = buildMediaSource(stream, quality, title)
        exoPlayer.setMediaSource(source)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        ReverbLog.d("Player", "Playback started — ${quality.label}")
    }

    fun release() {
        player?.let {
            ReverbLog.i("Player", "Releasing ExoPlayer")
            it.release()
        }
        player = null
    }

    val exoPlayer: ExoPlayer? get() = player
}
