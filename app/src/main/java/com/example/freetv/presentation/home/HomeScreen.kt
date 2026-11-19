package com.example.freetv.presentation.home

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.example.freetv.domain.model.ChannelItem
import com.example.freetv.domain.model.EpgProgram
import com.example.freetv.domain.model.formatChannelNumber
import com.example.freetv.player.NumericChannelController
import com.example.freetv.presentation.components.ChannelLogo
import com.example.freetv.presentation.components.EmptyState
import com.example.freetv.presentation.components.FocusSurface
import com.example.freetv.presentation.components.LoadingState
import com.example.freetv.presentation.components.MessagePill
import com.example.freetv.presentation.components.NumericInputOverlay
import com.example.freetv.presentation.components.MessageDialog
import com.example.freetv.presentation.components.ProgressBar
import com.example.freetv.presentation.components.Spinner
import com.example.freetv.presentation.components.TvButton
import kotlinx.coroutines.delay

enum class HomeDestination { FAVORITES, GUIDE, PLAYLISTS, CHANNELS, GROUPS, NUMBERS, SETTINGS, ADD_PLAYLIST }

@Composable
fun HomeScreen(
    onOpenPlayer: (channelId: Long, contextType: String, contextId: Long) -> Unit,
    onNavigate: (HomeDestination) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val programs by viewModel.currentPrograms.collectAsStateWithLifecycle()
    val numericInput by viewModel.numeric.input.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val startupMessage by viewModel.startupMessage.collectAsStateWithLifecycle()
    val diagnosticsText by viewModel.diagnosticsText.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is HomeEvent.OpenPlayer -> onOpenPlayer(event.channelId, event.contextType, event.contextId)
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val code = event.key.nativeKeyCode
                val digit = NumericChannelController.digitForKeyCode(code)
                when {
                    digit != null -> {
                        viewModel.numeric.onDigit(digit)
                        true
                    }
                    viewModel.numeric.isActive &&
                        (code == AndroidKeyEvent.KEYCODE_DPAD_CENTER || code == AndroidKeyEvent.KEYCODE_ENTER) -> {
                        viewModel.numeric.commitNow()
                        true
                    }
                    else -> false
                }
            },
    ) {
        Row(Modifier.fillMaxSize()) {
            SideMenu(onNavigate)
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = 24.dp, end = 40.dp, top = 24.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("FreeTV", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(24.dp))
                    state.importStatus?.let { status ->
                        Spinner(size = 20.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.weight(1f))
                    if (!state.online) OfflineBanner(onRetry = viewModel::retryConnection)
                }
                Spacer(Modifier.height(12.dp))
                when {
                    state.loading -> LoadingState()
                    !state.hasPlaylists -> EmptyState(
                        title = "No playlists yet",
                        message = "Add your M3U playlist link or file to detect channels automatically.",
                        icon = Icons.Filled.LiveTv,
                        actionText = "Add playlist",
                        onAction = { onNavigate(HomeDestination.ADD_PLAYLIST) },
                    )
                    else -> {
                        TabsRow(state.tabs, state.selectedTab, viewModel::selectTab)
                        Spacer(Modifier.height(16.dp))
                        if (state.channels.isEmpty() && state.importStatus != null) {
                            LoadingState(state.importStatus!!)
                        } else if (state.channels.isEmpty()) {
                            EmptyState(
                                title = "No channels here",
                                message = if (state.selectedTab == HomeViewModel.TAB_RECENT) {
                                    "Channels you watch will appear here."
                                } else {
                                    "Pick another language or category, or add channels to this group in Groups."
                                },
                            )
                        } else {
                            ChannelGrid(
                                channels = state.channels,
                                programs = programs,
                                digits = state.numberDigits,
                                onClick = viewModel::openChannel,
                                onLongClick = viewModel::toggleFavorite,
                            )
                        }
                    }
                }
            }
        }
        NumericInputOverlay(numericInput, Modifier.align(Alignment.TopEnd).padding(32.dp))
        MessagePill(message, Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp))
    }

    startupMessage?.let { MessageDialog("FreeTV", it, viewModel::dismissStartupMessage) }
    if (startupMessage == null) {
        diagnosticsText?.let { MessageDialog("Startup diagnostics", it, viewModel::dismissDiagnostics) }
    }
}

/** Shown while the TV has no internet: saved channels stay visible; Retry re-checks once. */
@Composable
private fun OfflineBanner(onRetry: () -> Unit) {
    Row(
        Modifier
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.18f), RoundedCornerShape(24.dp))
            .padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.WifiOff, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text("Offline – showing saved channels", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(12.dp))
        TvButton("Retry", onRetry, primary = false)
    }
}


@Composable
private fun SideMenu(onNavigate: (HomeDestination) -> Unit) {
    Column(
        Modifier
            .width(230.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MenuItem("Favourites", Icons.Filled.Favorite) { onNavigate(HomeDestination.FAVORITES) }
        MenuItem("TV Guide", Icons.Filled.DateRange) { onNavigate(HomeDestination.GUIDE) }
        MenuItem("Playlists", Icons.AutoMirrored.Filled.List) { onNavigate(HomeDestination.PLAYLISTS) }
        MenuItem("Channels", Icons.Filled.Tv) { onNavigate(HomeDestination.CHANNELS) }
        MenuItem("Groups", Icons.Filled.Folder) { onNavigate(HomeDestination.GROUPS) }
        MenuItem("Numbers", Icons.Filled.FormatListNumbered) { onNavigate(HomeDestination.NUMBERS) }
        MenuItem("Settings", Icons.Filled.Settings) { onNavigate(HomeDestination.SETTINGS) }
        Spacer(Modifier.height(24.dp))
        Text(
            "Type a channel number on the remote to jump to it. Hold OK on a channel to add it to favourites.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MenuItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, modifier = Modifier.fillMaxWidth(), focusedScale = 1.03f) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(14.dp))
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun TabsRow(tabs: List<HomeTab>, selected: String?, onSelect: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp)) {
        items(tabs, key = { it.key }) { tab ->
            FocusSurface(
                onClick = { onSelect(tab.key) },
                selected = tab.key == selected,
                shape = RoundedCornerShape(24.dp),
                focusedScale = 1.06f,
            ) {
                Text(
                    tab.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    color = if (tab.key == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun ChannelGrid(
    channels: List<ChannelItem>,
    programs: Map<String, EpgProgram>,
    digits: Int,
    onClick: (ChannelItem) -> Unit,
    onLongClick: (ChannelItem) -> Unit,
) {
    val gridState = rememberLazyGridState()
    var lastFocusedId by rememberSaveable { mutableLongStateOf(-1L) }
    val restoreFocus = remember { FocusRequester() }
    val targetId = if (channels.any { it.id == lastFocusedId }) lastFocusedId else -1L
    val now = System.currentTimeMillis()

    LazyVerticalGrid(
        columns = GridCells.Adaptive(200.dp),
        state = gridState,
        contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp, start = 4.dp, end = 4.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(channels, key = { _, c -> c.id }) { _, channel ->
            val program = channel.tvgId?.lowercase()?.let { programs[it] }
            val modifier = if (channel.id == targetId) Modifier.focusRequester(restoreFocus) else Modifier
            ChannelCard(
                channel = channel,
                numberText = formatChannelNumber(channel.number, digits),
                program = program,
                now = now,
                onClick = { onClick(channel) },
                onLongClick = { onLongClick(channel) },
                modifier = modifier.onFocusChanged { if (it.isFocused) lastFocusedId = channel.id },
            )
        }
    }

    LaunchedEffect(targetId) {
        if (targetId != -1L) {
            delay(50)
            runCatching { restoreFocus.requestFocus() }
        }
    }
}

@Composable
fun ChannelCard(
    channel: ChannelItem,
    numberText: String,
    program: EpgProgram?,
    now: Long,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusSurface(onClick = onClick, onLongClick = onLongClick, modifier = modifier.fillMaxWidth(), focusedScale = 1.07f) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChannelLogo(channel.logoUrl, channel.name, size = 64.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(numberText, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                    if (channel.isFavorite) {
                        Icon(
                            Icons.Filled.Favorite,
                            contentDescription = "Favourite",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(channel.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                program?.title ?: (channel.originalGroup ?: " "),
                style = MaterialTheme.typography.bodyMedium,
                color = if (program != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            if (program != null) {
                ProgressBar(program.progressAt(now), Modifier.fillMaxWidth(), height = 4.dp)
            } else {
                Box(Modifier.height(4.dp).fillMaxWidth().background(Color.Transparent))
            }
        }
    }
}
