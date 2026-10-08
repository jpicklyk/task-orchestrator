package io.github.jpicklyk.mcptask.current.infrastructure.time

import io.github.jpicklyk.mcptask.current.application.port.Clock
import java.time.Instant

/**
 * The production [Clock] for infrastructure and composition code. It delegates to [Clock.SYSTEM], the one
 * implementation, which exists on the port so application-layer defaults can name it without importing
 * infrastructure (layer rules).
 */
object SystemClock : Clock {
    override fun now(): Instant = Clock.SYSTEM.now()
}
