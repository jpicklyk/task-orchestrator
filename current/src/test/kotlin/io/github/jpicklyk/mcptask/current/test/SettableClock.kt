package io.github.jpicklyk.mcptask.current.test

import io.github.jpicklyk.mcptask.current.application.port.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * A [Clock] a test sets and advances explicitly, so claim and lease expiry is tested by moving the clock instead of
 * sleeping. Starts at a fixed instant; reads are millisecond-truncated like the production clock.
 */
class SettableClock(
    start: Instant = Instant.parse("2026-03-01T10:00:00Z")
) : Clock {
    private val current = AtomicReference(start.truncatedTo(ChronoUnit.MILLIS))

    override fun now(): Instant = current.get()

    /** Moves the clock forward by [duration]. */
    fun advance(duration: Duration) {
        current.updateAndGet { it.plus(duration) }
    }

    /** Moves the clock forward by [seconds] seconds. */
    fun advanceSeconds(seconds: Long) = advance(Duration.ofSeconds(seconds))

    /** Sets the clock to [instant]. */
    fun set(instant: Instant) {
        current.set(instant.truncatedTo(ChronoUnit.MILLIS))
    }
}
