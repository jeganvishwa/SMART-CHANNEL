package com.example.freetv.data.repository

import com.example.freetv.data.entity.ChannelEntity
import com.example.freetv.data.entity.ChannelListRow
import com.example.freetv.data.entity.ChannelStreamEntity
import com.example.freetv.data.entity.EpgProgramEntity
import com.example.freetv.data.entity.GroupWithCountRow
import com.example.freetv.data.entity.PlaylistEntity
import com.example.freetv.domain.model.ChannelDetail
import com.example.freetv.domain.model.ChannelGroup
import com.example.freetv.domain.model.ChannelItem
import com.example.freetv.domain.model.ChannelStream
import com.example.freetv.domain.model.EpgProgram
import com.example.freetv.domain.model.GroupType
import com.example.freetv.domain.model.Playlist
import com.example.freetv.domain.model.PlaylistSourceType
import com.example.freetv.domain.model.PlaylistStatus
import com.example.freetv.domain.model.StreamStatus

internal fun PlaylistEntity.toDomain() = Playlist(
    id = id,
    name = name,
    url = url,
    sourceType = if (sourceType == "FILE") PlaylistSourceType.FILE else PlaylistSourceType.URL,
    importedAt = importedAt,
    lastRefreshAt = lastRefreshAt,
    enabled = enabled,
    channelCount = channelCount,
    status = PlaylistStatus.entries.firstOrNull { it.name == status } ?: PlaylistStatus.NEW,
    lastError = lastError,
    autoRefresh = autoRefresh,
    headerEpgUrl = headerEpgUrl,
    languageCode = languageCode,
    folder = folder,
)

internal fun ChannelListRow.toDomain() = ChannelItem(
    id = id,
    name = name,
    number = number,
    logoUrl = logoUrl,
    tvgId = tvgId,
    tvgName = tvgName,
    language = language,
    originalGroup = originalGroup,
    playlistId = playlistId,
    hidden = hidden,
    inSource = inSource,
    isFavorite = isFavorite,
)

internal fun List<ChannelListRow>.toDomain() = map { it.toDomain() }

internal fun ChannelEntity.toDetail() = ChannelDetail(
    id = id,
    playlistId = playlistId,
    name = name,
    tvgId = tvgId,
    tvgName = tvgName,
    logoUrl = logoUrl,
    originalGroup = originalGroup,
    language = language,
    number = number,
    hidden = hidden,
    inSource = inSource,
)

internal fun ChannelStreamEntity.toDomain() = ChannelStream(
    id = id,
    channelId = channelId,
    url = url,
    label = label,
    priority = priority,
    fromPlaylist = fromPlaylist,
    userAgent = userAgent,
    referrer = referrer,
    lastStatus = StreamStatus.entries.firstOrNull { it.name == lastStatus } ?: StreamStatus.UNKNOWN,
)

internal fun GroupWithCountRow.toDomain() = ChannelGroup(
    id = id,
    name = name,
    type = GroupType.entries.firstOrNull { it.name == type } ?: GroupType.CUSTOM,
    position = position,
    showOnHome = showOnHome,
    hidden = hidden,
    channelCount = channelCount,
)

internal fun EpgProgramEntity.toDomain() = EpgProgram(
    epgChannelId = epgChannelId,
    startMs = startMs,
    endMs = endMs,
    title = title,
    description = description,
    category = category,
)
