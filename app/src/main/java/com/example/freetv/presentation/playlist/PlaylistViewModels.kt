package com.example.freetv.presentation.playlist

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.example.freetv.domain.model.ImportProgress
import com.example.freetv.domain.model.ImportResult
import com.example.freetv.domain.model.Playlist
import com.example.freetv.domain.model.PlaylistSourceType
import com.example.freetv.domain.repository.PlaylistRepository
import com.example.freetv.presentation.navigation.FreeTvRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

// ---------------------------------------------------------------------------
// Playlist manager
// ---------------------------------------------------------------------------

@HiltViewModel
class PlaylistManagerViewModel @Inject constructor(
    private val repository: PlaylistRepository,
) : ViewModel() {

    val playlists: StateFlow<List<Playlist>?> = repository.observePlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _progress = MutableStateFlow<Map<Long, ImportProgress>>(emptyMap())
    val progress: StateFlow<Map<Long, ImportProgress>> = _progress.asStateFlow()

    val message = MutableStateFlow<String?>(null)

    fun refresh(id: Long) {
        if (id in _progress.value) return
        viewModelScope.launch {
            _progress.update { it + (id to ImportProgress(com.example.freetv.domain.model.ImportStage.DOWNLOADING)) }
            val result = repository.refresh(id) { p -> _progress.update { it + (id to p) } }
            _progress.update { it - id }
            message.value = when (result) {
                is ImportResult.Success -> "Updated: ${result.channelCount} channels (${result.newChannels} new, ${result.removedChannels} removed)"
                is ImportResult.Failure -> "Refresh failed: ${result.message}"
                is ImportResult.DuplicatePlaylist -> null
            }
        }
    }

    fun refreshAll() {
        playlists.value.orEmpty().filter { it.enabled }.forEach { refresh(it.id) }
    }

    fun setEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch { repository.setEnabled(id, enabled) }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            repository.delete(id)
            message.value = "Playlist deleted"
        }
    }

    fun clearMessage() {
        message.value = null
    }
}

// ---------------------------------------------------------------------------
// Add / edit playlist (URL or local file)
// ---------------------------------------------------------------------------

sealed interface AddPlaylistStatus {
    data object Editing : AddPlaylistStatus
    data class Working(val progress: ImportProgress) : AddPlaylistStatus
    data class Error(val message: String) : AddPlaylistStatus
    data class Duplicate(val existingName: String) : AddPlaylistStatus
}

data class AddPlaylistUiState(
    val isEdit: Boolean = false,
    val isFileMode: Boolean = false,
    val name: String = "",
    val url: String = "",
    val filePath: String = "",
    val autoRefresh: Boolean = true,
    val status: AddPlaylistStatus = AddPlaylistStatus.Editing,
)

sealed interface AddPlaylistEvent {
    data class Imported(val playlistId: Long, val summary: String) : AddPlaylistEvent
    data object Saved : AddPlaylistEvent
}

@HiltViewModel
class AddPlaylistViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: PlaylistRepository,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<FreeTvRoute.AddPlaylist>()

    private val _ui = MutableStateFlow(
        AddPlaylistUiState(isEdit = route.playlistId > 0, isFileMode = route.mode == "FILE"),
    )
    val ui: StateFlow<AddPlaylistUiState> = _ui.asStateFlow()

    private val events = Channel<AddPlaylistEvent>(Channel.BUFFERED)
    val eventFlow: Flow<AddPlaylistEvent> = events.receiveAsFlow()

    init {
        if (route.playlistId > 0) {
            viewModelScope.launch {
                repository.getPlaylist(route.playlistId)?.let { p ->
                    _ui.update {
                        it.copy(
                            name = p.name,
                            url = p.url.orEmpty(),
                            autoRefresh = p.autoRefresh,
                            isFileMode = p.sourceType == PlaylistSourceType.FILE,
                        )
                    }
                }
            }
        }
    }

    fun setName(value: String) = _ui.update { it.copy(name = value) }
    fun setUrl(value: String) = _ui.update { it.copy(url = value.trim()) }
    fun setFilePath(value: String) = _ui.update { it.copy(filePath = value.trim()) }
    fun setAutoRefresh(value: Boolean) = _ui.update { it.copy(autoRefresh = value) }
    fun dismissStatus() = _ui.update { it.copy(status = AddPlaylistStatus.Editing) }
    fun showError(message: String) = _ui.update { it.copy(status = AddPlaylistStatus.Error(message)) }

    fun save(allowDuplicate: Boolean = false) {
        val state = _ui.value
        if (state.status is AddPlaylistStatus.Working) return
        viewModelScope.launch {
            if (state.isEdit) {
                val result = repository.updatePlaylist(route.playlistId, state.name, state.url, state.autoRefresh)
                result.fold(
                    onSuccess = { events.send(AddPlaylistEvent.Saved) },
                    onFailure = { e -> showError(e.message ?: "Could not save") },
                )
                return@launch
            }
            if (state.url.isBlank()) {
                showError("Enter the playlist URL first.")
                return@launch
            }
            working()
            handle(repository.importFromUrl(state.name, state.url, state.autoRefresh, allowDuplicate, ::onProgress))
        }
    }

    fun importUri(uri: Uri) {
        viewModelScope.launch {
            working()
            handle(repository.importFromUri(_ui.value.name, uri, ::onProgress))
        }
    }

    fun importPath() {
        val path = _ui.value.filePath
        if (path.isBlank()) {
            showError("Enter the full file path, for example /sdcard/Download/tv.m3u")
            return
        }
        viewModelScope.launch {
            working()
            handle(repository.importFromFile(_ui.value.name, File(path), ::onProgress))
        }
    }

    private fun working() = _ui.update {
        it.copy(status = AddPlaylistStatus.Working(ImportProgress(com.example.freetv.domain.model.ImportStage.DOWNLOADING, null, "Starting…")))
    }

    private fun onProgress(progress: ImportProgress) {
        _ui.update { it.copy(status = AddPlaylistStatus.Working(progress)) }
    }

    private suspend fun handle(result: ImportResult) {
        when (result) {
            is ImportResult.Success -> {
                _ui.update { it.copy(status = AddPlaylistStatus.Editing) }
                events.send(
                    AddPlaylistEvent.Imported(
                        result.playlistId,
                        "${result.channelCount} channels detected" +
                            (if (result.duplicatesMerged > 0) ", ${result.duplicatesMerged} duplicates merged" else "") +
                            (if (result.invalidEntries > 0) ", ${result.invalidEntries} invalid entries skipped" else ""),
                    ),
                )
            }
            is ImportResult.DuplicatePlaylist -> _ui.update { it.copy(status = AddPlaylistStatus.Duplicate(result.existingName)) }
            is ImportResult.Failure -> showError(result.message)
        }
    }
}
