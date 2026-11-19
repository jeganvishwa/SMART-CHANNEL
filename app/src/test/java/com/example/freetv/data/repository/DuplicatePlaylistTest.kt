package com.example.freetv.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.freetv.data.database.DefaultGroupsCallback
import com.example.freetv.data.database.FreeTvDatabase
import com.example.freetv.data.entity.PlaylistEntity
import com.example.freetv.data.network.Downloader
import com.example.freetv.data.network.StreamTester
import com.example.freetv.data.parser.M3uParser
import com.example.freetv.domain.model.AppSettings
import com.example.freetv.domain.model.GroupResult
import com.example.freetv.domain.model.GroupType
import com.example.freetv.domain.model.ImportResult
import com.example.freetv.domain.repository.SettingsRepository
import com.example.freetv.utils.UrlSanitizer
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DuplicatePlaylistTest {

    private lateinit var db: FreeTvDatabase
    private lateinit var repo: PlaylistRepositoryImpl
    private lateinit var channels: ChannelRepositoryImpl
    private var served = "#EXTM3U\n#EXTINF:-1 tvg-id=\"a\",Alpha\nhttps://example.com/a.m3u8\n#EXTINF:-1 tvg-id=\"b\",Beta\nhttps://example.com/b.m3u8\n"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, FreeTvDatabase::class.java)
            .addCallback(DefaultGroupsCallback)
            .allowMainThreadQueries()
            .build()
        val downloader = mockk<Downloader>()
        coEvery { downloader.download(any(), any(), any(), any()) } answers {
            val target = secondArg<File>()
            target.parentFile?.mkdirs()
            target.writeText(served)
            target
        }
        val settings = mockk<SettingsRepository>()
        coEvery { settings.current() } returns AppSettings()
        repo = PlaylistRepositoryImpl(
            context = context,
            playlistDao = db.playlistDao(),
            syncer = PlaylistSyncer(db),
            deduplicator = PlaylistDeduplicator(db),
            downloader = downloader,
            settings = settings,
            io = Dispatchers.IO,
            cpu = Dispatchers.Default,
        )
        channels = ChannelRepositoryImpl(db, StreamTester(OkHttpClient(), Dispatchers.IO))
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun addingTheSameUrlReplacesTheExistingPlaylist() = runBlocking {
        val first = repo.importFromUrl("Old name", "https://Example.com/list.m3u", autoRefresh = true) as ImportResult.Success
        val alpha = channels.getAllVisible().first { it.name == "Alpha" }
        channels.setFavorite(alpha.id, true)
        channels.assignNumber(alpha.id, 77)

        // Same URL (different host case, extra spaces and trailing slash), new content.
        served = "#EXTM3U\n#EXTINF:-1 tvg-id=\"a\",Alpha HD\nhttps://example.com/a2.m3u8\n#EXTINF:-1 tvg-id=\"c\",Gamma\nhttps://example.com/c.m3u8\n"
        val second = repo.importFromUrl("New name", "  https://example.com/list.m3u/ ", autoRefresh = false) as ImportResult.Success

        assertTrue(second.replacedExisting)
        assertEquals(first.playlistId, second.playlistId)
        val playlists = repo.observePlaylists().first()
        assertEquals(1, playlists.size)
        assertEquals("New name", playlists.single().name)
        assertEquals(false, playlists.single().autoRefresh)
        val renamed = channels.getChannel(alpha.id)!!
        assertEquals("Alpha HD", renamed.name)
        assertEquals(77, renamed.number)
        assertTrue(db.favoriteDao().isFavorite(alpha.id))
        assertEquals(setOf("Alpha HD", "Gamma"), channels.getAllVisible().map { it.name }.toSet())
    }

    @Test
    fun existingDuplicatesAreMergedKeepingFavouritesGroupsAndNumbers() = runBlocking {
        val m3u = M3uParser.parse(served)
        val keepId = db.playlistDao().insert(PlaylistEntity(name = "Tamil TV", url = "https://x.org/tam.m3u", sourceType = "URL", importedAt = 0, languageCode = "tam", folder = "Languages/Tamil"))
        PlaylistSyncer(db).sync(keepId, m3u, autoNumber = true)
        db.playlistDao().getById(keepId)?.let { db.playlistDao().update(it.copy(channelCount = 2)) }
        val dupId = db.playlistDao().insert(PlaylistEntity(name = "My copy", url = "https://X.org/tam.m3u/", sourceType = "URL", importedAt = 1))
        PlaylistSyncer(db).sync(dupId, m3u, autoNumber = true)
        db.playlistDao().getById(dupId)?.let { db.playlistDao().update(it.copy(channelCount = 2)) }

        // User data on the duplicate's channels
        val dupBeta = db.channelDao().getByPlaylist(dupId).first { it.name == "Beta" }
        channels.setFavorite(dupBeta.id, true)
        val myGroup = (GroupRepositoryImpl(db).createGroup("Mine", GroupType.CUSTOM) as GroupResult.Success).groupId
        GroupRepositoryImpl(db).addChannels(myGroup, listOf(dupBeta.id))
        channels.assignNumber(dupBeta.id, 500)

        val removed = repo.mergeDuplicatePlaylists()

        assertEquals(1, removed)
        val remaining = db.playlistDao().getAll()
        assertEquals(listOf(keepId), remaining.map { it.id })
        val keptBeta = db.channelDao().getByPlaylist(keepId).first { it.name == "Beta" }
        assertTrue(db.favoriteDao().isFavorite(keptBeta.id))
        assertTrue(channels.getVisibleInGroup(myGroup).any { it.id == keptBeta.id })
        assertEquals(500, channels.getChannel(keptBeta.id)!!.number)
        assertEquals(2, channels.getAllVisible().size)
        assertEquals(0, repo.mergeDuplicatePlaylists())
    }

    @Test
    fun urlNormalisation() {
        val a = UrlSanitizer.normalizeForCompare(" HTTPS://Example.COM/Path/List.m3u/ ")
        assertEquals("https://example.com/Path/List.m3u", a)
        assertEquals(a, UrlSanitizer.normalizeForCompare("https://example.com/Path/List.m3u"))
        assertTrue(a != UrlSanitizer.normalizeForCompare("https://example.com/path/list.m3u"))
    }
}
