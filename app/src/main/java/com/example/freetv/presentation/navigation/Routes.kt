package com.example.freetv.presentation.navigation

import kotlinx.serialization.Serializable

/** Type-safe navigation destinations. */
sealed interface FreeTvRoute {
    @Serializable
    data object Home : FreeTvRoute

    /** Full-screen player. [contextType] is a PlaybackContextType name; [contextId] is the group id when relevant. */
    @Serializable
    data class Player(val channelId: Long, val contextType: String = "ALL", val contextId: Long = -1) : FreeTvRoute

    @Serializable
    data object Playlists : FreeTvRoute

    /** Add (playlistId = -1) or edit a playlist. [mode] = "URL" or "FILE". */
    @Serializable
    data class AddPlaylist(val playlistId: Long = -1, val mode: String = "URL") : FreeTvRoute

    /** Channel manager; with a playlist id it is the "Detected channels" screen of that playlist. */
    @Serializable
    data class Channels(val playlistId: Long = -1) : FreeTvRoute

    @Serializable
    data class ChannelEdit(val channelId: Long) : FreeTvRoute

    @Serializable
    data object ChannelNumbers : FreeTvRoute

    @Serializable
    data object Groups : FreeTvRoute

    @Serializable
    data class GroupDetail(val groupId: Long) : FreeTvRoute

    @Serializable
    data object Favorites : FreeTvRoute

    @Serializable
    data object Guide : FreeTvRoute

    @Serializable
    data object Settings : FreeTvRoute
}
