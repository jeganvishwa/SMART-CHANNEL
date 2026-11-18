package com.example.freetv.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.freetv.domain.model.StartScreen
import com.example.freetv.domain.repository.ChannelRepository
import com.example.freetv.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What to open after the splash screen. */
data class StartupDecision(val openFavorites: Boolean, val autoPlayChannelId: Long?)

@HiltViewModel
class MainViewModel @Inject constructor(
    settings: SettingsRepository,
    channels: ChannelRepository,
) : ViewModel() {

    private val _startup = MutableStateFlow<StartupDecision?>(null)
    /** Null while loading; the splash screen stays visible until it is set. */
    val startup: StateFlow<StartupDecision?> = _startup.asStateFlow()

    val pureBlack: StateFlow<Boolean> = settings.settings.map { it.pureBlackTheme }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        viewModelScope.launch {
            val s = settings.current()
            val lastId = s.lastWatchedChannelId
            val autoPlay = if (s.autoPlayLastChannel && lastId != null) {
                channels.getChannel(lastId)?.takeIf { !it.hidden }?.id
            } else {
                null
            }
            _startup.value = StartupDecision(
                openFavorites = s.startScreen == StartScreen.FAVORITES,
                autoPlayChannelId = autoPlay,
            )
        }
    }
}
