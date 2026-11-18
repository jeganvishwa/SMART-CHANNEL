package com.example.freetv.presentation.playlist

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.example.freetv.domain.model.ImportStage
import com.example.freetv.domain.model.Playlist
import com.example.freetv.domain.model.PlaylistSourceType
import com.example.freetv.domain.model.PlaylistStatus
import com.example.freetv.presentation.components.ChoiceDialog
import com.example.freetv.presentation.components.ConfirmDialog
import com.example.freetv.presentation.components.EmptyState
import com.example.freetv.presentation.components.ListRow
import com.example.freetv.presentation.components.LoadingState
import com.example.freetv.presentation.components.MessageDialog
import com.example.freetv.presentation.components.ProgressBar
import com.example.freetv.presentation.components.ScreenScaffold
import com.example.freetv.presentation.components.SectionHeader
import com.example.freetv.presentation.components.Spinner
import com.example.freetv.presentation.components.StatusDot
import com.example.freetv.presentation.components.ToggleRow
import com.example.freetv.presentation.components.TvButton
import com.example.freetv.presentation.components.TvTextField
import com.example.freetv.utils.UrlSanitizer
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

@Composable
fun PlaylistManagerScreen(
    onAddUrl: () -> Unit,
    onImportFile: () -> Unit,
    onEdit: (Long) -> Unit,
    onViewChannels: (Long) -> Unit,
    viewModel: PlaylistManagerViewModel = hiltViewModel(),
) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var actionsFor by remember { mutableStateOf<Playlist?>(null) }
    var deleteFor by remember { mutableStateOf<Playlist?>(null) }
    val firstFocus = remember { FocusRequester() }

    ScreenScaffold(
        title = "Playlists",
        subtitle = "Add as many M3U playlists as you like. Your numbers, groups and favourites are kept when they refresh.",
        actions = {
            TvButton("Add URL", onAddUrl, icon = Icons.Filled.Add, modifier = Modifier.focusRequester(firstFocus))
            TvButton("Import file", onImportFile, icon = Icons.Filled.Folder, primary = false)
            TvButton("Refresh all", viewModel::refreshAll, icon = Icons.Filled.Refresh, primary = false)
        },
    ) {
        val list = playlists
        when {
            list == null -> LoadingState()
            list.isEmpty() -> EmptyState(
                title = "No playlists",
                message = "Add an M3U / M3U8 playlist URL or import an .m3u file from storage.",
                icon = Icons.AutoMirrored.Filled.List,
            )
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { playlist ->
                    PlaylistRow(playlist, progress[playlist.id]?.let { p -> p.detail ?: p.stage.name }) { actionsFor = playlist }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        delay(50)
        runCatching { firstFocus.requestFocus() }
    }

    actionsFor?.let { p ->
        val options = listOf("Refresh now", "View channels", "Edit", if (p.enabled) "Disable" else "Enable", "Delete")
        ChoiceDialog(
            title = p.name,
            options = options,
            selectedIndex = -1,
            onSelect = { index ->
                actionsFor = null
                when (index) {
                    0 -> viewModel.refresh(p.id)
                    1 -> onViewChannels(p.id)
                    2 -> onEdit(p.id)
                    3 -> viewModel.setEnabled(p.id, !p.enabled)
                    4 -> deleteFor = p
                }
            },
            onDismiss = { actionsFor = null },
        )
    }
    deleteFor?.let { p ->
        ConfirmDialog(
            title = "Delete playlist?",
            message = "\"${p.name}\" and its ${p.channelCount} channels will be removed, including their numbers and favourites.",
            confirmText = "Delete",
            onConfirm = {
                deleteFor = null
                viewModel.delete(p.id)
            },
            onDismiss = { deleteFor = null },
        )
    }
    message?.let { MessageDialog("Playlists", it, viewModel::clearMessage) }
}

@Composable
private fun PlaylistRow(playlist: Playlist, progressText: String?, onClick: () -> Unit) {
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val source = if (playlist.sourceType == PlaylistSourceType.FILE) "Local file" else UrlSanitizer.redact(playlist.url)
    val refreshed = playlist.lastRefreshAt?.let { "updated " + dateFormat.format(Date(it)) } ?: "never updated"
    val statusText = when {
        progressText != null -> "Refreshing… $progressText"
        playlist.status == PlaylistStatus.ERROR -> "Error: ${playlist.lastError}"
        !playlist.enabled -> "Disabled"
        else -> "${playlist.channelCount} channels · $refreshed"
    }
    ListRow(
        title = playlist.name,
        subtitle = "$source\n$statusText",
        onClick = onClick,
        leading = {
            if (progressText != null) {
                Spinner(size = 24.dp)
            } else {
                StatusDot(
                    when {
                        !playlist.enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                        playlist.status == PlaylistStatus.ERROR -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    },
                )
            }
        },
    )
}

@Composable
fun AddPlaylistScreen(
    onImported: (playlistId: Long) -> Unit,
    onDone: () -> Unit,
    viewModel: AddPlaylistViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var importedSummary by remember { mutableStateOf<Pair<Long, String>?>(null) }
    val firstFocus = remember { FocusRequester() }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importUri(uri)
    }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.importPath() else viewModel.showError("Storage permission is needed to read the file.")
    }

    LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is AddPlaylistEvent.Imported -> importedSummary = event.playlistId to event.summary
                AddPlaylistEvent.Saved -> onDone()
            }
        }
    }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { firstFocus.requestFocus() }
    }

    val title = when {
        ui.isEdit -> "Edit playlist"
        ui.isFileMode -> "Import M3U file"
        else -> "Add M3U playlist"
    }

    ScreenScaffold(title = title) {
        Column(
            Modifier
                .fillMaxSize()
                .widthIn(max = 900.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TvTextField(
                label = "Playlist name (optional)",
                value = ui.name,
                onValueChange = viewModel::setName,
                placeholder = "e.g. Tamil free channels",
                modifier = Modifier.fillMaxWidth().focusRequester(firstFocus),
            )
            if (!ui.isFileMode) {
                TvTextField(
                    label = "Playlist URL (M3U / M3U8)",
                    value = ui.url,
                    onValueChange = viewModel::setUrl,
                    placeholder = "https://example.com/playlist.m3u",
                    keyboardType = KeyboardType.Uri,
                    hint = "Only add playlists you are allowed to use.",
                    modifier = Modifier.fillMaxWidth(),
                )
                ToggleRow(
                    title = "Refresh automatically",
                    subtitle = "Update channels in the background at the interval set in Settings",
                    checked = ui.autoRefresh,
                    onToggle = viewModel::setAutoRefresh,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton(if (ui.isEdit) "Save" else "Add and detect channels", { viewModel.save() })
                    TvButton("Cancel", onDone, primary = false)
                }
            } else if (!ui.isEdit) {
                SectionHeader("Option 1 – choose with the system file picker")
                TvButton("Browse files…", {
                    try {
                        pickFile.launch(arrayOf("*/*"))
                    } catch (_: ActivityNotFoundException) {
                        viewModel.showError("This TV has no file picker. Use option 2 and type the file path instead.")
                    }
                }, icon = Icons.Filled.Folder)
                SectionHeader("Option 2 – type the file path")
                TvTextField(
                    label = "File path",
                    value = ui.filePath,
                    onValueChange = viewModel::setFilePath,
                    placeholder = "/sdcard/Download/playlist.m3u",
                    keyboardType = KeyboardType.Uri,
                    modifier = Modifier.fillMaxWidth(),
                )
                TvButton("Import from path", {
                    val needsPermission = Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2 &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                    if (needsPermission) storagePermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE) else viewModel.importPath()
                }, primary = false)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton("Save", { viewModel.save() })
                    TvButton("Cancel", onDone, primary = false)
                }
            }
        }
    }

    when (val status = ui.status) {
        is AddPlaylistStatus.Working -> ImportProgressOverlay(status.progress.stage, status.progress.fraction, status.progress.detail)
        is AddPlaylistStatus.Error -> MessageDialog("Could not import", status.message, viewModel::dismissStatus)
        is AddPlaylistStatus.Duplicate -> ConfirmDialog(
            title = "Playlist already added",
            message = "This URL is already used by \"${status.existingName}\". Add it again anyway?",
            confirmText = "Add anyway",
            onConfirm = {
                viewModel.dismissStatus()
                viewModel.save(allowDuplicate = true)
            },
            onDismiss = viewModel::dismissStatus,
        )
        AddPlaylistStatus.Editing -> Unit
    }

    importedSummary?.let { (id, summary) ->
        ConfirmDialog(
            title = "Playlist imported",
            message = "$summary. Channels were numbered and sorted into language and category groups automatically.",
            confirmText = "View channels",
            dismissText = "Done",
            onConfirm = {
                importedSummary = null
                onImported(id)
            },
            onDismiss = {
                importedSummary = null
                onDone()
            },
        )
    }
}

@Composable
private fun ImportProgressOverlay(stage: ImportStage, fraction: Float?, detail: String?) {
    com.example.freetv.presentation.components.TvDialog(title = "Importing playlist", onDismiss = {}) {
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spinner(size = 32.dp)
                    Spacer(Modifier.padding(start = 12.dp))
                    Text(
                        when (stage) {
                            ImportStage.DOWNLOADING -> "Downloading"
                            ImportStage.PARSING -> "Detecting channels"
                            ImportStage.SAVING -> "Saving channels"
                            ImportStage.DONE -> "Done"
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                Spacer(Modifier.height(12.dp))
                if (fraction != null) ProgressBar(fraction, Modifier.fillMaxWidth())
                if (detail != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
