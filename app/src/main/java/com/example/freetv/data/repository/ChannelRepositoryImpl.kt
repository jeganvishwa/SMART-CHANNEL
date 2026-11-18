package com.example.freetv.data.repository

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.example.freetv.data.database.FreeTvDatabase
import com.example.freetv.data.entity.ChannelEntity
import com.example.freetv.data.entity.ChannelStreamEntity
import com.example.freetv.data.entity.FavoriteEntity
import com.example.freetv.data.network.StreamTester
import com.example.freetv.domain.model.ChannelDetail
import com.example.freetv.domain.model.ChannelItem
import com.example.freetv.domain.model.ChannelStream
import com.example.freetv.domain.model.NumberAssignResult
import com.example.freetv.domain.model.StreamStatus
import com.example.freetv.domain.model.StreamTestResult
import com.example.freetv.domain.repository.ChannelRepository
import com.example.freetv.utils.UrlSanitizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChannelRepositoryImpl @Inject constructor(
    private val db: FreeTvDatabase,
    private val streamTester: StreamTester,
) : ChannelRepository {

    private val channelDao = db.channelDao()
    private val favoriteDao = db.favoriteDao()

    override fun observeAllVisible(): Flow<List<ChannelItem>> = channelDao.observeAllVisible().map { it.toDomain() }
    override fun observeVisibleInGroup(groupId: Long): Flow<List<ChannelItem>> =
        channelDao.observeVisibleInGroup(groupId).map { it.toDomain() }
    override fun observeFavorites(): Flow<List<ChannelItem>> = channelDao.observeFavorites().map { it.toDomain() }
    override fun observeForManager(playlistId: Long?, query: String): Flow<List<ChannelItem>> =
        channelDao.observeForManager(playlistId ?: -1L, query.trim()).map { it.toDomain() }
    override fun observeChannelCount(): Flow<Int> = channelDao.observeCount()
    override fun observeMaxNumber(): Flow<Int?> = channelDao.observeMaxNumber()

    override suspend fun getAllVisible(): List<ChannelItem> = channelDao.getAllVisible().toDomain()
    override suspend fun getVisibleInGroup(groupId: Long): List<ChannelItem> = channelDao.getVisibleInGroup(groupId).toDomain()
    override suspend fun getFavorites(): List<ChannelItem> = channelDao.getFavorites().toDomain()

    override suspend fun getChannel(id: Long): ChannelDetail? = channelDao.getById(id)?.toDetail()
    override fun observeChannel(id: Long): Flow<ChannelDetail?> = channelDao.observeById(id).map { it?.toDetail() }
    override suspend fun getChannelByNumber(number: Int): ChannelDetail? = channelDao.getByNumber(number)?.toDetail()

    override suspend fun createChannel(name: String, streamUrl: String, logoUrl: String?): Long = db.withTransaction {
        require(UrlSanitizer.isValidRemoteUrl(streamUrl)) { "Invalid stream URL" }
        val id = channelDao.insert(
            ChannelEntity(
                playlistId = null,
                stableKey = "user:" + UUID.randomUUID(),
                name = name.trim().ifEmpty { "New channel" },
                logoUrl = logoUrl?.trim()?.takeIf { it.isNotEmpty() },
                number = (channelDao.maxNumber() ?: 0) + 1,
                numberEdited = true,
                nameEdited = true,
                createdAt = System.currentTimeMillis(),
            ),
        )
        channelDao.insertStream(ChannelStreamEntity(channelId = id, url = streamUrl.trim(), priority = 0, fromPlaylist = false))
        id
    }

    override suspend fun updateChannel(id: Long, name: String, logoUrl: String?, language: String?) {
        val old = channelDao.getById(id) ?: return
        val newName = name.trim().ifEmpty { old.name }
        val newLogo = logoUrl?.trim()?.takeIf { it.isNotEmpty() }
        val newLanguage = language?.trim()?.takeIf { it.isNotEmpty() }
        channelDao.update(
            old.copy(
                name = newName,
                logoUrl = newLogo,
                language = newLanguage,
                nameEdited = old.nameEdited || newName != old.name,
                logoEdited = old.logoEdited || newLogo != old.logoUrl,
                languageEdited = old.languageEdited || newLanguage != old.language,
            ),
        )
    }

    override suspend fun setHidden(id: Long, hidden: Boolean) = channelDao.setHidden(id, hidden)

    override suspend fun deleteChannel(id: Long) {
        channelDao.getById(id)?.let { channelDao.delete(it) }
    }

    override suspend fun markWatched(id: Long) = channelDao.markWatched(id, System.currentTimeMillis())

    // ---------------- Numbers ----------------

    override suspend fun assignNumber(channelId: Long, number: Int?): NumberAssignResult = db.withTransaction {
        if (number == null) {
            channelDao.setNumber(channelId, null, true)
            return@withTransaction NumberAssignResult.Success
        }
        if (number < 1 || number > MAX_CHANNEL_NUMBER) {
            return@withTransaction NumberAssignResult.Invalid("Number must be between 1 and $MAX_CHANNEL_NUMBER")
        }
        val holder = channelDao.getByNumber(number)
        if (holder != null && holder.id != channelId) {
            return@withTransaction NumberAssignResult.Conflict(holder.id, holder.name)
        }
        channelDao.setNumber(channelId, number, true)
        NumberAssignResult.Success
    }

    override suspend fun swapNumber(channelId: Long, number: Int) = db.withTransaction {
        val me = channelDao.getById(channelId) ?: return@withTransaction
        val holder = channelDao.getByNumber(number)
        if (holder != null && holder.id != channelId) {
            channelDao.setNumber(holder.id, null, true)
            channelDao.setNumber(channelId, number, true)
            channelDao.setNumber(holder.id, me.number, true)
        } else {
            channelDao.setNumber(channelId, number, true)
        }
    }

    override suspend fun autoNumberAll(startAt: Int) = db.withTransaction {
        val ids = channelDao.idsInNumberingOrder()
        channelDao.clearAllNumbers()
        ids.forEachIndexed { index, id -> channelDao.setNumber(id, startAt + index, false) }
    }

    override suspend fun moveNumber(channelId: Long, up: Boolean) = db.withTransaction {
        val me = channelDao.getById(channelId) ?: return@withTransaction
        val myNumber = me.number ?: return@withTransaction
        val other = if (up) channelDao.previousByNumber(myNumber) else channelDao.nextByNumber(myNumber)
        if (other?.number == null) return@withTransaction
        channelDao.setNumber(me.id, null, true)
        channelDao.setNumber(other.id, myNumber, true)
        channelDao.setNumber(me.id, other.number, true)
    }

    // ---------------- Streams ----------------

    override suspend fun getStreams(channelId: Long): List<ChannelStream> = channelDao.getStreams(channelId).map { it.toDomain() }

    override fun observeStreams(channelId: Long): Flow<List<ChannelStream>> =
        channelDao.observeStreams(channelId).map { list -> list.map { it.toDomain() } }

    override suspend fun addStream(channelId: Long, url: String, label: String?): Result<Long> {
        val clean = url.trim()
        if (!UrlSanitizer.isValidRemoteUrl(clean)) return Result.failure(IllegalArgumentException("Invalid stream URL"))
        val priority = (channelDao.getStreams(channelId).maxOfOrNull { it.priority } ?: -1) + 1
        val id = channelDao.insertStream(
            ChannelStreamEntity(
                channelId = channelId,
                url = clean,
                label = label?.trim()?.takeIf { it.isNotEmpty() },
                priority = priority,
                fromPlaylist = false,
            ),
        )
        return if (id == -1L) Result.failure(IllegalArgumentException("This URL is already added")) else Result.success(id)
    }

    override suspend fun updateStream(streamId: Long, url: String, label: String?): Result<Unit> {
        val stream = channelDao.getStream(streamId) ?: return Result.failure(IllegalArgumentException("Stream not found"))
        val clean = url.trim()
        if (!UrlSanitizer.isValidRemoteUrl(clean)) return Result.failure(IllegalArgumentException("Invalid stream URL"))
        return try {
            // An edited playlist stream becomes a user stream so a refresh keeps the edit.
            channelDao.updateStream(
                stream.copy(
                    url = clean,
                    label = label?.trim()?.takeIf { it.isNotEmpty() },
                    fromPlaylist = stream.fromPlaylist && clean == stream.url,
                    lastStatus = if (clean == stream.url) stream.lastStatus else StreamStatus.UNKNOWN.name,
                ),
            )
            Result.success(Unit)
        } catch (_: SQLiteConstraintException) {
            Result.failure(IllegalArgumentException("This URL is already added"))
        }
    }

    override suspend fun deleteStream(streamId: Long) = channelDao.deleteStream(streamId)

    override suspend fun makePrimary(streamId: Long) = db.withTransaction {
        val stream = channelDao.getStream(streamId) ?: return@withTransaction
        val ordered = channelDao.getStreams(stream.channelId).sortedBy { if (it.id == streamId) -1 else it.priority }
        ordered.forEachIndexed { index, s -> channelDao.setStreamPriority(s.id, index) }
    }

    override suspend fun moveStream(streamId: Long, up: Boolean) = db.withTransaction {
        val stream = channelDao.getStream(streamId) ?: return@withTransaction
        val list = channelDao.getStreams(stream.channelId).toMutableList()
        val index = list.indexOfFirst { it.id == streamId }
        val target = if (up) index - 1 else index + 1
        if (index < 0 || target !in list.indices) return@withTransaction
        val tmp = list[index]
        list[index] = list[target]
        list[target] = tmp
        list.forEachIndexed { i, s -> channelDao.setStreamPriority(s.id, i) }
    }

    override suspend fun setStreamStatus(streamId: Long, status: StreamStatus) =
        channelDao.setStreamStatus(streamId, status.name, System.currentTimeMillis())

    override suspend fun testStream(streamId: Long): StreamTestResult {
        val stream = channelDao.getStream(streamId) ?: return StreamTestResult(false, "Stream not found")
        val result = streamTester.test(stream.url, stream.userAgent, stream.referrer)
        setStreamStatus(streamId, if (result.ok) StreamStatus.OK else StreamStatus.FAILED)
        return result
    }

    // ---------------- Favourites ----------------

    override fun observeIsFavorite(channelId: Long): Flow<Boolean> = favoriteDao.observeIsFavorite(channelId)

    override suspend fun toggleFavorite(channelId: Long): Boolean {
        val now = !favoriteDao.isFavorite(channelId)
        setFavorite(channelId, now)
        return now
    }

    override suspend fun setFavorite(channelId: Long, favorite: Boolean) {
        if (favorite) {
            favoriteDao.insert(FavoriteEntity(channelId, System.currentTimeMillis(), favoriteDao.maxPosition() + 1))
        } else {
            favoriteDao.delete(channelId)
        }
    }

    companion object {
        const val MAX_CHANNEL_NUMBER = 99_999
    }
}
