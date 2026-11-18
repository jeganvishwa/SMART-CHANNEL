package com.example.freetv.data.parser

import java.io.BufferedReader
import java.io.Reader

/** One channel entry found in an M3U file. */
data class M3uEntry(
    val name: String,
    val url: String,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val tvgLogo: String? = null,
    val groupTitle: String? = null,
    val language: String? = null,
    val userAgent: String? = null,
    val referrer: String? = null,
    /** All other #EXTINF attributes (lower-case keys). */
    val attributes: Map<String, String> = emptyMap(),
)

data class M3uPlaylist(
    /** Attributes of the #EXTM3U header line, e.g. url-tvg. */
    val headerAttributes: Map<String, String>,
    val entries: List<M3uEntry>,
    /** Lines that looked like entries but had no usable URL or an unsupported scheme. */
    val invalidEntries: Int,
) {
    val epgUrl: String?
        get() = (headerAttributes["url-tvg"] ?: headerAttributes["x-tvg-url"])
            ?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
}

class InvalidPlaylistException(message: String) : Exception(message)

/**
 * Streaming, tolerant M3U / M3U8 (extended M3U) parser.
 *
 * Handles: quoted attributes (single or double quotes) containing commas,
 * unquoted attributes, missing logos / groups / names, #EXTGRP, #EXTVLCOPT
 * user-agent and referrer, Windows line endings, UTF-8 BOM, plain URL lists.
 * Untrusted text is trimmed, stripped of control characters and length-capped.
 */
object M3uParser {

    private const val MAX_NAME = 200
    private const val MAX_ATTR = 2_048
    private const val MAX_URL = 4_096
    private val SUPPORTED_SCHEMES = setOf("http", "https")

    fun parse(text: String): M3uPlaylist = parse(text.reader())

    fun parse(reader: Reader): M3uPlaylist {
        val buffered = reader as? BufferedReader ?: BufferedReader(reader)
        val header = mutableMapOf<String, String>()
        val entries = ArrayList<M3uEntry>()
        var invalid = 0
        var sawHeader = false
        var sawAnyContent = false

        var pendingInfo: ExtInf? = null
        var pendingGroup: String? = null
        var pendingUserAgent: String? = null
        var pendingReferrer: String? = null

        var firstLine = true
        while (true) {
            var line = buffered.readLine() ?: break
            if (firstLine) {
                line = line.removePrefix("﻿")
                firstLine = false
            }
            line = line.trim()
            if (line.isEmpty()) continue
            sawAnyContent = true

            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    sawHeader = true
                    header.putAll(parseAttributes(line.substring(7)).first)
                }

                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    if (pendingInfo != null) invalid++ // previous #EXTINF had no URL
                    pendingInfo = parseExtInf(line.substring(8))
                }

                line.startsWith("#EXTGRP:", ignoreCase = true) ->
                    pendingGroup = clean(line.substring(8), MAX_NAME)

                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val option = line.substring(11)
                    val key = option.substringBefore('=').trim().lowercase()
                    val value = clean(option.substringAfter('=', ""), MAX_ATTR)
                    when (key) {
                        "http-user-agent" -> pendingUserAgent = value
                        "http-referrer", "http-referer" -> pendingReferrer = value
                    }
                }

                line.startsWith("#") -> Unit // other directives (#KODIPROP, #EXTVLCOPT others, comments)

                else -> {
                    val url = line.take(MAX_URL)
                    if (!isSupportedUrl(url)) {
                        invalid++
                    } else {
                        val info = pendingInfo
                        val attrs = info?.attributes.orEmpty()
                        val tvgName = attrs["tvg-name"]
                        val name = info?.title?.takeIf { it.isNotEmpty() }
                            ?: tvgName?.takeIf { it.isNotEmpty() }
                            ?: nameFromUrl(url)
                        entries += M3uEntry(
                            name = name,
                            url = url,
                            tvgId = attrs["tvg-id"]?.takeIf { it.isNotEmpty() },
                            tvgName = tvgName?.takeIf { it.isNotEmpty() },
                            tvgLogo = (attrs["tvg-logo"] ?: attrs["logo"])?.takeIf { isSupportedUrl(it) },
                            groupTitle = (attrs["group-title"] ?: pendingGroup)?.takeIf { it.isNotEmpty() },
                            language = (attrs["tvg-language"] ?: attrs["language"])?.takeIf { it.isNotEmpty() },
                            userAgent = attrs["user-agent"] ?: attrs["http-user-agent"] ?: pendingUserAgent,
                            referrer = attrs["http-referrer"] ?: attrs["referrer"] ?: pendingReferrer,
                            attributes = attrs - KNOWN_KEYS,
                        )
                    }
                    pendingInfo = null
                    pendingGroup = null
                    pendingUserAgent = null
                    pendingReferrer = null
                }
            }
        }
        if (pendingInfo != null) invalid++

        if (!sawHeader && entries.isEmpty()) {
            throw InvalidPlaylistException(
                if (sawAnyContent) "This is not an M3U playlist (no #EXTM3U header and no stream URLs)."
                else "The playlist is empty.",
            )
        }
        return M3uPlaylist(header, entries, invalid)
    }

    private val KNOWN_KEYS = setOf(
        "tvg-id", "tvg-name", "tvg-logo", "logo", "group-title", "tvg-language", "language",
        "user-agent", "http-user-agent", "http-referrer", "referrer",
    )

    private class ExtInf(val attributes: Map<String, String>, val title: String)

    /** Parses `-1 key="value" key2=value2,Display Name`. */
    private fun parseExtInf(body: String): ExtInf {
        val (attributes, rest) = parseAttributes(body, skipDuration = true)
        return ExtInf(attributes, clean(rest ?: "", MAX_NAME))
    }

    /**
     * Reads `key=value` pairs until the first comma that is outside quotes.
     * Returns the attributes and the text after that comma (the title), if any.
     */
    internal fun parseAttributes(input: String, skipDuration: Boolean = false): Pair<Map<String, String>, String?> {
        val attrs = LinkedHashMap<String, String>()
        var i = 0
        val n = input.length

        if (skipDuration) {
            while (i < n && input[i].isWhitespace()) i++
            // duration token: -1, 0, 123.4 …
            while (i < n && !input[i].isWhitespace() && input[i] != ',') i++
        }

        while (i < n) {
            val c = input[i]
            when {
                c.isWhitespace() -> i++
                c == ',' -> return attrs to input.substring(i + 1)
                else -> {
                    val keyStart = i
                    while (i < n && input[i] != '=' && input[i] != ',' && !input[i].isWhitespace()) i++
                    val key = input.substring(keyStart, i).lowercase()
                    if (i < n && input[i] == '=') {
                        i++ // '='
                        val value: String
                        if (i < n && (input[i] == '"' || input[i] == '\'')) {
                            val quote = input[i]
                            val end = input.indexOf(quote, i + 1)
                            if (end == -1) {
                                value = input.substring(i + 1)
                                i = n
                            } else {
                                value = input.substring(i + 1, end)
                                i = end + 1
                            }
                        } else {
                            val start = i
                            while (i < n && !input[i].isWhitespace() && input[i] != ',') i++
                            value = input.substring(start, i)
                        }
                        if (key.isNotEmpty()) attrs[key] = clean(value, MAX_ATTR)
                    }
                    // a bare word without '=' is ignored
                }
            }
        }
        return attrs to null
    }

    fun isSupportedUrl(url: String): Boolean {
        val scheme = url.substringBefore("://", "").lowercase()
        return scheme in SUPPORTED_SCHEMES && url.length > scheme.length + 3 && url.none { it.isWhitespace() }
    }

    private fun nameFromUrl(url: String): String {
        val path = url.substringBefore('?').substringBefore('#').trimEnd('/')
        val last = path.substringAfterLast('/')
        val base = last.substringBeforeLast('.').ifEmpty { last }
        return clean(base.ifEmpty { url.substringAfter("://").substringBefore('/') }, MAX_NAME)
    }

    private fun clean(value: String, max: Int): String =
        value.filterNot { it.isISOControl() }
            .replace("&amp;", "&")
            .trim()
            .take(max)
}
