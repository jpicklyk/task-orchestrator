package io.github.jpicklyk.mcptask.current.infrastructure.time

import io.github.jpicklyk.mcptask.current.application.port.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/** The production [Clock]: the system wall clock, truncated to milliseconds (the persisted precision). */
object SystemClock : Clock {
    override fun now(): Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)
}
