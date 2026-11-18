package com.example.freetv.presentation.navigation

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.foundation.layout.fillMaxSize
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import com.example.freetv.presentation.StartupDecision
import com.example.freetv.presentation.channels.ChannelEditScreen
import com.example.freetv.presentation.channels.ChannelManagerScreen
import com.example.freetv.presentation.channels.ChannelNumbersScreen
import com.example.freetv.presentation.epg.EpgScreen
import com.example.freetv.presentation.favorites.FavoritesScreen
import com.example.freetv.presentation.groups.GroupDetailScreen
import com.example.freetv.presentation.groups.GroupManagerScreen
import com.example.freetv.presentation.home.HomeDestination
import com.example.freetv.presentation.home.HomeScreen
import com.example.freetv.presentation.player.PlayerScreen
import com.example.freetv.presentation.playlist.AddPlaylistScreen
import com.example.freetv.presentation.playlist.PlaylistManagerScreen
import com.example.freetv.presentation.settings.SettingsScreen

@Composable
fun FreeTvNavHost(
    startup: StartupDecision,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    // Apply the start-up decision once (not again after rotation / process restore).
    var startupHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(startup) {
        if (!startupHandled) {
            startupHandled = true
            if (startup.openFavorites) navController.navigate(FreeTvRoute.Favorites)
            startup.autoPlayChannelId?.let { navController.navigate(FreeTvRoute.Player(it)) }
        }
    }

    fun play(channelId: Long, type: String = "ALL", contextId: Long = -1) {
        navController.navigate(FreeTvRoute.Player(channelId, type, contextId)) { launchSingleTop = true }
    }

    // A TV Surface provides the correct content colour (light text) to every screen.
    Surface(
        modifier = modifier.fillMaxSize(),
        colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
    ) {
    NavHost(
        navController = navController,
        startDestination = FreeTvRoute.Home,
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
    ) {
        composable<FreeTvRoute.Home> {
            HomeScreen(
                onOpenPlayer = { id, type, ctx -> play(id, type, ctx) },
                onNavigate = { destination ->
                    navController.navigate(
                        when (destination) {
                            HomeDestination.FAVORITES -> FreeTvRoute.Favorites
                            HomeDestination.GUIDE -> FreeTvRoute.Guide
                            HomeDestination.PLAYLISTS -> FreeTvRoute.Playlists
                            HomeDestination.CHANNELS -> FreeTvRoute.Channels()
                            HomeDestination.GROUPS -> FreeTvRoute.Groups
                            HomeDestination.NUMBERS -> FreeTvRoute.ChannelNumbers
                            HomeDestination.SETTINGS -> FreeTvRoute.Settings
                            HomeDestination.ADD_PLAYLIST -> FreeTvRoute.AddPlaylist()
                        },
                    )
                },
            )
        }
        composable<FreeTvRoute.Player> {
            PlayerScreen(onExit = { navController.popBackStack() })
        }
        composable<FreeTvRoute.Playlists> {
            PlaylistManagerScreen(
                onAddUrl = { navController.navigate(FreeTvRoute.AddPlaylist(mode = "URL")) },
                onImportFile = { navController.navigate(FreeTvRoute.AddPlaylist(mode = "FILE")) },
                onEdit = { navController.navigate(FreeTvRoute.AddPlaylist(playlistId = it)) },
                onViewChannels = { navController.navigate(FreeTvRoute.Channels(it)) },
            )
        }
        composable<FreeTvRoute.AddPlaylist> {
            AddPlaylistScreen(
                onImported = { id ->
                    navController.popBackStack()
                    navController.navigate(FreeTvRoute.Channels(id))
                },
                onDone = { navController.popBackStack() },
            )
        }
        composable<FreeTvRoute.Channels> {
            ChannelManagerScreen(
                onEdit = { navController.navigate(FreeTvRoute.ChannelEdit(it)) },
                onNumbers = { navController.navigate(FreeTvRoute.ChannelNumbers) },
            )
        }
        composable<FreeTvRoute.ChannelEdit> {
            ChannelEditScreen(
                onPlay = { play(it) },
                onBack = { navController.popBackStack() },
            )
        }
        composable<FreeTvRoute.ChannelNumbers> { ChannelNumbersScreen() }
        composable<FreeTvRoute.Groups> {
            GroupManagerScreen(onOpenGroup = { navController.navigate(FreeTvRoute.GroupDetail(it)) })
        }
        composable<FreeTvRoute.GroupDetail> {
            GroupDetailScreen(onPlay = { channelId, groupId -> play(channelId, "GROUP", groupId) })
        }
        composable<FreeTvRoute.Favorites> {
            FavoritesScreen(onPlay = { play(it, "FAVORITES") })
        }
        composable<FreeTvRoute.Guide> {
            EpgScreen(onPlay = { play(it) })
        }
        composable<FreeTvRoute.Settings> { SettingsScreen() }
    }
}
}
