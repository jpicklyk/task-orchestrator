package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.graph.DependencyEdges
import io.github.jpicklyk.mcptask.current.domain.graph.TopoOrder
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import java.util.UUID

/** How one item of a tree completion ended, in processing order. */
sealed interface TreeItemOutcome {
    val itemId: UUID

    /** The trigger applied: [result] is the advance pipeline's own result. */
    data class Applied(
        override val itemId: UUID,
        val result: AdvanceResult
    ) : TreeItemOutcome

    /** The item was already TERMINAL (or a cascade earlier in this call made it so): never gate-checked, never propagates. */
    data class AlreadyTerminal(
        override val itemId: UUID
    ) : TreeItemOutcome

    /** An in-set blocker of the item failed (directly or upstream): [blockedBy] are the failed items it waits on. */
    data class UpstreamFailed(
        override val itemId: UUID,
        val blockedBy: List<UUID>
    ) : TreeItemOutcome

    /** The advance pipeline rejected the item: [failure] carries the catalog error the `transition` op returns. */
    data class Failed(
        override val itemId: UUID,
        val failure: AdvanceFailure
    ) : TreeItemOutcome
}

/** Every item's outcome, in processing order: blockers first, the root (when given) last. */
data class TreeCompletionResult(
    val outcomes: List<TreeItemOutcome>
)

/**
 * The tree completion algorithm (AR-96), shared by the `items_write` `complete_tree` op: the target set, its blocking
 * order ([TopoOrder] over the in-set blocking edges, ties in target order, cyclic items last), and one trigger per item
 * through the SAME [AdvanceService] pipeline the `advance` `transition` op runs (ownership, dependencies, note gate,
 * leases, cascades, unblocks; claim ownership and leases always enforced).
 *
 * Every advance joins the caller's write unit, so the applied transitions of one call commit together. A policy
 * rejection is data ([TreeItemOutcome.Failed]): it never rolls the call back, and every in-set dependent of the
 * rejected item is [TreeItemOutcome.UpstreamFailed], except after a [AdvanceFailure.ResolutionFailed] (the item simply
 * cannot take the trigger from its role), whose dependents still run. An [AdvanceFailure.ApplyFailed] (a store fault,
 * which has already rolled the shared connection back) ends the call with its error. A
 * `PerRootConfigUnavailableException` propagates.
 */
class TreeCompletionService(
    private val repositoryProvider: RepositoryProvider,
    private val advanceServiceFactory: AdvanceServiceFactory,
    private val degradedModePolicy: DegradedModePolicy
) {
    /**
     * The descendants of [rootId] (in store order), and the root itself when [includeRoot]; null when it does not exist.
     * Lifecycle-free descendants (no role) are left out: no trigger applies to them, so the expansion never targets one.
     * The root is returned whatever its role (an explicitly targeted lifecycle-free item still fails as usual).
     */
    suspend fun descendantTargets(
        rootId: UUID,
        includeRoot: Boolean
    ): Pair<List<WorkItem>, WorkItem?>? {
        val items = repositoryProvider.workItemRepository()
        val root = items.getById(rootId) ?: return null
        return items.findDescendants(rootId).filter { it.role != null } to root.takeIf { includeRoot }
    }

    /**
     * Applies [trigger] (`complete` or `cancel`) to [targets] in blocking order, then to [root] (when given) last.
     * Duplicate targets collapse to their first occurrence.
     */
    suspend fun run(
        targets: List<WorkItem>,
        root: WorkItem?,
        trigger: String,
        actorClaim: ActorClaim?,
        verification: VerificationResult?
    ): Outcome<TreeCompletionResult> {
        val distinct = targets.distinctBy { it.id }.filter { it.id != root?.id }
        val byId = distinct.associateBy { it.id }
        val ids = distinct.map { it.id }
        val idSet = ids.toSet()

        // Every in-set blocking edge, normalized to blocker -> blocked (RELATES_TO never orders or propagates). One batched
        // read for the whole target set (AR-96), not one per target.
        val stored =
            repositoryProvider
                .dependencyRepository()
                .findByItemIds(idSet)
                .values
                .flatten()
                .distinctBy { it.id }
        val edges = DependencyEdges.normalize(stored).blocking.filter { it.blocker in idSet && it.blocked in idSet }
        val dependents = HashMap<UUID, MutableList<UUID>>()
        for (edge in edges) dependents.getOrPut(edge.blocker) { ArrayList() } += edge.blocked
        val order = TopoOrder.order(ids, edges)

        val outcomes = ArrayList<TreeItemOutcome>(distinct.size + 1)
        // dependent -> the failed items it waits on (direct or upstream), in discovery order.
        val skipped = LinkedHashMap<UUID, LinkedHashSet<UUID>>()
        // Ancestors a cascade earlier in this call terminalized: their snapshot is stale, so they are already done.
        val terminalized = HashSet<UUID>()

        fun propagate(
            from: UUID,
            failed: Set<UUID>
        ) {
            for (dependent in dependents[from].orEmpty()) skipped.getOrPut(dependent) { LinkedHashSet() } += failed
        }

        for (id in order.ordered + order.cyclic) {
            val item = byId.getValue(id)
            val blockedBy = skipped[id]
            when {
                // A done item is done whatever its blockers did: it satisfies its dependents.
                item.role == Role.TERMINAL || id in terminalized -> outcomes += TreeItemOutcome.AlreadyTerminal(id)
                blockedBy != null -> {
                    outcomes += TreeItemOutcome.UpstreamFailed(id, blockedBy.toList())
                    propagate(id, blockedBy)
                }
                else ->
                    when (val outcome = advance(item, trigger, actorClaim, verification, terminalized)) {
                        is Outcome.Err -> return outcome
                        is Outcome.Ok -> {
                            outcomes += outcome.value
                            val failed = outcome.value as? TreeItemOutcome.Failed
                            if (failed != null && failed.failure !is AdvanceFailure.ResolutionFailed) propagate(id, setOf(id))
                        }
                    }
            }
        }

        if (root != null) {
            if (root.role == Role.TERMINAL || root.id in terminalized) {
                outcomes += TreeItemOutcome.AlreadyTerminal(root.id)
            } else {
                when (val outcome = advance(root, trigger, actorClaim, verification, terminalized)) {
                    is Outcome.Err -> return outcome
                    is Outcome.Ok -> outcomes += outcome.value
                }
            }
        }
        return Outcome.Ok(TreeCompletionResult(outcomes))
    }

    /** One item through the advance pipeline; a store fault ([AdvanceFailure.ApplyFailed]) ends the call. */
    private suspend fun advance(
        item: WorkItem,
        trigger: String,
        actorClaim: ActorClaim?,
        verification: VerificationResult?,
        terminalized: MutableSet<UUID>
    ): Outcome<TreeItemOutcome> {
        val outcome =
            withEventActor(actorClaim) {
                advanceServiceFactory.forItem(item).advance(
                    item = item,
                    trigger = trigger,
                    summary = null,
                    actorClaim = actorClaim,
                    verification = verification,
                    degradedModePolicy = degradedModePolicy,
                    enforceOwnership = true,
                    credentialRefs = emptyList(),
                    enforceResourceLeases = true
                )
            }
        return when (outcome) {
            is AdvanceOutcome.Success -> {
                outcome.result.cascadeEvents
                    .filter { it.applied && it.targetRole == Role.TERMINAL }
                    .forEach { terminalized += it.itemId }
                Outcome.Ok(TreeItemOutcome.Applied(item.id, outcome.result))
            }
            is AdvanceOutcome.Failure ->
                when (val failure = outcome.failure) {
                    is AdvanceFailure.ApplyFailed -> Outcome.Err(applyFault(failure))
                    else -> Outcome.Ok(TreeItemOutcome.Failed(item.id, failure))
                }
        }
    }

    private fun applyFault(failure: AdvanceFailure.ApplyFailed): DomainError = failure.error
}
