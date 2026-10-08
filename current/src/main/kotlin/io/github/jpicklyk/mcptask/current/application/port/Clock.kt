package io.github.jpicklyk.mcptask.current.application.port

import java.time.Instant

/** Source of wall-clock time. A unit of work reads it once per attempt; tests substitute a fixed clock. */
fun interface Clock {
    fun now(): Instant
}
