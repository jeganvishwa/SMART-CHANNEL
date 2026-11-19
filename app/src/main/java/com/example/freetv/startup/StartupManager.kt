package com.example.freetv.startup

import android.util.Log
import com.example.freetv.data.network.NetworkMonitor
import com.example.freetv.domain.model.StartScreen
import com.example.freetv.domain.repository.ChannelRepository
import com.example.freetv.domain.repository.PlaylistRepository
import com.example.freetv.domain.repository.SettingsRepository
import com.example.freetv.preferences.LastWatchedChannel
import com.example.freetv.preferences.RestoreOutcome
import com.example.freetv.preferences.StartupPreferences
import com.example.freetv.utils.UrlSanitizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** Channel to play automatically after start-up. */
data class AutoPlayTarget(val channelId: Long, val contextType: String, val contextId: Long)

/** What to show when FreeTV opens. */
data class StartupDecision(
    val openFavorites: Boolean,
    val autoPlay: AutoPlayTarget?,
    /** Message for the home screen (offline, last channel missing…), or null. */
    val homeMessage: String?,
    val online: Boolean,
    val restoreOutcome: RestoreOutcome,
    val restoreMessage: String?,
)

/** Everything the start-up decision depends on (gathered by [StartupManager]). */
data class StartupInputs(
    val autoPlayEnabled: Boolean,
    val startScreen: StartScreen,
    val lastWatched: LastWatchedChannel?,
    val channelExists: Boolean,
    val channelHidden: Boolean,
    val playlistEnabled: Boolean,
    val validStreamCount: Int,
    val online: Boolean,
)

/** Pure start-up rules, kept separate so they can be unit-tested. */
object StartupEvaluator {

    const val OFFLINE_MESSAGE = "You are offline. Showing your saved channels."

    fun decide(i: StartupInputs): StartupDecision {
        val openFavorites = i.startScreen == StartScreen.FAVORITES
        fun home(outcome: RestoreOutcome, reason: String?, message: String?) = StartupDecision(
            openFavorites = openFavorites,
            autoPlay = null,
            homeMessage = message ?: if (!i.online) OFFLINE_MESSAGE else null,
            online = i.online,
            restoreOutcome = outcome,
            restoreMessage = reason,
        )

        if (!i.autoPlayEnabled) return home(RestoreOutcome.NOT_ATTEMPTED, "Auto-play is off", null)
        val last = i.lastWatched ?: return home(RestoreOutcome.NOT_ATTEMPTED, "No channel has been watched yet", null)
        val name = last.channelName.ifBlank { "the last channel" }

        if (!i.channelExists) {
            val msg = "Your last channel \"$name\" is no longer available."
            return home(RestoreOutcome.FAILED, msg, msg)
        }
        if (i.channelHidden || !i.playlistEnabled) {
            val msg = "\"$name\" is hidden or its playlist is disabled, so it was not played."
            return home(RestoreOutcome.FAILED, msg, msg)
        }
        if (i.validStreamCount == 0) {
            val msg = "\"$name\" has no valid stream URL."
            return home(RestoreOutcome.FAILED, msg, msg)
        }
        if (!i.online) {
            return home(RestoreOutcome.FAILED, "No internet connection – \"$name\" was not played", OFFLINE_MESSAGE)
        }
        return StartupDecision(
            openFavorites = openFavorites,
            autoPlay = AutoPlayTarget(last.channelId, last.contextType, last.groupId),
            homeMessage = null,
            online = true,
            restoreOutcome = RestoreOutcome.PENDING,
            restoreMessage = "Playing \"$name\"",
        )
    }
}

/**
 * Decides what FreeTV shows when the user opens it. Only runs when the user
 * (or the HOME button) opens the app – FreeTV never launches itself from the
 * background or at boot.
 */
@Singleton
class StartupManager @Inject constructor(
    private val settings: SettingsRepository,
    private val startupPrefs: StartupPreferences,
    private val channels: ChannelRepository,
    private val playlists: PlaylistRepository,
    private val networkMonitor: NetworkMonitor,
    private val restoreManager: PlaybackRestoreManager,
) {
    suspend fun run(): StartupDecision {
        return try {
            val decision = withTimeoutOrNull(STARTUP_TIMEOUT_MS) { StartupEvaluator.decide(gatherInputs()) }
                ?: timeoutDecision()
            restoreManager.recordStartup(decision)
            startupPrefs.saveStartupError(null)
            decision
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Start-up check failed", e)
            val message = "Start-up check failed: ${e.message ?: e.javaClass.simpleName}"
            runCatching { startupPrefs.saveStartupError(message) }
            StartupDecision(false, null, "Could not restore your last session. Showing home.", networkMonitor.currentlyOnline(), RestoreOutcome.FAILED, message)
        }
    }

    private suspend fun gatherInputs(): StartupInputs {
        val s = settings.current()
        val prefs = startupPrefs.current()
        val last = prefs.lastWatched ?: s.lastWatchedChannelId?.let { id ->
            // Older installs only stored the id.
            LastWatchedChannel(id, "", null, null, -1, "ALL", 0, -1)
        }
        val channel = last?.let { channels.getChannel(it.channelId) }
        val playlistEnabled = channel?.playlistId?.let { playlists.getPlaylist(it)?.enabled } ?: true
        val validStreams = channel?.let { c -> channels.getStreams(c.id).count { UrlSanitizer.isValidRemoteUrl(it.url) } } ?: 0
        return StartupInputs(
            autoPlayEnabled = s.autoPlayLastChannel,
            startScreen = s.startScreen,
            lastWatched = last?.let { if (it.channelName.isBlank() && channel != null) it.copy(channelName = channel.name) else it },
            channelExists = channel != null,
            channelHidden = channel?.hidden == true,
            playlistEnabled = playlistEnabled,
            validStreamCount = validStreams,
            online = networkMonitor.currentlyOnline(),
        )
    }

    private fun timeoutDecision(): StartupDecision {
        val message = "Start-up check took too long; showing home."
        return StartupDecision(false, null, message, networkMonitor.currentlyOnline(), RestoreOutcome.FAILED, message)
    }

    companion object {
        private const val TAG = "StartupManager"
        private const val STARTUP_TIMEOUT_MS = 4_000L
    }
}
