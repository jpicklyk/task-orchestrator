package io.github.jpicklyk.mcptask.current.application.report

import io.github.jpicklyk.mcptask.current.application.port.AttemptCounts
import io.github.jpicklyk.mcptask.current.application.port.BudgetFacts
import io.github.jpicklyk.mcptask.current.application.port.DecisionCounts
import io.github.jpicklyk.mcptask.current.application.port.RunFacts
import io.github.jpicklyk.mcptask.current.application.port.RunHeader
import io.github.jpicklyk.mcptask.current.application.port.StalledSeat
import io.github.jpicklyk.mcptask.current.application.port.StalledSeats
import java.time.Instant
import java.util.UUID

/** What a rollup covers: one subtree ([anchorId], R16) or one run ([runId], R28), and the project root it lies in. */
data class RollupScope(
    val anchorId: UUID?,
    val runId: UUID?,
    val rootId: UUID
)

/** The half-open window `[since, until)` the event and cost figures cover; [clamped] when a derived default was cut to the cap. */
data class RollupWindow(
    val since: Instant,
    val until: Instant,
    val clamped: Boolean
)

/** The transition figures of a rollup: applied transitions in the window, how many of them cascaded, and rejections. */
data class RollupTransitions(
    val total: Long,
    val cascades: Long,
    val rejected: Long
)

/**
 * The cost figures of a rollup, with the usage metric definitions (plan R9): [calls] rows, [errors] rows with outcome
 * error, [retries] the sum of `attempts - 1`, and the response sums (a NULL counts 0). [basis] says which calls were
 * counted (`targets` for a subtree, `run` for a run); both are lower bounds.
 */
data class RollupCost(
    val calls: Long,
    val errors: Long,
    val retries: Long,
    val responseBytes: Long,
    val responseTokensEst: Long,
    val basis: String
)

/**
 * The inputs of [RollupAssembler.assemble]. [roleCounts] maps a stored role (`queue`, `work`, `review`, `blocked`,
 * `terminal`) to its current item count; lifecycle-free items are not in it. Every run-only part is nullable, and null
 * means "omitted": an anchor rollup passes none of them.
 */
data class RollupParts(
    val scope: RollupScope,
    val window: RollupWindow,
    val roleCounts: Map<String, Long>,
    val transitions: RollupTransitions,
    val notesWritten: Long,
    val cost: RollupCost,
    val run: RunHeader? = null,
    val blockedReasons: Map<String, Int>? = null,
    val decisions: DecisionCounts? = null,
    val seats: StalledSeats? = null,
    val attempts: AttemptCounts? = null,
    val budget: BudgetFacts? = null,
    val resumable: Int? = null,
    val dwellMs: Map<String, Long>? = null
)

/** The item figures of a rollup: [total] items with a lifecycle, and [byRole] in role order with zero roles omitted. */
data class RollupItems(
    val total: Long,
    val byRole: Map<String, Long>
)

/**
 * The blocked figures of a rollup: [total] items in the blocked role, and [byReason] when run facts track reasons. A run
 * rollup adds [resumable] (blocked `awaiting_decision` items whose decision no longer waits) and [dwellMs] (per reason,
 * the milliseconds the current members spent blocked inside the window; only reasons with a positive dwell).
 */
data class RollupBlocked(
    val total: Long,
    val byReason: Map<String, Int>?,
    val resumable: Int? = null,
    val dwellMs: Map<String, Long>? = null
)

/** The seat figures of a rollup: at most [RollupAssembler.STALLED_CAP] [stalled] entries, and [count], the true total. */
data class RollupSeats(
    val active: Int,
    val stalled: List<StalledSeat>,
    val count: Int
)

/**
 * The `report rollup` answer: pointers and counts only, never note bodies. A null section is omitted on the wire; an anchor
 * rollup has no [run], [decisions], [seats], [attempts], [budget] and no `blocked.byReason`.
 */
data class RollupReport(
    val scope: RollupScope,
    val window: RollupWindow,
    val items: RollupItems,
    val blocked: RollupBlocked,
    val transitions: RollupTransitions,
    val notesWritten: Long,
    val cost: RollupCost,
    val run: RunHeader?,
    val decisions: DecisionCounts?,
    val seats: RollupSeats?,
    val attempts: AttemptCounts?,
    val budget: BudgetFacts?
)

/**
 * Builds the rollup (plan R16, W6 brief section 3.2). [assemble] is pure: no I/O, no clock; the anchor path and R28's run
 * path (and its `snapshot(runId)`) both go through it, so the shape rules live in one place.
 */
object RollupAssembler {
    /** The most `seats.stalled` entries a rollup carries; `seats.count` keeps the true total. */
    const val STALLED_CAP = 5

    /** The stored roles in wire order. */
    val ROLE_ORDER: List<String> = listOf("queue", "work", "review", "blocked", "terminal")

    /** `cost.basis` of an anchor rollup: calls naming a subtree item, or that wrote one of its events. */
    const val BASIS_TARGETS = "targets"

    /** `cost.basis` of a run rollup: calls stamped with the run id (a lower bound: an unstamped call is missed). */
    const val BASIS_RUN = "run"

    /**
     * The rollup of [parts]: `items.byRole` in [ROLE_ORDER] with zero roles omitted (a role outside the order is ignored),
     * `items.total` their sum, `blocked.total` the blocked-role count, and `seats.stalled` capped at [STALLED_CAP] entries
     * whatever the port returned, with `seats.count` the larger of the reported count and the entries returned.
     */
    fun assemble(parts: RollupParts): RollupReport {
        val byRole = LinkedHashMap<String, Long>()
        for (role in ROLE_ORDER) {
            val count = parts.roleCounts[role] ?: 0L
            if (count > 0) byRole[role] = count
        }
        return RollupReport(
            scope = parts.scope,
            window = parts.window,
            items = RollupItems(byRole.values.sum(), byRole),
            blocked =
                RollupBlocked(
                    total = byRole[BLOCKED] ?: 0L,
                    byReason = parts.blockedReasons,
                    resumable = parts.resumable,
                    dwellMs = parts.dwellMs?.filterValues { it > 0 }
                ),
            transitions = parts.transitions,
            notesWritten = parts.notesWritten,
            cost = parts.cost,
            run = parts.run,
            decisions = parts.decisions,
            seats =
                parts.seats?.let { seats ->
                    RollupSeats(
                        active = seats.active,
                        stalled = seats.stalled.take(STALLED_CAP),
                        count = maxOf(seats.count, seats.stalled.size)
                    )
                },
            attempts = parts.attempts,
            budget = parts.budget
        )
    }

    private const val BLOCKED = "blocked"
}

/**
 * The [RunFacts] that tracks nothing: every method answers null and does no I/O, so every run-only rollup section is
 * omitted. Production serves [io.github.jpicklyk.mcptask.current.application.run.StoreRunFacts] (the [ReportService]
 * default); this stub stays for callers that want the anchor-only shape.
 */
object NotTrackedRunFacts : RunFacts {
    override suspend fun run(runId: UUID): RunHeader? = null

    override suspend fun members(runId: UUID): Set<UUID>? = null

    override suspend fun blockedReasons(itemIds: Set<UUID>): Map<String, Int>? = null

    override suspend fun openDecisions(runId: UUID): DecisionCounts? = null

    override suspend fun stalledSeats(
        runId: UUID,
        limit: Int
    ): StalledSeats? = null

    override suspend fun attempts(runId: UUID): AttemptCounts? = null

    override suspend fun budget(runId: UUID): BudgetFacts? = null
}
