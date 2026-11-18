package com.example.freetv.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.example.freetv.di.NetworkModule
import com.example.freetv.domain.model.BufferProfile
import com.example.freetv.domain.model.ChannelStream
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the single ExoPlayer instance. Channel switching reuses it (only the
 * media item changes); it is released when the player screen goes away.
 */
@OptIn(UnstableApi::class)
@Singleton
class ExoPlayerManager @Inject constructor(
    @ApplicationContext private val context: Context,
    okHttpClient: OkHttpClient,
) {
    private val httpFactory = OkHttpDataSource.Factory(okHttpClient).setUserAgent(NetworkModule.USER_AGENT)
    private val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)

    private var player: ExoPlayer? = null
    private var profile: BufferProfile? = null

    fun getOrCreate(bufferProfile: BufferProfile): ExoPlayer {
        val existing = player
        if (existing != null && profile == bufferProfile) return existing
        existing?.release()

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                bufferProfile.minBufferMs,
                bufferProfile.maxBufferMs,
                bufferProfile.playbackMs,
                bufferProfile.rebufferMs,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)

        val created = ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        created.playWhenReady = true
        player = created
        profile = bufferProfile
        return created
    }

    /** Loads [stream] into [player]. [forceHls] is used when the URL has no extension but is HLS. */
    fun load(player: ExoPlayer, stream: ChannelStream, forceHls: Boolean) {
        httpFactory.setUserAgent(stream.userAgent?.takeIf { it.isNotBlank() } ?: NetworkModule.USER_AGENT)
        httpFactory.setDefaultRequestProperties(
            buildMap {
                stream.referrer?.takeIf { it.isNotBlank() }?.let {
                    put("Referer", it)
                }
            },
        )
        val item = MediaItem.Builder()
            .setUri(stream.url)
            .setMediaId(stream.id.toString())
            .apply { mimeTypeFor(stream.url, forceHls)?.let { setMimeType(it) } }
            .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setMaxPlaybackSpeed(1.02f).build())
            .build()
        player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
    }

    fun release() {
        player?.release()
        player = null
        profile = null
    }

    companion object {
        /** Guesses the container from the URL so extension-less HLS/DASH links still work. */
        fun mimeTypeFor(url: String, forceHls: Boolean): String? {
            if (forceHls) return MimeTypes.APPLICATION_M3U8
            val lower = url.lowercase()
            val path = lower.substringBefore('?').substringBefore('#')
            return when {
                path.endsWith(".m3u8") || path.endsWith(".m3u") || lower.contains(".m3u8") -> MimeTypes.APPLICATION_M3U8
                path.endsWith(".mpd") || lower.contains(".mpd?") -> MimeTypes.APPLICATION_MPD
                else -> null
            }
        }
    }
}
