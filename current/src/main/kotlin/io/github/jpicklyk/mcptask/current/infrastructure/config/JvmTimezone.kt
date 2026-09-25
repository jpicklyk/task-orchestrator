package io.github.jpicklyk.mcptask.current.infrastructure.config

import org.slf4j.Logger
import java.util.TimeZone

/**
 * Guards against a non-UTC JVM default timezone corrupting claim-freshness comparisons.
 *
 * Claim TTL/expiry columns are stored via Exposed's `timestamp` type (see `WorkItemsTable.kt`),
 * while some claim SQL uses SQLite's `datetime('now')` (see `SQLiteWorkItemRepository.kt`); both
 * paths assume the JVM default timezone is UTC. The Docker image pins this via a `-Duser.timezone=UTC`
 * JVM flag on the `CMD` (see `Dockerfile`), but a non-Docker launch (`java -jar orchestrator.jar`)
 * has no such flag and would silently run with the host's local timezone, skewing claim-expiry math.
 *
 * [enforceUtc] is the runtime backstop for that case: it detects a non-UTC default and overrides it
 * before any Exposed/DB class can cache the zone. Called as the first statements of
 * `CurrentMain.main()`, before `ShutdownCoordinator`/`CurrentMcpServer` construction.
 *
 * Decision: override + WARN, not warn-only (would leave claim freshness wrong indefinitely) and not
 * fail-fast (would break local dev runs for no gain, since we can trivially fix it in-process).
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
                "JVM default timezone is '{}', not UTC. Overriding to UTC for claim-freshness " +
                    "correctness (claim TTL/expiry comparisons assume a UTC JVM default). Set " +
                    "-Duser.timezone=UTC explicitly to silence this warning.",
                current.id
            )
            TimeZone.setDefault(utc)
            true
        }
    }
}
