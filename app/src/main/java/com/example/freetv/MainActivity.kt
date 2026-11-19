package com.example.freetv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.freetv.presentation.MainViewModel
import com.example.freetv.presentation.navigation.FreeTvNavHost
import com.example.freetv.presentation.theme.FreeTvTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single activity hosting every Compose for TV screen, opened from the
 * Android TV / Google TV Apps row (LEANBACK_LAUNCHER) or a regular launcher.
 * FreeTV is a normal app – it does not register as a Home launcher.
 *
 * The splash screen stays up until the start-up decision (home / favourites /
 * auto-play last channel) is ready. FreeTV never starts itself in the background.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { mainViewModel.startup.value == null }
        setContent {
            val pureBlack by mainViewModel.pureBlack.collectAsStateWithLifecycle()
            val startup by mainViewModel.startup.collectAsStateWithLifecycle()
            FreeTvTheme(pureBlack = pureBlack) {
                startup?.let { FreeTvNavHost(startup = it) }
            }
        }
    }
}
