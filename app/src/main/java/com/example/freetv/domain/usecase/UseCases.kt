package com.example.freetv.domain.usecase

import com.example.freetv.domain.model.ChannelDetail
import com.example.freetv.domain.model.ChannelItem
import com.example.freetv.domain.model.NumberAssignResult
import com.example.freetv.domain.model.PlaybackContextType
import com.example.freetv.domain.repository.ChannelRepository
import com.example.freetv.domain.repository.SettingsRepository
import com.example.freetv.preferences.LastWatchedChannel
import com.example.freetv.preferences.StartupPreferences
import javax.inject.Inject

/** Validates and assigns a channel number, reporting duplicates instead of overwriting them. */
class AssignChannelNumberUseCase @Inject constructor(
    private val channels: ChannelRepository,
) {
    suspend operator fun invoke(channelId: Long, input: String): NumberAssignResult {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return channels.assignNumber(channelId, null)
        val number = trimmed.toIntOrNull() ?: return NumberAssignResult.Invalid("Enter digits only")
        return channels.assignNumber(channelId, number)
    }
}

/** Resolves a typed remote-control number to a channel. */
class ResolveChannelNumberUseCase @Inject constructor(
    private val channels: ChannelRepository,
) {
    suspend operator fun invoke(number: Int): ChannelDetail? =
        channels.getChannelByNumber(number)?.takeIf { !it.hidden }
}

/** Loads the ordered channel list used for channel up / down in the player. */
class GetPlaybackListUseCase @Inject constructor(
    private val channels: ChannelRepository,
) {
    suspend operator fun invoke(type: PlaybackContextType, groupId: Long): List<ChannelItem> = when (type) {
        PlaybackContextType.GROUP -> channels.getVisibleInGroup(groupId)
        PlaybackContextType.FAVORITES -> channels.getFavorites()
        PlaybackContextType.ALL -> channels.getAllVisible()
    }.ifEmpty { channels.getAllVisible() }
}

/**
 * Records a channel as watched: Room keeps the "recently watched" time and
 * DataStore keeps the full last-watched record used to restore it on start-up.
 * Called once per channel change (never per frame).
 */
class RememberWatchedChannelUseCase @Inject constructor(
    private val channels: ChannelRepository,
    private val settings: SettingsRepository,
    private val startupPrefs: StartupPreferences,
) {
    suspend operator fun invoke(channel: ChannelItem, contextType: PlaybackContextType, contextId: Long) {
        val now = System.currentTimeMillis()
        channels.markWatched(channel.id)
        settings.setLastWatchedChannelId(channel.id)
        startupPrefs.saveLastWatched(
            LastWatchedChannel(
                channelId = channel.id,
                channelName = channel.name,
                playlistId = channel.playlistId,
                language = channel.language,
                groupId = if (contextType == PlaybackContextType.GROUP) contextId else -1,
                contextType = contextType.name,
                watchedAt = now,
                streamIndex = -1,
            ),
        )
    }

    /** Remembers which source worked, so start-up tries it first. */
    suspend fun rememberWorkingStream(channelId: Long, streamIndex: Int) =
        startupPrefs.saveLastStreamIndex(channelId, streamIndex)
}
