package io.github.jpicklyk.mcptask.current.domain.model

import java.time.Instant
import java.util.UUID

/** Where a run is in its lifecycle: open, paused (manually, or by a budget or attempts limit), or closed for good. */
enum class RunStatus(
    val wire: String
) {
    OPEN("open"),
    PAUSED("paused"),
    CLOSED("closed");

    companion object {
        fun fromWire(value: String): RunStatus? = entries.firstOrNull { it.wire == value }
    }
}

/** Why a run is paused. Only [MANUAL] can be resumed; a [BUDGET] or [ATTEMPTS] pause can only be closed. */
enum class RunPauseReason(
    val wire: String
) {
    BUDGET("budget"),
    ATTEMPTS("attempts"),
    MANUAL("manual");

    companion object {
        fun fromWire(value: String): RunPauseReason? = entries.firstOrNull { it.wire == value }
    }
}

/** Why a run closed. */
enum class RunTerminalReason(
    val wire: String
) {
    COMPLETED("completed"),
    CANCELLED("cancelled"),
    FAILED("failed"),
    ABANDONED("abandoned"),
    BUDGET_EXHAUSTED("budget_exhausted");

    companion object {
        fun fromWire(value: String): RunTerminalReason? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * A run budget, or a set of configured budget ceilings. Every field is optional and a null field means unlimited:
 * budgets are opt-in and the server never invents a cap. [costMicroUsd] is in millionths of a US dollar.
 */
data class RunBudget(
    val tokens: Long? = null,
    val costMicroUsd: Long? = null,
    val attemptsPerSeat: Int? = null,
    val maxActiveSeats: Int? = null
) {
    /** True when no field is set (the budget limits nothing). */
    val isUnlimited: Boolean get() = tokens == null && costMicroUsd == null && attemptsPerSeat == null && maxActiveSeats == null
}

/**
 * One run: a bounded, budgeted pass over a set of member items of one root (members live in `run_items`, not here).
 *
 * [hints] are free-form coordinator hints; [marks] are named instants (written by later items). [version] is the
 * optimistic-concurrency counter, 1 at open.
 */
data class Run(
    val id: UUID = UUID.randomUUID(),
    val rootId: UUID,
    val scopeId: UUID? = null,
    val label: String? = null,
    val title: String? = null,
    val status: RunStatus = RunStatus.OPEN,
    val pauseReason: RunPauseReason? = null,
    val pauseDecisionId: UUID? = null,
    val terminalReason: RunTerminalReason? = null,
    val openedBy: String? = null,
    val openedByKind: String? = null,
    val openedAt: Instant,
    val closedBy: String? = null,
    val closedAt: Instant? = null,
    val coordinatorSeenAt: Instant? = null,
    val stallAfterSeconds: Int,
    val budget: RunBudget? = null,
    val reportedTokens: Long = 0,
    val reportedCostMicroUsd: Long = 0,
    val observedTokens: Long = 0,
    val hints: Map<String, String> = emptyMap(),
    val marks: Map<String, Instant> = emptyMap(),
    val version: Long = 1
) {
    init {
        require(label == null || label.length <= MAX_LABEL_LENGTH) { "label must be at most $MAX_LABEL_LENGTH characters" }
        require(title == null || title.length <= MAX_TITLE_LENGTH) { "title must be at most $MAX_TITLE_LENGTH characters" }
        require(stallAfterSeconds > 0) { "stallAfterSeconds must be positive" }
    }

    companion object {
        const val MAX_LABEL_LENGTH = 64
        const val MAX_TITLE_LENGTH = 200

        /** The most member items one run may hold. */
        const val MAX_MEMBERS = 500

        /** The stall threshold when neither the run nor any config layer sets one. */
        const val DEFAULT_STALL_AFTER_SECONDS = 600
    }
}

/** One member that still had an active seat when its run closed, as recorded on `run.ended`. */
data class RunInFlight(
    val itemId: UUID,
    val role: String,
    val seat: String
)

/** What a run close released: cleared claims, released lease rows and ended seats. */
data class RunReleased(
    val claims: Int,
    val leases: Int,
    val seats: Int
) {
    companion object {
        val NONE = RunReleased(0, 0, 0)
    }
}
