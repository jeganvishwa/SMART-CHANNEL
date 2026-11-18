package com.example.freetv.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.freetv.domain.model.AppSettings
import com.example.freetv.domain.model.BufferProfile
import com.example.freetv.domain.model.StartScreen
import com.example.freetv.domain.model.StartupLanguageMode
import com.example.freetv.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** DataStore-backed settings, including the remembered language (home tab). */
@Singleton
class SettingsDataStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    private object Keys {
        val LAST_TAB = stringPreferencesKey("last_selected_tab")
        val DEFAULT_LANGUAGE = longPreferencesKey("default_language_group")
        val STARTUP_LANGUAGE_MODE = stringPreferencesKey("startup_language_mode")
        val AUTO_REFRESH = booleanPreferencesKey("auto_refresh")
        val REFRESH_HOURS = intPreferencesKey("refresh_interval_hours")
        val BUFFER = stringPreferencesKey("buffer_profile")
        val NUMERIC_TIMEOUT = longPreferencesKey("numeric_timeout_ms")
        val NUMBER_DIGITS = intPreferencesKey("number_digits")
        val AUTOPLAY_LAST = booleanPreferencesKey("autoplay_last")
        val LAST_WATCHED = longPreferencesKey("last_watched_channel")
        val START_SCREEN = stringPreferencesKey("start_screen")
        val PURE_BLACK = booleanPreferencesKey("pure_black")
        val EPG_URL = stringPreferencesKey("epg_url")
        val EPG_AUTO = booleanPreferencesKey("epg_auto_refresh")
        val EPG_OFFSET = intPreferencesKey("epg_offset_minutes")
        val MAX_RETRIES = intPreferencesKey("max_retries_per_stream")
        val AUTO_NUMBER = booleanPreferencesKey("auto_number_new")
    }

    override val settings: Flow<AppSettings> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toSettings() }

    override suspend fun current(): AppSettings = settings.first()

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            lastSelectedTab = this[Keys.LAST_TAB],
            defaultLanguageGroupId = this[Keys.DEFAULT_LANGUAGE],
            startupLanguageMode = enumOr(this[Keys.STARTUP_LANGUAGE_MODE], d.startupLanguageMode),
            autoRefreshEnabled = this[Keys.AUTO_REFRESH] ?: d.autoRefreshEnabled,
            refreshIntervalHours = this[Keys.REFRESH_HOURS] ?: d.refreshIntervalHours,
            bufferProfile = enumOr(this[Keys.BUFFER], d.bufferProfile),
            numericInputTimeoutMs = this[Keys.NUMERIC_TIMEOUT] ?: d.numericInputTimeoutMs,
            channelNumberDigits = this[Keys.NUMBER_DIGITS] ?: d.channelNumberDigits,
            autoPlayLastChannel = this[Keys.AUTOPLAY_LAST] ?: d.autoPlayLastChannel,
            lastWatchedChannelId = this[Keys.LAST_WATCHED],
            startScreen = enumOr(this[Keys.START_SCREEN], d.startScreen),
            pureBlackTheme = this[Keys.PURE_BLACK] ?: d.pureBlackTheme,
            epgUrl = this[Keys.EPG_URL],
            epgAutoRefresh = this[Keys.EPG_AUTO] ?: d.epgAutoRefresh,
            epgOffsetMinutes = this[Keys.EPG_OFFSET] ?: d.epgOffsetMinutes,
            maxRetriesPerStream = this[Keys.MAX_RETRIES] ?: d.maxRetriesPerStream,
            autoNumberNewChannels = this[Keys.AUTO_NUMBER] ?: d.autoNumberNewChannels,
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(value: String?, default: E): E =
        value?.let { v -> enumValues<E>().firstOrNull { it.name == v } } ?: default

    private suspend fun <T> set(key: Preferences.Key<T>, value: T?) {
        dataStore.edit { prefs -> if (value == null) prefs.remove(key) else prefs[key] = value }
    }

    override suspend fun setLastSelectedTab(tab: String) = set(Keys.LAST_TAB, tab)
    override suspend fun setDefaultLanguageGroupId(groupId: Long?) = set(Keys.DEFAULT_LANGUAGE, groupId)
    override suspend fun setStartupLanguageMode(mode: StartupLanguageMode) = set(Keys.STARTUP_LANGUAGE_MODE, mode.name)
    override suspend fun setAutoRefresh(enabled: Boolean) = set(Keys.AUTO_REFRESH, enabled)
    override suspend fun setRefreshIntervalHours(hours: Int) = set(Keys.REFRESH_HOURS, hours.coerceIn(1, 168))
    override suspend fun setBufferProfile(profile: BufferProfile) = set(Keys.BUFFER, profile.name)
    override suspend fun setNumericInputTimeoutMs(ms: Long) = set(Keys.NUMERIC_TIMEOUT, ms.coerceIn(500, 10_000))
    override suspend fun setChannelNumberDigits(digits: Int) = set(Keys.NUMBER_DIGITS, digits.coerceIn(1, 5))
    override suspend fun setAutoPlayLastChannel(enabled: Boolean) = set(Keys.AUTOPLAY_LAST, enabled)
    override suspend fun setLastWatchedChannelId(id: Long?) = set(Keys.LAST_WATCHED, id)
    override suspend fun setStartScreen(screen: StartScreen) = set(Keys.START_SCREEN, screen.name)
    override suspend fun setPureBlackTheme(enabled: Boolean) = set(Keys.PURE_BLACK, enabled)
    override suspend fun setEpgUrl(url: String?) = set(Keys.EPG_URL, url?.trim()?.takeIf { it.isNotEmpty() })
    override suspend fun setEpgAutoRefresh(enabled: Boolean) = set(Keys.EPG_AUTO, enabled)
    override suspend fun setEpgOffsetMinutes(minutes: Int) = set(Keys.EPG_OFFSET, minutes.coerceIn(-720, 720))
    override suspend fun setMaxRetriesPerStream(retries: Int) = set(Keys.MAX_RETRIES, retries.coerceIn(0, 5))
    override suspend fun setAutoNumberNewChannels(enabled: Boolean) = set(Keys.AUTO_NUMBER, enabled)
}
