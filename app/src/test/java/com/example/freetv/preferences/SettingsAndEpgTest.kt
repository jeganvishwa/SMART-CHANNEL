package com.example.freetv.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.freetv.data.parser.XmltvChannel
import com.example.freetv.data.parser.XmltvParser
import com.example.freetv.data.parser.XmltvProgramme
import com.example.freetv.domain.model.StartupLanguageMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

class LanguagePersistenceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun open(file: File, scope: CoroutineScope) =
        SettingsDataStore(PreferenceDataStoreFactory.create(scope = scope) { file })

    @Test
    fun `selected language survives an app restart`() = runBlocking {
        val file = File(tmp.root, "settings.preferences_pb")

        val firstScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val first = open(file, firstScope)
        first.setLastSelectedTab("g:1") // e.g. Tamil
        first.setDefaultLanguageGroupId(2)
        first.setStartupLanguageMode(StartupLanguageMode.LAST_SELECTED)
        firstScope.cancel() // "close the app"

        val secondScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val restored = open(file, secondScope).current()
        secondScope.cancel()

        assertEquals("g:1", restored.lastSelectedTab)
        assertEquals(2L, restored.defaultLanguageGroupId)
        assertEquals(StartupLanguageMode.LAST_SELECTED, restored.startupLanguageMode)
    }

    @Test
    fun `values are clamped to safe ranges`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = open(File(tmp.root, "clamp.preferences_pb"), scope)
        store.setChannelNumberDigits(9)
        store.setNumericInputTimeoutMs(50)
        store.setMaxRetriesPerStream(100)
        val s = store.current()
        scope.cancel()
        assertEquals(5, s.channelNumberDigits)
        assertEquals(500L, s.numericInputTimeoutMs)
        assertEquals(5, s.maxRetriesPerStream)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class XmltvParserTest {

    private val xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <tv>
          <channel id="sun.in"><display-name>Sun TV</display-name></channel>
          <programme start="20250101060000 +0530" stop="20250101070000 +0530" channel="sun.in">
            <title>Morning News</title><desc>Headlines</desc><category>News</category>
          </programme>
          <programme start="20250101070000 +0530" channel="sun.in"><title>Serial</title></programme>
          <programme start="bad" stop="bad" channel="sun.in"><title>Broken</title></programme>
        </tv>
    """.trimIndent()

    @Test
    fun parsesChannelsProgrammesAndTimeZones() {
        val channels = mutableListOf<XmltvChannel>()
        val programmes = mutableListOf<XmltvProgramme>()
        XmltvParser(ZoneId.of("UTC")).parse(xml.byteInputStream(), { channels += it }, { programmes += it })

        assertEquals(listOf(XmltvChannel("sun.in", "Sun TV")), channels)
        assertEquals(2, programmes.size)
        val expectedStart = ZonedDateTime.of(2025, 1, 1, 0, 30, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals(expectedStart, programmes[0].startMs)
        assertEquals(expectedStart + 3_600_000, programmes[0].endMs)
        assertEquals("Morning News", programmes[0].title)
        assertEquals("Headlines", programmes[0].description)
        // Missing stop → default 30 minutes
        assertEquals(programmes[1].startMs + 30 * 60_000, programmes[1].endMs)
    }

    @Test
    fun appliesUserOffsetAndDefaultZone() {
        val parser = XmltvParser(ZoneId.of("Asia/Kolkata"), offsetMinutes = 30)
        val noZone = parser.parseTime("20250101060000")!!
        val expected = ZonedDateTime.of(2025, 1, 1, 6, 0, 0, 0, ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli() + 30 * 60_000
        assertEquals(expected, noZone)
    }
}
