package io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt

import java.time.Duration
import java.time.Instant

/**
 * Per-key flood limit for a log line (the API JWT rejection INFO line, the claim-defect and pipeline-fault WARN lines): at most [MAX_PER_WINDOW] lines per
 * [WINDOW] per key. A key's window opens at its first rejection when none is open and stays open
 * while `now < start + WINDOW`. Rejections beyond the cap are counted; the next window's first line
 * reports (and resets) that count. Keyed by a bounded, constant-derived key set (never token content), so state is O(1). Thread-safe.
 */
internal class LogFloodLimiter {
    private class State(
        var windowStart: Instant,
        var emitted: Int = 0,
        var suppressed: Int = 0,
    )

    private val states = HashMap<String, State>()

    /** The suppressed count to report on the line to emit, or null when this rejection must not be logged. */
    fun tryAcquire(
        key: String,
        now: Instant
    ): Int? =
        synchronized(states) {
            val state = states.getOrPut(key) { State(now) }
            if (!now.isBefore(state.windowStart.plus(WINDOW))) {
                state.windowStart = now
                state.emitted = 0
            }
            if (state.emitted >= MAX_PER_WINDOW) {
                state.suppressed++
                null
            } else {
                state.emitted++
                val reported = state.suppressed
                state.suppressed = 0
                reported
            }
        }

    private companion object {
        const val MAX_PER_WINDOW = 10
        val WINDOW: Duration = Duration.ofSeconds(60)
    }
}
