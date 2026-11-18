package com.example.freetv.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlSanitizerTest {

    @Test
    fun `valid http and https urls are accepted`() {
        assertTrue(UrlSanitizer.isValidRemoteUrl("https://example.com/list.m3u"))
        assertTrue(UrlSanitizer.isValidRemoteUrl("http://192.168.1.10:8080/tv.m3u8"))
        assertTrue(UrlSanitizer.isValidRemoteUrl("  https://example.com/a.m3u  "))
        assertTrue(UrlSanitizer.isValidRemoteUrl("http://user:pw@example.com/get.php"))
    }

    @Test
    fun `invalid urls are rejected`() {
        assertFalse(UrlSanitizer.isValidRemoteUrl(null))
        assertFalse(UrlSanitizer.isValidRemoteUrl(""))
        assertFalse(UrlSanitizer.isValidRemoteUrl("example.com/list.m3u"))
        assertFalse(UrlSanitizer.isValidRemoteUrl("ftp://example.com/list.m3u"))
        assertFalse(UrlSanitizer.isValidRemoteUrl("javascript://example.com"))
        assertFalse(UrlSanitizer.isValidRemoteUrl("https:///nohost"))
        assertFalse(UrlSanitizer.isValidRemoteUrl("https://exa mple.com/x"))
    }

    @Test
    fun `user info is redacted`() {
        assertEquals(
            "http://***@example.com/get.php",
            UrlSanitizer.redact("http://alice:secret@example.com/get.php"),
        )
    }

    @Test
    fun `sensitive query parameters are redacted and others kept`() {
        assertEquals(
            "http://h.tv/get.php?username=***&password=***&type=m3u_plus",
            UrlSanitizer.redact("http://h.tv/get.php?username=bob&password=pw1&type=m3u_plus"),
        )
        assertEquals(
            "https://h.tv/live.m3u8?Token=***#x",
            UrlSanitizer.redact("https://h.tv/live.m3u8?Token=abc#x"),
        )
    }

    @Test
    fun `urls without secrets are unchanged`() {
        val url = "https://example.com/playlist.m3u?lang=ta"
        assertEquals(url, UrlSanitizer.redact(url))
    }
}
