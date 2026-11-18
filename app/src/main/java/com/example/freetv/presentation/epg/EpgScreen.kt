package com.example.freetv.presentation.epg

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.example.freetv.domain.model.ChannelItem
import com.example.freetv.domain.model.EpgProgram
import com.example.freetv.domain.model.formatChannelNumber
import com.example.freetv.domain.repository.ChannelRepository
import com.example.freetv.domain.repository.EpgRepository
import com.example.freetv.domain.repository.SettingsRepository
import com.example.freetv.presentation.components.ChannelLogo
import com.example.freetv.presentation.components.EmptyState
import com.example.freetv.presentation.components.ListRow
import com.example.freetv.presentation.components.MessageDialog
import com.example.freetv.presentation.components.ProgressBar
import com.example.freetv.presentation.components.ScreenScaffold
import com.example.freetv.presentation.components.Spinner
import com.example.freetv.presentation.components.TvButton
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

data class ScheduleState(val channel: ChannelItem? = null, val programs: List<EpgProgram> = emptyList(), val loading: Boolean = false)

@HiltViewModel
class EpgViewModel @Inject constructor(
    channels: ChannelRepository,
    private val epg: EpgRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    val channelList: StateFlow<List<ChannelItem>> = channels.observeAllVisible()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val programs: StateFlow<Map<String, EpgProgram>> = epg.observeCurrentPrograms()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    val lastRefresh: StateFlow<Long?> = epg.observeLastRefresh()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val settingsState = settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val schedule = MutableStateFlow(ScheduleState())
    val refreshing = MutableStateFlow<String?>(null)
    val message = MutableStateFlow<String?>(null)
    private var scheduleJob: Job? = null

    fun focus(channel: ChannelItem) {
        if (schedule.value.channel?.id == channel.id) return
        scheduleJob?.cancel()
        schedule.value = ScheduleState(channel, emptyList(), true)
        scheduleJob = viewModelScope.launch {
            delay(250) // debounce fast D-pad scrolling
            val now = System.currentTimeMillis()
            val list = epg.getSchedule(channel.tvgId, channel.tvgName, channel.name, now - HOUR, now + 24 * HOUR)
            schedule.value = ScheduleState(channel, list, false)
        }
    }

    fun refresh() {
        if (refreshing.value != null) return
        viewModelScope.launch {
            val s = settings.current()
            val url = s.epgUrl
            if (url.isNullOrBlank()) {
                message.value = "Set the XMLTV guide URL in Settings → TV Guide first."
                return@launch
            }
            refreshing.value = "Starting…"
            val result = epg.refresh(url, s.epgOffsetMinutes) { refreshing.value = it }
            refreshing.value = null
            message.value = result.message
            schedule.value.channel?.let {
                schedule.value = ScheduleState()
                focus(it)
            }
        }
    }

    companion object {
        private const val HOUR = 3_600_000L
    }
}

@Composable
fun EpgScreen(onPlay: (Long) -> Unit, viewModel: EpgViewModel = hiltViewModel()) {
    val channels by viewModel.channelList.collectAsStateWithLifecycle()
    val programs by viewModel.programs.collectAsStateWithLifecycle()
    val schedule by viewModel.schedule.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val lastRefresh by viewModel.lastRefresh.collectAsStateWithLifecycle()
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val timeFormat = remember { android.text.format.DateFormat.getTimeFormat(context) }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val digits = settings?.channelNumberDigits ?: 3

    ScreenScaffold(
        title = "TV Guide",
        subtitle = when {
            refreshing != null -> refreshing
            lastRefresh != null -> "Guide updated " + dateFormat.format(Date(lastRefresh!!))
            else -> "No guide loaded yet"
        },
        actions = {
            if (refreshing != null) Spinner(size = 32.dp)
            TvButton("Refresh guide", viewModel::refresh, icon = Icons.Filled.Refresh, enabled = refreshing == null)
        },
    ) {
        if (channels.isEmpty()) {
            EmptyState("No channels", "Import a playlist first.", icon = Icons.Filled.DateRange)
        } else {
            Row(Modifier.fillMaxHeight()) {
                LazyColumn(
                    Modifier.width(520.dp).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(channels, key = { it.id }) { channel ->
                        val now = channel.tvgId?.lowercase()?.let { programs[it] }
                        ListRow(
                            title = "${formatChannelNumber(channel.number, digits)}  ${channel.name}",
                            subtitle = now?.title ?: "—",
                            onClick = { onPlay(channel.id) },
                            leading = { ChannelLogo(channel.logoUrl, channel.name, size = 40.dp) },
                            modifier = Modifier.onFocusChanged { if (it.isFocused) viewModel.focus(channel) },
                        )
                    }
                }
                Spacer(Modifier.width(24.dp))
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                        .padding(20.dp),
                ) {
                    val channel = schedule.channel
                    if (channel == null) {
                        Text("Move to a channel to see its schedule. Press OK to watch.", style = MaterialTheme.typography.bodyLarge)
                    } else {
                        Text(channel.name, style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(12.dp))
                        when {
                            schedule.loading -> Spinner(size = 32.dp)
                            schedule.programs.isEmpty() -> Text(
                                "No programme information for this channel. The guide matches channels by tvg-id or name.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(schedule.programs, key = { it.startMs }) { program ->
                                    ProgramItem(program, timeFormat)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    message?.let { MessageDialog("TV Guide", it) { viewModel.message.value = null } }
}

@Composable
private fun ProgramItem(program: EpgProgram, timeFormat: DateFormat) {
    val now = System.currentTimeMillis()
    val live = now in program.startMs until program.endMs
    Column(Modifier.fillMaxWidth()) {
        Text(
            "${timeFormat.format(Date(program.startMs))} – ${timeFormat.format(Date(program.endMs))}   ${program.title}",
            style = MaterialTheme.typography.titleMedium,
            color = if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (live) {
            Spacer(Modifier.height(4.dp))
            ProgressBar(program.progressAt(now), Modifier.fillMaxWidth())
        }
        program.description?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
