package io.github.jpicklyk.mcptask.current.infrastructure.time

import io.github.jpicklyk.mcptask.current.application.port.Clock
import java.time.Instant

/** The production [Clock]: the system wall clock. */
object SystemClock : Clock {
    override fun now(): Instant = Instant.now()
}
