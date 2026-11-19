package com.example.freetv.startup

import com.example.freetv.domain.model.StartScreen
import com.example.freetv.domain.model.StartupLanguageMode
import com.example.freetv.preferences.LastWatchedChannel
import com.example.freetv.preferences.RestoreOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StartupEvaluatorTest {

    private val last = LastWatchedChannel(7, "Sun TV", 1, "Tamil", 3, "GROUP", 1_000, 1)

    private fun inputs(
        autoPlay: Boolean = true,
        lastWatched: LastWatchedChannel? = last,
        exists: Boolean = true,
        hidden: Boolean = false,
        playlistEnabled: Boolean = true,
        streams: Int = 2,
        online: Boolean = true,
        startScreen: StartScreen = StartScreen.HOME,
    ) = StartupInputs(autoPlay, startScreen, lastWatched, exists, hidden, playlistEnabled, streams, online)

    @Test
    fun `auto-play on with a valid channel plays it in its group`() {
        val d = StartupEvaluator.decide(inputs())
        assertEquals(AutoPlayTarget(7, "GROUP", 3), d.autoPlay)
        assertEquals(RestoreOutcome.PENDING, d.restoreOutcome)
        assertNull(d.homeMessage)
    }

    @Test
    fun `auto-play off never plays`() {
        val d = StartupEvaluator.decide(inputs(autoPlay = false))
        assertNull(d.autoPlay)
        assertEquals(RestoreOutcome.NOT_ATTEMPTED, d.restoreOutcome)
    }

    @Test
    fun `deleted channel is not played and the user is told`() {
        val d = StartupEvaluator.decide(inputs(exists = false))
        assertNull(d.autoPlay)
        assertEquals(RestoreOutcome.FAILED, d.restoreOutcome)
        assertNotNull(d.homeMessage)
    }

    @Test
    fun `hidden channel, disabled playlist or no stream url are not played`() {
        assertNull(StartupEvaluator.decide(inputs(hidden = true)).autoPlay)
        assertNull(StartupEvaluator.decide(inputs(playlistEnabled = false)).autoPlay)
        assertNull(StartupEvaluator.decide(inputs(streams = 0)).autoPlay)
    }

    @Test
    fun `offline shows home with an offline message`() {
        val d = StartupEvaluator.decide(inputs(online = false))
        assertNull(d.autoPlay)
        assertEquals(StartupEvaluator.OFFLINE_MESSAGE, d.homeMessage)
        assertEquals(RestoreOutcome.FAILED, d.restoreOutcome)
        // Offline with auto-play off still shows the offline notice.
        assertEquals(StartupEvaluator.OFFLINE_MESSAGE, StartupEvaluator.decide(inputs(autoPlay = false, online = false)).homeMessage)
    }

    @Test
    fun `no watched channel yet is not an error`() {
        val d = StartupEvaluator.decide(inputs(lastWatched = null))
        assertNull(d.autoPlay)
        assertEquals(RestoreOutcome.NOT_ATTEMPTED, d.restoreOutcome)
        assertNull(d.homeMessage)
    }

    @Test
    fun `favourites start screen is kept`() {
        assertEquals(true, StartupEvaluator.decide(inputs(startScreen = StartScreen.FAVORITES)).openFavorites)
    }
}

class HomeTabRestorerTest {

    private val tabs = listOf(
        TabCandidate("g:1", "தமிழ் Tamil", true, "Tamil"),
        TabCandidate("g:2", "English", true, "English"),
        TabCandidate("fav", "Favourites", false, null),
        TabCandidate("all", "All channels", false, null),
        TabCandidate("g:5", "News", false, "News"),
    )

    private fun choose(
        lastTab: String? = null,
        lastLanguage: String? = null,
        restoreLanguage: Boolean = true,
        restoreGroup: Boolean = true,
        mode: StartupLanguageMode = StartupLanguageMode.LAST_SELECTED,
        default: String? = null,
        tabList: List<TabCandidate> = tabs,
    ) = HomeTabRestorer.choose(tabList, mode, default, restoreLanguage, restoreGroup, lastTab, lastLanguage, "all")

    @Test
    fun `last group wins when group restore is on`() {
        assertEquals("g:5", choose(lastTab = "g:5", lastLanguage = "g:2").key)
    }

    @Test
    fun `language is restored when group restore is off`() {
        assertEquals("g:2", choose(lastTab = "g:5", lastLanguage = "g:2", restoreGroup = false).key)
    }

    @Test
    fun `default language is used when nothing is remembered`() {
        assertEquals("g:2", choose(default = "g:2").key)
    }

    @Test
    fun `default language mode beats the remembered language`() {
        assertEquals("g:1", choose(lastLanguage = "g:2", default = "g:1", mode = StartupLanguageMode.DEFAULT_LANGUAGE).key)
    }

    @Test
    fun `missing saved language falls back to Tamil with a message`() {
        val c = choose(lastLanguage = "g:99")
        assertEquals("g:1", c.key)
        assertNotNull(c.message)
    }

    @Test
    fun `restore off falls back to Tamil without a message`() {
        val c = choose(lastLanguage = "g:2", restoreLanguage = false, restoreGroup = false)
        assertEquals("g:1", c.key)
        assertNull(c.message)
    }

    @Test
    fun `without language tabs falls back to all channels`() {
        assertEquals("all", choose(tabList = tabs.filter { !it.isLanguage }).key)
    }
}
