package com.example.freetv.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {

    @Test
    fun `parses standard extended m3u with quoted attributes`() {
        val text = """
            #EXTM3U url-tvg="https://epg.example.com/guide.xml.gz"
            #EXTINF:-1 tvg-id="suntv.in" tvg-name="Sun TV" tvg-logo="https://logo.example.com/sun.png" group-title="Tamil",Sun TV HD
            https://example.com/sun/index.m3u8
            #EXTINF:-1 tvg-id="bbc.uk" group-title="News",BBC News
            http://example.com/bbc.m3u8
        """.trimIndent()

        val result = M3uParser.parse(text)

        assertEquals(2, result.entries.size)
        assertEquals("https://epg.example.com/guide.xml.gz", result.epgUrl)
        val sun = result.entries[0]
        assertEquals("Sun TV HD", sun.name)
        assertEquals("suntv.in", sun.tvgId)
        assertEquals("Sun TV", sun.tvgName)
        assertEquals("https://logo.example.com/sun.png", sun.tvgLogo)
        assertEquals("Tamil", sun.groupTitle)
        assertEquals("https://example.com/sun/index.m3u8", sun.url)
        assertEquals(0, result.invalidEntries)
    }

    @Test
    fun `metadata containing commas inside quotes does not break the name`() {
        val text = """
            #EXTM3U
            #EXTINF:-1 tvg-name="News, Live" group-title="News, World",World News, 24x7
            https://example.com/world.m3u8
        """.trimIndent()

        val entry = M3uParser.parse(text).entries.single()

        assertEquals("World News, 24x7", entry.name)
        assertEquals("News, Live", entry.tvgName)
        assertEquals("News, World", entry.groupTitle)
    }

    @Test
    fun `missing logo, group and name are handled`() {
        val text = """
            #EXTM3U
            #EXTINF:-1,
            https://example.com/live/channel42.m3u8
            #EXTINF:-1 tvg-name="Only TVG Name",
            https://example.com/x.m3u8
        """.trimIndent()

        val entries = M3uParser.parse(text).entries

        assertEquals("channel42", entries[0].name)
        assertNull(entries[0].tvgLogo)
        assertNull(entries[0].groupTitle)
        assertEquals("Only TVG Name", entries[1].name)
    }

    @Test
    fun `invalid entries are counted and skipped`() {
        val text = """
            #EXTM3U
            #EXTINF:-1,No URL follows
            #EXTINF:-1,Bad scheme
            ftp://example.com/stream
            #EXTINF:-1,Good
            https://example.com/good.m3u8
        """.trimIndent()

        val result = M3uParser.parse(text)

        assertEquals(1, result.entries.size)
        assertEquals("Good", result.entries.single().name)
        assertEquals(2, result.invalidEntries)
    }

    @Test
    fun `utf8 names, BOM, CRLF and unquoted attributes`() {
        val text = "﻿#EXTM3U\r\n#EXTINF:-1 tvg-id=kalaignar.in group-title=\"தமிழ்\",கலைஞர் டிவி\r\nhttps://example.com/k.m3u8\r\n"

        val entry = M3uParser.parse(text).entries.single()

        assertEquals("கலைஞர் டிவி", entry.name)
        assertEquals("kalaignar.in", entry.tvgId)
        assertEquals("தமிழ்", entry.groupTitle)
    }

    @Test
    fun `extgrp and extvlcopt are applied to the next entry`() {
        val text = """
            #EXTM3U
            #EXTINF:-1,Music One
            #EXTGRP:Music
            #EXTVLCOPT:http-user-agent=MyPlayer/1.0
            #EXTVLCOPT:http-referrer=https://site.example.com/
            https://example.com/music.m3u8
        """.trimIndent()

        val entry = M3uParser.parse(text).entries.single()

        assertEquals("Music", entry.groupTitle)
        assertEquals("MyPlayer/1.0", entry.userAgent)
        assertEquals("https://site.example.com/", entry.referrer)
    }

    @Test
    fun `plain url list without header is accepted`() {
        val result = M3uParser.parse("https://example.com/a.m3u8\nhttps://example.com/b.m3u8\n")
        assertEquals(2, result.entries.size)
    }

    @Test
    fun `non playlist content is rejected`() {
        assertThrows(InvalidPlaylistException::class.java) { M3uParser.parse("<html><body>Not found</body></html>") }
        assertThrows(InvalidPlaylistException::class.java) { M3uParser.parse("") }
    }

    @Test
    fun `control characters are stripped from untrusted metadata`() {
        val text = "#EXTM3U\n#EXTINF:-1 tvg-name=\"A\u0007B\",Evil\u0000Name\nhttps://example.com/x.m3u8\n"
        val entry = M3uParser.parse(text).entries.single()
        assertEquals("EvilName", entry.name)
        assertTrue(entry.tvgName!!.none { it.isISOControl() })
    }
}
