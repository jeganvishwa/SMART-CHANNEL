package com.example.freetv.player

/** What to do after a playback failure. */
sealed interface FailoverDecision {
    /** Retry the same stream ([attempt] starts at 1). */
    data class Retry(val index: Int, val attempt: Int) : FailoverDecision

    /** Switch to the next backup stream. */
    data class SwitchTo(val index: Int) : FailoverDecision

    /** Every stream has failed; stop (no infinite loops). */
    data object Exhausted : FailoverDecision
}

/**
 * Pure failover policy: each stream is retried [maxRetriesPerStream] times,
 * then the next backup is tried, in priority order, wrapping around from a
 * manually chosen start index. Each stream gets one full pass only.
 */
class StreamFailoverManager(private val maxRetriesPerStream: Int) {

    var streamCount: Int = 0
        private set
    var currentIndex: Int = 0
        private set
    private var startIndex = 0
    private var retries = 0
    private var tried = 0

    /** Starts a new channel. Returns the index to play, or null when there are no streams. */
    fun start(count: Int, preferredIndex: Int = 0): Int? {
        streamCount = count
        if (count <= 0) return null
        startIndex = preferredIndex.coerceIn(0, count - 1)
        currentIndex = startIndex
        retries = 0
        tried = 1
        return currentIndex
    }

    /** User picked a stream manually: restart the cycle from it. */
    fun select(index: Int): Int? = start(streamCount, index)

    /** Playback started successfully – reset retry counters for later errors. */
    fun onSuccess() {
        retries = 0
        tried = 1
        startIndex = currentIndex
    }

    fun onFailure(): FailoverDecision {
        if (streamCount <= 0) return FailoverDecision.Exhausted
        if (retries < maxRetriesPerStream) {
            retries++
            return FailoverDecision.Retry(currentIndex, retries)
        }
        if (tried >= streamCount) return FailoverDecision.Exhausted
        currentIndex = (currentIndex + 1) % streamCount
        retries = 0
        tried++
        return FailoverDecision.SwitchTo(currentIndex)
    }
}
