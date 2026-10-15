package io.github.jpicklyk.mcptask.current.application.read

import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.application.port.AssigneeHolder
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.domain.graph.BlockerEvaluator
import io.github.jpicklyk.mcptask.current.domain.model.BlockedReason
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.Run
import io.github.jpicklyk.mcptask.current.domain.model.RunLiveness
import io.github.jpicklyk.mcptask.current.domain.model.RunSeat
import io.github.jpicklyk.mcptask.current.domain.model.RunStatus
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import java.time.Instant
import java.util.UUID

/**
 * Why a member is waiting: role BLOCKED with its effective typed reason ([AWAITING_DECISION], [DEPENDENCY], [RESOURCE] or
 * [MANUAL]; a legacy NULL reads as manual) or an unsatisfied blocking dependency ([DEPENDENCY]).
 */
enum class FrontierWaitReason(
    val wire: String
) {
    MANUAL("manual"),
    DEPENDENCY("dependency"),
    AWAITING_DECISION("awaiting_decision"),
    RESOURCE("resource")
}

/** One active member-seat assignment: [seat] attempt [attempt] on [itemId] (role [role] at read time). */
data class FrontierSeat(
    val itemId: UUID,
    val role: Role?,
    val seat: String,
    val attempt: Int,
    val lastSeenAt: Instant?,
    val stalled: Boolean
)

/** One waiting member; [blockers] are the unsatisfied blockers visible under the call scope, sorted (empty for [FrontierWaitReason.MANUAL]). */
data class FrontierWaiting(
    val itemId: UUID,
    val reason: FrontierWaitReason,
    val blockers: List<UUID> = emptyList(),
    /** The decision an [FrontierWaitReason.AWAITING_DECISION] member waits on. */
    val decisionId: UUID? = null
)

/**
 * The frontier of one run at one instant: [counts] of the visible members by role (all five roles present),
 * the [ready] members (membership order), the active member seats [inFlight] (assignment order) and the [waiting]
 * members (membership order). [stalled] is the coordinator's liveness ([RunLiveness.isCoordinatorStalled]).
 */
data class RunFrontier(
    val run: Run,
    val stalled: Boolean,
    val counts: Map<Role, Int>,
    val ready: List<WorkItem>,
    val inFlight: List<FrontierSeat>,
    val waiting: List<FrontierWaiting>
)

/**
 * The run frontier read (W5 3.4): ONE read unit with a constant number of statements whatever the number of members,
 * seats or dependency edges (the membership cap equals the SQL IN chunk size, so every batched IN read is one
 * statement). No writes.
 *
 * - `ready`: a visible member whose role is neither TERMINAL nor BLOCKED, with no active member-seat assignment, no
 *   unsatisfied blocking edge ([BlockerEvaluator]) and no foreign live claim. Always empty for a CLOSED run; a PAUSED
 *   run computes it normally.
 * - A foreign live claim: `claimExpiresAt > now` and the holder pair (claimedBy, claimAssignmentId) is not the pair of
 *   any active assignment of the run (the coordinator included). An expired claim is never foreign.
 * - `inFlight`: every active member-seat assignment (the coordinator excluded) whose item is visible, stalled per
 *   [RunLiveness.isSeatStalled] with the run's pinned threshold.
 * - `waiting`: per visible non-terminal member, role BLOCKED is `manual`; otherwise at least one unsatisfied blocking
 *   edge is `dependency`, listing the unsatisfied blockers visible under the scope.
 *
 * Scope (the [WorkViewQueries.blocked] convention): members and blockers are filtered by visibility; blocked-ness is
 * decided on the true rows, so an invisible blocker still keeps its item waiting and is only left out of `blockers`.
 */
class RunFrontierQueries(
    private val unitOfWork: UnitOfWork
) {
    /** The frontier of run [runId] at [now] under [scope], or null when there is no such run. */
    suspend fun frontier(
        runId: UUID,
        now: Instant,
        scope: AccessScope
    ): RunFrontier? =
        unitOfWork.read {
            val run = stores.runStore().getById(runId) ?: return@read null
            val memberIds = stores.runStore().members(runId)
            val seats = stores.runSeatStore().list(runId).filter { it.isActive }
            val memberSeats = seats.filter { !it.isCoordinator && it.itemId != null }
            val itemIds = LinkedHashSet<UUID>(memberIds)
            memberSeats.forEach { itemIds += it.itemId!! }
            val rows = stores.itemStore().findByIdSet(itemIds).associateBy { it.id }
            val edges = stores.dependencyRepository().blockingEdgesFor(memberIds.toSet())
            val blockerIds = edges.mapTo(LinkedHashSet()) { it.blocker }
            val blockerRows = stores.itemStore().findByIdSet(blockerIds).associateBy { it.id }
            val visible: Set<UUID>? =
                if (scope.isUnrestricted) null else stores.itemStore().filterVisible(rows.keys + blockerRows.keys, scope)

            fun isVisible(id: UUID): Boolean = visible == null || id in visible

            val members = memberIds.mapNotNull { rows[it] }.filter { isVisible(it.id) }
            val counts = LinkedHashMap<Role, Int>()
            Role.entries.forEach { counts[it] = 0 }
            members.forEach { m -> m.role?.let { counts[it] = counts.getValue(it) + 1 } }

            val roles: Map<UUID, Role?> = blockerRows.mapValues { it.value.role }
            val unsatisfied = BlockerEvaluator.unsatisfied(members.map { it.id }, edges, roles)
            val seated = memberSeats.mapTo(HashSet()) { it.itemId!! }
            val holders = seats.mapTo(HashSet()) { AssigneeHolder(it.principalId, it.id) }

            val ready =
                if (run.status == RunStatus.CLOSED) {
                    emptyList()
                } else {
                    members.filter { m ->
                        m.role != null &&
                            m.role != Role.TERMINAL &&
                            m.role != Role.BLOCKED &&
                            m.id !in seated &&
                            unsatisfied[m.id].isNullOrEmpty() &&
                            !hasForeignLiveClaim(m, holders, now)
                    }
                }

            val inFlight =
                memberSeats
                    .filter { isVisible(it.itemId!!) }
                    .map { seat -> inFlightOf(seat, rows[seat.itemId!!], run, now) }

            val waiting =
                members.mapNotNull { m ->
                    when {
                        m.role == null || m.role == Role.TERMINAL -> null
                        m.role == Role.BLOCKED ->
                            FrontierWaiting(
                                m.id,
                                when (m.effectiveBlockedReason) {
                                    BlockedReason.AWAITING_DECISION -> FrontierWaitReason.AWAITING_DECISION
                                    BlockedReason.DEPENDENCY -> FrontierWaitReason.DEPENDENCY
                                    BlockedReason.RESOURCE -> FrontierWaitReason.RESOURCE
                                    else -> FrontierWaitReason.MANUAL
                                },
                                decisionId = m.blockedRef
                            )
                        unsatisfied[m.id].isNullOrEmpty() -> null
                        else ->
                            FrontierWaiting(
                                m.id,
                                FrontierWaitReason.DEPENDENCY,
                                unsatisfied
                                    .getValue(m.id)
                                    .map { it.blockerId }
                                    .distinct()
                                    .filter { isVisible(it) }
                                    .sortedBy { it.toString() }
                            )
                    }
                }

            RunFrontier(
                run = run,
                stalled = RunLiveness.isCoordinatorStalled(run, now),
                counts = counts,
                ready = ready,
                inFlight = inFlight,
                waiting = waiting
            )
        }

    private fun inFlightOf(
        seat: RunSeat,
        item: WorkItem?,
        run: Run,
        now: Instant
    ): FrontierSeat =
        FrontierSeat(
            itemId = seat.itemId!!,
            role = item?.role,
            seat = seat.seat,
            attempt = seat.attempt,
            lastSeenAt = seat.lastSeenAt,
            stalled = RunLiveness.isSeatStalled(seat, now, run.stallAfterSeconds)
        )

    private fun hasForeignLiveClaim(
        item: WorkItem,
        holders: Set<AssigneeHolder>,
        now: Instant
    ): Boolean {
        val expires = item.claimExpiresAt ?: return false
        val holder = item.claimedBy ?: return false
        if (!expires.isAfter(now)) return false
        val assignment = item.claimAssignmentId ?: return true
        return AssigneeHolder(holder, assignment) !in holders
    }
}
