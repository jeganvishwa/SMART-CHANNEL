package com.example.freetv.player

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.example.freetv.domain.model.BufferProfile
import com.example.freetv.domain.model.ChannelStream
import com.example.freetv.utils.UrlSanitizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

enum class PlaybackStatus { IDLE, LOADING, BUFFERING, PLAYING, RETRYING, SWITCHING, OFFLINE, ALL_FAILED, NO_STREAMS }

data class PlaybackState(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val streamIndex: Int = 0,
    val streamCount: Int = 0,
    val attempt: Int = 0,
    val message: String? = null,
)

data class TrackOption(val groupIndex: Int, val trackIndex: Int, val label: String, val selected: Boolean)

/**
 * Coordinates the shared ExoPlayer, stream failover and error recovery for
 * the player screen. Owned by PlayerViewModel (one per player screen).
 */
@OptIn(UnstableApi::class)
class PlaybackController(
    private val playerManager: ExoPlayerManager,
    private val scope: CoroutineScope,
    private val isOnline: StateFlow<Boolean>,
    private val onStreamResult: (streamId: Long, ok: Boolean) -> Unit,
) {
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _player = MutableStateFlow<ExoPlayer?>(null)
    val player: StateFlow<ExoPlayer?> = _player.asStateFlow()

    private val _tracks = MutableStateFlow(Tracks.EMPTY)
    val tracks: StateFlow<Tracks> = _tracks.asStateFlow()

    private var bufferProfile = BufferProfile.BALANCED
    private var failover = StreamFailoverManager(2)
    private var streams: List<ChannelStream> = emptyList()
    private var triedHlsFallback = false
    private var pendingJob: Job? = null
    private var stallJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    stallJob?.cancel()
                    failover.onSuccess()
                    current()?.let { onStreamResult(it.id, true) }
                    _state.update { it.copy(status = PlaybackStatus.PLAYING, attempt = 0, message = null) }
                }
                Player.STATE_BUFFERING -> {
                    if (_state.value.status == PlaybackStatus.PLAYING) {
                        _state.update { it.copy(status = PlaybackStatus.BUFFERING) }
                    }
                    startStallWatchdog()
                }
                Player.STATE_ENDED -> {
                    // A live stream should never end: treat as a failure and fail over.
                    handleFailure("Stream ended")
                }
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            handleError(error)
        }

        override fun onTracksChanged(tracks: Tracks) {
            _tracks.value = tracks
        }
    }

    fun configure(maxRetriesPerStream: Int, profile: BufferProfile) {
        failover = StreamFailoverManager(maxRetriesPerStream)
        if (profile != bufferProfile && _player.value != null) {
            bufferProfile = profile
            detachPlayer()
            attach()
        } else {
            bufferProfile = profile
        }
    }

    /** Creates (or reuses) the player. Call when the screen starts. */
    fun attach() {
        if (_player.value != null) return
        val p = playerManager.getOrCreate(bufferProfile)
        p.addListener(listener)
        _player.value = p
        // Resume the current channel after returning to the app.
        if (streams.isNotEmpty() && _state.value.status != PlaybackStatus.ALL_FAILED) {
            playIndex(failover.currentIndex)
        }
    }

    /** Stops playback and frees decoders. Call when the screen stops. */
    fun detach() {
        pendingJob?.cancel()
        stallJob?.cancel()
        detachPlayer()
    }

    private fun detachPlayer() {
        _player.value?.removeListener(listener)
        _player.value = null
        playerManager.release()
    }

    fun playChannel(channelStreams: List<ChannelStream>, preferredIndex: Int = 0) {
        pendingJob?.cancel()
        stallJob?.cancel()
        streams = channelStreams
        val index = failover.start(channelStreams.size, preferredIndex)
        if (index == null) {
            _player.value?.stop()
            _state.value = PlaybackState(PlaybackStatus.NO_STREAMS, 0, 0, 0, "This channel has no stream URL.")
            return
        }
        playIndex(index)
    }

    fun selectStream(index: Int) {
        if (index !in streams.indices) return
        pendingJob?.cancel()
        failover.select(index)
        playIndex(index)
    }

    fun retryAll() {
        if (streams.isEmpty()) return
        playChannel(streams, 0)
    }

    fun togglePlayPause() {
        val p = _player.value ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    private fun current(): ChannelStream? = streams.getOrNull(failover.currentIndex)

    private fun playIndex(index: Int, forceHls: Boolean = false, attempt: Int = 0) {
        val p = _player.value ?: return
        val stream = streams.getOrNull(index) ?: return
        if (!forceHls) triedHlsFallback = false
        _state.value = PlaybackState(
            status = if (attempt > 0) PlaybackStatus.RETRYING else PlaybackStatus.LOADING,
            streamIndex = index,
            streamCount = streams.size,
            attempt = attempt,
        )
        playerManager.load(p, stream, forceHls)
        startStallWatchdog()
    }

    private fun startStallWatchdog() {
        stallJob?.cancel()
        stallJob = scope.launch {
            delay(STALL_TIMEOUT_MS)
            val p = _player.value ?: return@launch
            if (p.playbackState == Player.STATE_BUFFERING || p.playbackState == Player.STATE_IDLE) {
                handleFailure("Stream timed out")
            }
        }
    }

    private fun handleError(error: PlaybackException) {
        val p = _player.value ?: return
        val stream = current()
        Log.w(TAG, "Playback error ${error.errorCodeName} on ${UrlSanitizer.redact(stream?.url)}")

        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            p.seekToDefaultPosition()
            p.prepare()
            return
        }

        if (!isOnline.value || error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED && !isOnline.value) {
            waitForNetwork()
            return
        }

        val unrecognised = error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
        if (unrecognised && !triedHlsFallback && stream != null &&
            ExoPlayerManager.mimeTypeFor(stream.url, false) == null
        ) {
            triedHlsFallback = true
            playIndex(failover.currentIndex, forceHls = true)
            return
        }

        handleFailure(describe(error))
    }

    private fun waitForNetwork() {
        stallJob?.cancel()
        pendingJob?.cancel()
        _state.update { it.copy(status = PlaybackStatus.OFFLINE, message = "No internet connection. Waiting for network…") }
        pendingJob = scope.launch {
            isOnline.first { it }
            delay(1_000)
            playIndex(failover.currentIndex)
        }
    }

    private fun handleFailure(reason: String) {
        stallJob?.cancel()
        pendingJob?.cancel()
        if (!isOnline.value) {
            waitForNetwork()
            return
        }
        val failedStream = current()
        when (val decision = failover.onFailure()) {
            is FailoverDecision.Retry -> {
                _state.update {
                    it.copy(status = PlaybackStatus.RETRYING, attempt = decision.attempt, message = reason)
                }
                pendingJob = scope.launch {
                    delay(RETRY_BASE_DELAY_MS * decision.attempt)
                    playIndex(decision.index, forceHls = triedHlsFallback, attempt = decision.attempt)
                }
            }
            is FailoverDecision.SwitchTo -> {
                failedStream?.let { onStreamResult(it.id, false) }
                _state.update {
                    it.copy(
                        status = PlaybackStatus.SWITCHING,
                        streamIndex = decision.index,
                        message = "$reason – switching to backup source ${decision.index + 1}",
                    )
                }
                pendingJob = scope.launch {
                    delay(SWITCH_DELAY_MS)
                    playIndex(decision.index)
                }
            }
            FailoverDecision.Exhausted -> {
                failedStream?.let { onStreamResult(it.id, false) }
                _player.value?.stop()
                _state.update {
                    it.copy(
                        status = PlaybackStatus.ALL_FAILED,
                        message = "All ${streams.size} source(s) failed. Last error: $reason",
                    )
                }
            }
        }
    }

    // ---------------- Tracks ----------------

    fun trackOptions(type: Int): List<TrackOption> {
        val result = ArrayList<TrackOption>()
        _tracks.value.groups.forEachIndexed { groupIndex, group ->
            if (group.type != type) return@forEachIndexed
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val format = group.getTrackFormat(i)
                val language = format.language?.let { Locale.forLanguageTag(it).displayLanguage }?.takeIf { it.isNotBlank() }
                val label = listOfNotNull(
                    format.label,
                    language,
                    if (type == C.TRACK_TYPE_AUDIO && format.channelCount > 0) "${format.channelCount}ch" else null,
                ).distinct().joinToString(" · ").ifEmpty { "Track ${result.size + 1}" }
                result += TrackOption(groupIndex, i, label, group.isTrackSelected(i))
            }
        }
        return result
    }

    fun selectTrack(type: Int, option: TrackOption?) {
        val p = _player.value ?: return
        val builder = p.trackSelectionParameters.buildUpon()
        if (option == null) {
            builder.setTrackTypeDisabled(type, true)
        } else {
            val group = _tracks.value.groups.getOrNull(option.groupIndex) ?: return
            builder.setTrackTypeDisabled(type, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
        }
        p.trackSelectionParameters = builder.build()
    }

    private fun describe(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "Server refused the stream (HTTP error)"
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "Cannot connect to the stream"
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Connection timed out"
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "Stream not found"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "Unsupported or broken stream format"
        PlaybackException.ERROR_CODE_DRM_UNSPECIFIED,
        PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED,
        PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR,
        PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
        PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION -> "Protected (DRM) stream – not supported"
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "This TV cannot decode the stream format"
        else -> error.errorCodeName.removePrefix("ERROR_CODE_").replace('_', ' ').lowercase()
            .replaceFirstChar { it.uppercase() }
    }

    companion object {
        private const val TAG = "PlaybackController"
        private const val STALL_TIMEOUT_MS = 20_000L
        private const val RETRY_BASE_DELAY_MS = 1_500L
        private const val SWITCH_DELAY_MS = 400L
    }
}
