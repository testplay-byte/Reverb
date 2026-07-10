package app.reverb.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import app.reverb.core.common.ReverbLog
import app.reverb.source.api.Quality
import app.reverb.source.api.ResolvedStream
import app.reverb.source.api.StreamFormat
import okhttp3.OkHttpClient

/**
 * Wraps Media3 ExoPlayer for Reverb's playback needs.
 *
 * Handles HLS, DASH, and progressive streams. Uses the shared OkHttp client
 * for datasource (so CF cookies + ad-block + custom headers are applied).
 *
 * Reference: PLAN.md §5.3 + §7 (player module).
 */
class ReverbPlayer(
    private val context: Context,
    private val httpClient: OkHttpClient,
) {
    private var player: ExoPlayer? = null

    /** Create + return the ExoPlayer (call once; reuse for subsequent playback). */
    fun getOrCreatePlayer(): ExoPlayer {
        player?.let { return it }
        ReverbLog.i("Player", "Creating ExoPlayer instance")

        // OkHttp datasource factory — uses the shared client (CF cookies + ad-block + headers).
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
     * Dispatches to HlsMediaSource / DashMediaSource / ProgressiveMediaSource.
     */
    fun buildMediaSource(stream: ResolvedStream, quality: Quality, title: String? = null): MediaSource {
        val exoPlayer = getOrCreatePlayer()
        val dataSourceFactory = OkHttpDataSource.Factory(httpClient)
            .setUserAgent(app.reverb.core.network.UserAgentInterceptor.DEFAULT_UA)

        // Apply stream headers (Referer, etc.).
        stream.headers.forEach { (key, value) ->
            dataSourceFactory.setDefaultRequestProperty(key, value)
        }

        val mediaItem = MediaItem.Builder()
            .setUri(quality.url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title ?: quality.label)
                    .build()
            )
            .build()

        val source = when (quality.format) {
            StreamFormat.HLS -> {
                ReverbLog.d("Player", "Building HLS media source — ${quality.url.take(80)}")
                HlsMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(mediaItem)
            }
            StreamFormat.DASH -> {
                ReverbLog.d("Player", "Building DASH media source — ${quality.url.take(80)}")
                DashMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(mediaItem)
            }
            StreamFormat.PROGRESSIVE -> {
                ReverbLog.d("Player", "Building progressive media source — ${quality.url.take(80)}")
                ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(mediaItem)
            }
            StreamFormat.BLOB -> {
                ReverbLog.w("Player", "Blob streams not directly playable by ExoPlayer — treating as progressive")
                ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(mediaItem)
            }
        }
        return source
    }

    /**
     * Play a [ResolvedStream] at the given [quality].
     * Replaces any current media + starts playback immediately.
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

    /** Release the player (call in onDestroy / onStop). */
    fun release() {
        player?.let {
            ReverbLog.i("Player", "Releasing ExoPlayer")
            it.release()
        }
        player = null
    }

    /** The underlying ExoPlayer (null if not created). */
    val exoPlayer: ExoPlayer? get() = player
}
