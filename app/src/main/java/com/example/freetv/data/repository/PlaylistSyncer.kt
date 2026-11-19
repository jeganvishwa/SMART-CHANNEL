package com.example.freetv.data.repository

import androidx.room.withTransaction
import com.example.freetv.data.database.FreeTvDatabase
import com.example.freetv.data.entity.ChannelEntity
import com.example.freetv.data.entity.ChannelGroupMemberEntity
import com.example.freetv.data.entity.ChannelStreamEntity
import com.example.freetv.data.parser.ChannelClassifier
import com.example.freetv.data.parser.M3uEntry
import com.example.freetv.data.parser.M3uPlaylist
import com.example.freetv.domain.model.GroupType
import com.example.freetv.domain.model.ImportResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes a parsed playlist into the database.
 *
 * Refresh rules (user data is never lost):
 * - Channels are matched by (playlistId, stableKey).
 * - Metadata is updated unless the user edited it (name / logo / language).
 * - Numbers, favourites, group memberships and user-added streams are kept.
 * - Playlist streams are replaced; user streams are untouched.
 * - A channel that vanished from the playlist is deleted only when the user
 *   never customised it; otherwise it is kept and flagged `inSource = false`.
 */
@Singleton
class PlaylistSyncer @Inject constructor(
    private val db: FreeTvDatabase,
) {
    private val channelDao = db.channelDao()
    private val groupDao = db.groupDao()
    private val favoriteDao = db.favoriteDao()

    suspend fun sync(
        playlistId: Long,
        parsed: M3uPlaylist,
        autoNumber: Boolean,
        now: Long = System.currentTimeMillis(),
        /**
         * Language group of a language playlist (e.g. "Tamil"). Every channel of such a
         * playlist is put in that group, even when its entries carry no language tag.
         */
        playlistLanguage: String? = null,
    ): ImportResult.Success = db.withTransaction {
        val (merged, duplicates) = ChannelClassifier.merge(parsed.entries)
        val existing = channelDao.getByPlaylist(playlistId).associateBy { it.stableKey }
        val autoGroups = groupDao.getAll().filter { it.type == GroupType.LANGUAGE.name || it.type == GroupType.CATEGORY.name }
        val playlistLanguageGroup = playlistLanguage?.let { lang ->
            autoGroups.firstOrNull { it.type == GroupType.LANGUAGE.name && it.name.equals(lang, ignoreCase = true) }
        }
        val languageKeywords = autoGroups.filter { it.type == GroupType.LANGUAGE.name }.associate { it.name to it.keywords }
        val groupPositions = HashMap<Long, Int>()
        var nextNumber = (channelDao.maxNumber() ?: 0) + 1

        var created = 0
        var updated = 0
        val seen = HashSet<Long>(merged.size * 2)

        for (channel in merged) {
            val entry = channel.primary
            val language = ChannelClassifier.detectLanguage(entry.language, entry.groupTitle, entry.name, languageKeywords)
                ?: playlistLanguage
            val old = existing[channel.key]
            val channelId: Long
            if (old == null) {
                channelId = channelDao.insert(
                    ChannelEntity(
                        playlistId = playlistId,
                        stableKey = channel.key,
                        name = entry.name,
                        tvgId = entry.tvgId,
                        tvgName = entry.tvgName,
                        logoUrl = entry.tvgLogo,
                        originalGroup = entry.groupTitle,
                        language = language,
                        number = if (autoNumber) nextNumber++ else null,
                        createdAt = now,
                    ),
                )
                created++
                channelDao.insertStreams(
                    channel.streams.mapIndexed { index, e -> e.toStream(channelId, index) },
                )
            } else {
                channelId = old.id
                val refreshed = old.copy(
                    name = if (old.nameEdited) old.name else entry.name,
                    tvgId = entry.tvgId,
                    tvgName = entry.tvgName,
                    logoUrl = if (old.logoEdited) old.logoUrl else entry.tvgLogo,
                    originalGroup = entry.groupTitle,
                    language = if (old.languageEdited) old.language else language,
                    inSource = true,
                )
                if (refreshed != old) {
                    channelDao.update(refreshed)
                    updated++
                }
                syncStreams(channelId, channel.streams)
            }
            seen += channelId

            for (group in autoGroups) {
                val isPlaylistLanguage = group.id == playlistLanguageGroup?.id
                if (isPlaylistLanguage || ChannelClassifier.matches(group.name, group.keywords, language, entry.groupTitle, entry.name)) {
                    val position = (groupPositions[group.id] ?: groupDao.maxMemberPosition(group.id)) + 1
                    groupPositions[group.id] = position
                    groupDao.insertMemberIgnore(ChannelGroupMemberEntity(channelId, group.id, position, "AUTO"))
                }
            }
        }

        var removed = 0
        for (old in existing.values) {
            if (old.id in seen) continue
            val customised = old.numberEdited || old.nameEdited || old.logoEdited || old.languageEdited ||
                favoriteDao.isFavorite(old.id) ||
                channelDao.countUserStreams(old.id) > 0 ||
                groupDao.countUserMemberships(old.id) > 0
            if (customised) {
                if (old.inSource) channelDao.update(old.copy(inSource = false))
            } else {
                channelDao.delete(old)
                removed++
            }
        }

        ImportResult.Success(
            playlistId = playlistId,
            channelCount = channelDao.countByPlaylist(playlistId),
            newChannels = created,
            updatedChannels = updated,
            removedChannels = removed,
            duplicatesMerged = duplicates,
            invalidEntries = parsed.invalidEntries,
        )
    }

    private suspend fun syncStreams(channelId: Long, entries: List<M3uEntry>) {
        val current = channelDao.getStreams(channelId)
        val urls = entries.map { it.url }.toSet()
        val stale = current.filter { it.fromPlaylist && it.url !in urls }.map { it.id }
        if (stale.isNotEmpty()) channelDao.deleteStreams(stale)
        var priority = (current.maxOfOrNull { it.priority } ?: -1) + 1
        for (entry in entries) {
            val match = current.firstOrNull { it.url == entry.url }
            if (match == null) {
                channelDao.insertStream(entry.toStream(channelId, priority++))
            } else if (match.fromPlaylist && (match.userAgent != entry.userAgent || match.referrer != entry.referrer)) {
                channelDao.updateStream(match.copy(userAgent = entry.userAgent, referrer = entry.referrer))
            }
        }
    }

    private fun M3uEntry.toStream(channelId: Long, priority: Int) = ChannelStreamEntity(
        channelId = channelId,
        url = url,
        label = null,
        priority = priority,
        fromPlaylist = true,
        userAgent = userAgent,
        referrer = referrer,
    )
}
