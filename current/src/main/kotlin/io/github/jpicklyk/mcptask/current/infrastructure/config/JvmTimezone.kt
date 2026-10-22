package io.github.jpicklyk.mcptask.current.infrastructure.config

import org.slf4j.Logger
import java.util.TimeZone

/**
 * Guards against a non-UTC JVM default timezone leaking into anything the server does with time.
 *
 * Correctness no longer depends on it: every persisted timestamp is formatted and parsed by
 * `UtcTimestampColumnType` as canonical UTC text with an explicit UTC offset, and claim and lease
 * expiry are computed in Kotlin from the bound `Clock` (no database clock is read). The guard stays
 * as defense in depth, so logs, third-party libraries and any future code that uses the default zone
 * agree with the stored data. The Docker image pins `-Duser.timezone=UTC` on the `CMD` (see
 * `Dockerfile`); a non-Docker launch (`java -jar orchestrator.jar`) has no such flag.
 *
 * [enforceUtc] detects a non-UTC default and overrides it before any Exposed/DB class can cache the
 * zone. Called as the first statements of `CurrentMain.main()`, before `ShutdownCoordinator` /
 * `CurrentMcpServer` construction.
 *
 * Decision: override + WARN, not warn-only (would leave the process zone inconsistent indefinitely)
 * and not fail-fast (would break local dev runs for no gain, since we can trivially fix it in-process).
 */
object JvmTimezone {
    /** Timezone IDs treated as equivalent to UTC — no override or warning for any of these. */
    private val utcEquivalentIds = setOf("UTC", "Etc/UTC", "GMT", "Z")

    /**
     * Checks the JVM's default timezone and overrides it to UTC (logging a WARN naming the
     * detected zone) if it is not already UTC-equivalent.
     *
     * @return true if the default timezone was non-UTC and has been overridden; false if it was
     *   already UTC-equivalent (no change made, no warning logged).
     */
    fun enforceUtc(logger: Logger): Boolean {
        val current = TimeZone.getDefault()
        val utc = TimeZone.getTimeZone("UTC")

        // Compare by ID against the known UTC-equivalent set rather than by offset alone: a zone
        // with a zero raw offset but DST rules (unlikely, but not impossible) should still be
        // treated as non-UTC since its offset can drift from zero.
        return if (current.id in utcEquivalentIds) {
            false
        } else {
            logger.warn(
                "JVM default timezone is '{}', not UTC. Overriding to UTC so process-level time handling " +
                    "agrees with the stored UTC timestamps. Set -Duser.timezone=UTC explicitly to silence this warning.",
                current.id
            )
            TimeZone.setDefault(utc)
            true
        }
    }
}
