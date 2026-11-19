package com.example.freetv.domain.repository

import android.net.Uri
import com.example.freetv.domain.model.AppSettings
import com.example.freetv.domain.model.BufferProfile
import com.example.freetv.domain.model.ChannelDetail
import com.example.freetv.domain.model.ChannelGroup
import com.example.freetv.domain.model.ChannelItem
import com.example.freetv.domain.model.ChannelStream
import com.example.freetv.domain.model.EpgImportResult
import com.example.freetv.domain.model.EpgProgram
import com.example.freetv.domain.model.GroupResult
import com.example.freetv.domain.model.GroupType
import com.example.freetv.domain.model.ImportProgress
import com.example.freetv.domain.model.ImportResult
import com.example.freetv.domain.model.NowNext
import com.example.freetv.domain.model.NumberAssignResult
import com.example.freetv.domain.model.Playlist
import com.example.freetv.domain.model.StartScreen
import com.example.freetv.domain.model.StartupLanguageMode
import com.example.freetv.domain.model.StreamStatus
import com.example.freetv.domain.model.StreamTestResult
import kotlinx.coroutines.flow.Flow
import java.io.File

interface PlaylistRepository {
    fun observePlaylists(): Flow<List<Playlist>>
    fun observePlaylistCount(): Flow<Int>
    suspend fun getPlaylist(id: Long): Playlist?
    suspend fun findByUrl(url: String): Playlist?

    suspend fun importFromUrl(
        name: String,
        url: String,
        autoRefresh: Boolean,
        allowDuplicate: Boolean = false,
        onProgress: (ImportProgress) -> Unit = {},
    ): ImportResult

    suspend fun importFromUri(name: String, uri: Uri, onProgress: (ImportProgress) -> Unit = {}): ImportResult
    suspend fun importFromFile(name: String, file: File, onProgress: (ImportProgress) -> Unit = {}): ImportResult

    suspend fun refresh(playlistId: Long, onProgress: (ImportProgress) -> Unit = {}): ImportResult
    /** Refreshes enabled playlists with auto-refresh on that are older than [maxAgeMs]. */
    suspend fun refreshDue(maxAgeMs: Long): Int

    suspend fun updatePlaylist(id: Long, name: String, url: String?, autoRefresh: Boolean): Result<Unit>
    suspend fun setEnabled(id: Long, enabled: Boolean)
    suspend fun delete(id: Long)
}

interface ChannelRepository {
    fun observeAllVisible(): Flow<List<ChannelItem>>
    fun observeVisibleInGroup(groupId: Long): Flow<List<ChannelItem>>
    fun observeFavorites(): Flow<List<ChannelItem>>
    fun observeRecentlyWatched(limit: Int = 50): Flow<List<ChannelItem>>
    fun observeForManager(playlistId: Long?, query: String): Flow<List<ChannelItem>>
    fun observeChannelCount(): Flow<Int>
    fun observeMaxNumber(): Flow<Int?>

    suspend fun getAllVisible(): List<ChannelItem>
    suspend fun getVisibleInGroup(groupId: Long): List<ChannelItem>
    suspend fun getFavorites(): List<ChannelItem>

    suspend fun getChannel(id: Long): ChannelDetail?
    fun observeChannel(id: Long): Flow<ChannelDetail?>
    suspend fun getChannelByNumber(number: Int): ChannelDetail?

    suspend fun createChannel(name: String, streamUrl: String, logoUrl: String?): Long
    suspend fun updateChannel(id: Long, name: String, logoUrl: String?, language: String?)
    suspend fun setHidden(id: Long, hidden: Boolean)
    suspend fun deleteChannel(id: Long)
    suspend fun markWatched(id: Long)

    // Numbers
    suspend fun assignNumber(channelId: Long, number: Int?): NumberAssignResult
    /** Gives [number] to [channelId]; the channel currently holding it gets [channelId]'s old number. */
    suspend fun swapNumber(channelId: Long, number: Int)
    suspend fun autoNumberAll(startAt: Int = 1)
    suspend fun moveNumber(channelId: Long, up: Boolean)

    // Streams
    suspend fun getStreams(channelId: Long): List<ChannelStream>
    fun observeStreams(channelId: Long): Flow<List<ChannelStream>>
    suspend fun addStream(channelId: Long, url: String, label: String?): Result<Long>
    suspend fun updateStream(streamId: Long, url: String, label: String?): Result<Unit>
    suspend fun deleteStream(streamId: Long)
    suspend fun makePrimary(streamId: Long)
    suspend fun moveStream(streamId: Long, up: Boolean)
    suspend fun setStreamStatus(streamId: Long, status: StreamStatus)
    suspend fun testStream(streamId: Long): StreamTestResult

    // Favourites
    fun observeIsFavorite(channelId: Long): Flow<Boolean>
    suspend fun toggleFavorite(channelId: Long): Boolean
    suspend fun setFavorite(channelId: Long, favorite: Boolean)
}

interface GroupRepository {
    fun observeGroups(): Flow<List<ChannelGroup>>
    fun observeGroup(id: Long): Flow<ChannelGroup?>
    suspend fun getGroups(): List<ChannelGroup>
    fun observeAllChannelsInGroup(groupId: Long): Flow<List<ChannelItem>>
    fun observeGroupIdsForChannel(channelId: Long): Flow<List<Long>>

    suspend fun createGroup(name: String, type: GroupType): GroupResult
    suspend fun renameGroup(id: Long, name: String): GroupResult
    suspend fun deleteGroup(id: Long)
    suspend fun moveGroup(id: Long, up: Boolean)
    suspend fun setHidden(id: Long, hidden: Boolean)
    suspend fun setShowOnHome(id: Long, show: Boolean)

    suspend fun addChannels(groupId: Long, channelIds: List<Long>)
    suspend fun removeChannel(groupId: Long, channelId: Long)
    suspend fun moveChannelToGroup(channelId: Long, fromGroupId: Long, toGroupId: Long)
    suspend fun moveChannelInGroup(groupId: Long, channelId: Long, up: Boolean)
    suspend fun setMembership(channelId: Long, groupId: Long, member: Boolean)
}

interface EpgRepository {
    suspend fun refresh(url: String, offsetMinutes: Int, onProgress: (String) -> Unit = {}): EpgImportResult
    suspend fun clear()
    /** Current programmes keyed by lower-case EPG channel id; re-emits every minute. */
    fun observeCurrentPrograms(): Flow<Map<String, EpgProgram>>
    suspend fun resolveEpgId(tvgId: String?, tvgName: String?, name: String): String?
    suspend fun getNowNext(tvgId: String?, tvgName: String?, name: String): NowNext
    suspend fun getSchedule(tvgId: String?, tvgName: String?, name: String, from: Long, to: Long): List<EpgProgram>
    fun observeLastRefresh(): Flow<Long?>
    fun observeEpgVersion(): Flow<Long>
}

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun current(): AppSettings
    suspend fun setLastSelectedTab(tab: String)
    suspend fun setDefaultLanguageGroupId(groupId: Long?)
    suspend fun setStartupLanguageMode(mode: StartupLanguageMode)
    suspend fun setAutoRefresh(enabled: Boolean)
    suspend fun setRefreshIntervalHours(hours: Int)
    suspend fun setBufferProfile(profile: BufferProfile)
    suspend fun setNumericInputTimeoutMs(ms: Long)
    suspend fun setChannelNumberDigits(digits: Int)
    suspend fun setAutoPlayLastChannel(enabled: Boolean)
    suspend fun setLastWatchedChannelId(id: Long?)
    suspend fun setStartScreen(screen: StartScreen)
    suspend fun setPureBlackTheme(enabled: Boolean)
    suspend fun setEpgUrl(url: String?)
    suspend fun setEpgAutoRefresh(enabled: Boolean)
    suspend fun setEpgOffsetMinutes(minutes: Int)
    suspend fun setMaxRetriesPerStream(retries: Int)
    suspend fun setAutoNumberNewChannels(enabled: Boolean)
}

interface BackupRepository {
    suspend fun backupTo(uri: Uri): Result<Unit>
    suspend fun restoreFrom(uri: Uri): Result<Unit>
    suspend fun quickBackup(): Result<File>
    suspend fun quickRestore(): Result<Unit>
    fun quickBackupFile(): File
    suspend fun clearCache(): Long
}
