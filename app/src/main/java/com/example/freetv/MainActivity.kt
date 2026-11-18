package com.example.freetv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.example.freetv.presentation.navigation.FreeTvNavHost
import com.example.freetv.presentation.theme.FreeTvTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single activity hosting every Compose for TV screen (home, player, managers).
 * Remote-control key handling is added in Phase 6 at this level so numeric
 * channel input works from any screen.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent {
            FreeTvTheme {
                FreeTvNavHost()
            }
        }
    }
}
