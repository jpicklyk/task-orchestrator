package io.github.jpicklyk.mcptask.current.interfaces.mcp

/**
 * Result of [CurrentMcpServer.run] — the testable seam that lets startup failures translate into a
 * non-zero process exit instead of a silent `exit 0` (the bug this type exists to fix: a failed DB
 * init, a failed schema update, or an unrecognized `MCP_TRANSPORT` used to log an error and then
 * `return` from `run()`, leaving the JVM to exit 0 with no server ever having come up).
 *
 * [CurrentMain.main] maps [Failed] to a thrown [StartupFailedException] — never `exitProcess` (that
 * would bypass shutdown hooks) — so the JVM exits non-zero via the normal uncaught-exception path.
 */
sealed interface StartupOutcome

/** The server started, served for its whole lifetime, and shut down cleanly. */
data object Started : StartupOutcome

/**
 * `FLYWAY_REPAIR=true` ran successfully and the process exits 0 WITHOUT ever serving — a success
 * outcome, not a [Failed] one, because a repair-then-exit run completing as intended is not a
 * startup failure (see `CLAUDE.md`'s "run repair and exit" documentation of this env var).
 * [CurrentMcpServer.run] returns this before [ServerComposition] is built and before the readiness
 * marker is written, so the Docker `HEALTHCHECK` never reports healthy for a repair-only run.
 */
data object RepairCompleted : StartupOutcome

/** A startup or shutdown-marker step failed; [reason] is the stable, testable failure category. */
data class Failed(
    val reason: Reason,
    val detail: String
) : StartupOutcome

/** Stable startup-failure categories — see the branch each guards in [CurrentMcpServer.run]. */
enum class Reason {
    DATABASE_INIT,
    SCHEMA_UPDATE,
    UNKNOWN_TRANSPORT,
    READINESS_MARKER,
    TRANSPORT_START,
}

/**
 * Thrown by [CurrentMain.main] when [CurrentMcpServer.run] returns [Failed]. A plain
 * [RuntimeException] so it propagates through main's existing `catch (e: Exception)` / rethrow,
 * which causes the JVM to exit non-zero without calling `exitProcess` (that call would skip
 * registered shutdown hooks).
 */
class StartupFailedException(
    val failure: Failed
) : RuntimeException(failure.detail)
