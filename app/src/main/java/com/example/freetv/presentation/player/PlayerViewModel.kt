package com.example.freetv.presentation.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.navigation.toRoute
import com.example.freetv.data.network.NetworkMonitor
import com.example.freetv.domain.model.ChannelItem
import com.example.freetv.domain.model.ChannelStream
import com.example.freetv.domain.model.NowNext
import com.example.freetv.domain.model.PlaybackContextType
import com.example.freetv.domain.model.StreamStatus
import com.example.freetv.domain.repository.ChannelRepository
import com.example.freetv.domain.repository.EpgRepository
import com.example.freetv.domain.repository.SettingsRepository
import com.example.freetv.domain.usecase.GetPlaybackListUseCase
import com.example.freetv.domain.usecase.RememberWatchedChannelUseCase
import com.example.freetv.domain.usecase.ResolveChannelNumberUseCase
import com.example.freetv.player.ExoPlayerManager
import com.example.freetv.player.NumericChannelController
import com.example.freetv.player.PlaybackController
import com.example.freetv.player.TrackOption
import com.example.freetv.presentation.navigation.FreeTvRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class PlayerPanel { NONE, CHANNELS, MENU, SOURCES, AUDIO, SUBTITLES }

data class PlayerUiState(
    val channel: ChannelItem? = null,
    val streams: List<ChannelStream> = emptyList(),
    val nowNext: NowNext = NowNext(null, null),
    val isFavorite: Boolean = false,
    val numberDigits: Int = 3,
    val resizeMode: Int = AspectRatioFrameLayout.RESIZE_MODE_FIT,
    val infoVisible: Boolean = true,
    val panel: PlayerPanel = PlayerPanel.NONE,
    val message: String? = null,
    val channelList: List<ChannelItem> = emptyList(),
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    playerManager: ExoPlayerManager,
    networkMonitor: NetworkMonitor,
    private val channels: ChannelRepository,
    private val epg: EpgRepository,
    private val settings: SettingsRepository,
    private val getPlaybackList: GetPlaybackListUseCase,
    private val resolveNumber: ResolveChannelNumberUseCase,
    private val rememberWatched: RememberWatchedChannelUseCase,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<FreeTvRoute.Player>()

    val controller = PlaybackController(
        playerManager = playerManager,
        scope = viewModelScope,
        isOnline = networkMonitor.isOnline,
        onStreamResult = { id, ok ->
            viewModelScope.launch { channels.setStreamStatus(id, if (ok) StreamStatus.OK else StreamStatus.FAILED) }
        },
    )

    private val _ui = MutableStateFlow(PlayerUiState())
    val ui: StateFlow<PlayerUiState> = _ui.asStateFlow()

    private var contextType = PlaybackContextType.entries.firstOrNull { it.name == route.contextType } ?: PlaybackContextType.ALL
    private var contextId = route.contextId
    private var previousChannelId: Long? = null
    private var infoJob: Job? = null
    private var messageJob: Job? = null
    private var favoriteJob: Job? = null
    private var timeoutMs = 2_000L
    private var maxDigits = 3

    val numeric = NumericChannelController(
        scope = viewModelScope,
        timeoutMs = { timeoutMs },
        maxDigits = { maxDigits },
        onCommit = ::playNumber,
    )

    init {
        viewModelScope.launch {
            settings.settings
                .map { Triple(it.maxRetriesPerStream, it.bufferProfile, it) }
                .distinctUntilChanged { a, b -> a.first == b.first && a.second == b.second }
                .collect { (retries, profile, _) -> controller.configure(retries, profile) }
        }
        viewModelScope.launch {
            settings.settings.collect { s ->
                timeoutMs = s.numericInputTimeoutMs
                _ui.update { it.copy(numberDigits = s.channelNumberDigits) }
            }
        }
        viewModelScope.launch {
            channels.observeMaxNumber().collect { max ->
                maxDigits = maxOf(_ui.value.numberDigits, (max ?: 0).toString().length)
            }
        }
        viewModelScope.launch {
            val list = getPlaybackList(contextType, contextId)
            _ui.update { it.copy(channelList = list) }
            play(route.channelId, rememberPrevious = false)
        }
    }

    fun onStart() = controller.attach()

    fun onStop() = controller.detach()

    override fun onCleared() {
        controller.detach()
        super.onCleared()
    }

    fun play(channelId: Long, rememberPrevious: Boolean = true) {
        viewModelScope.launch {
            val current = _ui.value.channel
            if (current?.id == channelId && _ui.value.streams.isNotEmpty()) return@launch
            val detail = channels.getChannel(channelId)
            if (detail == null) {
                showMessage("Channel not available")
                return@launch
            }
            if (rememberPrevious && current != null) previousChannelId = current.id

            var list = _ui.value.channelList
            if (list.none { it.id == channelId }) {
                contextType = PlaybackContextType.ALL
                contextId = -1
                list = getPlaybackList(PlaybackContextType.ALL, -1)
            }
            val item = list.firstOrNull { it.id == channelId } ?: ChannelItem(
                id = detail.id, name = detail.name, number = detail.number, logoUrl = detail.logoUrl,
                tvgId = detail.tvgId, tvgName = detail.tvgName, language = detail.language,
                originalGroup = detail.originalGroup, playlistId = detail.playlistId, hidden = detail.hidden,
                inSource = detail.inSource, isFavorite = false,
            )
            val streams = channels.getStreams(channelId)
            _ui.update {
                it.copy(channel = item, streams = streams, channelList = list, nowNext = NowNext(null, null), panel = PlayerPanel.NONE)
            }
            controller.playChannel(streams)
            showInfo()
            rememberWatched(channelId)
            observeFavorite(channelId)
            _ui.update { it.copy(nowNext = epg.getNowNext(item.tvgId, item.tvgName, item.name)) }
        }
    }

    private fun observeFavorite(channelId: Long) {
        favoriteJob?.cancel()
        favoriteJob = viewModelScope.launch {
            channels.observeIsFavorite(channelId).collect { fav -> _ui.update { it.copy(isFavorite = fav) } }
        }
    }

    fun next() = step(+1)

    fun previous() = step(-1)

    private fun step(delta: Int) {
        val list = _ui.value.channelList
        if (list.isEmpty()) return
        val index = list.indexOfFirst { it.id == _ui.value.channel?.id }
        val target = if (index < 0) 0 else (index + delta + list.size) % list.size
        play(list[target].id)
    }

    fun lastChannel() {
        val id = previousChannelId ?: return showMessage("No previous channel")
        play(id)
    }

    private fun playNumber(number: Int) {
        viewModelScope.launch {
            val channel = resolveNumber(number)
            if (channel == null) showMessage("Channel $number not found") else play(channel.id)
        }
    }

    fun toggleFavorite() {
        val channel = _ui.value.channel ?: return
        viewModelScope.launch {
            val fav = channels.toggleFavorite(channel.id)
            showMessage(if (fav) "Added to favourites" else "Removed from favourites")
        }
    }

    fun cycleAspectRatio() {
        val next = when (_ui.value.resizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        _ui.update { it.copy(resizeMode = next) }
        showMessage("Aspect ratio: ${aspectLabel(next)}")
    }

    fun selectStream(index: Int) {
        controller.selectStream(index)
        closePanel()
        showMessage("Source ${index + 1}")
    }

    fun audioOptions(): List<TrackOption> = controller.trackOptions(C.TRACK_TYPE_AUDIO)
    fun subtitleOptions(): List<TrackOption> = controller.trackOptions(C.TRACK_TYPE_TEXT)

    fun selectAudio(option: TrackOption) {
        controller.selectTrack(C.TRACK_TYPE_AUDIO, option)
        closePanel()
    }

    fun selectSubtitle(option: TrackOption?) {
        controller.selectTrack(C.TRACK_TYPE_TEXT, option)
        closePanel()
    }

    fun retry() = controller.retryAll()

    fun togglePlayPause() = controller.togglePlayPause()

    fun openPanel(panel: PlayerPanel) {
        _ui.update { it.copy(panel = panel, infoVisible = false) }
    }

    fun closePanel() {
        _ui.update { it.copy(panel = PlayerPanel.NONE) }
    }

    fun showInfo() {
        infoJob?.cancel()
        _ui.update { it.copy(infoVisible = true) }
        infoJob = viewModelScope.launch {
            delay(INFO_TIMEOUT_MS)
            _ui.update { it.copy(infoVisible = false) }
        }
    }

    fun hideInfo() {
        infoJob?.cancel()
        _ui.update { it.copy(infoVisible = false) }
    }

    private fun showMessage(text: String) {
        messageJob?.cancel()
        _ui.update { it.copy(message = text) }
        messageJob = viewModelScope.launch {
            delay(2_500)
            _ui.update { it.copy(message = null) }
        }
    }

    companion object {
        private const val INFO_TIMEOUT_MS = 5_000L

        fun aspectLabel(mode: Int): String = when (mode) {
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoom"
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Stretch"
            else -> "Fit"
        }
    }
}
