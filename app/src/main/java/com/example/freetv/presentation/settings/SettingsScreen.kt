package com.example.freetv.presentation.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.example.freetv.BuildConfig
import com.example.freetv.domain.model.BufferProfile
import com.example.freetv.domain.model.StartScreen
import com.example.freetv.domain.model.StartupLanguageMode
import com.example.freetv.presentation.components.ChoiceDialog
import com.example.freetv.presentation.components.ConfirmDialog
import com.example.freetv.presentation.components.ListRow
import com.example.freetv.presentation.components.LoadingState
import com.example.freetv.presentation.components.MessageDialog
import com.example.freetv.presentation.components.ScreenScaffold
import com.example.freetv.presentation.components.SectionHeader
import com.example.freetv.presentation.components.TextInputDialog
import com.example.freetv.presentation.components.ToggleRow
import com.example.freetv.presentation.components.TvDialog
import com.example.freetv.presentation.components.ValueRow
import com.example.freetv.presentation.components.Spinner
import com.example.freetv.utils.UrlSanitizer
import kotlinx.coroutines.delay

private class Choice(val title: String, val options: List<String>, val selected: Int, val onSelect: (Int) -> Unit)

private val REFRESH_HOURS = listOf(6, 12, 24, 48, 72, 168)
private val TIMEOUTS_MS = listOf(1_000L, 1_500L, 2_000L, 2_500L, 3_000L, 4_000L)
private val OFFSETS = (-12..12).flatMap { h -> listOf(h * 60, h * 60 + 30) }.filter { it in -720..720 }.distinct()

@Composable
fun SettingsScreen(onGoogleTvSetup: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.state.collectAsStateWithLifecycle()
    val startup by viewModel.startup.collectAsStateWithLifecycle()
    val languages by viewModel.languageGroups.collectAsStateWithLifecycle()
    val playlistEpg by viewModel.playlistEpgUrl.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val restart by viewModel.restartRequired.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var choice by remember { mutableStateOf<Choice?>(null) }
    var editingEpg by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }

    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) viewModel.backupTo(uri)
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.restoreFrom(uri)
    }

    LaunchedEffect(restart) { if (restart) restartApp(context) }

    val s = settings
    if (s == null) {
        LoadingState()
        return
    }
    val languageName = languages.firstOrNull { it.id == s.defaultLanguageGroupId }?.name ?: "None"

    ScreenScaffold(title = "Settings") {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { SectionHeader("Language") }
            item {
                ValueRow(
                    "On start, open",
                    if (s.startupLanguageMode == StartupLanguageMode.LAST_SELECTED) "Last selected language" else "Default language",
                    {
                        choice = Choice(
                            "On start, open",
                            listOf("Last selected language", "Default language"),
                            s.startupLanguageMode.ordinal,
                        ) { viewModel.setStartupLanguageMode(StartupLanguageMode.entries[it]) }
                    },
                    modifier = Modifier.focusRequester(firstFocus),
                )
            }
            item {
                ValueRow("Default language", languageName, {
                    choice = Choice(
                        "Default language",
                        listOf("None") + languages.map { it.name },
                        languages.indexOfFirst { it.id == s.defaultLanguageGroupId } + 1,
                    ) { index -> viewModel.setDefaultLanguage(if (index == 0) null else languages[index - 1].id) }
                }, subtitle = "Add more languages in Groups → Create group → Language")
            }

            item { SectionHeader("Startup & Google TV") }
            item {
                ListRow(
                    "Google TV Setup",
                    subtitle = "Set FreeTV as default Home, restore the original launcher, startup diagnostics",
                    onClick = onGoogleTvSetup,
                )
            }
            item {
                ToggleRow(
                    "Auto-play last watched channel", s.autoPlayLastChannel, viewModel::setAutoPlay,
                    subtitle = "Plays it when you open FreeTV. It does not make FreeTV open by itself when the TV turns on.",
                )
            }
            item { ToggleRow("Restore last selected language", startup?.restoreLastLanguage ?: true, viewModel::setRestoreLanguage) }
            item { ToggleRow("Restore last selected group", startup?.restoreLastGroup ?: true, viewModel::setRestoreGroup) }
            item {
                ToggleRow(
                    "Show startup diagnostics", startup?.showStartupDiagnostics ?: false, viewModel::setShowDiagnostics,
                    subtitle = "Shows a short report each time FreeTV opens",
                )
            }

            item { SectionHeader("Playlists") }
            item { ToggleRow("Automatic playlist refresh", s.autoRefreshEnabled, viewModel::setAutoRefresh) }
            item {
                ValueRow("Refresh interval", "${s.refreshIntervalHours} h", {
                    choice = Choice("Refresh interval", REFRESH_HOURS.map { "$it hours" }, REFRESH_HOURS.indexOf(s.refreshIntervalHours)) {
                        viewModel.setRefreshInterval(REFRESH_HOURS[it])
                    }
                })
            }
            item {
                ToggleRow(
                    "Auto-number new channels", s.autoNumberNewChannels, viewModel::setAutoNumber,
                    subtitle = "New channels get the next free number when a playlist is imported",
                )
            }

            item { SectionHeader("Player") }
            item {
                ValueRow("Buffering", s.bufferProfile.label, {
                    choice = Choice("Buffering", BufferProfile.entries.map { it.label }, s.bufferProfile.ordinal) {
                        viewModel.setBuffer(BufferProfile.entries[it])
                    }
                }, subtitle = "Fast start = quicker zapping; Stable = fewer pauses on slow networks")
            }
            item {
                ValueRow("Retries per source", s.maxRetriesPerStream.toString(), {
                    choice = Choice("Retries before switching to a backup", (0..5).map { it.toString() }, s.maxRetriesPerStream) {
                        viewModel.setRetries(it)
                    }
                })
            }
            item {
                ValueRow("Default home screen", if (s.startScreen == StartScreen.FAVORITES) "Favourites" else "Home", {
                    choice = Choice("Default home screen", listOf("Home", "Favourites"), if (s.startScreen == StartScreen.FAVORITES) 1 else 0) {
                        viewModel.setStartScreen(if (it == 1) StartScreen.FAVORITES else StartScreen.HOME)
                    }
                })
            }

            item { SectionHeader("Remote control") }
            item {
                ValueRow("Number input timeout", "${s.numericInputTimeoutMs / 1000.0} s", {
                    choice = Choice("Number input timeout", TIMEOUTS_MS.map { "${it / 1000.0} seconds" }, TIMEOUTS_MS.indexOf(s.numericInputTimeoutMs)) {
                        viewModel.setNumericTimeout(TIMEOUTS_MS[it])
                    }
                })
            }
            item {
                ValueRow("Channel number format", "${s.channelNumberDigits} digits (${"1".padStart(s.channelNumberDigits, '0')})", {
                    choice = Choice("Channel number digits", (1..5).map { "$it digits (${"1".padStart(it, '0')})" }, s.channelNumberDigits - 1) {
                        viewModel.setDigits(it + 1)
                    }
                }, subtitle = "Also the number of key presses that switch instantly")
            }

            item { SectionHeader("Appearance") }
            item { ToggleRow("Pure black background", s.pureBlackTheme, viewModel::setPureBlack, subtitle = "Dark theme is always on; pure black suits OLED TVs") }

            item { SectionHeader("TV Guide (XMLTV)") }
            item {
                ValueRow("Guide URL", s.epgUrl?.let { UrlSanitizer.redact(it) } ?: "Not set", { editingEpg = true }, subtitle = ".xml or .xml.gz")
            }
            if (playlistEpg != null && playlistEpg != s.epgUrl) {
                item {
                    ListRow(
                        title = "Use the guide from your playlist",
                        subtitle = UrlSanitizer.redact(playlistEpg),
                        onClick = { viewModel.setEpgUrl(playlistEpg!!) },
                    )
                }
            }
            item { ToggleRow("Refresh guide automatically", s.epgAutoRefresh, viewModel::setEpgAuto) }
            item {
                ValueRow("Guide time correction", formatOffset(s.epgOffsetMinutes), {
                    choice = Choice("Guide time correction", OFFSETS.map { formatOffset(it) }, OFFSETS.indexOf(s.epgOffsetMinutes)) {
                        viewModel.setEpgOffset(OFFSETS[it])
                    }
                }, subtitle = "Shift programme times if the guide is in the wrong time zone")
            }
            item { ListRow("Refresh guide now", onClick = viewModel::refreshEpg) }
            item { ListRow("Clear guide data", onClick = viewModel::clearEpg) }

            item { SectionHeader("Storage & backup") }
            item { ListRow("Clear cache", subtitle = "Logos and temporary downloads", onClick = viewModel::clearCache) }
            item {
                ListRow("Back up database to a file…", subtitle = "Playlists, channels, numbers, groups, favourites", onClick = {
                    try {
                        createBackup.launch("freetv-backup.db")
                    } catch (_: ActivityNotFoundException) {
                        viewModel.quickBackup()
                    }
                })
            }
            item { ListRow("Quick backup", subtitle = viewModel.quickBackupPath(), onClick = viewModel::quickBackup) }
            item { ListRow("Restore from quick backup", onClick = { confirmRestore = true }) }
            item {
                ListRow("Restore from a file…", onClick = {
                    try {
                        openBackup.launch(arrayOf("*/*"))
                    } catch (_: ActivityNotFoundException) {
                        viewModel.message.value = "This TV has no file picker. Use Quick backup / restore instead."
                    }
                })
            }

            item { SectionHeader("About") }
            item {
                ListRow(
                    title = "FreeTV ${BuildConfig.VERSION_NAME}",
                    subtitle = "IPTV player for your own M3U playlists. No server, no account, no built-in channels. " +
                        "It does not bypass DRM, logins or geo-restrictions – only add streams you are allowed to watch.",
                    onClick = {},
                )
            }
            item {
                Text(
                    "Remote: ▲▼ / CH± change channel · ◄ channel list · ► or OK options · 0-9 jump to number · BACK exit player",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        delay(50)
        runCatching { firstFocus.requestFocus() }
    }

    choice?.let { c ->
        ChoiceDialog(
            title = c.title,
            options = c.options,
            selectedIndex = c.selected,
            onSelect = {
                c.onSelect(it)
                choice = null
            },
            onDismiss = { choice = null },
        )
    }
    if (editingEpg) {
        TextInputDialog(
            title = "XMLTV guide URL",
            initial = s.epgUrl.orEmpty(),
            keyboardType = KeyboardType.Uri,
            hint = "Leave empty to turn the guide off.",
            onConfirm = {
                viewModel.setEpgUrl(it)
                editingEpg = false
            },
            onDismiss = { editingEpg = false },
        )
    }
    if (confirmRestore) {
        ConfirmDialog(
            title = "Restore backup?",
            message = "All current playlists, numbers, groups and favourites are replaced by the backup. The app restarts.",
            confirmText = "Restore",
            onConfirm = {
                confirmRestore = false
                viewModel.quickRestore()
            },
            onDismiss = { confirmRestore = false },
        )
    }
    busy?.let {
        TvDialog(title = "Please wait", onDismiss = {}) {
            Spinner(size = 32.dp)
            Text(it, style = MaterialTheme.typography.bodyLarge)
        }
    }
    message?.let { MessageDialog("Settings", it) { viewModel.message.value = null } }
}

private fun formatOffset(minutes: Int): String = when {
    minutes == 0 -> "None"
    else -> (if (minutes > 0) "+" else "−") + "${kotlin.math.abs(minutes) / 60}h" +
        (if (kotlin.math.abs(minutes) % 60 != 0) " ${kotlin.math.abs(minutes) % 60}m" else "")
}

private fun restartApp(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    context.startActivity(intent)
    Runtime.getRuntime().exit(0)
}
