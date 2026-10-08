package io.github.jpicklyk.mcptask.current.application.port

import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.coroutines.coroutineContext

/** Source of wall-clock time. A unit of work reads it once per attempt; tests substitute a fixed clock. */
fun interface Clock {
    fun now(): Instant

    companion object {
        /** The system wall clock at millisecond precision (the precision persisted timestamps carry). */
        val SYSTEM: Clock = Clock { Instant.now().truncatedTo(ChronoUnit.MILLIS) }
    }
}

/**
 * The instant every store decision in the current unit of work must use: the ambient unit's `now` (read once
 * per attempt, so every claim, lease and filter in the unit agrees), else [Clock.now] when called outside a
 * unit. Stores and application callers use ONLY this for "now"; nothing reads the database clock.
 */
suspend fun Clock.unitNow(): Instant = coroutineContext[UnitElement]?.unit?.now ?: now()
