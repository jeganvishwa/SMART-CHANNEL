package com.example.freetv.presentation.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.example.freetv.di.DefaultDispatcher
import com.example.freetv.launcher.DefaultLauncherManager
import com.example.freetv.launcher.LauncherInfo
import com.example.freetv.launcher.LauncherStatus
import com.example.freetv.presentation.components.FocusSurface
import com.example.freetv.presentation.components.ListRow
import com.example.freetv.presentation.components.LoadingState
import com.example.freetv.presentation.components.MessageDialog
import com.example.freetv.presentation.components.ScreenScaffold
import com.example.freetv.presentation.components.SectionHeader
import com.example.freetv.presentation.components.TvButton
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class GoogleTvSetupViewModel @Inject constructor(
    private val launcherManager: DefaultLauncherManager,
    @DefaultDispatcher private val cpu: CoroutineDispatcher,
) : ViewModel() {
    private val _info = MutableStateFlow<LauncherInfo?>(null)
    val info: StateFlow<LauncherInfo?> = _info.asStateFlow()
    val message = MutableStateFlow<String?>(null)

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { _info.value = withContext(cpu) { launcherManager.inspect() } }
    }

    fun requestRoleIntent() = launcherManager.createRequestHomeRoleIntent()

    fun openHomeSettings(context: Context) {
        val opened = launcherManager.openHomeSettings(context)
        message.value = if (opened == null) {
            "This TV has no settings screen for choosing the Home app. Use the manual steps or the ADB guide below."
        } else {
            "Opened $opened. Look for Apps → Default apps → Home app (names differ between TV brands)."
        }
    }
}

private fun copyToClipboard(context: Context, text: String): Boolean = try {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("FreeTV", text))
    true
} catch (_: Exception) {
    false
}

@Composable
fun GoogleTvSetupScreen(onDiagnostics: () -> Unit, viewModel: GoogleTvSetupViewModel = hiltViewModel()) {
    val info by viewModel.info.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var copied by remember { mutableStateOf<String?>(null) }
    val firstFocus = remember { FocusRequester() }
    val roleRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { viewModel.refresh() }

    val launcher = info
    if (launcher == null) {
        LoadingState("Checking Home app settings…")
        return
    }
    val copy: (String) -> Unit = { text -> copied = if (copyToClipboard(context, text)) "Copied:\n$text" else "Could not copy on this TV." }
    val others = launcher.homeActivities.filter { !it.isFreeTv }

    ScreenScaffold(
        title = "Google TV Setup",
        subtitle = "Default Home app, original launcher and startup checks",
        actions = {
            TvButton("Check again", viewModel::refresh, icon = Icons.Filled.Refresh, primary = false)
            TvButton("Startup diagnostics", onDiagnostics, primary = false)
        },
    ) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { SectionHeader("Set FreeTV as default Home") }
            item {
                ListRow(
                    title = when (launcher.status) {
                        LauncherStatus.FREE_TV_IS_DEFAULT -> "FreeTV is the default Home app"
                        LauncherStatus.ANOTHER_LAUNCHER_IS_DEFAULT -> "Another Home app is the default: ${launcher.currentDefault?.label ?: "unknown"}"
                        LauncherStatus.DEFAULT_NOT_SELECTED -> "No default Home app is selected"
                        LauncherStatus.STATUS_UNKNOWN -> "Default Home app: unknown"
                    },
                    subtitle = launcher.detail,
                    onClick = viewModel::refresh,
                    modifier = Modifier.focusRequester(firstFocus),
                )
            }
            item {
                GuideCard(
                    "What does \"default Home app\" mean?",
                    "The Home app is the screen Android opens when you press the HOME button on the remote. " +
                        "If you choose FreeTV, pressing HOME brings you back to FreeTV's channels. " +
                        "FreeTV never changes this by itself – you decide.",
                )
            }
            if (launcher.canRequestRole) {
                item {
                    ListRow(
                        title = "Ask Android to make FreeTV the Home app",
                        subtitle = "Shows Android's own confirmation dialog (Android 10+). Some Google TV models do not show it.",
                        onClick = {
                            val intent = viewModel.requestRoleIntent()
                            if (intent == null) {
                                viewModel.message.value = "This TV does not offer the Home app dialog. Use the steps below."
                            } else {
                                try {
                                    roleRequest.launch(intent)
                                } catch (_: Exception) {
                                    viewModel.message.value = "This TV could not open the Home app dialog. Use the steps below."
                                }
                            }
                        },
                    )
                }
            }
            item {
                ListRow(
                    title = "Open Home app settings",
                    subtitle = "Opens the closest system settings screen this TV provides",
                    onClick = { viewModel.openHomeSettings(context) },
                    leading = { androidx.tv.material3.Icon(Icons.Filled.Home, contentDescription = null) },
                )
            }
            item {
                GuideCard(
                    "Manual steps",
                    "1. Open the TV's Settings.\n" +
                        "2. Go to Apps → Default apps → Home app (on some TVs: Apps → Special app access, or System → Home screen).\n" +
                        "3. Choose FreeTV.\n" +
                        "4. Press HOME on the remote to check.",
                )
            }
            item {
                GuideCard(
                    "Device compatibility",
                    "Many Google TV devices only allow Google's own Home screen and ignore other Home apps, " +
                        "even when one is selected. Whether FreeTV can be the Home app depends on the TV maker and firmware. " +
                        "Being the Home app also does not guarantee that FreeTV opens when the TV powers on or wakes from standby – " +
                        "the TV's firmware decides that.",
                )
            }

            item { SectionHeader("ADB setup (advanced)") }
            item {
                GuideCard(
                    "Before you start",
                    "Enable Developer options and USB / Wireless debugging on the TV, then connect from a computer with " +
                        "\"adb connect <TV-IP>:5555\" (Android 14 TV: use adb pair first). These commands depend on the TV's firmware " +
                        "and may be unavailable or ignored on some devices. Press OK on a command to copy it.",
                )
            }
            item { CommandRow("List the apps that can be Home", "adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME", copy) }
            item { CommandRow("Check that FreeTV is installed", "adb shell pm list packages ${context.packageName}", copy) }
            item { CommandRow("Make FreeTV the Home app (this TV's real component)", "adb shell cmd package set-home-activity ${launcher.ownComponent}", copy) }
            item { CommandRow("Test the HOME button", "adb shell input keyevent KEYCODE_HOME", copy) }
            item { CommandRow("See which screen is open (Windows)", "adb shell dumpsys activity activities | findstr mResumedActivity", copy) }

            item { SectionHeader("Restore the original Google TV launcher") }
            item {
                GuideCard(
                    "Using Settings",
                    "1. Open the TV's Settings.\n" +
                        "2. Go to Apps → Default apps → Home app, if your TV has it.\n" +
                        "3. Select the original Google TV Home.\n" +
                        "If your TV has no such setting, use ADB below. FreeTV never disables or uninstalls Google's launcher.",
                )
            }
            if (others.isEmpty()) {
                item {
                    GuideCard(
                        "Other Home apps found on this TV",
                        "None are visible to FreeTV. Run the \"List the apps that can be Home\" command above to find the original " +
                            "launcher's exact component, then use: adb shell cmd package set-home-activity <component>",
                    )
                }
            } else {
                item {
                    GuideCard(
                        "Other Home apps found on this TV",
                        "These components were read from this TV. Use the one that is the original Google TV Home.",
                    )
                }
                others.forEach { home ->
                    item(key = home.component) {
                        CommandRow("Restore ${home.label}", "adb shell cmd package set-home-activity ${home.component}", copy)
                    }
                }
            }

            item { SectionHeader("How FreeTV starts") }
            item {
                GuideCard(
                    "HOME button",
                    "Opens FreeTV only if FreeTV is the selected default Home app and the TV allows it.",
                )
            }
            item {
                GuideCard(
                    "Power on / wake from standby",
                    "The TV firmware decides which app appears. FreeTV cannot force itself to open at power-on or wake.",
                )
            }
            item {
                GuideCard(
                    "Auto-play last channel",
                    "When enabled in Settings, FreeTV plays your last channel each time you open it – if the channel still exists and the internet works.",
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        delay(80)
        runCatching { firstFocus.requestFocus() }
    }
    message?.let { MessageDialog("Google TV Setup", it) { viewModel.message.value = null } }
    copied?.let { MessageDialog("Command", it) { copied = null } }
}

/** Focusable text block (so D-pad scrolling reaches it on TV). */
@Composable
fun GuideCard(title: String, body: String) {
    ListRow(title = title, subtitle = body, onClick = {})
}

@Composable
private fun CommandRow(title: String, command: String, onCopy: (String) -> Unit) {
    FocusSurface(onClick = { onCopy(command) }, modifier = Modifier.fillMaxWidth(), focusedScale = 1.01f) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                command,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
