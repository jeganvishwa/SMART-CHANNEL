package com.example.freetv.domain.usecase

import com.example.freetv.domain.model.ChannelDetail
import com.example.freetv.domain.model.ChannelItem
import com.example.freetv.domain.model.NumberAssignResult
import com.example.freetv.domain.model.PlaybackContextType
import com.example.freetv.domain.repository.ChannelRepository
import com.example.freetv.domain.repository.SettingsRepository
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

/** Records a channel as watched (used for "auto-play last channel"). */
class RememberWatchedChannelUseCase @Inject constructor(
    private val channels: ChannelRepository,
    private val settings: SettingsRepository,
) {
    suspend operator fun invoke(channelId: Long) {
        channels.markWatched(channelId)
        settings.setLastWatchedChannelId(channelId)
    }
}
