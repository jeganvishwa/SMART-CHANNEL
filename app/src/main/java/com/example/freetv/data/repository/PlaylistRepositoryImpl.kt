package com.example.freetv.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.example.freetv.data.dao.PlaylistDao
import com.example.freetv.data.entity.PlaylistEntity
import com.example.freetv.data.network.DownloadException
import com.example.freetv.data.network.Downloader
import com.example.freetv.data.parser.InvalidPlaylistException
import com.example.freetv.data.parser.M3uParser
import com.example.freetv.di.DefaultDispatcher
import com.example.freetv.di.IoDispatcher
import com.example.freetv.domain.model.ImportProgress
import com.example.freetv.domain.model.ImportResult
import com.example.freetv.domain.model.ImportStage
import com.example.freetv.domain.model.Playlist
import com.example.freetv.domain.repository.PlaylistRepository
import com.example.freetv.domain.repository.SettingsRepository
import com.example.freetv.utils.UrlSanitizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playlistDao: PlaylistDao,
    private val syncer: PlaylistSyncer,
    private val downloader: Downloader,
    private val settings: SettingsRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
    @DefaultDispatcher private val cpu: CoroutineDispatcher,
) : PlaylistRepository {

    /** One import/refresh at a time keeps the database consistent and the TV responsive. */
    private val mutex = Mutex()

    private val playlistFilesDir get() = File(context.filesDir, "playlists")
    private val downloadDir get() = File(context.cacheDir, "playlist_downloads")

    override fun observePlaylists(): Flow<List<Playlist>> =
        playlistDao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observePlaylistCount(): Flow<Int> = playlistDao.observeCount()

    override suspend fun getPlaylist(id: Long): Playlist? = playlistDao.getById(id)?.toDomain()

    override suspend fun findByUrl(url: String): Playlist? = playlistDao.getByUrl(url.trim())?.toDomain()

    override suspend fun importFromUrl(
        name: String,
        url: String,
        autoRefresh: Boolean,
        allowDuplicate: Boolean,
        onProgress: (ImportProgress) -> Unit,
    ): ImportResult {
        val clean = url.trim()
        if (!UrlSanitizer.isValidRemoteUrl(clean)) {
            return ImportResult.Failure("Invalid URL. Enter a full address starting with http:// or https://")
        }
        if (!allowDuplicate) {
            playlistDao.getByUrl(clean)?.let { return ImportResult.DuplicatePlaylist(it.id, it.name) }
        }
        val id = playlistDao.insert(
            PlaylistEntity(
                name = name.trim().ifEmpty { defaultNameFromUrl(clean) },
                url = clean,
                sourceType = "URL",
                importedAt = System.currentTimeMillis(),
                autoRefresh = autoRefresh,
                status = "LOADING",
            ),
        )
        val result = refresh(id, onProgress)
        if (result is ImportResult.Failure) playlistDao.delete(id) // first import failed → let the user fix the URL
        return result
    }

    override suspend fun importFromUri(name: String, uri: Uri, onProgress: (ImportProgress) -> Unit): ImportResult {
        val displayName = try {
            DocumentFile.fromSingleUri(context, uri)?.name
        } catch (_: Exception) {
            null
        }
        return importLocal(name.trim().ifEmpty { displayName?.substringBeforeLast('.') ?: "Imported playlist" }) { target ->
            withContext(io) {
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw IOException("Cannot open the selected file.")
                input.use { inp -> target.outputStream().use { inp.copyTo(it) } }
            }
        }.let { it(onProgress) }
    }

    override suspend fun importFromFile(name: String, file: File, onProgress: (ImportProgress) -> Unit): ImportResult =
        importLocal(name.trim().ifEmpty { file.nameWithoutExtension }) { target ->
            withContext(io) {
                if (!file.canRead()) throw IOException("Cannot read ${file.path}. Check the path and storage permission.")
                file.inputStream().use { inp -> target.outputStream().use { inp.copyTo(it) } }
            }
        }.let { it(onProgress) }

    private fun importLocal(
        name: String,
        copy: suspend (File) -> Unit,
    ): suspend ((ImportProgress) -> Unit) -> ImportResult = { onProgress ->
        val id = playlistDao.insert(
            PlaylistEntity(
                name = name,
                url = null,
                sourceType = "FILE",
                importedAt = System.currentTimeMillis(),
                autoRefresh = false,
                status = "LOADING",
            ),
        )
        val target = File(playlistFilesDir, "$id.m3u").apply { parentFile?.mkdirs() }
        try {
            onProgress(ImportProgress(ImportStage.DOWNLOADING, null, "Copying file…"))
            copy(target)
            playlistDao.getById(id)?.let { playlistDao.update(it.copy(localFilePath = target.absolutePath)) }
            val result = refresh(id, onProgress)
            if (result is ImportResult.Failure) {
                playlistDao.delete(id)
                target.delete()
            }
            result
        } catch (e: CancellationException) {
            playlistDao.delete(id)
            target.delete()
            throw e
        } catch (e: Exception) {
            playlistDao.delete(id)
            target.delete()
            ImportResult.Failure(e.message ?: "Could not import the file.")
        }
    }

    override suspend fun refresh(playlistId: Long, onProgress: (ImportProgress) -> Unit): ImportResult = mutex.withLock {
        val playlist = playlistDao.getById(playlistId) ?: return ImportResult.Failure("Playlist not found.")
        playlistDao.setStatus(playlistId, "LOADING", null)
        var downloaded: File? = null
        try {
            val file = if (playlist.sourceType == "FILE") {
                File(playlist.localFilePath ?: throw DownloadException("The imported file is missing. Import it again."))
                    .also { if (!it.exists()) throw DownloadException("The imported file is missing. Import it again.") }
            } else {
                onProgress(ImportProgress(ImportStage.DOWNLOADING, null, "Connecting…"))
                downloader.download(playlist.url.orEmpty(), File(downloadDir, "$playlistId.m3u")) { done, total ->
                    onProgress(
                        ImportProgress(
                            ImportStage.DOWNLOADING,
                            if (total > 0) done.toFloat() / total else null,
                            formatBytes(done) + if (total > 0) " / " + formatBytes(total) else "",
                        ),
                    )
                }.also { downloaded = it }
            }

            onProgress(ImportProgress(ImportStage.PARSING, null, "Detecting channels…"))
            val parsed = withContext(cpu) { file.bufferedReader(Charsets.UTF_8).use { M3uParser.parse(it) } }
            if (parsed.entries.isEmpty()) throw InvalidPlaylistException("No playable channels were found in this playlist.")

            onProgress(ImportProgress(ImportStage.SAVING, null, "Saving ${parsed.entries.size} entries…"))
            val autoNumber = settings.current().autoNumberNewChannels
            val result = withContext(io) { syncer.sync(playlistId, parsed, autoNumber) }

            playlistDao.getById(playlistId)?.let {
                playlistDao.update(
                    it.copy(
                        status = "OK",
                        lastError = null,
                        lastRefreshAt = System.currentTimeMillis(),
                        channelCount = result.channelCount,
                        headerEpgUrl = parsed.epgUrl,
                    ),
                )
            }
            onProgress(ImportProgress(ImportStage.DONE, 1f, "${result.channelCount} channels"))
            result
        } catch (e: CancellationException) {
            playlistDao.setStatus(playlistId, "ERROR", "Cancelled")
            throw e
        } catch (e: Exception) {
            val message = when (e) {
                is InvalidPlaylistException, is DownloadException -> e.message
                is IOException -> Downloader.friendlyMessage(e)
                else -> e.message
            } ?: "Unknown error"
            Log.w(TAG, "Refresh failed for playlist $playlistId (${UrlSanitizer.redact(playlist.url)}): $message")
            playlistDao.setStatus(playlistId, "ERROR", message)
            ImportResult.Failure(message)
        } finally {
            downloaded?.delete()
        }
    }

    override suspend fun refreshDue(maxAgeMs: Long): Int {
        val now = System.currentTimeMillis()
        var refreshed = 0
        for (p in playlistDao.getAll()) {
            if (!p.enabled || !p.autoRefresh || p.sourceType != "URL") continue
            val last = p.lastRefreshAt ?: 0L
            if (now - last < maxAgeMs) continue
            if (refresh(p.id) is ImportResult.Success) refreshed++
        }
        return refreshed
    }

    override suspend fun updatePlaylist(id: Long, name: String, url: String?, autoRefresh: Boolean): Result<Unit> {
        val playlist = playlistDao.getById(id) ?: return Result.failure(IllegalArgumentException("Playlist not found"))
        var newUrl = playlist.url
        if (playlist.sourceType == "URL") {
            val clean = url?.trim().orEmpty()
            if (!UrlSanitizer.isValidRemoteUrl(clean)) return Result.failure(IllegalArgumentException("Invalid URL"))
            val other = playlistDao.getByUrl(clean)
            if (other != null && other.id != id) {
                return Result.failure(IllegalArgumentException("This URL is already used by \"${other.name}\""))
            }
            newUrl = clean
        }
        playlistDao.update(
            playlist.copy(name = name.trim().ifEmpty { playlist.name }, url = newUrl, autoRefresh = autoRefresh),
        )
        return Result.success(Unit)
    }

    override suspend fun setEnabled(id: Long, enabled: Boolean) = playlistDao.setEnabled(id, enabled)

    override suspend fun delete(id: Long) {
        val playlist = playlistDao.getById(id) ?: return
        playlistDao.delete(id)
        playlist.localFilePath?.let { withContext(io) { File(it).delete() } }
    }

    private fun defaultNameFromUrl(url: String): String {
        val host = url.substringAfter("://").substringBefore('/').substringAfterLast('@').substringBefore(':')
        val file = url.substringBefore('?').substringAfterLast('/').substringBeforeLast('.')
        return if (file.isNotBlank() && file.length <= 40) "$file ($host)" else host
    }

    companion object {
        private const val TAG = "PlaylistRepository"

        fun formatBytes(bytes: Long): String = when {
            bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f))
            bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024f)
            else -> "$bytes B"
        }
    }
}
