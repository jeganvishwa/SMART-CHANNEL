package com.example.freetv.domain.model

enum class PlaylistSourceType { URL, FILE }

enum class PlaylistStatus { NEW, LOADING, OK, ERROR }

data class Playlist(
    val id: Long,
    val name: String,
    val url: String?,
    val sourceType: PlaylistSourceType,
    val importedAt: Long,
    val lastRefreshAt: Long?,
    val enabled: Boolean,
    val channelCount: Int,
    val status: PlaylistStatus,
    val lastError: String?,
    val autoRefresh: Boolean,
    val headerEpgUrl: String?,
)

/** Lightweight channel used in lists, grids and the player zapping list. */
data class ChannelItem(
    val id: Long,
    val name: String,
    val number: Int?,
    val logoUrl: String?,
    val tvgId: String?,
    val tvgName: String?,
    val language: String?,
    val originalGroup: String?,
    val playlistId: Long?,
    val hidden: Boolean,
    val inSource: Boolean,
    val isFavorite: Boolean,
)

data class ChannelDetail(
    val id: Long,
    val playlistId: Long?,
    val name: String,
    val tvgId: String?,
    val tvgName: String?,
    val logoUrl: String?,
    val originalGroup: String?,
    val language: String?,
    val number: Int?,
    val hidden: Boolean,
    val inSource: Boolean,
)

enum class StreamStatus { UNKNOWN, OK, FAILED }

data class ChannelStream(
    val id: Long,
    val channelId: Long,
    val url: String,
    val label: String?,
    val priority: Int,
    val fromPlaylist: Boolean,
    val userAgent: String?,
    val referrer: String?,
    val lastStatus: StreamStatus,
)

enum class GroupType { LANGUAGE, CATEGORY, CUSTOM }

data class ChannelGroup(
    val id: Long,
    val name: String,
    val type: GroupType,
    val position: Int,
    val showOnHome: Boolean,
    val hidden: Boolean,
    val channelCount: Int,
)

data class EpgProgram(
    val epgChannelId: String,
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val description: String?,
    val category: String?,
) {
    fun progressAt(now: Long): Float =
        if (endMs <= startMs) 0f else ((now - startMs).toFloat() / (endMs - startMs)).coerceIn(0f, 1f)
}

data class NowNext(val now: EpgProgram?, val next: EpgProgram?)

/** Where the player's channel up/down list comes from. */
enum class PlaybackContextType { ALL, GROUP, FAVORITES }

enum class BufferProfile(val label: String, val minBufferMs: Int, val maxBufferMs: Int, val playbackMs: Int, val rebufferMs: Int) {
    LOW_LATENCY("Fast start", 4_000, 20_000, 1_000, 2_000),
    BALANCED("Balanced", 15_000, 50_000, 2_000, 4_000),
    STABLE("Stable (slow network)", 30_000, 90_000, 4_000, 8_000),
}

enum class StartupLanguageMode { LAST_SELECTED, DEFAULT_LANGUAGE }

enum class StartScreen { HOME, FAVORITES, LAST_CHANNEL }

data class AppSettings(
    val lastSelectedTab: String? = null,
    val defaultLanguageGroupId: Long? = null,
    val startupLanguageMode: StartupLanguageMode = StartupLanguageMode.LAST_SELECTED,
    val autoRefreshEnabled: Boolean = true,
    val refreshIntervalHours: Int = 24,
    val bufferProfile: BufferProfile = BufferProfile.BALANCED,
    val numericInputTimeoutMs: Long = 2_000,
    val channelNumberDigits: Int = 3,
    val autoPlayLastChannel: Boolean = false,
    val lastWatchedChannelId: Long? = null,
    val startScreen: StartScreen = StartScreen.HOME,
    val pureBlackTheme: Boolean = false,
    val epgUrl: String? = null,
    val epgAutoRefresh: Boolean = true,
    val epgOffsetMinutes: Int = 0,
    val maxRetriesPerStream: Int = 2,
    val autoNumberNewChannels: Boolean = true,
)

/** Formats a channel number using the configured number of digits (e.g. 5 → "005"). */
fun formatChannelNumber(number: Int?, digits: Int): String =
    number?.toString()?.padStart(digits.coerceIn(1, 6), '0') ?: "—"
