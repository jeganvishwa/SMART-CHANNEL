package com.example.freetv.presentation.groups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
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
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import androidx.tv.material3.Icon
import com.example.freetv.domain.model.ChannelGroup
import com.example.freetv.domain.model.ChannelItem
import com.example.freetv.domain.model.GroupResult
import com.example.freetv.domain.model.GroupType
import com.example.freetv.domain.model.formatChannelNumber
import com.example.freetv.domain.repository.ChannelRepository
import com.example.freetv.domain.repository.GroupRepository
import com.example.freetv.domain.repository.SettingsRepository
import com.example.freetv.presentation.components.ChannelLogo
import com.example.freetv.presentation.components.ChoiceDialog
import com.example.freetv.presentation.components.ConfirmDialog
import com.example.freetv.presentation.components.EmptyState
import com.example.freetv.presentation.components.ListRow
import com.example.freetv.presentation.components.LoadingState
import com.example.freetv.presentation.components.MessageDialog
import com.example.freetv.presentation.components.ScreenScaffold
import com.example.freetv.presentation.components.TextInputDialog
import com.example.freetv.presentation.components.TvButton
import com.example.freetv.presentation.components.TvDialog
import com.example.freetv.presentation.navigation.FreeTvRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// ---------------------------------------------------------------------------
// ViewModels
// ---------------------------------------------------------------------------

@HiltViewModel
class GroupManagerViewModel @Inject constructor(
    private val groups: GroupRepository,
) : ViewModel() {
    val groupList: StateFlow<List<ChannelGroup>?> = groups.observeGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val message = MutableStateFlow<String?>(null)

    fun create(name: String, type: GroupType) = viewModelScope.launch {
        (groups.createGroup(name, type) as? GroupResult.Error)?.let { message.value = it.message }
    }

    fun rename(id: Long, name: String) = viewModelScope.launch {
        (groups.renameGroup(id, name) as? GroupResult.Error)?.let { message.value = it.message }
    }

    fun delete(id: Long) = viewModelScope.launch { groups.deleteGroup(id) }
    fun move(id: Long, up: Boolean) = viewModelScope.launch { groups.moveGroup(id, up) }
    fun setHidden(id: Long, hidden: Boolean) = viewModelScope.launch { groups.setHidden(id, hidden) }
    fun setShowOnHome(id: Long, show: Boolean) = viewModelScope.launch { groups.setShowOnHome(id, show) }
}

@HiltViewModel
class GroupDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val groups: GroupRepository,
    channels: ChannelRepository,
    settings: SettingsRepository,
) : ViewModel() {
    val groupId = savedStateHandle.toRoute<FreeTvRoute.GroupDetail>().groupId

    val group: StateFlow<ChannelGroup?> = groups.observeGroup(groupId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val members: StateFlow<List<ChannelItem>?> = groups.observeAllChannelsInGroup(groupId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val allChannels: StateFlow<List<ChannelItem>> = channels.observeForManager(null, "")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val allGroups: StateFlow<List<ChannelGroup>> = groups.observeGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val digits: StateFlow<Int> = settings.settings.map { it.channelNumberDigits }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 3)

    fun add(channelIds: List<Long>) = viewModelScope.launch { groups.addChannels(groupId, channelIds) }
    fun remove(channelId: Long) = viewModelScope.launch { groups.removeChannel(groupId, channelId) }
    fun move(channelId: Long, up: Boolean) = viewModelScope.launch { groups.moveChannelInGroup(groupId, channelId, up) }
    fun moveTo(channelId: Long, target: Long) = viewModelScope.launch { groups.moveChannelToGroup(channelId, groupId, target) }
}

// ---------------------------------------------------------------------------
// Group manager
// ---------------------------------------------------------------------------

@Composable
fun GroupManagerScreen(onOpenGroup: (Long) -> Unit, viewModel: GroupManagerViewModel = hiltViewModel()) {
    val list by viewModel.groupList.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var createType by remember { mutableStateOf<GroupType?>(null) }
    var actionsFor by remember { mutableStateOf<ChannelGroup?>(null) }
    var renaming by remember { mutableStateOf<ChannelGroup?>(null) }
    var deleting by remember { mutableStateOf<ChannelGroup?>(null) }
    val firstFocus = remember { FocusRequester() }

    ScreenScaffold(
        title = "Groups",
        subtitle = "Language groups appear first on the home screen. A channel can be in several groups.",
        actions = { TvButton("Create group", { creating = true }, icon = Icons.Filled.Add, modifier = Modifier.focusRequester(firstFocus)) },
    ) {
        val groups = list
        when {
            groups == null -> LoadingState()
            groups.isEmpty() -> EmptyState("No groups", "Create a group to organise your channels.", icon = Icons.Filled.Folder)
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(groups, key = { it.id }) { group ->
                    val flags = listOfNotNull(
                        group.type.name.lowercase().replaceFirstChar { it.uppercase() },
                        "${group.channelCount} channels",
                        if (group.hidden) "Hidden" else null,
                        if (!group.showOnHome) "Not on home" else null,
                    ).joinToString(" · ")
                    ListRow(
                        title = group.name,
                        subtitle = flags,
                        onClick = { onOpenGroup(group.id) },
                        onLongClick = { actionsFor = group },
                        trailing = { TvButton("Options", { actionsFor = group }, primary = false) },
                    )
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        delay(50)
        runCatching { firstFocus.requestFocus() }
    }

    if (creating) {
        ChoiceDialog(
            title = "Group type",
            options = listOf("Language (Tamil, Telugu…)", "Category (News, Sports…)", "Custom"),
            selectedIndex = -1,
            onSelect = {
                creating = false
                createType = listOf(GroupType.LANGUAGE, GroupType.CATEGORY, GroupType.CUSTOM)[it]
            },
            onDismiss = { creating = false },
        )
    }
    createType?.let { type ->
        TextInputDialog(
            title = "New ${type.name.lowercase()} group",
            initial = "",
            confirmText = "Create",
            onConfirm = {
                viewModel.create(it, type)
                createType = null
            },
            onDismiss = { createType = null },
        )
    }
    actionsFor?.let { group ->
        ChoiceDialog(
            title = group.name,
            options = listOf(
                "Open",
                "Rename",
                "Move up",
                "Move down",
                if (group.hidden) "Unhide group" else "Hide group",
                if (group.showOnHome) "Remove from home screen" else "Show on home screen",
                "Delete",
            ),
            selectedIndex = -1,
            onSelect = { index ->
                actionsFor = null
                when (index) {
                    0 -> onOpenGroup(group.id)
                    1 -> renaming = group
                    2 -> viewModel.move(group.id, true)
                    3 -> viewModel.move(group.id, false)
                    4 -> viewModel.setHidden(group.id, !group.hidden)
                    5 -> viewModel.setShowOnHome(group.id, !group.showOnHome)
                    6 -> deleting = group
                }
            },
            onDismiss = { actionsFor = null },
        )
    }
    renaming?.let { group ->
        TextInputDialog(
            title = "Rename group",
            initial = group.name,
            onConfirm = {
                viewModel.rename(group.id, it)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { group ->
        ConfirmDialog(
            title = "Delete \"${group.name}\"?",
            message = "The group is removed. Its channels stay in your playlists and other groups.",
            confirmText = "Delete",
            onConfirm = {
                viewModel.delete(group.id)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
    message?.let { MessageDialog("Groups", it) { viewModel.message.value = null } }
}

// ---------------------------------------------------------------------------
// Group detail: channels in the group, add / remove / move
// ---------------------------------------------------------------------------

@Composable
fun GroupDetailScreen(onPlay: (Long, Long) -> Unit, viewModel: GroupDetailViewModel = hiltViewModel()) {
    val group by viewModel.group.collectAsStateWithLifecycle()
    val members by viewModel.members.collectAsStateWithLifecycle()
    val all by viewModel.allChannels.collectAsStateWithLifecycle()
    val groups by viewModel.allGroups.collectAsStateWithLifecycle()
    val digits by viewModel.digits.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<ChannelItem?>(null) }
    var moveFor by remember { mutableStateOf<ChannelItem?>(null) }
    val firstFocus = remember { FocusRequester() }

    ScreenScaffold(
        title = group?.name ?: "Group",
        subtitle = members?.let { "${it.size} channels" },
        actions = { TvButton("Add channels", { picking = true }, icon = Icons.Filled.Add, modifier = Modifier.focusRequester(firstFocus)) },
    ) {
        val list = members
        when {
            list == null -> LoadingState()
            list.isEmpty() -> EmptyState("Empty group", "Use \"Add channels\" to put channels in this group.")
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(list, key = { it.id }) { channel ->
                    ListRow(
                        title = "${formatChannelNumber(channel.number, digits)}   ${channel.name}",
                        subtitle = channel.language ?: channel.originalGroup,
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
            options = listOf("Play", "Move up", "Move down", "Move to another group", "Remove from group"),
            selectedIndex = -1,
            onSelect = { index ->
                actionsFor = null
                when (index) {
                    0 -> onPlay(channel.id, viewModel.groupId)
                    1 -> viewModel.move(channel.id, true)
                    2 -> viewModel.move(channel.id, false)
                    3 -> moveFor = channel
                    4 -> viewModel.remove(channel.id)
                }
            },
            onDismiss = { actionsFor = null },
        )
    }
    moveFor?.let { channel ->
        val targets = groups.filter { it.id != viewModel.groupId }
        ChoiceDialog(
            title = "Move ${channel.name} to…",
            options = targets.map { it.name },
            selectedIndex = -1,
            onSelect = { index ->
                viewModel.moveTo(channel.id, targets[index].id)
                moveFor = null
            },
            onDismiss = { moveFor = null },
        )
    }
    if (picking) {
        ChannelPickerDialog(
            channels = all,
            alreadyIn = members.orEmpty().map { it.id }.toSet(),
            digits = digits,
            onConfirm = {
                viewModel.add(it)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun ChannelPickerDialog(
    channels: List<ChannelItem>,
    alreadyIn: Set<Long>,
    digits: Int,
    onConfirm: (List<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(setOf<Long>()) }
    val candidates = channels.filter { it.id !in alreadyIn }
    TvDialog(title = "Select channels (${selected.size} selected)", onDismiss = onDismiss) {
        TvButton("Add ${selected.size} channels", { onConfirm(selected.toList()) }, enabled = selected.isNotEmpty())
        LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(candidates, key = { it.id }) { channel ->
                val isSelected = channel.id in selected
                ListRow(
                    title = "${formatChannelNumber(channel.number, digits)}   ${channel.name}",
                    subtitle = channel.originalGroup,
                    selected = isSelected,
                    onClick = { selected = if (isSelected) selected - channel.id else selected + channel.id },
                    trailing = if (isSelected) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}
