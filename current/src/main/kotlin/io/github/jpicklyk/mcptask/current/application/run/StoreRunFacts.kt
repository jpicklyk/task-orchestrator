package io.github.jpicklyk.mcptask.current.application.run

import io.github.jpicklyk.mcptask.current.application.port.AttemptCounts
import io.github.jpicklyk.mcptask.current.application.port.BudgetFacts
import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.application.port.DecisionCounts
import io.github.jpicklyk.mcptask.current.application.port.RunFacts
import io.github.jpicklyk.mcptask.current.application.port.RunFactsSnapshot
import io.github.jpicklyk.mcptask.current.application.port.RunHeader
import io.github.jpicklyk.mcptask.current.application.port.StalledSeat
import io.github.jpicklyk.mcptask.current.application.port.StalledSeats
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.domain.model.BlockedReason
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequest
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequestStatus
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.Run
import io.github.jpicklyk.mcptask.current.domain.model.RunLimitDecisions
import io.github.jpicklyk.mcptask.current.domain.model.RunLiveness
import io.github.jpicklyk.mcptask.current.domain.model.RunSeat
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * [RunFacts] over the W5 tables (plan R28): runs and membership ([io.github.jpicklyk.mcptask.current.application.port.RunStore]),
 * seat assignments ([io.github.jpicklyk.mcptask.current.application.port.RunSeatStore]), decision requests and items.
 * Every method is a [UnitOfWork.read] that JOINS the caller's unit (a report read, or the close write when the rollup is
 * snapshotted), and issues a constant number of statements whatever the members, seats or decisions. The time-dependent
 * facts (stalled seats, effective severity) are read at [clock]'s now, to the millisecond.
 */
class StoreRunFacts(
    private val unitOfWork: UnitOfWork,
    private val clock: Clock
) : RunFacts {
    /** The run row as a [RunHeader] (instants to the millisecond, the precision the row keeps). */
    override suspend fun run(runId: UUID): RunHeader? =
        unitOfWork.read {
            val run = stores.runStore().getById(runId) ?: return@read null
            RunHeader(
                status = run.status,
                terminalReason = run.terminalReason,
                openedAt = run.openedAt.truncatedTo(ChronoUnit.MILLIS),
                closedAt = run.closedAt?.truncatedTo(ChronoUnit.MILLIS),
                rootId = run.rootId
            )
        }

    /** The CURRENT members (`run_items`). */
    override suspend fun members(runId: UUID): Set<UUID> =
        unitOfWork.read { stores.runStore().members(runId).toCollection(LinkedHashSet()) }

    /**
     * The items of [itemIds] in the blocked role, counted by effective reason wire (a legacy NULL reason reads `manual`),
     * in reason-wire order; the counts sum to the blocked items among [itemIds].
     */
    override suspend fun blockedReasons(itemIds: Set<UUID>): Map<String, Int> {
        if (itemIds.isEmpty()) return emptyMap()
        return unitOfWork.read {
            stores
                .itemStore()
                .findByIdSet(itemIds)
                .mapNotNull { it.effectiveBlockedReason?.wire }
                .groupingBy { it }
                .eachCount()
                .toSortedMap()
        }
    }

    /**
     * The blocked `awaiting_decision` items of [itemIds] whose decision no longer waits for an answer (answered,
     * withdrawn or gone): the work view's `resumable` rule.
     */
    override suspend fun resumable(itemIds: Set<UUID>): Int {
        if (itemIds.isEmpty()) return 0
        return unitOfWork.read {
            val awaiting =
                stores
                    .itemStore()
                    .findByIdSet(itemIds)
                    .filter { it.role == Role.BLOCKED && it.effectiveBlockedReason == BlockedReason.AWAITING_DECISION }
            if (awaiting.isEmpty()) return@read 0
            val refs = awaiting.mapNotNullTo(LinkedHashSet()) { it.blockedRef }
            val decisions =
                if (refs.isEmpty()) emptyMap() else stores.decisionRequestStore().findByIds(refs).associateBy { it.id }
            awaiting.count { item -> decisions[item.blockedRef]?.status?.isPending != true }
        }
    }

    /** The run's decisions still open or acknowledged, by class and by effective severity at now (overdue reads one step up). */
    override suspend fun openDecisions(runId: UUID): DecisionCounts {
        val now = clock.now().truncatedTo(ChronoUnit.MILLIS)
        return unitOfWork.read {
            val open =
                stores
                    .decisionRequestStore()
                    .list(runId = runId, statuses = PENDING)
            decisionCounts(open, now)
        }
    }

    /**
     * The active member seats of the run (the coordinator excluded) and those of them stalled under the run's
     * `stallAfterSeconds` at now, silent longest first (then item id, then seat), at most [limit] entries; `count` is the
     * true number. Null when there is no such run.
     */
    override suspend fun stalledSeats(
        runId: UUID,
        limit: Int
    ): StalledSeats? {
        val now = clock.now().truncatedTo(ChronoUnit.MILLIS)
        return unitOfWork.read {
            val run = stores.runStore().getById(runId) ?: return@read null
            stalledOf(run, stores.runSeatStore().list(runId).filter { it.isActive && !it.isCoordinator }, now, limit)
        }
    }

    /** Every member-seat assignment of the run, ended ones included (the coordinator excluded), and the highest attempt. */
    override suspend fun attempts(runId: UUID): AttemptCounts =
        unitOfWork.read {
            val rows = stores.runSeatStore().list(runId, includeEnded = true).filter { !it.isCoordinator }
            AttemptCounts(total = rows.size, max = rows.maxOfOrNull { it.attempt } ?: 0)
        }

    /**
     * The token budget from the run row (V5, never from `call_log`): used is the larger of the reported and the observed
     * tokens, limit the pinned `budget.tokens` (null when none), basis `enforced`. Null when there is no such run.
     */
    override suspend fun budget(runId: UUID): BudgetFacts? =
        unitOfWork.read {
            val run = stores.runStore().getById(runId) ?: return@read null
            budgetOf(run, stores.decisionRequestStore().list(runId = runId))
        }

    /**
     * Every fact for [run] with one read per table: the member items, the run's seats (ended ones included), the run's
     * decisions (any status) and, only when a member awaits a decision, the referenced decisions. The header and budget
     * figures come from [run] itself.
     */
    override suspend fun snapshot(
        run: Run,
        members: Set<UUID>,
        stalledLimit: Int
    ): RunFactsSnapshot {
        val now = clock.now().truncatedTo(ChronoUnit.MILLIS)
        return unitOfWork.read {
            val items = if (members.isEmpty()) emptyList() else stores.itemStore().findByIdSet(members)
            val awaiting = items.filter { it.role == Role.BLOCKED && it.effectiveBlockedReason == BlockedReason.AWAITING_DECISION }
            val refs = awaiting.mapNotNullTo(LinkedHashSet()) { it.blockedRef }
            val referenced =
                if (refs.isEmpty()) emptyMap() else stores.decisionRequestStore().findByIds(refs).associateBy { it.id }
            val allDecisions = stores.decisionRequestStore().list(runId = run.id)
            val open = allDecisions.filter { it.status in PENDING }
            val allSeats = stores.runSeatStore().list(run.id, includeEnded = true).filter { !it.isCoordinator }
            val active = allSeats.filter { it.isActive }
            RunFactsSnapshot(
                header =
                    RunHeader(
                        status = run.status,
                        terminalReason = run.terminalReason,
                        openedAt = run.openedAt.truncatedTo(ChronoUnit.MILLIS),
                        closedAt = run.closedAt?.truncatedTo(ChronoUnit.MILLIS),
                        rootId = run.rootId
                    ),
                blockedReasons =
                    items
                        .mapNotNull { it.effectiveBlockedReason?.wire }
                        .groupingBy { it }
                        .eachCount()
                        .toSortedMap(),
                resumable = awaiting.count { item -> referenced[item.blockedRef]?.status?.isPending != true },
                decisions = decisionCounts(open, now),
                seats = stalledOf(run, active, now, stalledLimit),
                attempts = AttemptCounts(total = allSeats.size, max = allSeats.maxOfOrNull { it.attempt } ?: 0),
                budget = budgetOf(run, allDecisions)
            )
        }
    }

    private fun decisionCounts(
        open: List<DecisionRequest>,
        now: Instant
    ): DecisionCounts =
        DecisionCounts(
            open = open.size,
            byClass = open.groupingBy { it.decisionClass }.eachCount().toSortedMap(),
            bySeverity = open.groupingBy { it.effectiveSeverity(now).wire }.eachCount().toSortedMap()
        )

    private fun stalledOf(
        run: Run,
        active: List<RunSeat>,
        now: Instant,
        limit: Int
    ): StalledSeats {
        val stalled =
            active
                .filter { RunLiveness.isSeatStalled(it, now, run.stallAfterSeconds) }
                .map { seat ->
                    StalledSeat(
                        seat = seat.seat,
                        itemId = requireNotNull(seat.itemId) { "a member seat names its item" },
                        silentMs = Duration.between(RunLiveness.seatReference(seat), now).toMillis()
                    )
                }.sortedWith(compareByDescending<StalledSeat> { it.silentMs }.thenBy { it.itemId.toString() }.thenBy { it.seat })
        return StalledSeats(active = active.size, stalled = stalled.take(limit.coerceAtLeast(0)), count = stalled.size)
    }

    /** The budget figures of [run]; breaches and extends come from the run's `run-budget` decisions in [decisions]. */
    private fun budgetOf(
        run: Run,
        decisions: List<DecisionRequest>
    ): BudgetFacts {
        val budgetDecisions = decisions.filter { it.decisionClass == RunLimitDecisions.RUN_BUDGET }
        return BudgetFacts(
            used = maxOf(run.reportedTokens, run.observedTokens),
            limit = run.budget?.tokens,
            basis = BUDGET_BASIS_ENFORCED,
            breaches = budgetDecisions.size,
            extends = budgetDecisions.count { it.answer?.option == RunLimitDecisions.EXTEND }
        )
    }

    companion object {
        /** `budget.basis`: the figure the run's token budget is enforced on. */
        const val BUDGET_BASIS_ENFORCED = "enforced"

        private val PENDING: Set<DecisionRequestStatus> = setOf(DecisionRequestStatus.OPEN, DecisionRequestStatus.ACKNOWLEDGED)
    }
}
