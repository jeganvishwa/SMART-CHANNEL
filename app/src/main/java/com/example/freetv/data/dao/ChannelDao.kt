package com.example.freetv.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.freetv.data.entity.ChannelEntity
import com.example.freetv.data.entity.ChannelListRow
import com.example.freetv.data.entity.ChannelStreamEntity
import kotlinx.coroutines.flow.Flow

private const val ROW_COLUMNS =
    "c.id, c.name, c.number, c.logoUrl, c.tvgId, c.tvgName, c.language, c.originalGroup, " +
        "c.playlistId, c.hidden, c.inSource, " +
        "EXISTS(SELECT 1 FROM favorites f WHERE f.channelId = c.id) AS isFavorite"

/** Channel is playable: not hidden and its playlist (if any) is enabled. */
private const val VISIBLE =
    "c.hidden = 0 AND (c.playlistId IS NULL OR c.playlistId IN (SELECT id FROM playlists WHERE enabled = 1))"

private const val NUMBER_ORDER =
    "CASE WHEN c.number IS NULL THEN 1 ELSE 0 END, c.number, c.name COLLATE NOCASE"

@Dao
interface ChannelDao {

    // ---------- Lists ----------

    @Query("SELECT $ROW_COLUMNS FROM channels c WHERE $VISIBLE ORDER BY $NUMBER_ORDER")
    fun observeAllVisible(): Flow<List<ChannelListRow>>

    @Query("SELECT $ROW_COLUMNS FROM channels c WHERE $VISIBLE ORDER BY $NUMBER_ORDER")
    suspend fun getAllVisible(): List<ChannelListRow>

    @Query(
        "SELECT $ROW_COLUMNS FROM channels c " +
            "JOIN channel_group_members m ON m.channelId = c.id " +
            "WHERE m.groupId = :groupId AND m.excluded = 0 AND $VISIBLE " +
            "ORDER BY $NUMBER_ORDER",
    )
    fun observeVisibleInGroup(groupId: Long): Flow<List<ChannelListRow>>

    @Query(
        "SELECT $ROW_COLUMNS FROM channels c " +
            "JOIN channel_group_members m ON m.channelId = c.id " +
            "WHERE m.groupId = :groupId AND m.excluded = 0 AND $VISIBLE " +
            "ORDER BY $NUMBER_ORDER",
    )
    suspend fun getVisibleInGroup(groupId: Long): List<ChannelListRow>

    @Query(
        "SELECT $ROW_COLUMNS FROM channels c " +
            "JOIN channel_group_members m ON m.channelId = c.id " +
            "WHERE m.groupId = :groupId AND m.excluded = 0 " +
            "ORDER BY m.position, $NUMBER_ORDER",
    )
    fun observeAllInGroup(groupId: Long): Flow<List<ChannelListRow>>

    @Query(
        "SELECT $ROW_COLUMNS FROM channels c JOIN favorites fav ON fav.channelId = c.id " +
            "WHERE $VISIBLE ORDER BY fav.position, fav.addedAt",
    )
    fun observeFavorites(): Flow<List<ChannelListRow>>

    @Query(
        "SELECT $ROW_COLUMNS FROM channels c JOIN favorites fav ON fav.channelId = c.id " +
            "WHERE $VISIBLE ORDER BY fav.position, fav.addedAt",
    )
    suspend fun getFavorites(): List<ChannelListRow>

    @Query(
        "SELECT $ROW_COLUMNS FROM channels c WHERE c.lastWatchedAt IS NOT NULL AND $VISIBLE " +
            "ORDER BY c.lastWatchedAt DESC LIMIT :limit",
    )
    fun observeRecentlyWatched(limit: Int): Flow<List<ChannelListRow>>

    /** Manager list: includes hidden channels; optional playlist filter (-1 = all) and name search. */
    @Query(
        "SELECT $ROW_COLUMNS FROM channels c " +
            "WHERE (:playlistId = -1 OR c.playlistId = :playlistId) " +
            "AND (:query = '' OR c.name LIKE '%' || :query || '%' OR CAST(c.number AS TEXT) = :query) " +
            "ORDER BY $NUMBER_ORDER",
    )
    fun observeForManager(playlistId: Long, query: String): Flow<List<ChannelListRow>>

    // ---------- Single channel ----------

    @Query("SELECT * FROM channels WHERE id = :id")
    suspend fun getById(id: Long): ChannelEntity?

    @Query("SELECT * FROM channels WHERE id = :id")
    fun observeById(id: Long): Flow<ChannelEntity?>

    @Query("SELECT * FROM channels WHERE number = :number LIMIT 1")
    suspend fun getByNumber(number: Int): ChannelEntity?

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId")
    suspend fun getByPlaylist(playlistId: Long): List<ChannelEntity>

    @Query("SELECT COUNT(*) FROM channels WHERE playlistId = :playlistId")
    suspend fun countByPlaylist(playlistId: Long): Int

    @Query("SELECT COUNT(*) FROM channels")
    fun observeCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(channel: ChannelEntity): Long

    @Update
    suspend fun update(channel: ChannelEntity)

    @Delete
    suspend fun delete(channel: ChannelEntity)

    @Query("DELETE FROM channels WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("UPDATE channels SET lastWatchedAt = :time WHERE id = :id")
    suspend fun markWatched(id: Long, time: Long)

    @Query("UPDATE channels SET hidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: Long, hidden: Boolean)

    // ---------- Numbers ----------

    @Query("SELECT MAX(number) FROM channels")
    suspend fun maxNumber(): Int?

    @Query("SELECT MAX(number) FROM channels")
    fun observeMaxNumber(): Flow<Int?>

    @Query("UPDATE channels SET number = :number, numberEdited = :edited WHERE id = :id")
    suspend fun setNumber(id: Long, number: Int?, edited: Boolean)

    @Query("SELECT * FROM channels WHERE number < :number ORDER BY number DESC LIMIT 1")
    suspend fun previousByNumber(number: Int): ChannelEntity?

    @Query("SELECT * FROM channels WHERE number > :number ORDER BY number ASC LIMIT 1")
    suspend fun nextByNumber(number: Int): ChannelEntity?

    @Query("UPDATE channels SET number = NULL")
    suspend fun clearAllNumbers()

    /** Channel ids in "natural" order used for automatic renumbering. */
    @Query(
        "SELECT c.id FROM channels c LEFT JOIN playlists p ON p.id = c.playlistId " +
            "ORDER BY CASE WHEN c.number IS NULL THEN 1 ELSE 0 END, c.number, p.id, c.id",
    )
    suspend fun idsInNumberingOrder(): List<Long>

    // ---------- Streams ----------

    @Query("SELECT * FROM channel_streams WHERE channelId = :channelId ORDER BY priority, id")
    suspend fun getStreams(channelId: Long): List<ChannelStreamEntity>

    @Query("SELECT * FROM channel_streams WHERE channelId = :channelId ORDER BY priority, id")
    fun observeStreams(channelId: Long): Flow<List<ChannelStreamEntity>>

    @Query("SELECT * FROM channel_streams WHERE id = :id")
    suspend fun getStream(id: Long): ChannelStreamEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStream(stream: ChannelStreamEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStreams(streams: List<ChannelStreamEntity>)

    @Update
    suspend fun updateStream(stream: ChannelStreamEntity)

    @Query("DELETE FROM channel_streams WHERE id = :id")
    suspend fun deleteStream(id: Long)

    @Query("DELETE FROM channel_streams WHERE id IN (:ids)")
    suspend fun deleteStreams(ids: List<Long>)

    @Query("UPDATE channel_streams SET priority = :priority WHERE id = :id")
    suspend fun setStreamPriority(id: Long, priority: Int)

    @Query("UPDATE channel_streams SET lastStatus = :status, lastCheckedAt = :time WHERE id = :id")
    suspend fun setStreamStatus(id: Long, status: String, time: Long)

    @Query("SELECT COUNT(*) FROM channel_streams WHERE channelId = :channelId AND fromPlaylist = 0")
    suspend fun countUserStreams(channelId: Long): Int
}
