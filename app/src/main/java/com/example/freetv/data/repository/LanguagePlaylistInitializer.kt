package com.example.freetv.data.repository

import android.util.Log
import com.example.freetv.data.dao.GroupDao
import com.example.freetv.data.dao.PlaylistDao
import com.example.freetv.data.database.DefaultGroupsCallback
import com.example.freetv.data.entity.ChannelGroupEntity
import com.example.freetv.data.entity.PlaylistEntity
import com.example.freetv.di.ApplicationScope
import com.example.freetv.domain.model.GroupType
import com.example.freetv.domain.model.ImportResult
import com.example.freetv.domain.model.LanguageCatalog
import com.example.freetv.domain.repository.PlaylistRepository
import com.example.freetv.preferences.StartupPreferences
import com.example.freetv.utils.UrlSanitizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Keeps the logical "Languages / <language>" folders (language groups) in place. */
@Singleton
class LanguageFolderManager @Inject constructor(
    private val groupDao: GroupDao,
) {
    /** Returns the id of the language group for [groupName], creating it if the user deleted it. */
    suspend fun ensureLanguageGroup(groupName: String): Long {
        groupDao.getByName(groupName)?.let { return it.id }
        val keywords = DefaultGroupsCallback.DEFAULT_GROUPS.firstOrNull { it.first.equals(groupName, true) }?.third
        return groupDao.insert(
            ChannelGroupEntity(
                name = groupName,
                type = GroupType.LANGUAGE.name,
                position = groupDao.maxPosition() + 1,
                keywords = keywords,
            ),
        )
    }
}

/**
 * Adds the four built-in language playlists (Tamil, English, Hindi, Malayalam)
 * as normal playlist records and imports them with the existing M3U pipeline.
 * Existing records with the same URL are reused, never duplicated.
 */
@Singleton
class LanguagePlaylistInitializer @Inject constructor(
    private val playlistDao: PlaylistDao,
    private val deduplicator: PlaylistDeduplicator,
    private val playlists: PlaylistRepository,
    private val folders: LanguageFolderManager,
    private val startupPrefs: StartupPreferences,
    @ApplicationScope private val scope: CoroutineScope,
) {
    data class Status(val running: Boolean = false, val current: String? = null, val lastResult: String? = null)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()
    private var job: Job? = null

    /** Creates or updates the four playlist records. Returns their ids in catalogue order. */
    suspend fun ensurePlaylists(now: Long = System.currentTimeMillis()): List<Long> =
        LanguageCatalog.PLAYLISTS.map { lp ->
            folders.ensureLanguageGroup(lp.groupName)
            val key = UrlSanitizer.normalizeForCompare(lp.url)
            val existing = playlistDao.getAll().firstOrNull { it.url != null && UrlSanitizer.normalizeForCompare(it.url) == key }
            if (existing != null) {
                if (existing.languageCode != lp.code || existing.folder != lp.folder) {
                    playlistDao.update(existing.copy(languageCode = lp.code, folder = lp.folder))
                }
                existing.id
            } else {
                playlistDao.insert(
                    PlaylistEntity(
                        name = lp.playlistName,
                        url = lp.url,
                        sourceType = "URL",
                        importedAt = now,
                        autoRefresh = true,
                        status = "NEW",
                        languageCode = lp.code,
                        folder = lp.folder,
                    ),
                )
            }
        }

    /**
     * Every app start: merge playlists that share a URL. First launch only: add the
     * language playlists and import them in the background.
     */
    fun initializeOnFirstLaunch() {
        scope.launch {
            runCatching {
                val merged = deduplicator.mergeDuplicates()
                if (merged > 0) Log.i(TAG, "Merged $merged duplicate playlist(s)")
                if (startupPrefs.current().languagePlaylistsSeeded) return@launch
                val ids = ensurePlaylists()
                startupPrefs.setLanguagePlaylistsSeeded()
                refresh(ids.filter { id -> playlistDao.getById(id)?.lastRefreshAt == null })
            }.onFailure { Log.w(TAG, "Language playlist setup failed", it) }
        }
    }

    /** Adds any missing language playlists and refreshes all four (Playlists screen button). */
    fun addAndRefreshAll() {
        scope.launch {
            runCatching { refresh(ensurePlaylists()) }
                .onFailure { _status.value = Status(false, null, "Could not update language playlists: ${it.message}") }
        }
    }

    private fun refresh(ids: List<Long>) {
        if (ids.isEmpty() || job?.isActive == true) return
        job = scope.launch {
            var ok = 0
            val failed = mutableListOf<String>()
            for (id in ids) {
                val name = playlistDao.getById(id)?.name ?: continue
                _status.value = Status(true, name, null)
                // A failed refresh keeps the previously imported channels (nothing is deleted on error).
                when (val result = playlists.refresh(id)) {
                    is ImportResult.Success -> ok++
                    is ImportResult.Failure -> failed += "$name (${result.message})"
                }
            }
            _status.value = Status(
                running = false,
                current = null,
                lastResult = if (failed.isEmpty()) "Language playlists updated ($ok)." else "Updated $ok; failed: ${failed.joinToString("; ")}",
            )
        }
    }

    companion object {
        private const val TAG = "LanguagePlaylists"
    }
}
