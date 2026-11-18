package com.example.freetv.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.freetv.data.database.DefaultGroupsCallback
import com.example.freetv.data.database.FreeTvDatabase
import com.example.freetv.data.entity.PlaylistEntity
import com.example.freetv.data.network.StreamTester
import com.example.freetv.data.parser.M3uParser
import com.example.freetv.domain.model.GroupResult
import com.example.freetv.domain.model.GroupType
import com.example.freetv.domain.model.NumberAssignResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseLogicTest {

    private lateinit var db: FreeTvDatabase
    private lateinit var syncer: PlaylistSyncer
    private lateinit var channels: ChannelRepositoryImpl
    private lateinit var groups: GroupRepositoryImpl

    private val playlistV1 = """
        #EXTM3U
        #EXTINF:-1 tvg-id="sun.in" group-title="Tamil",Sun TV
        https://example.com/sun1.m3u8
        #EXTINF:-1 tvg-id="sun.in" group-title="Tamil",Sun TV Backup
        https://example.com/sun2.m3u8
        #EXTINF:-1 tvg-id="vijay.in" group-title="Tamil Entertainment",Vijay TV
        https://example.com/vijay.m3u8
        #EXTINF:-1 tvg-id="bbc.uk" group-title="English News",BBC News
        https://example.com/bbc.m3u8
        #EXTINF:-1 tvg-id="asianet.in" group-title="Malayalam",Asianet
        https://example.com/asianet.m3u8
    """.trimIndent()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, FreeTvDatabase::class.java)
            .addCallback(DefaultGroupsCallback)
            .allowMainThreadQueries()
            .build()
        syncer = PlaylistSyncer(db)
        channels = ChannelRepositoryImpl(db, StreamTester(OkHttpClient(), Dispatchers.IO))
        groups = GroupRepositoryImpl(db)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun newPlaylist(name: String): Long = db.playlistDao().insert(
        PlaylistEntity(name = name, url = "https://example.com/$name.m3u", sourceType = "URL", importedAt = 0),
    )

    private suspend fun groupId(name: String) = db.groupDao().getByName(name)!!.id

    @Test
    fun importDetectsChannelsMergesDuplicatesAndAutoNumbers() = runBlocking {
        val pid = newPlaylist("one")
        val result = syncer.sync(pid, M3uParser.parse(playlistV1), autoNumber = true)

        assertEquals(4, result.channelCount)
        assertEquals(1, result.duplicatesMerged)
        val all = channels.getAllVisible()
        assertEquals(listOf(1, 2, 3, 4), all.map { it.number })
        val sun = all.first { it.name == "Sun TV" }
        assertEquals(2, channels.getStreams(sun.id).size)
    }

    @Test
    fun channelsAreAssignedToLanguageAndCategoryGroups() = runBlocking {
        val pid = newPlaylist("one")
        syncer.sync(pid, M3uParser.parse(playlistV1), autoNumber = true)

        val tamil = channels.getVisibleInGroup(groupId("Tamil")).map { it.name }
        val news = channels.getVisibleInGroup(groupId("News")).map { it.name }
        val malayalam = channels.getVisibleInGroup(groupId("Malayalam")).map { it.name }

        assertEquals(listOf("Sun TV", "Vijay TV"), tamil)
        assertEquals(listOf("BBC News"), news)
        assertEquals(listOf("Asianet"), malayalam)
    }

    @Test
    fun multiplePlaylistsKeepUniqueNumbers() = runBlocking {
        syncer.sync(newPlaylist("one"), M3uParser.parse(playlistV1), autoNumber = true)
        syncer.sync(
            newPlaylist("two"),
            M3uParser.parse("#EXTM3U\n#EXTINF:-1,DD National\nhttps://example.com/dd.m3u8\n"),
            autoNumber = true,
        )
        val all = channels.getAllVisible()
        assertEquals(5, all.size)
        assertEquals(5, all.mapNotNull { it.number }.toSet().size)
        assertEquals(5, all.first { it.name == "DD National" }.number)
    }

    @Test
    fun refreshPreservesNumbersFavouritesGroupsAndUserStreams() = runBlocking {
        val pid = newPlaylist("one")
        syncer.sync(pid, M3uParser.parse(playlistV1), autoNumber = true)
        val vijay = channels.getAllVisible().first { it.name == "Vijay TV" }
        val bbc = channels.getAllVisible().first { it.name == "BBC News" }
        val custom = (groups.createGroup("My Shows", GroupType.CUSTOM) as GroupResult.Success).groupId

        // User customisations
        assertEquals(NumberAssignResult.Success, channels.assignNumber(vijay.id, 50))
        channels.setFavorite(vijay.id, true)
        groups.addChannels(custom, listOf(vijay.id))
        channels.addStream(vijay.id, "https://backup.example.com/vijay.m3u8", "My backup")
        groups.removeChannel(groupId("News"), bbc.id) // user removed an automatic assignment

        // Refresh: Vijay renamed + new URL, Asianet removed, new channel added.
        val v2 = """
            #EXTM3U
            #EXTINF:-1 tvg-id="sun.in" group-title="Tamil",Sun TV
            https://example.com/sun1.m3u8
            #EXTINF:-1 tvg-id="vijay.in" group-title="Tamil",Star Vijay
            https://example.com/vijay-new.m3u8
            #EXTINF:-1 tvg-id="bbc.uk" group-title="English News",BBC News
            https://example.com/bbc.m3u8
            #EXTINF:-1 tvg-id="zee.in" group-title="Tamil",Zee Tamil
            https://example.com/zee.m3u8
        """.trimIndent()
        val result = syncer.sync(pid, M3uParser.parse(v2), autoNumber = true)

        val refreshed = channels.getChannel(vijay.id)!!
        assertEquals("Star Vijay", refreshed.name)
        assertEquals(50, refreshed.number)
        assertTrue(db.favoriteDao().isFavorite(vijay.id))
        assertTrue(groups.observeAllChannelsInGroup(custom).first().any { it.id == vijay.id })
        val streams = channels.getStreams(vijay.id).map { it.url }
        assertTrue("https://backup.example.com/vijay.m3u8" in streams)
        assertTrue("https://example.com/vijay-new.m3u8" in streams)
        assertFalse("https://example.com/vijay.m3u8" in streams)
        // Removed automatic assignment stays removed
        assertTrue(channels.getVisibleInGroup(groupId("News")).none { it.id == bbc.id })
        // Asianet (not customised) removed, Zee added, Sun's vanished backup stream dropped
        assertEquals(1, result.removedChannels)
        assertEquals(1, result.newChannels)
        assertEquals(1, channels.getStreams(channels.getAllVisible().first { it.name == "Sun TV" }.id).size)
    }

    @Test
    fun customisedChannelMissingFromRefreshIsKept() = runBlocking {
        val pid = newPlaylist("one")
        syncer.sync(pid, M3uParser.parse(playlistV1), autoNumber = true)
        val asianet = channels.getAllVisible().first { it.name == "Asianet" }
        channels.setFavorite(asianet.id, true)

        syncer.sync(pid, M3uParser.parse("#EXTM3U\n#EXTINF:-1 tvg-id=\"sun.in\",Sun TV\nhttps://example.com/sun1.m3u8\n"), autoNumber = true)

        val kept = channels.getChannel(asianet.id)
        assertNotNull(kept)
        assertFalse(kept!!.inSource)
    }

    @Test
    fun duplicateNumbersArePreventedAndCanBeSwapped() = runBlocking {
        syncer.sync(newPlaylist("one"), M3uParser.parse(playlistV1), autoNumber = true)
        val list = channels.getAllVisible()
        val first = list[0]
        val second = list[1]

        val conflict = channels.assignNumber(second.id, first.number)
        assertTrue(conflict is NumberAssignResult.Conflict)
        assertEquals(first.name, (conflict as NumberAssignResult.Conflict).otherChannelName)

        channels.swapNumber(second.id, first.number!!)
        assertEquals(first.number, channels.getChannel(second.id)!!.number)
        assertEquals(second.number, channels.getChannel(first.id)!!.number)

        assertTrue(channels.assignNumber(first.id, 0) is NumberAssignResult.Invalid)
        assertEquals(NumberAssignResult.Success, channels.assignNumber(first.id, 999))
        assertEquals(first.id, channels.getChannelByNumber(999)!!.id)
    }

    @Test
    fun autoNumberingRenumbersEveryChannel() = runBlocking {
        syncer.sync(newPlaylist("one"), M3uParser.parse(playlistV1), autoNumber = false)
        assertTrue(channels.getAllVisible().all { it.number == null })
        channels.autoNumberAll(1)
        assertEquals(listOf(1, 2, 3, 4), channels.getAllVisible().map { it.number })
    }

    @Test
    fun groupCreationRejectsDuplicatesAndSupportsMoving() = runBlocking {
        val kids = groups.createGroup("Cartoons", GroupType.CUSTOM)
        assertTrue(kids is GroupResult.Success)
        assertTrue(groups.createGroup("cartoons", GroupType.CUSTOM) is GroupResult.Error)
        assertTrue(groups.createGroup("   ", GroupType.CUSTOM) is GroupResult.Error)

        syncer.sync(newPlaylist("one"), M3uParser.parse(playlistV1), autoNumber = true)
        val sun = channels.getAllVisible().first { it.name == "Sun TV" }
        val cartoons = (kids as GroupResult.Success).groupId
        groups.moveChannelToGroup(sun.id, groupId("Tamil"), cartoons)

        assertTrue(channels.getVisibleInGroup(cartoons).any { it.id == sun.id })
        assertTrue(channels.getVisibleInGroup(groupId("Tamil")).none { it.id == sun.id })
    }

    @Test
    fun disabledPlaylistHidesItsChannels() = runBlocking {
        val pid = newPlaylist("one")
        syncer.sync(pid, M3uParser.parse(playlistV1), autoNumber = true)
        db.playlistDao().setEnabled(pid, false)
        assertTrue(channels.getAllVisible().isEmpty())
    }
}
