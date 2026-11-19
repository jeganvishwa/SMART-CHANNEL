package com.example.freetv.presentation.player

import android.view.KeyEvent as AndroidKeyEvent
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.example.freetv.domain.model.EpgProgram
import com.example.freetv.domain.model.formatChannelNumber
import com.example.freetv.player.NumericChannelController
import com.example.freetv.player.PlaybackState
import com.example.freetv.player.PlaybackStatus
import com.example.freetv.player.TrackOption
import com.example.freetv.presentation.components.ChannelLogo
import com.example.freetv.presentation.components.ListRow
import com.example.freetv.presentation.components.MessagePill
import com.example.freetv.presentation.components.NumericInputOverlay
import com.example.freetv.presentation.components.ProgressBar
import com.example.freetv.presentation.components.Spinner
import kotlinx.coroutines.delay
import java.util.Date

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    onExit: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val playback by viewModel.controller.state.collectAsStateWithLifecycle()
    val player by viewModel.controller.player.collectAsStateWithLifecycle()
    val numericInput by viewModel.numeric.input.collectAsStateWithLifecycle()
    val rootFocus = remember { FocusRequester() }

    // A failed automatic restore returns to home after showing the error briefly.
    LaunchedEffect(Unit) {
        viewModel.exitEvents.collect {
            delay(3_000)
            onExit()
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onStart() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onStop() }

    BackHandler {
        when {
            numericInput.isNotEmpty() -> viewModel.numeric.clear()
            ui.panel == PlayerPanel.SOURCES || ui.panel == PlayerPanel.AUDIO || ui.panel == PlayerPanel.SUBTITLES ->
                viewModel.openPanel(PlayerPanel.MENU)
            ui.panel != PlayerPanel.NONE -> viewModel.closePanel()
            ui.infoVisible -> viewModel.hideInfo()
            else -> onExit()
        }
    }

    // Give key focus back to the video surface whenever no panel is open.
    LaunchedEffect(ui.panel) {
        if (ui.panel == PlayerPanel.NONE) {
            delay(30)
            runCatching { rootFocus.requestFocus() }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val code = event.key.nativeKeyCode
                val digit = NumericChannelController.digitForKeyCode(code)
                if (ui.panel != PlayerPanel.NONE) {
                    // Panels use normal D-pad focus; only dedicated channel keys still zap.
                    return@onPreviewKeyEvent when (code) {
                        AndroidKeyEvent.KEYCODE_CHANNEL_UP -> { viewModel.next(); true }
                        AndroidKeyEvent.KEYCODE_CHANNEL_DOWN -> { viewModel.previous(); true }
                        else -> false
                    }
                }
                when {
                    digit != null -> { viewModel.numeric.onDigit(digit); true }
                    code == AndroidKeyEvent.KEYCODE_DPAD_UP || code == AndroidKeyEvent.KEYCODE_CHANNEL_UP -> { viewModel.next(); true }
                    code == AndroidKeyEvent.KEYCODE_DPAD_DOWN || code == AndroidKeyEvent.KEYCODE_CHANNEL_DOWN -> { viewModel.previous(); true }
                    code == AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { viewModel.openPanel(PlayerPanel.CHANNELS); true }
                    code == AndroidKeyEvent.KEYCODE_DPAD_RIGHT || code == AndroidKeyEvent.KEYCODE_MENU -> {
                        viewModel.openPanel(PlayerPanel.MENU); true
                    }
                    code == AndroidKeyEvent.KEYCODE_DPAD_CENTER || code == AndroidKeyEvent.KEYCODE_ENTER ||
                        code == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        when {
                            viewModel.numeric.isActive -> viewModel.numeric.commitNow()
                            playback.status == PlaybackStatus.ALL_FAILED -> viewModel.retry()
                            ui.infoVisible -> viewModel.openPanel(PlayerPanel.MENU)
                            else -> viewModel.showInfo()
                        }
                        true
                    }
                    code == AndroidKeyEvent.KEYCODE_INFO || code == AndroidKeyEvent.KEYCODE_GUIDE -> {
                        if (ui.infoVisible) viewModel.hideInfo() else viewModel.showInfo(); true
                    }
                    code == AndroidKeyEvent.KEYCODE_LAST_CHANNEL -> { viewModel.lastChannel(); true }
                    code == AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == AndroidKeyEvent.KEYCODE_MEDIA_PLAY ||
                        code == AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> { viewModel.togglePlayPause(); true }
                    else -> false
                }
            }
            .focusRequester(rootFocus)
            .focusable(),
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    useController = false
                    isFocusable = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    keepScreenOn = true
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                }
            },
            update = { view ->
                if (view.player !== player) view.player = player
                view.resizeMode = ui.resizeMode
            },
            onRelease = { view -> view.player = null },
            modifier = Modifier.fillMaxSize(),
        )

        StatusOverlay(playback, Modifier.align(Alignment.Center))

        AnimatedVisibility(
            visible = ui.infoVisible && ui.panel == PlayerPanel.NONE,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            InfoBanner(ui, playback)
        }

        when (ui.panel) {
            PlayerPanel.CHANNELS -> ChannelListPanel(ui, onSelect = { viewModel.play(it) }, modifier = Modifier.align(Alignment.CenterStart))
            PlayerPanel.MENU -> MenuPanel(ui, playback, viewModel, Modifier.align(Alignment.CenterEnd))
            PlayerPanel.SOURCES -> OptionsPanel(
                title = "Sources",
                options = ui.streams.mapIndexed { i, s ->
                    TrackOption(0, i, (s.label ?: "Source ${i + 1}") + statusSuffix(s.lastStatus.name), i == playback.streamIndex)
                },
                onSelect = { viewModel.selectStream(it.trackIndex) },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
            PlayerPanel.AUDIO -> OptionsPanel(
                title = "Audio track",
                options = viewModel.audioOptions(),
                onSelect = { viewModel.selectAudio(it) },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
            PlayerPanel.SUBTITLES -> OptionsPanel(
                title = "Subtitles",
                options = listOf(TrackOption(-1, -1, "Off", viewModel.subtitleOptions().none { it.selected })) + viewModel.subtitleOptions(),
                onSelect = { viewModel.selectSubtitle(if (it.groupIndex < 0) null else it) },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
            PlayerPanel.NONE -> Unit
        }

        NumericInputOverlay(numericInput, Modifier.align(Alignment.TopEnd).padding(32.dp))
        MessagePill(ui.message, Modifier.align(Alignment.TopCenter).padding(top = 32.dp))
    }
}

private fun statusSuffix(status: String) = when (status) {
    "OK" -> "  ✓"
    "FAILED" -> "  ✗"
    else -> ""
}

@Composable
private fun StatusOverlay(state: PlaybackState, modifier: Modifier = Modifier) {
    when (state.status) {
        PlaybackStatus.LOADING, PlaybackStatus.BUFFERING, PlaybackStatus.RETRYING, PlaybackStatus.SWITCHING -> {
            Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
                Spinner(size = 64.dp)
                val text = when (state.status) {
                    PlaybackStatus.RETRYING -> "Retrying (attempt ${state.attempt})…"
                    PlaybackStatus.SWITCHING -> state.message ?: "Switching source…"
                    PlaybackStatus.BUFFERING -> "Buffering…"
                    else -> "Loading…"
                }
                Spacer(Modifier.height(12.dp))
                Text(text, style = MaterialTheme.typography.titleMedium, color = Color.White)
            }
        }
        PlaybackStatus.ALL_FAILED, PlaybackStatus.NO_STREAMS, PlaybackStatus.OFFLINE -> {
            Column(
                modifier
                    .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
                    .padding(28.dp)
                    .width(620.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    when (state.status) {
                        PlaybackStatus.OFFLINE -> "You are offline"
                        PlaybackStatus.NO_STREAMS -> "No stream for this channel"
                        else -> "Channel unavailable"
                    },
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                )
                Spacer(Modifier.height(8.dp))
                Text(state.message.orEmpty(), style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.8f))
                Spacer(Modifier.height(12.dp))
                Text(
                    "OK: retry   ▲▼: change channel   ◄: channel list   ►: options",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.6f),
                )
            }
        }
        else -> Unit
    }
}

@Composable
private fun InfoBanner(ui: PlayerUiState, playback: PlaybackState) {
    val channel = ui.channel ?: return
    val context = LocalContext.current
    val timeFormat = remember { android.text.format.DateFormat.getTimeFormat(context) }
    Box(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.92f))))
            .padding(start = 48.dp, end = 48.dp, top = 64.dp, bottom = 32.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(channel.logoUrl, channel.name, size = 96.dp)
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        formatChannelNumber(channel.number, ui.numberDigits),
                        style = MaterialTheme.typography.displayMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(20.dp))
                    Text(channel.name, style = MaterialTheme.typography.headlineLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (ui.isFavorite) {
                        Spacer(Modifier.width(12.dp))
                        Icon(Icons.Filled.Favorite, contentDescription = "Favourite", tint = MaterialTheme.colorScheme.secondary)
                    }
                }
                ProgramLine("Now", ui.nowNext.now, timeFormat, showProgress = true)
                ProgramLine("Next", ui.nowNext.next, timeFormat, showProgress = false)
            }
            Column(horizontalAlignment = Alignment.End) {
                if (playback.streamCount > 1) {
                    Text("Source ${playback.streamIndex + 1}/${playback.streamCount}", style = MaterialTheme.typography.titleMedium, color = Color.White)
                }
                Text(
                    "◄ Channels   ► Options",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@Composable
private fun ProgramLine(label: String, program: EpgProgram?, timeFormat: java.text.DateFormat, showProgress: Boolean) {
    if (program == null) return
    Column(Modifier.padding(top = 6.dp)) {
        Text(
            "$label  ${timeFormat.format(Date(program.startMs))}–${timeFormat.format(Date(program.endMs))}  ${program.title}",
            style = MaterialTheme.typography.titleMedium,
            color = if (showProgress) Color.White else Color.White.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (showProgress) {
            Spacer(Modifier.height(6.dp))
            ProgressBar(program.progressAt(System.currentTimeMillis()), Modifier.width(420.dp))
        }
    }
}

@Composable
private fun PanelContainer(title: String, modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .width(440.dp)
            .fillMaxHeight()
            .background(Color(0xF0101520))
            .padding(24.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = Color.White)
        Spacer(Modifier.height(16.dp))
        content()
    }
}

@Composable
private fun ChannelListPanel(ui: PlayerUiState, onSelect: (Long) -> Unit, modifier: Modifier) {
    val currentIndex = ui.channelList.indexOfFirst { it.id == ui.channel?.id }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 3).coerceAtLeast(0))
    val focus = remember { FocusRequester() }
    PanelContainer("Channels", modifier) {
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(ui.channelList, key = { _, c -> c.id }) { index, channel ->
                ListRow(
                    title = "${formatChannelNumber(channel.number, ui.numberDigits)}  ${channel.name}",
                    onClick = { onSelect(channel.id) },
                    selected = channel.id == ui.channel?.id,
                    modifier = if (index == currentIndex) Modifier.focusRequester(focus) else Modifier,
                    leading = { ChannelLogo(channel.logoUrl, channel.name, size = 40.dp) },
                )
            }
        }
    }
    LaunchedEffect(Unit) {
        delay(50)
        runCatching { focus.requestFocus() }
    }
}

@Composable
private fun MenuPanel(ui: PlayerUiState, playback: PlaybackState, viewModel: PlayerViewModel, modifier: Modifier) {
    val focus = remember { FocusRequester() }
    PanelContainer("Options", modifier) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                ListRow(
                    title = if (ui.isFavorite) "Remove from favourites" else "Add to favourites",
                    onClick = viewModel::toggleFavorite,
                    modifier = Modifier.focusRequester(focus),
                )
            }
            item {
                ListRow(
                    title = "Source",
                    subtitle = "${playback.streamIndex + 1} of ${ui.streams.size}",
                    onClick = { viewModel.openPanel(PlayerPanel.SOURCES) },
                )
            }
            item { ListRow(title = "Audio track", onClick = { viewModel.openPanel(PlayerPanel.AUDIO) }) }
            item { ListRow(title = "Subtitles", onClick = { viewModel.openPanel(PlayerPanel.SUBTITLES) }) }
            item {
                ListRow(
                    title = "Aspect ratio",
                    subtitle = PlayerViewModel.aspectLabel(ui.resizeMode),
                    onClick = viewModel::cycleAspectRatio,
                )
            }
            item { ListRow(title = "Channel list", onClick = { viewModel.openPanel(PlayerPanel.CHANNELS) }) }
            item { ListRow(title = "Previous channel", onClick = { viewModel.closePanel(); viewModel.lastChannel() }) }
            item { ListRow(title = "Retry stream", onClick = { viewModel.closePanel(); viewModel.retry() }) }
        }
    }
    LaunchedEffect(Unit) {
        delay(50)
        runCatching { focus.requestFocus() }
    }
}

@Composable
private fun OptionsPanel(title: String, options: List<TrackOption>, onSelect: (TrackOption) -> Unit, modifier: Modifier) {
    val focus = remember { FocusRequester() }
    PanelContainer(title, modifier) {
        if (options.isEmpty()) {
            Text("No options available for this stream.", style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.7f))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(options) { index, option ->
                ListRow(
                    title = option.label,
                    onClick = { onSelect(option) },
                    selected = option.selected,
                    modifier = if (index == 0) Modifier.focusRequester(focus) else Modifier,
                    trailing = if (option.selected) {
                        { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(24.dp)) }
                    } else {
                        null
                    },
                )
            }
        }
    }
    LaunchedEffect(options.size) {
        delay(50)
        runCatching { focus.requestFocus() }
    }
}
