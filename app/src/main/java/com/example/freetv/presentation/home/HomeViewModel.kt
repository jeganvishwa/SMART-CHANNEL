package com.example.freetv.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.freetv.data.network.NetworkMonitor
import com.example.freetv.data.repository.LanguagePlaylistInitializer
import com.example.freetv.domain.model.AppSettings
import com.example.freetv.domain.model.ChannelGroup
import com.example.freetv.domain.model.ChannelItem
import com.example.freetv.domain.model.EpgProgram
import com.example.freetv.domain.model.GroupType
import com.example.freetv.domain.model.LanguageCatalog
import com.example.freetv.domain.model.PlaylistStatus
import com.example.freetv.domain.repository.ChannelRepository
import com.example.freetv.domain.repository.EpgRepository
import com.example.freetv.domain.repository.GroupRepository
import com.example.freetv.domain.repository.PlaylistRepository
import com.example.freetv.domain.repository.SettingsRepository
import com.example.freetv.domain.usecase.ResolveChannelNumberUseCase
import com.example.freetv.player.NumericChannelController
import com.example.freetv.preferences.StartupPreferences
import com.example.freetv.startup.HomeTabRestorer
import com.example.freetv.startup.PlaybackRestoreManager
import com.example.freetv.startup.StartupDiagnosticsManager
import com.example.freetv.startup.TabCandidate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A tab on the home screen: favourites, recently watched, all channels, or a group. */
data class HomeTab(val key: String, val title: String, val groupId: Long?, val isLanguage: Boolean, val groupName: String? = null)

data class HomeUiState(
    val loading: Boolean = true,
    val hasPlaylists: Boolean = false,
    val tabs: List<HomeTab> = emptyList(),
    val selectedTab: String? = null,
    val channels: List<ChannelItem> = emptyList(),
    val numberDigits: Int = 3,
    val online: Boolean = true,
    /** Progress text while the language playlists are being imported, or null. */
    val importStatus: String? = null,
)

sealed interface HomeEvent {
    data class OpenPlayer(val channelId: Long, val contextType: String, val contextId: Long) : HomeEvent
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val channels: ChannelRepository,
    groups: GroupRepository,
    private val playlists: PlaylistRepository,
    private val epg: EpgRepository,
    private val settings: SettingsRepository,
    private val startupPrefs: StartupPreferences,
    private val resolveNumber: ResolveChannelNumberUseCase,
    private val networkMonitor: NetworkMonitor,
    private val restoreManager: PlaybackRestoreManager,
    private val diagnostics: StartupDiagnosticsManager,
    languagePlaylists: LanguagePlaylistInitializer,
) : ViewModel() {

    private val selectedTab = MutableStateFlow<String?>(null)
    private val events = Channel<HomeEvent>(Channel.BUFFERED)
    val eventFlow: Flow<HomeEvent> = events.receiveAsFlow()

    /** Short transient message (pill). */
    val message = MutableStateFlow<String?>(null)

    /** Message that needs acknowledging (dialog): start-up problems. */
    val startupMessage: StateFlow<String?> = restoreManager.homeMessage

    /** Start-up diagnostics summary (only when enabled in settings). */
    val diagnosticsText = MutableStateFlow<String?>(null)

    private val tabs: Flow<List<HomeTab>> = groups.observeGroups().map { list -> buildTabs(list) }

    private var currentSettings = AppSettings()
    private var maxDigits = 3

    val numeric = NumericChannelController(
        scope = viewModelScope,
        timeoutMs = { currentSettings.numericInputTimeoutMs },
        maxDigits = { maxDigits },
        onCommit = ::openNumber,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val tabChannels: Flow<List<ChannelItem>> = selectedTab.flatMapLatest { key ->
        when {
            key == null -> flowOf(emptyList())
            key == TAB_FAVORITES -> channels.observeFavorites()
            key == TAB_RECENT -> channels.observeRecentlyWatched()
            key == TAB_ALL -> channels.observeAllVisible()
            key.startsWith("g:") -> channels.observeVisibleInGroup(key.removePrefix("g:").toLong())
            else -> flowOf(emptyList())
        }
    }

    private val importStatus: Flow<String?> = languagePlaylists.status.map { s ->
        if (s.running) "Loading ${s.current ?: "playlists"}…" else null
    }

    val uiState: StateFlow<HomeUiState> = combine(
        combine(tabs, selectedTab, tabChannels) { a, b, c -> Triple(a, b, c) },
        playlists.observePlaylistCount(),
        settings.settings,
        networkMonitor.isOnline,
        importStatus,
    ) { (tabList, selected, list), playlistCount, s, online, importing ->
        HomeUiState(
            loading = selected == null,
            hasPlaylists = playlistCount > 0,
            tabs = tabList,
            selectedTab = selected,
            channels = list,
            numberDigits = s.channelNumberDigits,
            online = online,
            importStatus = importing,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /** Current EPG programme per EPG channel id (empty when no guide is loaded). */
    val currentPrograms: StateFlow<Map<String, EpgProgram>> =
        epg.observeCurrentPrograms().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    init {
        viewModelScope.launch { settings.settings.collect { currentSettings = it } }
        viewModelScope.launch {
            channels.observeMaxNumber().collect { max ->
                maxDigits = maxOf(currentSettings.channelNumberDigits, (max ?: 0).toString().length)
            }
        }
        viewModelScope.launch { restoreTab() }
        viewModelScope.launch {
            if (startupPrefs.current().showStartupDiagnostics) {
                diagnosticsText.value = diagnostics.toText(diagnostics.run())
            }
        }
    }

    /** Restores the remembered group / language (or the default / Tamil) on start. */
    private suspend fun restoreTab() {
        val s = settings.current()
        val prefs = startupPrefs.current()
        val tabList = tabs.first()
        val choice = HomeTabRestorer.choose(
            tabs = tabList.map { TabCandidate(it.key, it.title, it.isLanguage, it.groupName) },
            mode = s.startupLanguageMode,
            defaultLanguageTab = s.defaultLanguageGroupId?.let { "g:$it" },
            restoreLanguage = prefs.restoreLastLanguage,
            restoreGroup = prefs.restoreLastGroup,
            lastTab = s.lastSelectedTab,
            lastLanguageTab = prefs.lastLanguageTab,
            allTabKey = TAB_ALL,
        )
        selectedTab.value = choice.key
        choice.message?.let(::showMessage)
    }

    fun selectTab(key: String) {
        if (selectedTab.value == key) return
        selectedTab.value = key
        val isLanguage = uiState.value.tabs.firstOrNull { it.key == key }?.isLanguage == true
        viewModelScope.launch {
            settings.setLastSelectedTab(key)
            if (isLanguage) startupPrefs.setLastLanguageTab(key)
        }
    }

    fun openChannel(channel: ChannelItem) {
        val key = selectedTab.value
        val (type, id) = when {
            key == TAB_FAVORITES -> "FAVORITES" to -1L
            key != null && key.startsWith("g:") -> "GROUP" to key.removePrefix("g:").toLong()
            else -> "ALL" to -1L
        }
        events.trySend(HomeEvent.OpenPlayer(channel.id, type, id))
    }

    fun toggleFavorite(channel: ChannelItem) {
        viewModelScope.launch {
            val now = channels.toggleFavorite(channel.id)
            showMessage(if (now) "Added ${channel.name} to favourites" else "Removed ${channel.name} from favourites")
        }
    }

    /** Manual retry from the offline banner: re-checks the network and refreshes failed playlists once. */
    fun retryConnection() {
        viewModelScope.launch {
            if (!networkMonitor.currentlyOnline()) {
                showMessage("Still offline. Check the TV's network connection.")
                return@launch
            }
            showMessage("Back online")
            playlists.observePlaylists().first()
                .filter { it.enabled && it.status == PlaylistStatus.ERROR }
                .forEach { playlists.refresh(it.id) }
        }
    }

    fun dismissStartupMessage() = restoreManager.consumeHomeMessage()

    fun dismissDiagnostics() {
        diagnosticsText.value = null
    }

    private fun openNumber(number: Int) {
        viewModelScope.launch {
            val channel = resolveNumber(number)
            if (channel == null) {
                showMessage("Channel $number not found")
            } else {
                events.trySend(HomeEvent.OpenPlayer(channel.id, "ALL", -1))
            }
        }
    }

    private fun showMessage(text: String) {
        message.value = text
        viewModelScope.launch {
            delay(3_000)
            if (message.value == text) message.value = null
        }
    }

    private fun buildTabs(groups: List<ChannelGroup>): List<HomeTab> {
        val visible = groups.filter { it.showOnHome && !it.hidden }
        // Built-in languages first in catalogue order (Tamil, English, Hindi, Malayalam), then other languages.
        val languages = visible.filter { it.type == GroupType.LANGUAGE }
            .sortedWith(compareBy({ g -> LanguageCatalog.PLAYLISTS.indexOfFirst { it.groupName.equals(g.name, true) }.let { if (it < 0) 99 else it } }, { it.position }))
        val others = visible.filter { it.type != GroupType.LANGUAGE }
        return buildList {
            languages.forEach { add(HomeTab("g:${it.id}", LanguageCatalog.labelFor(it.name), it.id, true, it.name)) }
            add(HomeTab(TAB_FAVORITES, "Favourites", null, false))
            add(HomeTab(TAB_RECENT, "Recently watched", null, false))
            add(HomeTab(TAB_ALL, "All channels", null, false))
            others.forEach { add(HomeTab("g:${it.id}", it.name, it.id, false, it.name)) }
        }
    }

    companion object {
        const val TAB_FAVORITES = "fav"
        const val TAB_RECENT = "recent"
        const val TAB_ALL = "all"
    }
}
