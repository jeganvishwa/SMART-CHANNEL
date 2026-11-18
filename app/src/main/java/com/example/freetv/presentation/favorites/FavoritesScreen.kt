package com.example.freetv.presentation.favorites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.freetv.domain.model.ChannelItem
import com.example.freetv.domain.model.EpgProgram
import com.example.freetv.domain.model.formatChannelNumber
import com.example.freetv.domain.repository.ChannelRepository
import com.example.freetv.domain.repository.EpgRepository
import com.example.freetv.domain.repository.SettingsRepository
import com.example.freetv.presentation.components.ConfirmDialog
import com.example.freetv.presentation.components.EmptyState
import com.example.freetv.presentation.components.LoadingState
import com.example.freetv.presentation.components.ScreenScaffold
import com.example.freetv.presentation.home.ChannelCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val channels: ChannelRepository,
    epg: EpgRepository,
    settings: SettingsRepository,
) : ViewModel() {
    val favorites: StateFlow<List<ChannelItem>?> = channels.observeFavorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val programs: StateFlow<Map<String, EpgProgram>> = epg.observeCurrentPrograms()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    val digits: StateFlow<Int> = settings.settings.map { it.channelNumberDigits }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 3)

    fun remove(channelId: Long) = viewModelScope.launch { channels.setFavorite(channelId, false) }
}

@Composable
fun FavoritesScreen(onPlay: (Long) -> Unit, viewModel: FavoritesViewModel = hiltViewModel()) {
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val programs by viewModel.programs.collectAsStateWithLifecycle()
    val digits by viewModel.digits.collectAsStateWithLifecycle()
    var removing by remember { mutableStateOf<ChannelItem?>(null) }
    val firstFocus = remember { FocusRequester() }

    ScreenScaffold(title = "Favourites", subtitle = "OK to play · hold OK to remove") {
        val list = favorites
        when {
            list == null -> LoadingState()
            list.isEmpty() -> EmptyState(
                title = "No favourites yet",
                message = "Hold OK on a channel on the home screen, or use Options in the player, to add it here.",
                icon = Icons.Filled.FavoriteBorder,
            )
            else -> {
                val now = System.currentTimeMillis()
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(200.dp),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(list, key = { _, c -> c.id }) { index, channel ->
                        ChannelCard(
                            channel = channel,
                            numberText = formatChannelNumber(channel.number, digits),
                            program = channel.tvgId?.lowercase()?.let { programs[it] },
                            now = now,
                            onClick = { onPlay(channel.id) },
                            onLongClick = { removing = channel },
                            modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                        )
                    }
                }
                LaunchedEffect(Unit) {
                    delay(80)
                    runCatching { firstFocus.requestFocus() }
                }
            }
        }
    }

    removing?.let { channel ->
        ConfirmDialog(
            title = "Remove favourite?",
            message = "Remove ${channel.name} from favourites?",
            confirmText = "Remove",
            onConfirm = {
                viewModel.remove(channel.id)
                removing = null
            },
            onDismiss = { removing = null },
        )
    }
}
