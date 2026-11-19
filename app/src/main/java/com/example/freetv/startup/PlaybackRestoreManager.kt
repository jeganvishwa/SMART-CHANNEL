package com.example.freetv.startup

import com.example.freetv.di.ApplicationScope
import com.example.freetv.preferences.RestoreOutcome
import com.example.freetv.preferences.StartupPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks the outcome of restoring the last watched channel and carries
 * messages from start-up / the player back to the home screen.
 */
@Singleton
class PlaybackRestoreManager @Inject constructor(
    private val startupPrefs: StartupPreferences,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _homeMessage = MutableStateFlow<String?>(null)
    /** One-off message for the home screen (consumed when shown). */
    val homeMessage: StateFlow<String?> = _homeMessage.asStateFlow()

    private val _lastDecision = MutableStateFlow<StartupDecision?>(null)
    val lastDecision: StateFlow<StartupDecision?> = _lastDecision.asStateFlow()

    suspend fun recordStartup(decision: StartupDecision) {
        _lastDecision.value = decision
        decision.homeMessage?.let { _homeMessage.value = it }
        startupPrefs.saveRestore(decision.restoreOutcome, decision.restoreMessage)
    }

    /** The restored channel started playing. */
    fun markRestoreSuccess(channelName: String) {
        scope.launch { startupPrefs.saveRestore(RestoreOutcome.SUCCESS, "Played \"$channelName\"") }
    }

    /** Every source of the restored channel failed; the player returns to home. */
    fun markRestoreFailed(channelName: String, reason: String?) {
        val message = "Could not play \"$channelName\"" + (reason?.let { ": $it" } ?: "")
        _homeMessage.value = message
        scope.launch { startupPrefs.saveRestore(RestoreOutcome.FAILED, message) }
    }

    fun postHomeMessage(message: String) {
        _homeMessage.value = message
    }

    fun consumeHomeMessage() {
        _homeMessage.value = null
    }
}
