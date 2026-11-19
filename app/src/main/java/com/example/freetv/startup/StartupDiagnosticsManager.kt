package com.example.freetv.startup

import android.content.Context
import com.example.freetv.BuildConfig
import com.example.freetv.data.network.NetworkMonitor
import com.example.freetv.domain.model.LanguageCatalog
import com.example.freetv.domain.repository.GroupRepository
import com.example.freetv.domain.repository.SettingsRepository
import com.example.freetv.launcher.DefaultLauncherManager
import com.example.freetv.launcher.LauncherStatus
import com.example.freetv.preferences.RestoreOutcome
import com.example.freetv.preferences.StartupPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

enum class DiagnosticState { PASS, FAIL, YES, NO, UNKNOWN, INFO }

data class DiagnosticItem(val label: String, val value: String, val state: DiagnosticState, val note: String? = null)

/** Builds the Settings → Google TV Setup → Startup Diagnostics report. */
@Singleton
class StartupDiagnosticsManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val launcherManager: DefaultLauncherManager,
    private val networkMonitor: NetworkMonitor,
    private val settings: SettingsRepository,
    private val startupPrefs: StartupPreferences,
    private val groups: GroupRepository,
) {
    suspend fun run(): List<DiagnosticItem> {
        val launcher = launcherManager.inspect()
        val s = settings.current()
        val prefs = startupPrefs.current()
        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

        val installed = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.isSuccess
        val languageTab = prefs.lastLanguageTab ?: s.lastSelectedTab
        val languageName = languageTab?.removePrefix("g:")?.toLongOrNull()?.let { id ->
            groups.getGroups().firstOrNull { it.id == id }?.name
        }
        val restore = prefs.lastRestore

        return listOf(
            DiagnosticItem(
                "FreeTV installed",
                if (installed) "Pass" else "Fail",
                if (installed) DiagnosticState.PASS else DiagnosticState.FAIL,
                "${context.packageName} ${BuildConfig.VERSION_NAME}",
            ),
            DiagnosticItem(
                "HOME intent registered",
                if (launcher.homeRegistered) "Pass" else "Fail",
                if (launcher.homeRegistered) DiagnosticState.PASS else DiagnosticState.FAIL,
                launcher.ownComponent,
            ),
            DiagnosticItem(
                "LEANBACK launcher registered",
                if (launcher.leanbackRegistered) "Pass" else "Fail",
                if (launcher.leanbackRegistered) DiagnosticState.PASS else DiagnosticState.FAIL,
            ),
            DiagnosticItem(
                "FreeTV selected as default HOME",
                when (launcher.status) {
                    LauncherStatus.FREE_TV_IS_DEFAULT -> "Yes"
                    LauncherStatus.ANOTHER_LAUNCHER_IS_DEFAULT, LauncherStatus.DEFAULT_NOT_SELECTED -> "No"
                    LauncherStatus.STATUS_UNKNOWN -> "Unknown"
                },
                when (launcher.status) {
                    LauncherStatus.FREE_TV_IS_DEFAULT -> DiagnosticState.YES
                    LauncherStatus.ANOTHER_LAUNCHER_IS_DEFAULT, LauncherStatus.DEFAULT_NOT_SELECTED -> DiagnosticState.NO
                    LauncherStatus.STATUS_UNKNOWN -> DiagnosticState.UNKNOWN
                },
                launcher.detail,
            ),
            networkMonitor.currentlyOnline().let { online ->
                DiagnosticItem("Internet available", if (online) "Yes" else "No", if (online) DiagnosticState.YES else DiagnosticState.NO)
            },
            DiagnosticItem(
                "Last selected language",
                languageName?.let { LanguageCatalog.labelFor(it) } ?: "Not selected",
                DiagnosticState.INFO,
            ),
            DiagnosticItem(
                "Last watched channel",
                prefs.lastWatched?.channelName?.takeIf { it.isNotBlank() } ?: "Not available",
                DiagnosticState.INFO,
                prefs.lastWatched?.watchedAt?.takeIf { it > 0 }?.let { "Watched " + dateFormat.format(Date(it)) },
            ),
            DiagnosticItem(
                "Auto-play enabled",
                if (s.autoPlayLastChannel) "Yes" else "No",
                if (s.autoPlayLastChannel) DiagnosticState.YES else DiagnosticState.NO,
            ),
            DiagnosticItem(
                "Last playback restoration",
                when (restore.outcome) {
                    RestoreOutcome.SUCCESS -> "Success"
                    RestoreOutcome.FAILED -> "Failed"
                    RestoreOutcome.PENDING -> "Started (result not reported)"
                    RestoreOutcome.NOT_ATTEMPTED -> "Not attempted"
                },
                when (restore.outcome) {
                    RestoreOutcome.SUCCESS -> DiagnosticState.PASS
                    RestoreOutcome.FAILED -> DiagnosticState.FAIL
                    else -> DiagnosticState.INFO
                },
                listOfNotNull(restore.message, restore.at?.let { dateFormat.format(Date(it)) }).joinToString(" · ").ifEmpty { null },
            ),
            DiagnosticItem(
                "Last startup error",
                prefs.lastStartupError ?: "None",
                if (prefs.lastStartupError == null) DiagnosticState.PASS else DiagnosticState.FAIL,
            ),
        )
    }

    fun toText(items: List<DiagnosticItem>): String = buildString {
        appendLine("FreeTV startup diagnostics")
        items.forEach { item ->
            append("- ").append(item.label).append(": ").append(item.value)
            item.note?.let { append(" (").append(it).append(")") }
            appendLine()
        }
    }
}
