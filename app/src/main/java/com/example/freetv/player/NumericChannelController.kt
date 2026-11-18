package com.example.freetv.player

import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Collects remote-control digits ("0", "02", "025"…) and commits the number
 * after [timeoutMs] of inactivity or as soon as [maxDigits] digits are typed.
 * Leading zeros are allowed (001 → 1).
 */
class NumericChannelController(
    private val scope: CoroutineScope,
    private val timeoutMs: () -> Long,
    private val maxDigits: () -> Int,
    private val onCommit: (Int) -> Unit,
) {
    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    private var commitJob: Job? = null

    fun onDigit(digit: Int) {
        require(digit in 0..9)
        val next = (_input.value + digit).takeLast(MAX_BUFFER)
        _input.value = next
        commitJob?.cancel()
        if (next.length >= maxDigits().coerceAtLeast(1)) {
            commitNow()
        } else {
            commitJob = scope.launch {
                delay(timeoutMs())
                commitNow()
            }
        }
    }

    fun commitNow() {
        commitJob?.cancel()
        commitJob = null
        val value = _input.value
        _input.value = ""
        val number = value.toIntOrNull() ?: return
        if (number > 0) onCommit(number)
    }

    fun clear() {
        commitJob?.cancel()
        commitJob = null
        _input.value = ""
    }

    val isActive: Boolean get() = _input.value.isNotEmpty()

    companion object {
        private const val MAX_BUFFER = 6

        /** Maps a key code (top-row or numpad digit) to 0–9, or null. */
        fun digitForKeyCode(keyCode: Int): Int? = when (keyCode) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> keyCode - KeyEvent.KEYCODE_0
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> keyCode - KeyEvent.KEYCODE_NUMPAD_0
            else -> null
        }
    }
}
