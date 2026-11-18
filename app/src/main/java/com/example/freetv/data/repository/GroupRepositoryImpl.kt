package com.example.freetv.data.repository

import androidx.room.withTransaction
import com.example.freetv.data.database.FreeTvDatabase
import com.example.freetv.data.entity.ChannelGroupEntity
import com.example.freetv.data.entity.ChannelGroupMemberEntity
import com.example.freetv.domain.model.ChannelGroup
import com.example.freetv.domain.model.ChannelItem
import com.example.freetv.domain.model.GroupResult
import com.example.freetv.domain.model.GroupType
import com.example.freetv.domain.repository.GroupRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GroupRepositoryImpl @Inject constructor(
    private val db: FreeTvDatabase,
) : GroupRepository {

    private val groupDao = db.groupDao()
    private val channelDao = db.channelDao()

    override fun observeGroups(): Flow<List<ChannelGroup>> =
        groupDao.observeAllWithCount().map { list -> list.map { it.toDomain() } }

    override fun observeGroup(id: Long): Flow<ChannelGroup?> =
        observeGroups().map { list -> list.firstOrNull { it.id == id } }

    override suspend fun getGroups(): List<ChannelGroup> {
        // Snapshot without counts is enough for pickers; counts come from the Flow.
        return groupDao.getAll().map {
            ChannelGroup(
                id = it.id,
                name = it.name,
                type = GroupType.entries.firstOrNull { t -> t.name == it.type } ?: GroupType.CUSTOM,
                position = it.position,
                showOnHome = it.showOnHome,
                hidden = it.hidden,
                channelCount = 0,
            )
        }
    }

    override fun observeAllChannelsInGroup(groupId: Long): Flow<List<ChannelItem>> =
        channelDao.observeAllInGroup(groupId).map { it.toDomain() }

    override fun observeGroupIdsForChannel(channelId: Long): Flow<List<Long>> =
        groupDao.observeGroupIdsForChannel(channelId)

    override suspend fun createGroup(name: String, type: GroupType): GroupResult = db.withTransaction {
        val clean = name.trim().take(60)
        if (clean.isEmpty()) return@withTransaction GroupResult.Error("Enter a group name")
        if (groupDao.getByName(clean) != null) return@withTransaction GroupResult.Error("A group named \"$clean\" already exists")
        val id = groupDao.insert(
            ChannelGroupEntity(name = clean, type = type.name, position = groupDao.maxPosition() + 1),
        )
        GroupResult.Success(id)
    }

    override suspend fun renameGroup(id: Long, name: String): GroupResult = db.withTransaction {
        val clean = name.trim().take(60)
        if (clean.isEmpty()) return@withTransaction GroupResult.Error("Enter a group name")
        val existing = groupDao.getByName(clean)
        if (existing != null && existing.id != id) {
            return@withTransaction GroupResult.Error("A group named \"$clean\" already exists")
        }
        val group = groupDao.getById(id) ?: return@withTransaction GroupResult.Error("Group not found")
        groupDao.update(group.copy(name = clean))
        GroupResult.Success(id)
    }

    override suspend fun deleteGroup(id: Long) = groupDao.delete(id)

    override suspend fun moveGroup(id: Long, up: Boolean) = db.withTransaction {
        val groups = groupDao.getAll().toMutableList()
        val index = groups.indexOfFirst { it.id == id }
        val target = if (up) index - 1 else index + 1
        if (index < 0 || target !in groups.indices) return@withTransaction
        val tmp = groups[index]
        groups[index] = groups[target]
        groups[target] = tmp
        groups.forEachIndexed { i, g -> if (g.position != i) groupDao.update(g.copy(position = i)) }
    }

    override suspend fun setHidden(id: Long, hidden: Boolean) {
        groupDao.getById(id)?.let { groupDao.update(it.copy(hidden = hidden)) }
    }

    override suspend fun setShowOnHome(id: Long, show: Boolean) {
        groupDao.getById(id)?.let { groupDao.update(it.copy(showOnHome = show)) }
    }

    override suspend fun addChannels(groupId: Long, channelIds: List<Long>) = db.withTransaction {
        var position = groupDao.maxMemberPosition(groupId)
        for (channelId in channelIds) {
            val existing = groupDao.getMember(channelId, groupId)
            if (existing == null) {
                groupDao.upsertMember(ChannelGroupMemberEntity(channelId, groupId, ++position, "USER"))
            } else if (existing.excluded || existing.assignedBy != "USER") {
                groupDao.upsertMember(existing.copy(excluded = false, assignedBy = "USER"))
            }
        }
    }

    override suspend fun removeChannel(groupId: Long, channelId: Long) = groupDao.excludeMember(channelId, groupId)

    override suspend fun moveChannelToGroup(channelId: Long, fromGroupId: Long, toGroupId: Long) = db.withTransaction {
        if (fromGroupId == toGroupId) return@withTransaction
        groupDao.excludeMember(channelId, fromGroupId)
        addChannels(toGroupId, listOf(channelId))
    }

    override suspend fun moveChannelInGroup(groupId: Long, channelId: Long, up: Boolean) = db.withTransaction {
        val ids = groupDao.memberIdsInOrder(groupId).toMutableList()
        val index = ids.indexOf(channelId)
        val target = if (up) index - 1 else index + 1
        if (index < 0 || target !in ids.indices) return@withTransaction
        ids[index] = ids[target].also { ids[target] = ids[index] }
        ids.forEachIndexed { i, id -> groupDao.setMemberPosition(id, groupId, i) }
    }

    override suspend fun setMembership(channelId: Long, groupId: Long, member: Boolean) {
        if (member) addChannels(groupId, listOf(channelId)) else removeChannel(groupId, channelId)
    }
}
