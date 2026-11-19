package com.example.freetv.presentation.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.example.freetv.presentation.components.ListRow
import com.example.freetv.presentation.components.LoadingState
import com.example.freetv.presentation.components.MessageDialog
import com.example.freetv.presentation.components.ScreenScaffold
import com.example.freetv.presentation.components.TvButton
import com.example.freetv.startup.DiagnosticItem
import com.example.freetv.startup.DiagnosticState
import com.example.freetv.startup.StartupDiagnosticsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StartupDiagnosticsViewModel @Inject constructor(
    private val manager: StartupDiagnosticsManager,
) : ViewModel() {
    private val _items = MutableStateFlow<List<DiagnosticItem>?>(null)
    val items: StateFlow<List<DiagnosticItem>?> = _items.asStateFlow()
    val message = MutableStateFlow<String?>(null)

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { _items.value = manager.run() }
    }

    fun copy(context: Context) {
        val text = manager.toText(_items.value.orEmpty())
        message.value = try {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("FreeTV diagnostics", text))
            "Diagnostics copied to the clipboard."
        } catch (_: Exception) {
            "Could not copy on this TV."
        }
    }
}

@Composable
fun StartupDiagnosticsScreen(viewModel: StartupDiagnosticsViewModel = hiltViewModel()) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val firstFocus = remember { FocusRequester() }

    ScreenScaffold(
        title = "Startup Diagnostics",
        subtitle = "\"Unknown\" means this TV does not let apps check that item – it is not a failure.",
        actions = {
            TvButton("Refresh diagnostics", viewModel::refresh, icon = Icons.Filled.Refresh, modifier = Modifier.focusRequester(firstFocus))
            TvButton("Copy diagnostics", { viewModel.copy(context) }, primary = false)
        },
    ) {
        val list = items
        if (list == null) {
            LoadingState("Running checks…")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(list, key = { it.label }) { item ->
                    ListRow(
                        title = item.label,
                        subtitle = item.note,
                        onClick = {},
                        trailing = {
                            Text(
                                item.value,
                                style = MaterialTheme.typography.titleMedium,
                                color = when (item.state) {
                                    DiagnosticState.PASS, DiagnosticState.YES -> MaterialTheme.colorScheme.primary
                                    DiagnosticState.FAIL, DiagnosticState.NO -> MaterialTheme.colorScheme.error
                                    DiagnosticState.UNKNOWN -> MaterialTheme.colorScheme.secondary
                                    DiagnosticState.INFO -> MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 2,
                            )
                        },
                    )
                }
                item {
                    GuideCard(
                        "About these checks",
                        "Default Home is read from Android (RoleManager / the HOME intent). If the TV hides it, the result is Unknown. " +
                            "\"Last playback restoration\" shows what happened the last time auto-play tried to resume a channel.",
                    )
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { firstFocus.requestFocus() }
    }
    message?.let { MessageDialog("Diagnostics", it) { viewModel.message.value = null } }
}
