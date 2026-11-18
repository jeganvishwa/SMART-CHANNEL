package com.example.freetv.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.freetv.data.entity.AppSettingsEntity
import com.example.freetv.data.entity.ChannelGroupEntity
import com.example.freetv.data.entity.ChannelGroupMemberEntity
import com.example.freetv.data.entity.EpgChannelEntity
import com.example.freetv.data.entity.EpgProgramEntity
import com.example.freetv.data.entity.FavoriteEntity
import com.example.freetv.data.entity.GroupWithCountRow
import com.example.freetv.data.entity.PlaylistEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY id")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists ORDER BY id")
    suspend fun getAll(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getById(id: Long): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE url = :url LIMIT 1")
    suspend fun getByUrl(url: String): PlaylistEntity?

    @Query("SELECT COUNT(*) FROM playlists")
    fun observeCount(): Flow<Int>

    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Update
    suspend fun update(playlist: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE playlists SET status = :status, lastError = :error WHERE id = :id")
    suspend fun setStatus(id: Long, status: String, error: String?)

    @Query("UPDATE playlists SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)
}

@Dao
interface GroupDao {
    @Query(
        "SELECT g.id, g.name, g.type, g.position, g.showOnHome, g.hidden, g.keywords, " +
            "(SELECT COUNT(*) FROM channel_group_members m WHERE m.groupId = g.id AND m.excluded = 0) AS channelCount " +
            "FROM channel_groups g ORDER BY g.position, g.id",
    )
    fun observeAllWithCount(): Flow<List<GroupWithCountRow>>

    @Query("SELECT * FROM channel_groups ORDER BY position, id")
    suspend fun getAll(): List<ChannelGroupEntity>

    @Query("SELECT * FROM channel_groups WHERE id = :id")
    suspend fun getById(id: Long): ChannelGroupEntity?

    @Query("SELECT * FROM channel_groups WHERE id = :id")
    fun observeById(id: Long): Flow<ChannelGroupEntity?>

    @Query("SELECT * FROM channel_groups WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun getByName(name: String): ChannelGroupEntity?

    @Query("SELECT COALESCE(MAX(position), -1) FROM channel_groups")
    suspend fun maxPosition(): Int

    @Insert
    suspend fun insert(group: ChannelGroupEntity): Long

    @Update
    suspend fun update(group: ChannelGroupEntity)

    @Query("DELETE FROM channel_groups WHERE id = :id")
    suspend fun delete(id: Long)

    // ---------- Membership ----------

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMemberIgnore(member: ChannelGroupMemberEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMembersIgnore(members: List<ChannelGroupMemberEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMember(member: ChannelGroupMemberEntity)

    @Query("SELECT * FROM channel_group_members WHERE channelId = :channelId AND groupId = :groupId")
    suspend fun getMember(channelId: Long, groupId: Long): ChannelGroupMemberEntity?

    @Query("SELECT * FROM channel_group_members WHERE channelId = :channelId")
    suspend fun getMembershipsForChannel(channelId: Long): List<ChannelGroupMemberEntity>

    @Query("SELECT groupId FROM channel_group_members WHERE channelId = :channelId AND excluded = 0")
    fun observeGroupIdsForChannel(channelId: Long): Flow<List<Long>>

    @Query("SELECT COALESCE(MAX(position), -1) FROM channel_group_members WHERE groupId = :groupId")
    suspend fun maxMemberPosition(groupId: Long): Int

    @Query("UPDATE channel_group_members SET excluded = 1 WHERE channelId = :channelId AND groupId = :groupId")
    suspend fun excludeMember(channelId: Long, groupId: Long)

    @Query("UPDATE channel_group_members SET position = :position WHERE channelId = :channelId AND groupId = :groupId")
    suspend fun setMemberPosition(channelId: Long, groupId: Long, position: Int)

    @Query("SELECT channelId FROM channel_group_members WHERE groupId = :groupId AND excluded = 0 ORDER BY position")
    suspend fun memberIdsInOrder(groupId: Long): List<Long>

    @Query("SELECT COUNT(*) FROM channel_group_members WHERE channelId = :channelId AND assignedBy = 'USER' AND excluded = 0")
    suspend fun countUserMemberships(channelId: Long): Int
}

@Dao
interface FavoriteDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE channelId = :channelId")
    suspend fun delete(channelId: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE channelId = :channelId)")
    suspend fun isFavorite(channelId: Long): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE channelId = :channelId)")
    fun observeIsFavorite(channelId: Long): Flow<Boolean>

    @Query("SELECT COALESCE(MAX(position), -1) FROM favorites")
    suspend fun maxPosition(): Int
}

@Dao
interface AppSettingsDao {
    @Query("SELECT value FROM app_settings WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Query("SELECT value FROM app_settings WHERE `key` = :key")
    fun observe(key: String): Flow<String?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: AppSettingsEntity)
}

@Dao
interface EpgDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPrograms(programs: List<EpgProgramEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannels(channels: List<EpgChannelEntity>)

    @Query("DELETE FROM epg_programs")
    suspend fun clearPrograms()

    @Query("DELETE FROM epg_channels")
    suspend fun clearChannels()

    @Query("DELETE FROM epg_programs WHERE endMs < :before")
    suspend fun deleteEndedBefore(before: Long)

    @Query("SELECT * FROM epg_channels")
    suspend fun getChannels(): List<EpgChannelEntity>

    @Query("SELECT * FROM epg_programs WHERE startMs <= :now AND endMs > :now")
    suspend fun getCurrentPrograms(now: Long): List<EpgProgramEntity>

    @Query("SELECT * FROM epg_programs WHERE epgChannelId = :epgId AND endMs > :now ORDER BY startMs LIMIT 2")
    suspend fun getNowNext(epgId: String, now: Long): List<EpgProgramEntity>

    @Query(
        "SELECT * FROM epg_programs WHERE epgChannelId = :epgId AND endMs > :from AND startMs < :to " +
            "ORDER BY startMs",
    )
    suspend fun getSchedule(epgId: String, from: Long, to: Long): List<EpgProgramEntity>

    @Query("SELECT COUNT(*) FROM epg_programs")
    suspend fun countPrograms(): Int
}
