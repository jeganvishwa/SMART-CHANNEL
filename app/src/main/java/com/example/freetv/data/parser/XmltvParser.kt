package com.example.freetv.data.parser

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class XmltvChannel(val id: String, val displayName: String)

data class XmltvProgramme(
    val channelId: String,
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val description: String?,
    val category: String?,
)

/**
 * Streaming XMLTV parser. Programmes are delivered in callbacks so huge guides
 * never have to fit in memory.
 */
class XmltvParser(
    private val defaultZone: ZoneId = ZoneId.systemDefault(),
    private val offsetMinutes: Int = 0,
) {

    fun parse(
        input: InputStream,
        onChannel: (XmltvChannel) -> Unit,
        onProgramme: (XmltvProgramme) -> Unit,
    ) {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        var channelId: String? = null
        var displayName: String? = null

        var progChannel: String? = null
        var progStart: Long? = null
        var progEnd: Long? = null
        var title: String? = null
        var desc: String? = null
        var category: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> {
                        channelId = parser.getAttributeValue(null, "id")
                        displayName = null
                    }
                    "display-name" -> if (channelId != null && displayName == null) {
                        displayName = parser.nextText().trim()
                    }
                    "programme" -> {
                        progChannel = parser.getAttributeValue(null, "channel")
                        progStart = parser.getAttributeValue(null, "start")?.let { parseTime(it) }
                        progEnd = parser.getAttributeValue(null, "stop")?.let { parseTime(it) }
                        title = null; desc = null; category = null
                    }
                    "title" -> if (progChannel != null && title == null) title = parser.nextText().trim()
                    "desc" -> if (progChannel != null && desc == null) desc = parser.nextText().trim()
                    "category" -> if (progChannel != null && category == null) category = parser.nextText().trim()
                }
            } else if (event == XmlPullParser.END_TAG) {
                when (parser.name) {
                    "channel" -> {
                        val id = channelId
                        if (!id.isNullOrBlank()) onChannel(XmltvChannel(id.trim(), displayName ?: id.trim()))
                        channelId = null
                    }
                    "programme" -> {
                        val ch = progChannel
                        val start = progStart
                        val end = progEnd ?: start?.plus(DEFAULT_DURATION_MS)
                        if (!ch.isNullOrBlank() && start != null && end != null && end > start) {
                            onProgramme(
                                XmltvProgramme(
                                    channelId = ch.trim(),
                                    startMs = start,
                                    endMs = end,
                                    title = (title ?: "").take(300).ifEmpty { "No title" },
                                    description = desc?.take(2_000),
                                    category = category?.take(100),
                                ),
                            )
                        }
                        progChannel = null
                    }
                }
            }
            event = parser.next()
        }
    }

    /**
     * Parses XMLTV time "yyyyMMddHHmmss +hhmm" (seconds and offset optional).
     * Without an offset the device zone is used. The user offset is added last.
     */
    fun parseTime(value: String): Long? {
        val trimmed = value.trim()
        val digits = trimmed.takeWhile { it.isDigit() }
        if (digits.length < 12) return null
        val normalized = digits.padEnd(14, '0').take(14)
        val local = try {
            LocalDateTime.parse(normalized, FORMAT)
        } catch (_: Exception) {
            return null
        }
        val zonePart = trimmed.substring(digits.length).trim()
        val epochMs = try {
            if (zonePart.matches(Regex("[+-]\\d{4}"))) {
                val offset = ZoneOffset.of(zonePart.substring(0, 3) + ":" + zonePart.substring(3))
                local.toInstant(offset).toEpochMilli()
            } else if (zonePart.equals("UTC", true) || zonePart.equals("GMT", true) || zonePart == "Z") {
                local.toInstant(ZoneOffset.UTC).toEpochMilli()
            } else {
                local.atZone(defaultZone).toInstant().toEpochMilli()
            }
        } catch (_: Exception) {
            return null
        }
        return epochMs + offsetMinutes * 60_000L
    }

    companion object {
        private val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
        private const val DEFAULT_DURATION_MS = 30 * 60_000L
    }
}
