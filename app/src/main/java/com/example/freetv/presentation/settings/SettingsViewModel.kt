package com.example.freetv.presentation.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.freetv.data.repository.PlaylistRepositoryImpl
import com.example.freetv.domain.model.AppSettings
import com.example.freetv.domain.model.BufferProfile
import com.example.freetv.domain.model.ChannelGroup
import com.example.freetv.domain.model.GroupType
import com.example.freetv.domain.model.StartScreen
import com.example.freetv.domain.model.StartupLanguageMode
import com.example.freetv.domain.repository.BackupRepository
import com.example.freetv.domain.repository.EpgRepository
import com.example.freetv.domain.repository.GroupRepository
import com.example.freetv.domain.repository.PlaylistRepository
import com.example.freetv.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val epg: EpgRepository,
    private val backup: BackupRepository,
    groups: GroupRepository,
    playlists: PlaylistRepository,
) : ViewModel() {

    val state: StateFlow<AppSettings?> = settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val languageGroups: StateFlow<List<ChannelGroup>> = groups.observeGroups()
        .map { list -> list.filter { it.type == GroupType.LANGUAGE } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** EPG URL advertised by an imported playlist (url-tvg), offered as a shortcut. */
    val playlistEpgUrl: StateFlow<String?> = playlists.observePlaylists()
        .map { list -> list.firstNotNullOfOrNull { it.headerEpgUrl } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val busy = MutableStateFlow<String?>(null)
    val message = MutableStateFlow<String?>(null)
    /** Set after a successful restore: the UI restarts the app. */
    val restartRequired = MutableStateFlow(false)

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    fun setStartupLanguageMode(mode: StartupLanguageMode) = launch { settings.setStartupLanguageMode(mode) }
    fun setDefaultLanguage(groupId: Long?) = launch { settings.setDefaultLanguageGroupId(groupId) }
    fun setAutoRefresh(value: Boolean) = launch { settings.setAutoRefresh(value) }
    fun setRefreshInterval(hours: Int) = launch { settings.setRefreshIntervalHours(hours) }
    fun setAutoNumber(value: Boolean) = launch { settings.setAutoNumberNewChannels(value) }
    fun setBuffer(profile: BufferProfile) = launch { settings.setBufferProfile(profile) }
    fun setRetries(value: Int) = launch { settings.setMaxRetriesPerStream(value) }
    fun setNumericTimeout(ms: Long) = launch { settings.setNumericInputTimeoutMs(ms) }
    fun setDigits(value: Int) = launch { settings.setChannelNumberDigits(value) }
    fun setAutoPlay(value: Boolean) = launch { settings.setAutoPlayLastChannel(value) }
    fun setStartScreen(screen: StartScreen) = launch { settings.setStartScreen(screen) }
    fun setPureBlack(value: Boolean) = launch { settings.setPureBlackTheme(value) }
    fun setEpgUrl(url: String) = launch { settings.setEpgUrl(url) }
    fun setEpgAuto(value: Boolean) = launch { settings.setEpgAutoRefresh(value) }
    fun setEpgOffset(minutes: Int) = launch { settings.setEpgOffsetMinutes(minutes) }

    fun refreshEpg() = launch {
        val s = settings.current()
        val url = s.epgUrl
        if (url.isNullOrBlank()) {
            message.value = "Enter the XMLTV guide URL first."
            return@launch
        }
        busy.value = "Loading TV guide…"
        val result = epg.refresh(url, s.epgOffsetMinutes) { busy.value = it }
        busy.value = null
        message.value = result.message
    }

    fun clearEpg() = launch {
        epg.clear()
        message.value = "TV guide data cleared."
    }

    fun clearCache() = launch {
        busy.value = "Clearing cache…"
        val freed = backup.clearCache()
        busy.value = null
        message.value = "Cache cleared (${PlaylistRepositoryImpl.formatBytes(freed)} freed)."
    }

    fun backupTo(uri: Uri) = launch {
        backup.backupTo(uri).fold(
            onSuccess = { message.value = "Backup saved." },
            onFailure = { message.value = "Backup failed: ${it.message}" },
        )
    }

    fun quickBackup() = launch {
        backup.quickBackup().fold(
            onSuccess = { message.value = "Backup saved to ${it.path}" },
            onFailure = { message.value = "Backup failed: ${it.message}" },
        )
    }

    fun restoreFrom(uri: Uri) = launch {
        backup.restoreFrom(uri).fold(
            onSuccess = { restartRequired.value = true },
            onFailure = { message.value = "Restore failed: ${it.message}" },
        )
    }

    fun quickRestore() = launch {
        backup.quickRestore().fold(
            onSuccess = { restartRequired.value = true },
            onFailure = { message.value = "Restore failed: ${it.message}" },
        )
    }

    fun quickBackupPath(): String = backup.quickBackupFile().path
}
