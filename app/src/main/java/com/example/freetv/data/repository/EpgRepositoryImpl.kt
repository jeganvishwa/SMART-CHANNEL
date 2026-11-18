package com.example.freetv.data.repository

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.example.freetv.data.database.FreeTvDatabase
import com.example.freetv.data.entity.AppSettingsEntity
import com.example.freetv.data.entity.EpgChannelEntity
import com.example.freetv.data.entity.EpgProgramEntity
import com.example.freetv.data.network.Downloader
import com.example.freetv.data.parser.ChannelClassifier
import com.example.freetv.data.parser.XmltvParser
import com.example.freetv.di.IoDispatcher
import com.example.freetv.domain.model.EpgImportResult
import com.example.freetv.domain.model.EpgProgram
import com.example.freetv.domain.model.NowNext
import com.example.freetv.domain.repository.EpgRepository
import com.example.freetv.utils.UrlSanitizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.time.ZoneId
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EpgRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: FreeTvDatabase,
    private val downloader: Downloader,
    @IoDispatcher private val io: CoroutineDispatcher,
) : EpgRepository {

    private val epgDao = db.epgDao()
    private val settingsDao = db.appSettingsDao()
    private val channelDao = db.channelDao()
    private val mutex = Mutex()
    private val version = MutableStateFlow(System.currentTimeMillis())

    /** Lazily built lookup of EPG channel ids (by id and by normalised display name). */
    @Volatile
    private var resolver: Resolver? = null

    private class Resolver(val ids: Set<String>, val byName: Map<String, String>)

    override suspend fun refresh(url: String, offsetMinutes: Int, onProgress: (String) -> Unit): EpgImportResult =
        mutex.withLock {
            if (!UrlSanitizer.isValidRemoteUrl(url)) return EpgImportResult(false, 0, 0, "Invalid EPG URL")
            val file = File(context.cacheDir, "epg_download.xml")
            try {
                onProgress("Downloading guide…")
                downloader.download(url, file) { done, _ -> onProgress("Downloading guide… ${PlaylistRepositoryImpl.formatBytes(done)}") }

                onProgress("Reading guide…")
                // Only keep programmes for channels that exist in the user's playlists.
                val channels = channelDao.getAllVisible()
                val tvgIds = channels.mapNotNull { it.tvgId?.lowercase()?.trim() }.filter { it.isNotEmpty() }.toHashSet()
                val nameKeys = channels.flatMap { listOfNotNull(it.tvgName, it.name) }
                    .map { ChannelClassifier.matchKey(it) }.filter { it.isNotEmpty() }.toHashSet()

                val now = System.currentTimeMillis()
                val windowStart = now - 6 * HOUR
                val windowEnd = now + 4 * 24 * HOUR
                val wanted = HashSet<String>()
                val epgChannels = ArrayList<EpgChannelEntity>()
                val programmes = ArrayList<EpgProgramEntity>()

                withContext(io) {
                    openMaybeGzip(file).use { input ->
                        XmltvParser(ZoneId.systemDefault(), offsetMinutes).parse(
                            input,
                            onChannel = { ch ->
                                val id = ch.id.lowercase()
                                val key = ChannelClassifier.matchKey(ch.displayName)
                                if (id in tvgIds || key in nameKeys) {
                                    wanted += id
                                    epgChannels += EpgChannelEntity(id, ch.displayName.take(200), key)
                                }
                            },
                            onProgramme = { p ->
                                val id = p.channelId.lowercase()
                                if ((id in wanted || id in tvgIds) && p.endMs > windowStart && p.startMs < windowEnd) {
                                    programmes += EpgProgramEntity(
                                        epgChannelId = id,
                                        startMs = p.startMs,
                                        endMs = p.endMs,
                                        title = p.title,
                                        description = p.description,
                                        category = p.category,
                                    )
                                    if (id !in wanted) {
                                        wanted += id
                                        epgChannels += EpgChannelEntity(id, p.channelId, ChannelClassifier.matchKey(p.channelId))
                                    }
                                }
                            },
                        )
                    }
                }

                onProgress("Saving ${programmes.size} programmes…")
                db.withTransaction {
                    epgDao.clearPrograms()
                    epgDao.clearChannels()
                    epgDao.insertChannels(epgChannels)
                    programmes.chunked(1_000).forEach { epgDao.insertPrograms(it) }
                    settingsDao.put(AppSettingsEntity(KEY_LAST_REFRESH, System.currentTimeMillis().toString()))
                }
                resolver = null
                version.value = System.currentTimeMillis()
                EpgImportResult(
                    ok = true,
                    programCount = programmes.size,
                    channelCount = epgChannels.size,
                    message = if (epgChannels.isEmpty()) {
                        "Guide loaded, but no channel matched your playlists (check tvg-id values)."
                    } else {
                        "Guide loaded: ${epgChannels.size} channels, ${programmes.size} programmes."
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "EPG import failed for ${UrlSanitizer.redact(url)}", e)
                EpgImportResult(false, 0, 0, "Guide import failed: ${Downloader.friendlyMessage(e)}")
            } finally {
                file.delete()
            }
        }

    private fun openMaybeGzip(file: File): InputStream {
        val input = BufferedInputStream(file.inputStream())
        input.mark(2)
        val b1 = input.read()
        val b2 = input.read()
        input.reset()
        return if (b1 == 0x1f && b2 == 0x8b) BufferedInputStream(GZIPInputStream(input)) else input
    }

    override suspend fun clear() {
        db.withTransaction {
            epgDao.clearPrograms()
            epgDao.clearChannels()
        }
        resolver = null
        version.value = System.currentTimeMillis()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeCurrentPrograms(): Flow<Map<String, EpgProgram>> = version.flatMapLatest {
        flow {
            while (true) {
                val now = System.currentTimeMillis()
                emit(epgDao.getCurrentPrograms(now).associate { it.epgChannelId to it.toDomain() })
                delay(MINUTE - now % MINUTE + 500)
            }
        }
    }

    private suspend fun resolver(): Resolver {
        resolver?.let { return it }
        val channels = epgDao.getChannels()
        val built = Resolver(
            ids = channels.map { it.id }.toHashSet(),
            byName = channels.associate { it.normalizedName to it.id },
        )
        resolver = built
        return built
    }

    override suspend fun resolveEpgId(tvgId: String?, tvgName: String?, name: String): String? {
        val r = resolver()
        tvgId?.lowercase()?.trim()?.takeIf { it.isNotEmpty() }?.let { if (it in r.ids) return it }
        tvgName?.let { r.byName[ChannelClassifier.matchKey(it)] }?.let { return it }
        return r.byName[ChannelClassifier.matchKey(name)]
    }

    override suspend fun getNowNext(tvgId: String?, tvgName: String?, name: String): NowNext {
        val id = resolveEpgId(tvgId, tvgName, name) ?: return NowNext(null, null)
        val now = System.currentTimeMillis()
        val list = epgDao.getNowNext(id, now).map { it.toDomain() }
        val current = list.firstOrNull { it.startMs <= now }
        val next = list.firstOrNull { it.startMs > now }
        return NowNext(current, next)
    }

    override suspend fun getSchedule(tvgId: String?, tvgName: String?, name: String, from: Long, to: Long): List<EpgProgram> {
        val id = resolveEpgId(tvgId, tvgName, name) ?: return emptyList()
        return epgDao.getSchedule(id, from, to).map { it.toDomain() }
    }

    override fun observeLastRefresh(): Flow<Long?> = settingsDao.observe(KEY_LAST_REFRESH).map { it?.toLongOrNull() }

    override fun observeEpgVersion(): Flow<Long> = version

    companion object {
        private const val TAG = "EpgRepository"
        private const val KEY_LAST_REFRESH = "epg_last_refresh"
        private const val MINUTE = 60_000L
        private const val HOUR = 3_600_000L
    }
}
