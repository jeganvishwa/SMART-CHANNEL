package com.example.freetv.presentation.navigation

import kotlinx.serialization.Serializable

/**
 * Type-safe navigation destinations. New screens (player, playlist manager,
 * channel manager, EPG, settings…) are added here in later phases.
 */
sealed interface FreeTvRoute {
    @Serializable
    data object Home : FreeTvRoute
}
