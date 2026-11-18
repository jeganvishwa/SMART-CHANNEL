package com.example.freetv.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.freetv.domain.model.ChannelGroup
import com.example.freetv.domain.model.ChannelItem
import com.example.freetv.domain.model.EpgProgram
import com.example.freetv.domain.model.GroupType
import com.example.freetv.domain.model.StartupLanguageMode
import com.example.freetv.domain.repository.ChannelRepository
import com.example.freetv.domain.repository.EpgRepository
import com.example.freetv.domain.repository.GroupRepository
import com.example.freetv.domain.repository.PlaylistRepository
import com.example.freetv.domain.repository.SettingsRepository
import com.example.freetv.domain.usecase.ResolveChannelNumberUseCase
import com.example.freetv.player.NumericChannelController
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

/** A tab on the home screen: favourites, all channels, or a group (language / category / custom). */
data class HomeTab(val key: String, val title: String, val groupId: Long?, val isLanguage: Boolean)

data class HomeUiState(
    val loading: Boolean = true,
    val hasPlaylists: Boolean = false,
    val tabs: List<HomeTab> = emptyList(),
    val selectedTab: String? = null,
    val channels: List<ChannelItem> = emptyList(),
    val numberDigits: Int = 3,
)

sealed interface HomeEvent {
    data class OpenPlayer(val channelId: Long, val contextType: String, val contextId: Long) : HomeEvent
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val channels: ChannelRepository,
    groups: GroupRepository,
    playlists: PlaylistRepository,
    private val epg: EpgRepository,
    private val settings: SettingsRepository,
    private val resolveNumber: ResolveChannelNumberUseCase,
) : ViewModel() {

    private val selectedTab = MutableStateFlow<String?>(null)
    private val events = Channel<HomeEvent>(Channel.BUFFERED)
    val eventFlow: Flow<HomeEvent> = events.receiveAsFlow()

    val message = MutableStateFlow<String?>(null)

    private val tabs: Flow<List<HomeTab>> = groups.observeGroups().map { list -> buildTabs(list) }

    val numeric = NumericChannelController(
        scope = viewModelScope,
        timeoutMs = { currentSettings.numericInputTimeoutMs },
        maxDigits = { maxDigits },
        onCommit = ::openNumber,
    )

    private var currentSettings = com.example.freetv.domain.model.AppSettings()
    private var maxDigits = 3

    @OptIn(ExperimentalCoroutinesApi::class)
    private val tabChannels: Flow<List<ChannelItem>> = selectedTab.flatMapLatest { key ->
        when {
            key == null -> flowOf(emptyList())
            key == TAB_FAVORITES -> channels.observeFavorites()
            key == TAB_ALL -> channels.observeAllVisible()
            key.startsWith("g:") -> channels.observeVisibleInGroup(key.removePrefix("g:").toLong())
            else -> flowOf(emptyList())
        }
    }

    val uiState: StateFlow<HomeUiState> = combine(
        tabs,
        selectedTab,
        tabChannels,
        playlists.observePlaylistCount(),
        settings.settings,
    ) { tabList, selected, list, playlistCount, s ->
        HomeUiState(
            loading = selected == null && tabList.isEmpty(),
            hasPlaylists = playlistCount > 0,
            tabs = tabList,
            selectedTab = selected,
            channels = list,
            numberDigits = s.channelNumberDigits,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /** Current EPG programme per EPG channel id (empty when no guide is loaded). */
    val currentPrograms: StateFlow<Map<String, EpgProgram>> =
        epg.observeCurrentPrograms().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    init {
        viewModelScope.launch {
            settings.settings.collect { s ->
                currentSettings = s
            }
        }
        viewModelScope.launch {
            channels.observeMaxNumber().collect { max ->
                maxDigits = maxOf(currentSettings.channelNumberDigits, (max ?: 0).toString().length)
            }
        }
        viewModelScope.launch { restoreLanguage() }
    }

    /** Restores the remembered language / tab (or the default language) on start. */
    private suspend fun restoreLanguage() {
        val s = settings.current()
        val tabList = tabs.first()
        val keys = tabList.map { it.key }.toSet()
        val remembered = s.lastSelectedTab?.takeIf { it in keys }
        val default = s.defaultLanguageGroupId?.let { "g:$it" }?.takeIf { it in keys }
        selectedTab.value = when (s.startupLanguageMode) {
            StartupLanguageMode.LAST_SELECTED -> remembered ?: default
            StartupLanguageMode.DEFAULT_LANGUAGE -> default ?: remembered
        } ?: TAB_ALL
    }

    fun selectTab(key: String) {
        if (selectedTab.value == key) return
        selectedTab.value = key
        viewModelScope.launch { settings.setLastSelectedTab(key) }
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
            delay(2_500)
            if (message.value == text) message.value = null
        }
    }

    private fun buildTabs(groups: List<ChannelGroup>): List<HomeTab> {
        val visible = groups.filter { it.showOnHome && !it.hidden }
        val languages = visible.filter { it.type == GroupType.LANGUAGE }
        val others = visible.filter { it.type != GroupType.LANGUAGE }
        return buildList {
            languages.forEach { add(HomeTab("g:${it.id}", it.name, it.id, true)) }
            add(HomeTab(TAB_FAVORITES, "Favourites", null, false))
            add(HomeTab(TAB_ALL, "All channels", null, false))
            others.forEach { add(HomeTab("g:${it.id}", it.name, it.id, false)) }
        }
    }

    companion object {
        const val TAB_FAVORITES = "fav"
        const val TAB_ALL = "all"
    }
}
