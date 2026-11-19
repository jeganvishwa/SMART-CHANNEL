package com.example.freetv.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** The channel the user watched last, saved on every channel change (not per frame). */
data class LastWatchedChannel(
    val channelId: Long,
    val channelName: String,
    val playlistId: Long?,
    val language: String?,
    /** Group the player was opened from (-1 when not a group). */
    val groupId: Long,
    /** PlaybackContextType name the player was opened with. */
    val contextType: String,
    val watchedAt: Long,
    /** Index of the stream that last played successfully (priority order), or -1. */
    val streamIndex: Int,
)

enum class RestoreOutcome { NOT_ATTEMPTED, PENDING, SUCCESS, FAILED }

data class RestoreRecord(val outcome: RestoreOutcome, val message: String?, val at: Long?)

data class StartupPrefs(
    val restoreLastLanguage: Boolean = true,
    val restoreLastGroup: Boolean = true,
    val showStartupDiagnostics: Boolean = false,
    /** Last selected language tab key ("g:<id>"), kept separately from the last tab of any kind. */
    val lastLanguageTab: String? = null,
    val lastWatched: LastWatchedChannel? = null,
    val lastRestore: RestoreRecord = RestoreRecord(RestoreOutcome.NOT_ATTEMPTED, null, null),
    val lastStartupError: String? = null,
    val languagePlaylistsSeeded: Boolean = false,
)

/** Start-up related preferences, stored in the app's existing DataStore. */
@Singleton
class StartupPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private object Keys {
        val RESTORE_LANGUAGE = booleanPreferencesKey("startup_restore_language")
        val RESTORE_GROUP = booleanPreferencesKey("startup_restore_group")
        val SHOW_DIAGNOSTICS = booleanPreferencesKey("startup_show_diagnostics")
        val LAST_LANGUAGE_TAB = stringPreferencesKey("last_language_tab")
        val LW_ID = longPreferencesKey("last_watched_id")
        val LW_NAME = stringPreferencesKey("last_watched_name")
        val LW_PLAYLIST = longPreferencesKey("last_watched_playlist")
        val LW_LANGUAGE = stringPreferencesKey("last_watched_language")
        val LW_GROUP = longPreferencesKey("last_watched_group")
        val LW_CONTEXT = stringPreferencesKey("last_watched_context")
        val LW_AT = longPreferencesKey("last_watched_at")
        val LW_STREAM = intPreferencesKey("last_watched_stream")
        val RESTORE_OUTCOME = stringPreferencesKey("restore_outcome")
        val RESTORE_MESSAGE = stringPreferencesKey("restore_message")
        val RESTORE_AT = longPreferencesKey("restore_at")
        val STARTUP_ERROR = stringPreferencesKey("startup_error")
        val LANGUAGE_SEEDED = booleanPreferencesKey("language_playlists_seeded")
    }

    val prefs: Flow<StartupPrefs> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toStartupPrefs() }

    suspend fun current(): StartupPrefs = prefs.first()

    private fun Preferences.toStartupPrefs(): StartupPrefs {
        val id = this[Keys.LW_ID]
        val lastWatched = if (id != null) {
            LastWatchedChannel(
                channelId = id,
                channelName = this[Keys.LW_NAME].orEmpty(),
                playlistId = this[Keys.LW_PLAYLIST]?.takeIf { it > 0 },
                language = this[Keys.LW_LANGUAGE],
                groupId = this[Keys.LW_GROUP] ?: -1,
                contextType = this[Keys.LW_CONTEXT] ?: "ALL",
                watchedAt = this[Keys.LW_AT] ?: 0,
                streamIndex = this[Keys.LW_STREAM] ?: -1,
            )
        } else {
            null
        }
        return StartupPrefs(
            restoreLastLanguage = this[Keys.RESTORE_LANGUAGE] ?: true,
            restoreLastGroup = this[Keys.RESTORE_GROUP] ?: true,
            showStartupDiagnostics = this[Keys.SHOW_DIAGNOSTICS] ?: false,
            lastLanguageTab = this[Keys.LAST_LANGUAGE_TAB],
            lastWatched = lastWatched,
            lastRestore = RestoreRecord(
                outcome = RestoreOutcome.entries.firstOrNull { it.name == this[Keys.RESTORE_OUTCOME] } ?: RestoreOutcome.NOT_ATTEMPTED,
                message = this[Keys.RESTORE_MESSAGE],
                at = this[Keys.RESTORE_AT],
            ),
            lastStartupError = this[Keys.STARTUP_ERROR],
            languagePlaylistsSeeded = this[Keys.LANGUAGE_SEEDED] ?: false,
        )
    }

    private fun <T> MutablePreferences.setOrRemove(key: Preferences.Key<T>, value: T?) {
        if (value == null) remove(key) else this[key] = value
    }

    suspend fun setRestoreLastLanguage(value: Boolean) = dataStore.edit { it[Keys.RESTORE_LANGUAGE] = value }.let { }
    suspend fun setRestoreLastGroup(value: Boolean) = dataStore.edit { it[Keys.RESTORE_GROUP] = value }.let { }
    suspend fun setShowStartupDiagnostics(value: Boolean) = dataStore.edit { it[Keys.SHOW_DIAGNOSTICS] = value }.let { }
    suspend fun setLastLanguageTab(tab: String) = dataStore.edit { it[Keys.LAST_LANGUAGE_TAB] = tab }.let { }
    suspend fun setLanguagePlaylistsSeeded() = dataStore.edit { it[Keys.LANGUAGE_SEEDED] = true }.let { }

    suspend fun saveLastWatched(channel: LastWatchedChannel) {
        dataStore.edit { p ->
            p[Keys.LW_ID] = channel.channelId
            p[Keys.LW_NAME] = channel.channelName.take(200)
            p.setOrRemove(Keys.LW_PLAYLIST, channel.playlistId)
            p.setOrRemove(Keys.LW_LANGUAGE, channel.language)
            p[Keys.LW_GROUP] = channel.groupId
            p[Keys.LW_CONTEXT] = channel.contextType
            p[Keys.LW_AT] = channel.watchedAt
            p[Keys.LW_STREAM] = channel.streamIndex
        }
    }

    /** Records which stream index worked for [channelId] (only if it is still the last watched channel). */
    suspend fun saveLastStreamIndex(channelId: Long, index: Int) {
        dataStore.edit { p ->
            if (p[Keys.LW_ID] == channelId && p[Keys.LW_STREAM] != index) p[Keys.LW_STREAM] = index
        }
    }

    suspend fun clearLastWatched() {
        dataStore.edit { p ->
            listOf(Keys.LW_ID, Keys.LW_PLAYLIST, Keys.LW_GROUP, Keys.LW_AT).forEach { p.remove(it) }
            listOf(Keys.LW_NAME, Keys.LW_LANGUAGE, Keys.LW_CONTEXT).forEach { p.remove(it) }
            p.remove(Keys.LW_STREAM)
        }
    }

    suspend fun saveRestore(outcome: RestoreOutcome, message: String?, at: Long = System.currentTimeMillis()) {
        dataStore.edit { p ->
            p[Keys.RESTORE_OUTCOME] = outcome.name
            p.setOrRemove(Keys.RESTORE_MESSAGE, message)
            p[Keys.RESTORE_AT] = at
        }
    }

    suspend fun saveStartupError(error: String?) {
        dataStore.edit { p -> p.setOrRemove(Keys.STARTUP_ERROR, error) }
    }
}
