package com.example.freetv.presentation.channels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.example.freetv.domain.model.ChannelDetail
import com.example.freetv.domain.model.ChannelGroup
import com.example.freetv.domain.model.ChannelItem
import com.example.freetv.domain.model.ChannelStream
import com.example.freetv.domain.model.NumberAssignResult
import com.example.freetv.domain.repository.ChannelRepository
import com.example.freetv.domain.repository.GroupRepository
import com.example.freetv.domain.repository.PlaylistRepository
import com.example.freetv.domain.repository.SettingsRepository
import com.example.freetv.domain.usecase.AssignChannelNumberUseCase
import com.example.freetv.presentation.navigation.FreeTvRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.FlowPreview
import javax.inject.Inject

/** Pending duplicate-number decision shown to the user. */
data class NumberConflict(val channelId: Long, val number: Int, val otherName: String)

/** Shared number assignment logic for the channel editor and number manager. */
abstract class NumberEditingViewModel(
    private val channels: ChannelRepository,
    private val assignNumber: AssignChannelNumberUseCase,
) : ViewModel() {
    val conflict = MutableStateFlow<NumberConflict?>(null)
    val message = MutableStateFlow<String?>(null)

    fun setNumber(channelId: Long, input: String) {
        viewModelScope.launch {
            when (val result = assignNumber(channelId, input)) {
                NumberAssignResult.Success -> Unit
                is NumberAssignResult.Conflict ->
                    conflict.value = NumberConflict(channelId, input.trim().toInt(), result.otherChannelName)
                is NumberAssignResult.Invalid -> message.value = result.reason
            }
        }
    }

    fun swapConflict() {
        val c = conflict.value ?: return
        conflict.value = null
        viewModelScope.launch { channels.swapNumber(c.channelId, c.number) }
    }

    fun dismissConflict() {
        conflict.value = null
    }

    fun clearMessage() {
        message.value = null
    }
}

// ---------------------------------------------------------------------------
// Channel manager / detected channels
// ---------------------------------------------------------------------------

@HiltViewModel
class ChannelManagerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val channels: ChannelRepository,
    playlists: PlaylistRepository,
    settings: SettingsRepository,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<FreeTvRoute.Channels>()
    val playlistId: Long? = route.playlistId.takeIf { it > 0 }

    val query = MutableStateFlow("")
    val message = MutableStateFlow<String?>(null)
    val title = MutableStateFlow("Channel manager")

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val channelList: StateFlow<List<ChannelItem>?> = query.debounce(200).flatMapLatest { q ->
        channels.observeForManager(playlistId, q)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val digits: StateFlow<Int> = settings.settings.map { it.channelNumberDigits }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 3)

    init {
        if (playlistId != null) {
            viewModelScope.launch {
                playlists.getPlaylist(playlistId)?.let { title.value = "Detected channels – ${it.name}" }
            }
        }
    }

    fun setQuery(value: String) {
        query.value = value.trim()
    }

    fun createChannel(name: String, url: String, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            runCatching { channels.createChannel(name, url, null) }
                .onSuccess(onCreated)
                .onFailure { message.value = it.message ?: "Could not create channel" }
        }
    }
}

// ---------------------------------------------------------------------------
// Channel editor
// ---------------------------------------------------------------------------

data class ChannelEditState(
    val channel: ChannelDetail? = null,
    val streams: List<ChannelStream> = emptyList(),
    val groups: List<ChannelGroup> = emptyList(),
    val memberOf: Set<Long> = emptySet(),
    val isFavorite: Boolean = false,
    val digits: Int = 3,
)

@HiltViewModel
class ChannelEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val channels: ChannelRepository,
    private val groups: GroupRepository,
    settings: SettingsRepository,
    assignNumber: AssignChannelNumberUseCase,
) : NumberEditingViewModel(channels, assignNumber) {

    val channelId = savedStateHandle.toRoute<FreeTvRoute.ChannelEdit>().channelId

    val testing = MutableStateFlow<Long?>(null)
    val deleted = MutableStateFlow(false)

    val state: StateFlow<ChannelEditState> = combine(
        channels.observeChannel(channelId),
        channels.observeStreams(channelId),
        groups.observeGroups(),
        groups.observeGroupIdsForChannel(channelId),
        combine(channels.observeIsFavorite(channelId), settings.settings) { fav, s -> fav to s.channelNumberDigits },
    ) { channel, streams, groupList, memberIds, (fav, digits) ->
        ChannelEditState(channel, streams, groupList, memberIds.toSet(), fav, digits)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChannelEditState())

    fun updateDetails(name: String? = null, logo: String? = null, language: String? = null) {
        val c = state.value.channel ?: return
        viewModelScope.launch {
            channels.updateChannel(c.id, name ?: c.name, logo ?: c.logoUrl, language ?: c.language)
        }
    }

    fun setFavorite(value: Boolean) = viewModelScope.launch { channels.setFavorite(channelId, value) }
    fun setHidden(value: Boolean) = viewModelScope.launch { channels.setHidden(channelId, value) }
    fun setGroup(groupId: Long, member: Boolean) = viewModelScope.launch { groups.setMembership(channelId, groupId, member) }

    fun addStream(url: String, label: String?) = viewModelScope.launch {
        channels.addStream(channelId, url, label).onFailure { message.value = it.message }
    }

    fun editStream(id: Long, url: String, label: String?) = viewModelScope.launch {
        channels.updateStream(id, url, label).onFailure { message.value = it.message }
    }

    fun deleteStream(id: Long) = viewModelScope.launch { channels.deleteStream(id) }
    fun makePrimary(id: Long) = viewModelScope.launch { channels.makePrimary(id) }
    fun moveStream(id: Long, up: Boolean) = viewModelScope.launch { channels.moveStream(id, up) }

    fun testStream(id: Long) {
        if (testing.value != null) return
        testing.value = id
        viewModelScope.launch {
            val result = channels.testStream(id)
            testing.value = null
            message.value = if (result.ok) "Stream reachable: ${result.message}" else "Stream failed: ${result.message}"
        }
    }

    fun deleteChannel() = viewModelScope.launch {
        channels.deleteChannel(channelId)
        deleted.value = true
    }
}

// ---------------------------------------------------------------------------
// Channel number manager
// ---------------------------------------------------------------------------

@HiltViewModel
class ChannelNumbersViewModel @Inject constructor(
    private val channels: ChannelRepository,
    settings: SettingsRepository,
    assignNumber: AssignChannelNumberUseCase,
) : NumberEditingViewModel(channels, assignNumber) {

    val channelList: StateFlow<List<ChannelItem>?> = channels.observeForManager(null, "")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val digits: StateFlow<Int> = settings.settings.map { it.channelNumberDigits }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 3)

    fun moveNumber(channelId: Long, up: Boolean) = viewModelScope.launch { channels.moveNumber(channelId, up) }

    fun autoNumberAll() = viewModelScope.launch {
        channels.autoNumberAll(1)
        message.value = "All channels renumbered from 1"
    }
}
