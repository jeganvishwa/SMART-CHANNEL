package com.example.freetv.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** An imported M3U source (remote URL or a local file copied into app storage). */
@Entity(
    tableName = "playlists",
    indices = [Index(value = ["url"])],
)
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Remote URL for URL playlists, null for file imports. */
    val url: String?,
    /** "URL" or "FILE". */
    val sourceType: String,
    /** Private copy of an imported file (absolute path inside app storage). */
    val localFilePath: String? = null,
    val importedAt: Long,
    val lastRefreshAt: Long? = null,
    val enabled: Boolean = true,
    val channelCount: Int = 0,
    /** NEW, LOADING, OK, ERROR. */
    val status: String = "NEW",
    val lastError: String? = null,
    val autoRefresh: Boolean = true,
    /** EPG URL announced in the playlist header (url-tvg / x-tvg-url). */
    val headerEpgUrl: String? = null,
    /** ISO 639-2 language code of a language playlist (e.g. "tam"); null for normal playlists. Added in DB v2. */
    val languageCode: String? = null,
    /** Logical folder shown in the UI, e.g. "Languages/Tamil". Added in DB v2. */
    val folder: String? = null,
)

/**
 * A channel. `(playlistId, stableKey)` identifies a playlist channel across
 * refreshes, so user data (number, groups, favourites, extra streams) survives.
 * `number` is globally unique (SQLite allows many NULLs in a unique index).
 */
@Entity(
    tableName = "channels",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["playlistId", "stableKey"], unique = true),
        Index(value = ["number"], unique = true),
        Index(value = ["tvgId"]),
        Index(value = ["name"]),
    ],
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Null for channels the user created manually. */
    val playlistId: Long?,
    val stableKey: String,
    val name: String,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val logoUrl: String? = null,
    val originalGroup: String? = null,
    val language: String? = null,
    val number: Int? = null,
    val hidden: Boolean = false,
    val nameEdited: Boolean = false,
    val logoEdited: Boolean = false,
    val languageEdited: Boolean = false,
    val numberEdited: Boolean = false,
    /** False when the channel disappeared from its playlist but was kept because the user customised it. */
    val inSource: Boolean = true,
    val createdAt: Long,
    val lastWatchedAt: Long? = null,
)

/** User or default group (language, category or custom). */
@Entity(
    tableName = "channel_groups",
    indices = [Index(value = ["name"], unique = true)],
)
data class ChannelGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** LANGUAGE, CATEGORY or CUSTOM. */
    val type: String,
    val position: Int,
    val showOnHome: Boolean = true,
    val hidden: Boolean = false,
    /** Extra comma-separated keywords used for automatic assignment (e.g. "தமிழ்,tam"). */
    val keywords: String? = null,
)

/**
 * Channel ↔ group membership (a channel may be in several groups, e.g. Tamil + News).
 * `excluded` remembers that the user removed an automatic assignment, so a
 * playlist refresh does not put the channel back.
 */
@Entity(
    tableName = "channel_group_members",
    primaryKeys = ["channelId", "groupId"],
    foreignKeys = [
        ForeignKey(
            entity = ChannelEntity::class,
            parentColumns = ["id"],
            childColumns = ["channelId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ChannelGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["groupId"])],
)
data class ChannelGroupMemberEntity(
    val channelId: Long,
    val groupId: Long,
    val position: Int,
    /** AUTO or USER. */
    val assignedBy: String,
    val excluded: Boolean = false,
)

/** One playable URL of a channel; lower priority plays first. */
@Entity(
    tableName = "channel_streams",
    foreignKeys = [
        ForeignKey(
            entity = ChannelEntity::class,
            parentColumns = ["id"],
            childColumns = ["channelId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["channelId", "url"], unique = true)],
)
data class ChannelStreamEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channelId: Long,
    val url: String,
    val label: String? = null,
    val priority: Int,
    val fromPlaylist: Boolean,
    val userAgent: String? = null,
    val referrer: String? = null,
    /** UNKNOWN, OK, FAILED. */
    val lastStatus: String = "UNKNOWN",
    val lastCheckedAt: Long? = null,
)

@Entity(
    tableName = "favorites",
    foreignKeys = [
        ForeignKey(
            entity = ChannelEntity::class,
            parentColumns = ["id"],
            childColumns = ["channelId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class FavoriteEntity(
    @PrimaryKey val channelId: Long,
    val addedAt: Long,
    val position: Int,
)

/** Small key/value store for database-bound state (EPG import stats, etc.). */
@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey val key: String,
    val value: String?,
)

/** XMLTV programme. `epgChannelId` is stored lower-case for matching. */
@Entity(
    tableName = "epg_programs",
    indices = [Index(value = ["epgChannelId", "startMs"]), Index(value = ["endMs"])],
)
data class EpgProgramEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epgChannelId: String,
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val description: String? = null,
    val category: String? = null,
)

/** XMLTV <channel> entry, used to match playlist channels by display name. */
@Entity(tableName = "epg_channels")
data class EpgChannelEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "displayName") val displayName: String,
    val normalizedName: String,
)

/** Row used by channel lists (lightweight projection). */
data class ChannelListRow(
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

data class GroupWithCountRow(
    val id: Long,
    val name: String,
    val type: String,
    val position: Int,
    val showOnHome: Boolean,
    val hidden: Boolean,
    val keywords: String?,
    val channelCount: Int,
)
