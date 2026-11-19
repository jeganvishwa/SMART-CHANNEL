package com.example.freetv.data.repository

import androidx.room.withTransaction
import com.example.freetv.data.database.FreeTvDatabase
import com.example.freetv.data.entity.FavoriteEntity
import com.example.freetv.data.entity.PlaylistEntity
import com.example.freetv.utils.UrlSanitizer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Merges playlists that point to the same URL into one record.
 *
 * The kept playlist is the one that already has channels (then a language
 * playlist, then the oldest). Before a duplicate is deleted, its favourites,
 * the user's own group choices and hand-set channel numbers are moved to the
 * matching channels (same stable key) of the kept playlist.
 */
@Singleton
class PlaylistDeduplicator @Inject constructor(
    private val db: FreeTvDatabase,
) {
    private val playlistDao = db.playlistDao()
    private val channelDao = db.channelDao()
    private val favoriteDao = db.favoriteDao()
    private val groupDao = db.groupDao()

    /** Returns how many duplicate playlist records were removed. */
    suspend fun mergeDuplicates(now: Long = System.currentTimeMillis()): Int = db.withTransaction {
        var removed = 0
        val groups = playlistDao.getAll()
            .filter { it.sourceType == "URL" && !it.url.isNullOrBlank() }
            .groupBy { UrlSanitizer.normalizeForCompare(it.url!!) }
            .values
            .filter { it.size > 1 }

        for (group in groups) {
            val keep = group.sortedWith(
                compareByDescending<PlaylistEntity> { it.channelCount > 0 }
                    .thenByDescending { it.languageCode != null }
                    .thenBy { it.id },
            ).first()
            val keptChannels = channelDao.getByPlaylist(keep.id).associateBy { it.stableKey }
            val languageSource = group.firstOrNull { it.languageCode != null }

            for (dup in group) {
                if (dup.id == keep.id) continue
                val numbersToMove = mutableListOf<Pair<Long, Int>>()
                for (channel in channelDao.getByPlaylist(dup.id)) {
                    val target = keptChannels[channel.stableKey] ?: continue
                    if (favoriteDao.isFavorite(channel.id) && !favoriteDao.isFavorite(target.id)) {
                        favoriteDao.insert(FavoriteEntity(target.id, now, favoriteDao.maxPosition() + 1))
                    }
                    groupDao.getMembershipsForChannel(channel.id)
                        .filter { it.assignedBy == "USER" && !it.excluded }
                        .forEach { m ->
                            val existing = groupDao.getMember(target.id, m.groupId)
                            if (existing == null) {
                                groupDao.upsertMember(m.copy(channelId = target.id, position = groupDao.maxMemberPosition(m.groupId) + 1))
                            } else if (existing.excluded || existing.assignedBy != "USER") {
                                groupDao.upsertMember(existing.copy(excluded = false, assignedBy = "USER"))
                            }
                        }
                    val number = channel.number
                    if (channel.numberEdited && number != null && !target.numberEdited) numbersToMove += target.id to number
                }
                playlistDao.delete(dup.id) // cascades to the duplicate's channels, freeing their numbers
                for ((channelId, number) in numbersToMove) {
                    if (channelDao.getByNumber(number) == null) channelDao.setNumber(channelId, number, true)
                }
                removed++
            }

            playlistDao.getById(keep.id)?.let { kept ->
                playlistDao.update(
                    kept.copy(
                        channelCount = channelDao.countByPlaylist(kept.id),
                        languageCode = kept.languageCode ?: languageSource?.languageCode,
                        folder = kept.folder ?: languageSource?.folder,
                    ),
                )
            }
        }
        removed
    }
}
