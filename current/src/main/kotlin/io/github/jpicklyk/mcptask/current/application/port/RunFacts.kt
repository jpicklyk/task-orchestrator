package io.github.jpicklyk.mcptask.current.application.port

import io.github.jpicklyk.mcptask.current.domain.model.RunStatus
import io.github.jpicklyk.mcptask.current.domain.model.RunTerminalReason
import java.time.Instant
import java.util.UUID

/** The header of one run as the rollup shows it: lifecycle state, open and close instants, and its project root. */
data class RunHeader(
    val status: RunStatus,
    val terminalReason: RunTerminalReason?,
    val openedAt: Instant,
    val closedAt: Instant?,
    val rootId: UUID
)

/** The open decision requests of one run: [open] in all, and the same count by decision class and by severity. */
data class DecisionCounts(
    val open: Int,
    val byClass: Map<String, Int>,
    val bySeverity: Map<String, Int>
)

/** One seat that has gone silent: its [seat] name, the [itemId] it holds, and how long it has been silent. */
data class StalledSeat(
    val seat: String,
    val itemId: UUID,
    val silentMs: Long
)

/**
 * The seats of one run: [active] seats, up to the requested number of [stalled] entries, and [count], the true number of
 * stalled seats (which may exceed the entries returned).
 */
data class StalledSeats(
    val active: Int,
    val stalled: List<StalledSeat>,
    val count: Int
)

/** The attempts of one run: [total] attempts over every seat, and [max], the most any one seat made. */
data class AttemptCounts(
    val total: Int,
    val max: Int
)

/** The budget of one run: [used] against [limit] (null when unlimited), and the [basis] the figure is enforced on. */
data class BudgetFacts(
    val used: Long,
    val limit: Long?,
    val basis: String
)

/**
 * The run-only facts the rollup reads (plan section 3.4a): the seam between the report side (W6, which owns the
 * assembler and the query side) and the run side (W5, which owns run membership and every run-only fact).
 *
 * Every method answers null when the fact is not tracked, and the rollup then omits the section it feeds. R16 ships only
 * [io.github.jpicklyk.mcptask.current.application.report.NotTrackedRunFacts], which answers null everywhere; R28
 * implements this port over the W5 tables. An implementation joins the caller's read unit.
 */
interface RunFacts {
    /** The header of run [runId], or null when not tracked or there is no such run. */
    suspend fun run(runId: UUID): RunHeader?

    /** The member item ids of run [runId], or null when not tracked. */
    suspend fun members(runId: UUID): Set<UUID>?

    /** The blocked items among [itemIds] counted by blocked reason, or null when not tracked. */
    suspend fun blockedReasons(itemIds: Set<UUID>): Map<String, Int>?

    /** The open decision requests of run [runId], or null when not tracked. */
    suspend fun openDecisions(runId: UUID): DecisionCounts?

    /** The seats of run [runId] with at most [limit] stalled entries, or null when not tracked. */
    suspend fun stalledSeats(
        runId: UUID,
        limit: Int
    ): StalledSeats?

    /** The attempts of run [runId], or null when not tracked. */
    suspend fun attempts(runId: UUID): AttemptCounts?

    /** The budget of run [runId], or null when not tracked. */
    suspend fun budget(runId: UUID): BudgetFacts?
}
