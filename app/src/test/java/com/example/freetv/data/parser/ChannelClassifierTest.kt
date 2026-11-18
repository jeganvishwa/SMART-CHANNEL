package com.example.freetv.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelClassifierTest {

    @Test
    fun `stable key prefers tvg id and normalises names`() {
        assertEquals("id:suntv.in", ChannelClassifier.stableKey(" SunTV.IN ", "Sun TV"))
        assertEquals("name:sun tv hd", ChannelClassifier.stableKey(null, "  Sun   TV HD "))
        assertEquals("name:sun tv hd", ChannelClassifier.stableKey("", "sun tv hd"))
    }

    @Test
    fun `duplicates are merged into one channel with backup streams`() {
        val entries = listOf(
            M3uEntry(name = "Sun TV", url = "https://a/1.m3u8", tvgId = "sun.in"),
            M3uEntry(name = "Sun TV SD", url = "https://a/2.m3u8", tvgId = "sun.in"),
            M3uEntry(name = "Sun TV", url = "https://a/1.m3u8", tvgId = "sun.in"), // exact duplicate URL
            M3uEntry(name = "Vijay", url = "https://a/3.m3u8"),
        )

        val (merged, duplicates) = ChannelClassifier.merge(entries)

        assertEquals(2, merged.size)
        assertEquals(2, duplicates)
        val sun = merged.first()
        assertEquals("Sun TV", sun.primary.name)
        assertEquals(listOf("https://a/1.m3u8", "https://a/2.m3u8"), sun.streams.map { it.url })
    }

    @Test
    fun `keyword matching uses whole words`() {
        assertTrue(ChannelClassifier.matches("Tamil", "tamil,தமிழ்", null, "Tamil Movies", "KTV"))
        assertTrue(ChannelClassifier.matches("Tamil", "tamil,தமிழ்", null, "தமிழ்", "X"))
        assertTrue(ChannelClassifier.matches("News", "news", null, null, "Sun News"))
        assertFalse(ChannelClassifier.matches("English", "english,eng", null, "Engineering", "Engine TV"))
        assertTrue(ChannelClassifier.matches("Hindi", "hindi", "hin;hindi", null, "Aaj"))
    }

    @Test
    fun `language detection falls back to group keywords`() {
        val languages = mapOf("Tamil" to "tamil", "Malayalam" to "malayalam")
        assertEquals("Malayalam", ChannelClassifier.detectLanguage(null, "Malayalam Entertainment", "Asianet", languages))
        assertEquals("ta", ChannelClassifier.detectLanguage("ta", "Malayalam", "X", languages))
        assertEquals(null, ChannelClassifier.detectLanguage(null, "Sports", "Star", languages))
    }
}
