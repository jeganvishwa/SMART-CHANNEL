package com.example.freetv.player

import android.view.KeyEvent
import androidx.media3.common.MimeTypes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamFailoverManagerTest {

    @Test
    fun `retries each stream then switches to backups and stops`() {
        val manager = StreamFailoverManager(maxRetriesPerStream = 1)
        assertEquals(0, manager.start(3))

        assertEquals(FailoverDecision.Retry(0, 1), manager.onFailure())
        assertEquals(FailoverDecision.SwitchTo(1), manager.onFailure())
        assertEquals(FailoverDecision.Retry(1, 1), manager.onFailure())
        assertEquals(FailoverDecision.SwitchTo(2), manager.onFailure())
        assertEquals(FailoverDecision.Retry(2, 1), manager.onFailure())
        assertEquals(FailoverDecision.Exhausted, manager.onFailure())
        // No infinite loop: stays exhausted.
        assertEquals(FailoverDecision.Exhausted, manager.onFailure())
    }

    @Test
    fun `manual selection wraps around the list once`() {
        val manager = StreamFailoverManager(maxRetriesPerStream = 0)
        manager.start(3)
        assertEquals(2, manager.select(2))
        assertEquals(FailoverDecision.SwitchTo(0), manager.onFailure())
        assertEquals(FailoverDecision.SwitchTo(1), manager.onFailure())
        assertEquals(FailoverDecision.Exhausted, manager.onFailure())
    }

    @Test
    fun `success resets the cycle`() {
        val manager = StreamFailoverManager(maxRetriesPerStream = 0)
        manager.start(2)
        assertEquals(FailoverDecision.SwitchTo(1), manager.onFailure())
        manager.onSuccess()
        assertEquals(FailoverDecision.SwitchTo(0), manager.onFailure())
        assertEquals(FailoverDecision.Exhausted, manager.onFailure())
    }

    @Test
    fun `no streams means exhausted`() {
        val manager = StreamFailoverManager(2)
        assertNull(manager.start(0))
        assertEquals(FailoverDecision.Exhausted, manager.onFailure())
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class NumericChannelControllerTest {

    @Test
    fun `digits commit after timeout`() = runTest {
        val committed = mutableListOf<Int>()
        val controller = NumericChannelController(this, { 2_000 }, { 3 }) { committed += it }

        controller.onDigit(2)
        controller.onDigit(5)
        assertEquals("25", controller.input.value)
        advanceTimeBy(1_999)
        runCurrent()
        assertTrue(committed.isEmpty())
        advanceTimeBy(2)
        runCurrent()

        assertEquals(listOf(25), committed)
        assertEquals("", controller.input.value)
    }

    @Test
    fun `reaching max digits commits immediately and leading zeros are ignored`() = runTest {
        val committed = mutableListOf<Int>()
        val controller = NumericChannelController(this, { 2_000 }, { 3 }) { committed += it }

        controller.onDigit(0)
        controller.onDigit(0)
        controller.onDigit(1)

        assertEquals(listOf(1), committed)
        assertEquals("", controller.input.value)
    }

    @Test
    fun `multi digit numbers like 125 work`() = runTest {
        val committed = mutableListOf<Int>()
        val controller = NumericChannelController(this, { 1_000 }, { 4 }) { committed += it }
        listOf(1, 2, 5).forEach(controller::onDigit)
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(listOf(125), committed)
    }

    @Test
    fun `zero alone and clear do not commit`() = runTest {
        val committed = mutableListOf<Int>()
        val controller = NumericChannelController(this, { 500 }, { 3 }) { committed += it }
        controller.onDigit(0)
        advanceTimeBy(600)
        runCurrent()
        controller.onDigit(7)
        controller.clear()
        advanceTimeBy(600)
        runCurrent()
        assertTrue(committed.isEmpty())
    }

    @Test
    fun `key codes map to digits`() {
        assertEquals(0, NumericChannelController.digitForKeyCode(KeyEvent.KEYCODE_0))
        assertEquals(9, NumericChannelController.digitForKeyCode(KeyEvent.KEYCODE_9))
        assertEquals(5, NumericChannelController.digitForKeyCode(KeyEvent.KEYCODE_NUMPAD_5))
        assertNull(NumericChannelController.digitForKeyCode(KeyEvent.KEYCODE_DPAD_UP))
    }
}

class MimeTypeDetectionTest {
    @Test
    fun `detects hls and dash from url`() {
        assertEquals(MimeTypes.APPLICATION_M3U8, ExoPlayerManager.mimeTypeFor("https://x/live/index.m3u8?token=1", false))
        assertEquals(MimeTypes.APPLICATION_MPD, ExoPlayerManager.mimeTypeFor("https://x/manifest.mpd", false))
        assertNull(ExoPlayerManager.mimeTypeFor("http://x/live/123", false))
        assertEquals(MimeTypes.APPLICATION_M3U8, ExoPlayerManager.mimeTypeFor("http://x/live/123", true))
    }
}
