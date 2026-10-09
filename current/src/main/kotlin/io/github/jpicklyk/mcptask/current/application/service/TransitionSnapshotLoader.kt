package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.port.DependencyStore
import io.github.jpicklyk.mcptask.current.application.port.LeaseStore
import io.github.jpicklyk.mcptask.current.application.port.NoteStore
import io.github.jpicklyk.mcptask.current.application.port.ReadScope
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.domain.graph.BlockingEdge
import io.github.jpicklyk.mcptask.current.domain.graph.DependencyEdges
import io.github.jpicklyk.mcptask.current.domain.lifecycle.BlockerState
import io.github.jpicklyk.mcptask.current.domain.lifecycle.ChildFacts
import io.github.jpicklyk.mcptask.current.domain.lifecycle.IndependenceFacts
import io.github.jpicklyk.mcptask.current.domain.lifecycle.ItemFacts
import io.github.jpicklyk.mcptask.current.domain.lifecycle.LeaseFacts
import io.github.jpicklyk.mcptask.current.domain.lifecycle.OwnershipFacts
import io.github.jpicklyk.mcptask.current.domain.lifecycle.RequiredNote
import io.github.jpicklyk.mcptask.current.domain.lifecycle.SchemaFacts
import io.github.jpicklyk.mcptask.current.domain.lifecycle.TransitionSnapshot
import io.github.jpicklyk.mcptask.current.domain.lifecycle.TransitionTable
import io.github.jpicklyk.mcptask.current.domain.lifecycle.Trigger
import io.github.jpicklyk.mcptask.current.domain.model.ClaimState
import io.github.jpicklyk.mcptask.current.domain.model.IndependencePolicy
import io.github.jpicklyk.mcptask.current.domain.model.IndependenceViolation
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.ResourceMode
import io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import java.time.Duration
import java.util.UUID

/**
 * Claim-ownership input to [TransitionSnapshotLoader.load]: whether the ownership gate is enforced
 * and the caller's trusted identity. The loader derives the live claim holder itself.
 */
data class OwnershipInput(
    val enforced: Boolean,
    val callerId: String?
) {
    companion object {
        /** Ownership not enforced (system cascades, REST, previews). */
        val NONE: OwnershipInput = OwnershipInput(enforced = false, callerId = null)
    }
}

/**
 * Resource-lease input to [TransitionSnapshotLoader.load]: [enforced] is the caller's lease switch
 * ANDed with the deployment kill switch. When false the loader reads no requirements and no leases.
 */
data class LeaseInput(
    val enforced: Boolean
) {
    companion object {
        val OFF: LeaseInput = LeaseInput(enforced = false)
        val ON: LeaseInput = LeaseInput(enforced = true)
    }
}

/**
 * A loaded snapshot plus the inputs the apply step reuses: the resolved [schema] and, when the lease
 * facts were loaded, the item's declared resource [requirements] (empty otherwise). [notes] are the
 * item's notes when the note gate's facts were loaded (null when they were not read).
 */
internal data class LoadedSnapshot(
    val snapshot: TransitionSnapshot,
    val schema: WorkItemSchema?,
    val requirements: List<ResourceRequirement>,
    val notes: List<Note>? = null
)

/**
 * Loads the [TransitionSnapshot] that [io.github.jpicklyk.mcptask.current.domain.lifecycle.TransitionPolicy]
 * evaluates, inside the caller's unit ([scope] supplies the unit instant). Shared by the advance pipeline
 * ([AdvanceService]) and the read-only previews ([TransitionPreview]), so a preview and an advance on the
 * same state see the same facts.
 *
 * Config (schema, independence policy, resource requirements) is resolved here too, inside the unit, so it
 * is read in the same unit as the facts it gates. A unit's config session has no last-known-good fallback:
 * a per-root read fault propagates as a `PerRootConfigUnavailableException`.
 *
 * Reads only what the trigger can need: notes and independence only when the trigger's note gate applies,
 * a schema matched and the table resolves a target; child counts only for a complete cascade; blockers only when the table target is
 * WORK, REVIEW or TERMINAL (and the trigger is not `cancel`); requirements and leases only when the lease
 * input is enforced and the target is WORK (zero lease-store reads when no exclusive key is declared).
 */
class TransitionSnapshotLoader(
    private val workItemRepository: WorkItemRepository,
    private val dependencyRepository: DependencyStore,
    private val noteRepository: NoteStore,
    private val resourceLeaseRepository: LeaseStore?,
    private val schemaResolver: suspend (WorkItem) -> WorkItemSchema?,
    private val resourceRequirementsResolver: suspend (WorkItem) -> List<ResourceRequirement> = { emptyList() },
    private val independencePolicyResolver: suspend (WorkItem) -> IndependencePolicy = { IndependencePolicy.DEFAULT }
) {
    /** The snapshot for [item] under [trigger]. */
    suspend fun load(
        scope: ReadScope,
        item: WorkItem,
        trigger: Trigger,
        ownership: OwnershipInput,
        leases: LeaseInput
    ): TransitionSnapshot = loadDetailed(scope, item, trigger, ownership, leases).snapshot

    internal suspend fun loadDetailed(
        scope: ReadScope,
        item: WorkItem,
        trigger: Trigger,
        ownership: OwnershipInput,
        leases: LeaseInput
    ): LoadedSnapshot {
        val schema = schemaResolver(item)
        val schemaFacts = schema?.let { SchemaFacts(it.hasReviewPhase(), it.lifecycleMode) } ?: SchemaFacts.SCHEMA_FREE
        val target = (TransitionTable.resolve(item.role, trigger, schemaFacts, item.previousRole) as? TransitionTable.Resolution.To)?.role

        var filledKeys: Set<String> = emptySet()
        var independence: IndependenceFacts? = null
        var notes: List<Note>? = null
        // No table target means the policy stops at TABLE: the note facts are never read, so skip them.
        if (schema != null && target != null && noteGateApplies(trigger)) {
            val itemNotes: List<Note> = noteRepository.findByItemId(item.id)
            notes = itemNotes
            filledKeys = GatePredicate.filledNoteKeys(itemNotes)
            val policy = independencePolicyResolver(item)
            val current = GatePredicate.violationsForStart(schema, item.role, itemNotes, policy)
            val all = GatePredicate.violationsForComplete(schema, itemNotes, policy)
            if (current != null || all != null) independence = IndependenceFacts(policy.mode, current, all)
        }

        val children =
            if (trigger is Trigger.Cascade.Complete) {
                val counts = workItemRepository.countChildrenByRole(item.id)
                ChildFacts(total = counts.values.sum(), terminal = counts[Role.TERMINAL] ?: 0)
            } else {
                ChildFacts.NONE
            }

        val blockers =
            if (target != null && target in DEPENDENCY_TARGETS && trigger != Trigger.User.CANCEL) {
                loadBlockers(item.id)
            } else {
                emptyList()
            }

        val ownershipFacts =
            OwnershipFacts(
                enforced = ownership.enforced,
                activeHolder = if (ClaimState.isActive(item, scope.now)) item.claimedBy else null,
                callerId = ownership.callerId
            )

        var requirements: List<ResourceRequirement> = emptyList()
        var leaseFacts = LeaseFacts.NONE
        if (leases.enforced && target == Role.WORK) {
            requirements = resourceRequirementsResolver(item)
            val exclusiveKeys = requirements.filter { it.mode == ResourceMode.EXCLUSIVE }.map { it.key }.distinct()
            val leaseRepo = resourceLeaseRepository
            if (exclusiveKeys.isNotEmpty() && leaseRepo != null) {
                val heldElsewhere = leaseRepo.findActiveByKeys(exclusiveKeys).filter { it.holderItemId != item.id }
                val retryAfterMs =
                    heldElsewhere.minOfOrNull { it.expiresAt }?.let { soonest ->
                        Duration.between(scope.now, soonest).toMillis().coerceAtLeast(1L)
                    }
                leaseFacts =
                    LeaseFacts(
                        enforced = true,
                        exclusiveKeys = exclusiveKeys,
                        heldElsewhere = heldElsewhere.map { it.resourceKey }.distinct(),
                        retryAfterMs = retryAfterMs
                    )
            }
        }

        val snapshot =
            TransitionSnapshot(
                item = ItemFacts(item.id, item.role, item.previousRole, item.parentId),
                schema = schemaFacts,
                requiredNotes = schema?.notes?.filter { it.required }?.map { RequiredNote(it.key, it.role, it.seat) },
                filledKeys = filledKeys,
                independence = independence,
                children = children,
                blockers = blockers,
                ownership = ownershipFacts,
                lease = leaseFacts
            )
        return LoadedSnapshot(snapshot, schema, requirements, notes)
    }

    /** All notes of [itemId] (read in the caller's unit). */
    internal suspend fun notesOf(itemId: UUID): List<Note> = noteRepository.findByItemId(itemId)

    /** Independence-attestation violations for [item]'s current phase under [schema] and its root's policy. */
    internal suspend fun currentPhaseViolations(
        schema: WorkItemSchema,
        item: WorkItem,
        notes: List<Note>
    ): List<IndependenceViolation>? = GatePredicate.violationsForStart(schema, item.role, notes, independencePolicyResolver(item))

    /** The normalized edges blocking [itemId], each with its blocker's current role (null = unreadable). */
    internal suspend fun loadBlockers(itemId: UUID): List<BlockerState> {
        val edges = blockingEdgesOf(itemId)
        if (edges.isEmpty()) return emptyList()
        val roles = rolesOf(edges.map { it.blocker }.toSet())
        return edges.map { BlockerState(it, roles[it.blocker]) }
    }

    /** Normalized blocking edges whose blocked side is [itemId]. */
    internal suspend fun blockingEdgesOf(itemId: UUID): List<BlockingEdge> =
        DependencyEdges.normalize(dependencyRepository.findByItemId(itemId)).blocking.filter { it.blocked == itemId }

    /** Normalized blocking edges whose blocker side is [itemId]. */
    internal suspend fun edgesBlockedBy(itemId: UUID): List<BlockingEdge> =
        DependencyEdges.normalize(dependencyRepository.findByItemId(itemId)).blocking.filter { it.blocker == itemId }

    /** Current roles of [ids]; an id with no readable row is absent. */
    internal suspend fun rolesOf(ids: Set<UUID>): Map<UUID, Role> =
        if (ids.isEmpty()) emptyMap() else workItemRepository.findByIds(ids).associate { it.id to it.role }

    companion object {
        private val DEPENDENCY_TARGETS: Set<Role> = setOf(Role.WORK, Role.REVIEW, Role.TERMINAL)

        /** START and COMPLETE, the start cascade, and a non-cancel-origin complete cascade gate on notes. */
        internal fun noteGateApplies(trigger: Trigger): Boolean =
            when (trigger) {
                Trigger.User.START, Trigger.User.COMPLETE, Trigger.Cascade.Start -> true
                is Trigger.Cascade.Complete -> !trigger.cancelOrigin
                else -> false
            }
    }
}
