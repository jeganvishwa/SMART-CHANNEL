package com.example.freetv.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.freetv.domain.repository.SettingsRepository
import com.example.freetv.startup.StartupDecision
import com.example.freetv.startup.StartupManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    settings: SettingsRepository,
    private val startupManager: StartupManager,
) : ViewModel() {

    private val _startup = MutableStateFlow<StartupDecision?>(null)
    /** Null while loading; the splash screen stays visible until it is set. */
    val startup: StateFlow<StartupDecision?> = _startup.asStateFlow()

    private val _homeRequests = MutableStateFlow(0)
    /** Incremented when the HOME button brings FreeTV (as default Home) to the front. */
    val homeRequests: StateFlow<Int> = _homeRequests.asStateFlow()

    val pureBlack: StateFlow<Boolean> = settings.settings.map { it.pureBlackTheme }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        viewModelScope.launch { _startup.value = startupManager.run() }
    }

    fun onHomePressed() {
        _homeRequests.value++
    }
}
