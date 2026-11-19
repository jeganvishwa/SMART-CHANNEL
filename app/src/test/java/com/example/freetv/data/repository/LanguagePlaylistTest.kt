package com.example.freetv.data.repository

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.freetv.data.database.DefaultGroupsCallback
import com.example.freetv.data.database.FreeTvDatabase
import com.example.freetv.data.entity.PlaylistEntity
import com.example.freetv.data.network.StreamTester
import com.example.freetv.data.parser.M3uParser
import com.example.freetv.domain.model.LanguageCatalog
import com.example.freetv.domain.repository.PlaylistRepository
import com.example.freetv.preferences.LastWatchedChannel
import com.example.freetv.preferences.StartupPreferences
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LanguagePlaylistTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: FreeTvDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var prefs: StartupPreferences
    private lateinit var initializer: LanguagePlaylistInitializer

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, FreeTvDatabase::class.java)
            .addCallback(DefaultGroupsCallback)
            .allowMainThreadQueries()
            .build()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        prefs = StartupPreferences(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "s.preferences_pb") })
        initializer = LanguagePlaylistInitializer(
            playlistDao = db.playlistDao(),
            playlists = mockk<PlaylistRepository>(relaxed = true),
            folders = LanguageFolderManager(db.groupDao()),
            startupPrefs = prefs,
            scope = scope,
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    @Test
    fun seedsFourLanguagePlaylistsWithFoldersAndIsIdempotent() = runBlocking {
        val first = initializer.ensurePlaylists()
        val second = initializer.ensurePlaylists()

        assertEquals(first, second)
        val all = db.playlistDao().getAll()
        assertEquals(4, all.size)
        val tamil = all.first { it.languageCode == "tam" }
        assertEquals("Tamil TV", tamil.name)
        assertEquals("Languages/Tamil", tamil.folder)
        assertEquals("https://iptv-org.github.io/iptv/languages/tam.m3u", tamil.url)
        assertEquals(setOf("tam", "eng", "hin", "mal"), all.map { it.languageCode }.toSet())
    }

    @Test
    fun existingPlaylistWithSameUrlIsReusedNotDuplicated() = runBlocking {
        val id = db.playlistDao().insert(
            PlaylistEntity(name = "My Tamil", url = LanguageCatalog.byCode("tam")!!.url, sourceType = "URL", importedAt = 0),
        )
        initializer.ensurePlaylists()
        val all = db.playlistDao().getAll()
        assertEquals(4, all.size)
        val reused = db.playlistDao().getById(id)!!
        assertEquals("My Tamil", reused.name) // user's name kept
        assertEquals("tam", reused.languageCode)
        assertEquals("Languages/Tamil", reused.folder)
    }

    @Test
    fun deletedLanguageGroupIsRecreated() = runBlocking {
        val hindi = db.groupDao().getByName("Hindi")!!
        db.groupDao().delete(hindi.id)
        initializer.ensurePlaylists()
        assertNotNull(db.groupDao().getByName("Hindi"))
    }

    @Test
    fun languagePlaylistChannelsGoToTheirLanguageGroup() = runBlocking {
        val pid = initializer.ensurePlaylists().first() // Tamil TV
        // iptv-org entries carry no language tag – only categories.
        val m3u = "#EXTM3U\n#EXTINF:-1 tvg-id=\"x.in\" group-title=\"News\",Polimer News\nhttps://example.com/p.m3u8\n"
        SyncHelper.sync(db, pid, m3u, "Tamil")

        val channels = ChannelRepositoryImpl(db, StreamTester(OkHttpClient(), Dispatchers.IO))
        val tamilGroup = db.groupDao().getByName("Tamil")!!.id
        val newsGroup = db.groupDao().getByName("News")!!.id
        val inTamil = channels.getVisibleInGroup(tamilGroup)
        assertEquals(listOf("Polimer News"), inTamil.map { it.name })
        assertEquals("Tamil", inTamil.single().language)
        assertEquals(listOf("Polimer News"), channels.getVisibleInGroup(newsGroup).map { it.name })
    }

    @Test
    fun recentlyWatchedListsLatestFirst() = runBlocking {
        val pid = initializer.ensurePlaylists().first()
        SyncHelper.sync(
            db, pid,
            "#EXTM3U\n#EXTINF:-1,A\nhttps://example.com/a.m3u8\n#EXTINF:-1,B\nhttps://example.com/b.m3u8\n",
            "Tamil",
        )
        val channels = ChannelRepositoryImpl(db, StreamTester(OkHttpClient(), Dispatchers.IO))
        val (a, b) = channels.getAllVisible()
        db.channelDao().markWatched(a.id, 1_000)
        db.channelDao().markWatched(b.id, 2_000)
        assertEquals(listOf("B", "A"), channels.observeRecentlyWatched().first().map { it.name })
    }

    @Test
    fun lastWatchedChannelRoundTripsAndStreamIndexOnlyForSameChannel() = runBlocking {
        prefs.saveLastWatched(LastWatchedChannel(5, "Sun TV", 1, "Tamil", 2, "GROUP", 123, -1))
        prefs.saveLastStreamIndex(5, 1)
        prefs.saveLastStreamIndex(6, 3) // another channel: ignored
        val saved = prefs.current().lastWatched!!
        assertEquals("Sun TV", saved.channelName)
        assertEquals(2L, saved.groupId)
        assertEquals(1, saved.streamIndex)
        assertTrue(prefs.current().restoreLastLanguage)
    }
}

private object SyncHelper {
    suspend fun sync(db: FreeTvDatabase, playlistId: Long, m3u: String, language: String?) {
        PlaylistSyncer(db).sync(playlistId, M3uParser.parse(m3u), autoNumber = true, playlistLanguage = language)
    }
}
