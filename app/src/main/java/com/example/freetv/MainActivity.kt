package com.example.freetv

import android.content.Intent
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
 * Single activity hosting every Compose for TV screen. It is reachable from the
 * Android TV / Google TV launcher (LEANBACK_LAUNCHER), a regular launcher, and –
 * if the user chooses FreeTV as the default Home app – the HOME button.
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
            val homeRequests by mainViewModel.homeRequests.collectAsStateWithLifecycle()
            FreeTvTheme(pureBlack = pureBlack) {
                startup?.let { FreeTvNavHost(startup = it, homeRequests = homeRequests) }
            }
        }
    }

    /** singleTask: HOME presses (when FreeTV is the default Home) arrive here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) mainViewModel.onHomePressed()
    }
}
