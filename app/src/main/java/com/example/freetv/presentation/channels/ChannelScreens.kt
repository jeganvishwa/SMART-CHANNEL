package com.example.freetv.presentation.channels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.example.freetv.domain.model.ChannelItem
import com.example.freetv.domain.model.ChannelStream
import com.example.freetv.domain.model.StreamStatus
import com.example.freetv.domain.model.formatChannelNumber
import com.example.freetv.presentation.components.ChannelLogo
import com.example.freetv.presentation.components.ChoiceDialog
import com.example.freetv.presentation.components.ConfirmDialog
import com.example.freetv.presentation.components.EmptyState
import com.example.freetv.presentation.components.ListRow
import com.example.freetv.presentation.components.LoadingState
import com.example.freetv.presentation.components.MessageDialog
import com.example.freetv.presentation.components.ScreenScaffold
import com.example.freetv.presentation.components.SectionHeader
import com.example.freetv.presentation.components.Spinner
import com.example.freetv.presentation.components.TextInputDialog
import com.example.freetv.presentation.components.ToggleRow
import com.example.freetv.presentation.components.TvButton
import com.example.freetv.presentation.components.ValueRow
import com.example.freetv.utils.UrlSanitizer
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------
// Channel manager / detected channels
// ---------------------------------------------------------------------------

@Composable
fun ChannelManagerScreen(
    onEdit: (Long) -> Unit,
    onNumbers: () -> Unit,
    viewModel: ChannelManagerViewModel = hiltViewModel(),
) {
    val list by viewModel.channelList.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val title by viewModel.title.collectAsStateWithLifecycle()
    val digits by viewModel.digits.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var searching by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(0) } // 0 none, 1 name, 2 url
    var newName by remember { mutableStateOf("") }
    val firstFocus = remember { FocusRequester() }

    ScreenScaffold(
        title = title,
        subtitle = list?.let { "${it.size} channels" + if (query.isNotEmpty()) " matching \"$query\"" else "" },
        actions = {
            TvButton(if (query.isEmpty()) "Search" else "Search: $query", { searching = true }, icon = Icons.Filled.Search, modifier = Modifier.focusRequester(firstFocus))
            TvButton("Numbers", onNumbers, icon = Icons.Filled.FormatListNumbered, primary = false)
            if (viewModel.playlistId == null) TvButton("Add channel", { creating = 1 }, icon = Icons.Filled.Add, primary = false)
        },
    ) {
        val channels = list
        when {
            channels == null -> LoadingState()
            channels.isEmpty() -> EmptyState(
                title = "No channels",
                message = if (query.isNotEmpty()) "Nothing matches \"$query\"." else "Import a playlist to detect channels.",
                icon = Icons.Filled.Tv,
            )
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(channels, key = { it.id }) { channel ->
                    ChannelRow(channel, digits) { onEdit(channel.id) }
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        delay(50)
        runCatching { firstFocus.requestFocus() }
    }

    if (searching) {
        TextInputDialog(
            title = "Search channels",
            initial = query,
            hint = "Type a channel name or number. Leave empty to show all.",
            confirmText = "Search",
            onConfirm = {
                viewModel.setQuery(it)
                searching = false
            },
            onDismiss = { searching = false },
        )
    }
    if (creating == 1) {
        TextInputDialog(
            title = "New channel name",
            initial = "",
            confirmText = "Next",
            onConfirm = {
                newName = it
                creating = 2
            },
            onDismiss = { creating = 0 },
        )
    }
    if (creating == 2) {
        TextInputDialog(
            title = "Stream URL for $newName",
            initial = "",
            keyboardType = KeyboardType.Uri,
            hint = "http(s) link to an HLS (.m3u8), DASH (.mpd) or direct stream you are allowed to watch.",
            confirmText = "Create",
            onConfirm = { url ->
                creating = 0
                viewModel.createChannel(newName, url, onEdit)
            },
            onDismiss = { creating = 0 },
        )
    }
    message?.let { MessageDialog("Channels", it) { viewModel.message.value = null } }
}

@Composable
private fun ChannelRow(channel: ChannelItem, digits: Int, onClick: () -> Unit) {
    val details = listOfNotNull(
        channel.originalGroup,
        channel.language,
        if (channel.hidden) "Hidden" else null,
        if (!channel.inSource) "Removed from playlist (kept)" else null,
        if (channel.isFavorite) "★ Favourite" else null,
    ).joinToString(" · ")
    ListRow(
        title = "${formatChannelNumber(channel.number, digits)}   ${channel.name}",
        subtitle = details,
        onClick = onClick,
        leading = { ChannelLogo(channel.logoUrl, channel.name, size = 44.dp) },
    )
}

// ---------------------------------------------------------------------------
// Channel editor
// ---------------------------------------------------------------------------

private enum class EditField { NAME, LOGO, LANGUAGE, NUMBER, ADD_STREAM_URL, EDIT_STREAM }

@Composable
fun ChannelEditScreen(
    onPlay: (Long) -> Unit,
    onBack: () -> Unit,
    viewModel: ChannelEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val conflict by viewModel.conflict.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val testing by viewModel.testing.collectAsStateWithLifecycle()
    val deleted by viewModel.deleted.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<EditField?>(null) }
    var streamActions by remember { mutableStateOf<ChannelStream?>(null) }
    var editStream by remember { mutableStateOf<ChannelStream?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(deleted) { if (deleted) onBack() }

    val channel = state.channel
    if (channel == null) {
        LoadingState()
        return
    }

    ScreenScaffold(
        title = channel.name,
        subtitle = "Channel ${formatChannelNumber(channel.number, state.digits)}" +
            (channel.originalGroup?.let { " · playlist group: $it" } ?: ""),
        actions = {
            TvButton("Play", { onPlay(channel.id) }, icon = Icons.Filled.PlayArrow, modifier = Modifier.focusRequester(firstFocus))
        },
    ) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { SectionHeader("Details") }
            item {
                Row(Modifier.fillMaxWidth()) {
                    ChannelLogo(channel.logoUrl, channel.name, size = 72.dp)
                }
            }
            item { ValueRow("Name", channel.name, { editing = EditField.NAME }) }
            item { ValueRow("Channel number", formatChannelNumber(channel.number, state.digits), { editing = EditField.NUMBER }) }
            item { ValueRow("Language", channel.language ?: "Not set", { editing = EditField.LANGUAGE }) }
            item { ValueRow("Logo URL", if (channel.logoUrl.isNullOrEmpty()) "None" else "Set", { editing = EditField.LOGO }) }
            item { ToggleRow("Favourite", state.isFavorite, { viewModel.setFavorite(it) }) }
            item { ToggleRow("Hidden", channel.hidden, { viewModel.setHidden(it) }, subtitle = "Hidden channels do not appear on the home screen") }

            item { SectionHeader("Stream sources (first = primary, others = automatic backups)") }
            itemsIndexed(state.streams, key = { _, s -> s.id }) { index, stream ->
                ListRow(
                    title = "${index + 1}. " + (stream.label ?: if (index == 0) "Primary" else "Backup $index"),
                    subtitle = UrlSanitizer.redact(stream.url) + "\n" + when (stream.lastStatus) {
                        StreamStatus.OK -> "Last check: working"
                        StreamStatus.FAILED -> "Last check: failed"
                        StreamStatus.UNKNOWN -> "Not tested"
                    } + if (stream.fromPlaylist) " · from playlist" else " · added by you",
                    onClick = { streamActions = stream },
                    trailing = if (testing == stream.id) {
                        { Spinner(size = 24.dp) }
                    } else {
                        null
                    },
                )
            }
            item {
                TvButton("Add backup stream", { editing = EditField.ADD_STREAM_URL }, icon = Icons.Filled.Add, primary = false)
            }

            item { SectionHeader("Groups") }
            items(state.groups, key = { it.id }) { group ->
                ToggleRow(
                    title = group.name,
                    subtitle = group.type.name.lowercase().replaceFirstChar { it.uppercase() },
                    checked = group.id in state.memberOf,
                    onToggle = { viewModel.setGroup(group.id, it) },
                )
            }

            item { SectionHeader("Danger zone") }
            item {
                if (channel.playlistId == null) {
                    TvButton("Delete channel", { confirmDelete = true }, primary = false)
                } else {
                    Column {
                        Text(
                            "This channel comes from a playlist. Hide it instead of deleting – a refresh would add it again.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        delay(50)
        runCatching { firstFocus.requestFocus() }
    }

    when (editing) {
        EditField.NAME -> TextInputDialog("Channel name", channel.name, { viewModel.updateDetails(name = it); editing = null }, { editing = null })
        EditField.LOGO -> TextInputDialog(
            "Logo URL", channel.logoUrl.orEmpty(),
            { viewModel.updateDetails(logo = it); editing = null }, { editing = null },
            keyboardType = KeyboardType.Uri,
        )
        EditField.LANGUAGE -> TextInputDialog(
            "Language", channel.language.orEmpty(),
            { viewModel.updateDetails(language = it); editing = null }, { editing = null },
            hint = "For example Tamil, English, Hindi, Malayalam",
        )
        EditField.NUMBER -> TextInputDialog(
            "Channel number", channel.number?.toString().orEmpty(),
            { viewModel.setNumber(channel.id, it); editing = null }, { editing = null },
            hint = "Leave empty to remove the number.",
            keyboardType = KeyboardType.Number,
        )
        EditField.ADD_STREAM_URL -> TextInputDialog(
            "Add backup stream URL", "",
            { viewModel.addStream(it, null); editing = null }, { editing = null },
            hint = "Used automatically when the earlier sources fail.",
            keyboardType = KeyboardType.Uri,
            confirmText = "Add",
        )
        EditField.EDIT_STREAM -> editStream?.let { s ->
            TextInputDialog(
                "Edit stream URL", s.url,
                { viewModel.editStream(s.id, it, s.label); editing = null }, { editing = null },
                keyboardType = KeyboardType.Uri,
            )
        }
        null -> Unit
    }

    streamActions?.let { stream ->
        ChoiceDialog(
            title = "Stream source",
            options = listOf("Test stream", "Set as primary", "Move up", "Move down", "Edit URL", "Delete"),
            selectedIndex = -1,
            onSelect = { index ->
                streamActions = null
                when (index) {
                    0 -> viewModel.testStream(stream.id)
                    1 -> viewModel.makePrimary(stream.id)
                    2 -> viewModel.moveStream(stream.id, true)
                    3 -> viewModel.moveStream(stream.id, false)
                    4 -> {
                        editStream = stream
                        editing = EditField.EDIT_STREAM
                    }
                    5 -> viewModel.deleteStream(stream.id)
                }
            },
            onDismiss = { streamActions = null },
        )
    }

    NumberConflictDialogs(conflict, message, viewModel)

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete channel?",
            message = "${channel.name} and its streams will be removed.",
            confirmText = "Delete",
            onConfirm = {
                confirmDelete = false
                viewModel.deleteChannel()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun NumberConflictDialogs(conflict: NumberConflict?, message: String?, viewModel: NumberEditingViewModel) {
    conflict?.let { c ->
        ConfirmDialog(
            title = "Number ${c.number} is already used",
            message = "Channel ${c.number} is \"${c.otherName}\". Swap the two numbers?",
            confirmText = "Swap numbers",
            onConfirm = viewModel::swapConflict,
            onDismiss = viewModel::dismissConflict,
        )
    }
    message?.let { MessageDialog("Channel", it, viewModel::clearMessage) }
}

// ---------------------------------------------------------------------------
// Channel number manager
// ---------------------------------------------------------------------------

@Composable
fun ChannelNumbersScreen(viewModel: ChannelNumbersViewModel = hiltViewModel()) {
    val list by viewModel.channelList.collectAsStateWithLifecycle()
    val digits by viewModel.digits.collectAsStateWithLifecycle()
    val conflict by viewModel.conflict.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var actionsFor by remember { mutableStateOf<ChannelItem?>(null) }
    var numberFor by remember { mutableStateOf<ChannelItem?>(null) }
    var confirmAuto by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }

    ScreenScaffold(
        title = "Channel numbers",
        subtitle = "Numbers are global and unique. Type them on the remote to switch channels.",
        actions = {
            TvButton("Auto-number all", { confirmAuto = true }, icon = Icons.Filled.FormatListNumbered, modifier = Modifier.focusRequester(firstFocus))
        },
    ) {
        val channels = list
        when {
            channels == null -> LoadingState()
            channels.isEmpty() -> EmptyState("No channels", "Import a playlist first.")
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(channels, key = { it.id }) { channel ->
                    ListRow(
                        title = "${formatChannelNumber(channel.number, digits)}   ${channel.name}",
                        subtitle = channel.originalGroup,
                        onClick = { actionsFor = channel },
                        leading = { ChannelLogo(channel.logoUrl, channel.name, size = 40.dp) },
                    )
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        delay(50)
        runCatching { firstFocus.requestFocus() }
    }

    actionsFor?.let { channel ->
        ChoiceDialog(
            title = channel.name,
            options = listOf("Change number", "Move up (swap with previous)", "Move down (swap with next)", "Remove number"),
            selectedIndex = -1,
            onSelect = { index ->
                actionsFor = null
                when (index) {
                    0 -> numberFor = channel
                    1 -> viewModel.moveNumber(channel.id, true)
                    2 -> viewModel.moveNumber(channel.id, false)
                    3 -> viewModel.setNumber(channel.id, "")
                }
            },
            onDismiss = { actionsFor = null },
        )
    }
    numberFor?.let { channel ->
        TextInputDialog(
            title = "Number for ${channel.name}",
            initial = channel.number?.toString().orEmpty(),
            keyboardType = KeyboardType.Number,
            onConfirm = {
                viewModel.setNumber(channel.id, it)
                numberFor = null
            },
            onDismiss = { numberFor = null },
        )
    }
    if (confirmAuto) {
        ConfirmDialog(
            title = "Renumber all channels?",
            message = "Every channel gets a new number from 1 in the current order. Manually set numbers will change.",
            confirmText = "Renumber",
            onConfirm = {
                confirmAuto = false
                viewModel.autoNumberAll()
            },
            onDismiss = { confirmAuto = false },
        )
    }
    NumberConflictDialogs(conflict, message, viewModel)
}
