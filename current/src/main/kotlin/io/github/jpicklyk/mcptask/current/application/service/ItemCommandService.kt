package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.config.EffectiveConfigResolver
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.application.support.LegacyFaults
import io.github.jpicklyk.mcptask.current.application.support.rethrowIfCancellation
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.EntityKind
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.FieldViolation
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.error.VersionConflictException
import io.github.jpicklyk.mcptask.current.domain.event.DeleteCause
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import io.github.jpicklyk.mcptask.current.domain.event.ReparentSide
import io.github.jpicklyk.mcptask.current.domain.lifecycle.Decision
import io.github.jpicklyk.mcptask.current.domain.lifecycle.FollowUp
import io.github.jpicklyk.mcptask.current.domain.lifecycle.ItemFacts
import io.github.jpicklyk.mcptask.current.domain.lifecycle.ParentFacts
import io.github.jpicklyk.mcptask.current.domain.lifecycle.TransitionPolicy
import io.github.jpicklyk.mcptask.current.domain.lifecycle.TransitionSnapshot
import io.github.jpicklyk.mcptask.current.domain.lifecycle.Trigger
import io.github.jpicklyk.mcptask.current.domain.model.LifecycleMode
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.validation.ValidationException
import java.util.UUID

/**
 * One item create. Carries no role, depth or rootId: every item is created in QUEUE, and its placement is derived
 * from the parent row read inside the write unit.
 */
data class ItemCreateCommand(
    val id: UUID = UUID.randomUUID(),
    val parentId: UUID?,
    val title: String,
    val description: String? = null,
    val summary: String = "",
    val statusLabel: String? = null,
    val priority: Priority = Priority.MEDIUM,
    val complexity: Int? = null,
    val requiresVerification: Boolean = false,
    val metadata: String? = null,
    val tags: String? = null,
    val type: String? = null,
    val properties: String? = null
)

/** What a patch does to the item's parent. */
sealed interface ParentChange {
    /** Leave the parent unchanged. */
    data object Keep : ParentChange

    /** Make the item a root (depth 0, its own root). */
    data object MoveToRoot : ParentChange

    /** Move the item under [parentId]. */
    data class MoveUnder(
        val parentId: UUID
    ) : ParentChange
}

/**
 * One item patch. Every field is the FINAL value (each surface merges its partial request against the current row
 * first); role, depth, rootId and the claim columns are never patched.
 *
 * @property expectedVersion when non-null, the patch fails with `version_conflict` unless the row (re-read inside
 *   the unit) is at exactly this version.
 */
data class ItemPatchCommand(
    val itemId: UUID,
    val expectedVersion: Long?,
    val parent: ParentChange,
    val title: String,
    val description: String?,
    val summary: String,
    val statusLabel: String?,
    val priority: Priority,
    val complexity: Int?,
    val requiresVerification: Boolean,
    val metadata: String?,
    val tags: String?,
    val type: String?,
    val properties: String?
)

/**
 * A committed patch: the stored [item], whether it moved, how many descendants were restamped, and the cascades the
 * move ran on the old parent (applied or suppressed).
 */
data class ItemPatchResult(
    val item: WorkItem,
    val reparented: Boolean,
    val descendantsRestamped: Int,
    val cascadeEvents: List<AdvanceCascadeEvent>
)

/** A committed delete of [id] and [descendantsDeleted] descendants, plus the cascades it ran on the old parent. */
data class ItemDeleteResult(
    val id: UUID,
    val descendantsDeleted: Int,
    val cascadeEvents: List<AdvanceCascadeEvent>
)

/**
 * The single owner of item creates, patches and deletes, shared by `manage_items`, the REST item routes and
 * `create_work_tree` (plan sections 3.3, 3.5, 3.6). JSON-free and transport-free: failures are [DomainError]s the
 * adapters map to their 3.x wire shapes ([ItemCommandErrors] classifies them).
 *
 * Every method is ONE write unit, joining an ambient one. Inside it:
 * - **Placement** is derived from the parent row read in the unit, under the writer lock held from BEGIN (AR-19):
 *   `depth = parent.depth + 1`, `rootId = parent.rootId ?: parent.id`; a root is depth 0 and its own root. A
 *   reparent restamps every descendant in one statement ([io.github.jpicklyk.mcptask.current.application.port.HierarchyStore.restampSubtree]).
 * - **Structural rules** come from [TransitionPolicy] with [Trigger.Structural]: an item is always created in QUEUE;
 *   a create or reparent under a TERMINAL parent whose effective lifecycle is AUTO is rejected
 *   (`invalid_transition`); a reparent away from, or a delete under, a parent re-evaluates that OLD parent exactly
 *   like a child completion ([AdvanceService.cascadeInUnit]), in the same unit.
 * - **Events** are recorded here, through the unit's sink: `item.created`, `item.updated`, two `item.reparented`
 *   rows per move, `item.deleted`, and the `note.deleted` / `dependency.removed` rows (cause `cascade`) a delete's
 *   foreign-key cascade implies.
 *
 * A [PerRootConfigUnavailableException] (resolving a TERMINAL parent's lifecycle) rolls the unit back and is
 * rethrown for the caller's `config_unavailable` mapping; one on a cascade target skips that cascade only.
 */
class ItemCommandService(
    private val repositoryProvider: RepositoryProvider,
    private val configResolver: EffectiveConfigResolver,
    private val unitOfWork: UnitOfWork,
    private val advanceServiceFactory: AdvanceServiceFactory,
    private val claimService: ClaimService,
    private val policy: TransitionPolicy = TransitionPolicy()
) {
    // The composite work-item store: every item, placement and hierarchy call goes through the one instance a
    // provider hands out (a provider that wraps it, such as a fault-injecting test double, sees every call).
    private val items get() = repositoryProvider.workItemRepository()
    private val hierarchy get() = repositoryProvider.workItemRepository()

    /** Creates one item in its own write unit (joining an ambient one). */
    suspend fun create(cmd: ItemCreateCommand): Outcome<WorkItem> = guarded(CREATE_OP) { createBody(cmd) }

    /**
     * Creates one item inside the CALLER's unit (an atomic composite such as `create_work_tree`): the caller's unit
     * decides commit, and an [Outcome.Err] returned from its block rolls everything back. A per-root config fault
     * propagates as the exception.
     */
    suspend fun createInUnit(cmd: ItemCreateCommand): Outcome<WorkItem> = unitOfWork.write(CREATE_OP) { createBody(cmd) }

    /** Patches one item (fields, and optionally its parent) in one write unit. */
    suspend fun patch(cmd: ItemPatchCommand): Outcome<ItemPatchResult> = guarded(PATCH_OP) { patchBody(cmd) }

    /**
     * Deletes [itemId] in one write unit. Non-recursive with direct children fails with `invalid_request`
     * ([ItemCommandErrors.childCount]) and writes nothing. Recursive deletes the whole subtree deepest level first;
     * a fault anywhere rolls every row back.
     */
    suspend fun delete(
        itemId: UUID,
        recursive: Boolean
    ): Outcome<ItemDeleteResult> = guarded(DELETE_OP) { deleteBody(itemId, recursive) }

    // -------------------------------------------------------------------------
    // create
    // -------------------------------------------------------------------------

    private suspend fun WriteScope.createBody(cmd: ItemCreateCommand): Outcome<WorkItem> {
        val parentId = cmd.parentId
        val depth: Int
        val rootId: UUID
        if (parentId == null) {
            depth = 0
            rootId = cmd.id
        } else {
            val placement = hierarchy.resolveChildPlacement(parentId) ?: return Outcome.Err(ItemCommandErrors.parentNotFound(parentId))
            val parent = items.getById(parentId) ?: return Outcome.Err(ItemCommandErrors.parentNotFound(parentId))
            val facts = parentFacts(parent)
            val decision = policy.evaluate(TransitionSnapshot.forCreate(cmd.id, facts), Trigger.Structural.Create(facts))
            if (decision is Decision.Reject) return Outcome.Err(decision.error)
            depth = placement.depth
            rootId = placement.rootId
        }
        val item =
            try {
                WorkItem(
                    id = cmd.id,
                    parentId = parentId,
                    rootId = rootId,
                    title = cmd.title,
                    description = cmd.description,
                    summary = cmd.summary,
                    role = Role.QUEUE,
                    statusLabel = cmd.statusLabel,
                    priority = cmd.priority,
                    complexity = cmd.complexity,
                    requiresVerification = cmd.requiresVerification,
                    depth = depth,
                    metadata = cmd.metadata,
                    tags = cmd.tags,
                    type = cmd.type,
                    properties = cmd.properties
                )
            } catch (e: ValidationException) {
                return Outcome.Err(ItemCommandErrors.invalid(e.message ?: "Validation failed"))
            }
        val created = items.create(item)
        events.record(DomainEvent.ItemCreated(created.id, eventRootOf(created, hierarchy), created.parentId))
        return Outcome.Ok(created)
    }

    // -------------------------------------------------------------------------
    // patch
    // -------------------------------------------------------------------------

    private suspend fun WriteScope.patchBody(cmd: ItemPatchCommand): Outcome<ItemPatchResult> {
        val existing = items.getById(cmd.itemId) ?: return Outcome.Err(ItemCommandErrors.itemNotFound(cmd.itemId))
        val expected = cmd.expectedVersion
        if (expected != null && expected != existing.version) {
            return Outcome.Err(ItemCommandErrors.versionConflict(existing.id, expected, existing.version))
        }

        val change =
            when (val p = cmd.parent) {
                ParentChange.Keep -> ParentChange.Keep
                ParentChange.MoveToRoot -> if (existing.parentId == null) ParentChange.Keep else p
                is ParentChange.MoveUnder -> if (p.parentId == existing.parentId) ParentChange.Keep else p
            }

        var newParentId = existing.parentId
        var newDepth = existing.depth
        var newRootId = existing.rootId
        var followUps: List<FollowUp> = emptyList()
        if (change != ParentChange.Keep) {
            val target = (change as? ParentChange.MoveUnder)?.parentId
            var facts: ParentFacts? = null
            if (target != null) {
                val parent = items.getById(target) ?: return Outcome.Err(ItemCommandErrors.parentNotFound(target))
                if (target == existing.id) return Outcome.Err(ItemCommandErrors.selfParent(existing.id))
                // Fail CLOSED: an ancestor lookup that cannot be read must not let a cycle through.
                val chain =
                    try {
                        hierarchy.findAncestorChains(setOf(target))[target].orEmpty()
                    } catch (e: Exception) {
                        e.rethrowIfCancellation()
                        return Outcome.Err(ItemCommandErrors.hierarchyLookupFailed(target, LegacyFaults.message(e)))
                    }
                if (chain.any { it.id == existing.id }) return Outcome.Err(ItemCommandErrors.cycle(existing.id, target))
                facts = parentFacts(parent)
            }
            val decision = policy.evaluate(snapshotOf(existing), Trigger.Structural.Reparent(facts))
            when (decision) {
                is Decision.Reject -> return Outcome.Err(decision.error)
                is Decision.Allow -> followUps = decision.followUps
                is Decision.NotApplicable -> Unit
            }
            if (target != null) {
                val placement = hierarchy.resolveChildPlacement(target) ?: return Outcome.Err(ItemCommandErrors.parentNotFound(target))
                newParentId = target
                newDepth = placement.depth
                newRootId = placement.rootId
            } else {
                newParentId = null
                newDepth = 0
                newRootId = existing.id
            }
        }
        val reparented = change != ParentChange.Keep

        val updated =
            try {
                existing
                    .update { item ->
                        item.copy(
                            parentId = newParentId,
                            rootId = newRootId,
                            depth = newDepth,
                            title = cmd.title,
                            description = cmd.description,
                            summary = cmd.summary,
                            statusLabel = cmd.statusLabel,
                            priority = cmd.priority,
                            complexity = cmd.complexity,
                            requiresVerification = cmd.requiresVerification,
                            metadata = cmd.metadata,
                            tags = cmd.tags,
                            type = cmd.type,
                            properties = cmd.properties
                        )
                    }.also { it.validate() }
            } catch (e: ValidationException) {
                return Outcome.Err(ItemCommandErrors.invalid(e.message ?: "Validation failed"))
            }

        val oldRoot = if (reparented) eventRootOf(existing, hierarchy) else existing.id
        val stored = items.update(updated) ?: return Outcome.Err(ItemCommandErrors.itemNotFound(existing.id))

        if (!reparented) {
            val changed = itemChangedFields(existing, stored)
            if (changed.isNotEmpty()) events.record(DomainEvent.ItemUpdated(stored.id, eventRootOf(stored, hierarchy), changed))
            return Outcome.Ok(ItemPatchResult(stored, reparented = false, descendantsRestamped = 0, cascadeEvents = emptyList()))
        }

        val newRoot = newRootId ?: stored.id
        events.record(
            listOf(
                DomainEvent.ItemReparented(stored.id, oldRoot, ReparentSide.LEFT, existing.parentId, stored.parentId),
                DomainEvent.ItemReparented(
                    stored.id,
                    eventRootOf(stored, hierarchy),
                    ReparentSide.ENTERED,
                    existing.parentId,
                    stored.parentId
                )
            )
        )

        val depthDelta = newDepth - existing.depth
        var restamped = 0
        val descendants = hierarchy.findDescendants(existing.id)
        if (descendants.isNotEmpty() && (depthDelta != 0 || descendants.any { it.rootId != newRoot })) {
            restamped = hierarchy.restampSubtree(existing.id, depthDelta, newRoot)
            val rows =
                descendants.mapNotNull { d ->
                    val changed =
                        buildList {
                            if (d.rootId != newRoot) add("rootId")
                            if (depthDelta != 0) add("depth")
                        }
                    if (changed.isEmpty()) null else DomainEvent.ItemUpdated(d.id, newRoot, changed)
                }
            events.record(rows)
        }

        val cascades = cascade(existing.parentId, followUps)
        return Outcome.Ok(ItemPatchResult(stored, reparented = true, descendantsRestamped = restamped, cascadeEvents = cascades))
    }

    // -------------------------------------------------------------------------
    // delete
    // -------------------------------------------------------------------------

    private suspend fun WriteScope.deleteBody(
        itemId: UUID,
        recursive: Boolean
    ): Outcome<ItemDeleteResult> {
        if (!recursive) {
            val children =
                try {
                    items.findChildren(itemId)
                } catch (e: Exception) {
                    e.rethrowIfCancellation()
                    return fault("Failed to check children: ${LegacyFaults.message(e)}")
                }
            if (children.isNotEmpty()) return Outcome.Err(ItemCommandErrors.hasChildren(itemId, children.size))
        }
        val root = items.getById(itemId) ?: return Outcome.Err(ItemCommandErrors.itemNotFound(itemId))

        var descendantsDeleted = 0
        if (recursive) {
            val descendants =
                try {
                    hierarchy.findDescendants(itemId)
                } catch (e: Exception) {
                    e.rethrowIfCancellation()
                    return fault("Failed to find descendants: ${LegacyFaults.message(e)}")
                }
            if (descendants.isNotEmpty()) {
                val released = claimService.releaseLeases(descendants.map { it.id }.toSet())
                if (released is Outcome.Err) {
                    return fault(
                        "Failed to release resource leases for ${descendants.size} descendants: ${LegacyFaults.message(released.error)}"
                    )
                }
                val byId = descendants.associateBy { it.id }
                // Levels derive from parentId links, NOT the stored depth (which can be stale): within one level no
                // row is the parent of another, so each batch delete is foreign-key safe.
                for (levelIds in descendantLevelsDeepestFirst(itemId, descendants)) {
                    val levelItems = levelIds.mapNotNull { byId[it] }
                    try {
                        val rows = cascadeRows(levelItems)
                        val deleted = items.deleteAll(levelIds)
                        if (deleted > 0) events.record(rows)
                        descendantsDeleted += deleted
                    } catch (e: Exception) {
                        e.rethrowIfCancellation()
                        return fault("Failed to delete descendants: ${LegacyFaults.message(e)}")
                    }
                }
            }
        }

        val released = claimService.releaseLeases(setOf(itemId))
        if (released is Outcome.Err) {
            return fault("Failed to release resource leases for '$itemId': ${LegacyFaults.message(released.error)}")
        }
        val rows = cascadeRows(listOf(root))
        val deleted =
            try {
                items.delete(itemId)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                return fault(LegacyFaults.message(e))
            }
        // A missing root rolls the whole unit back (descendant deletes and lease releases included).
        if (!deleted) return Outcome.Err(ItemCommandErrors.itemNotFound(itemId))
        events.record(rows)

        val followUps =
            when (val decision = policy.evaluate(snapshotOf(root), Trigger.Structural.Delete)) {
                is Decision.Allow -> decision.followUps
                else -> emptyList()
            }
        val cascades = cascade(root.parentId, followUps)
        return Outcome.Ok(ItemDeleteResult(itemId, descendantsDeleted, cascades))
    }

    /**
     * The rows deleting [doomed] produces, built BEFORE the delete (the rows they describe are about to vanish): one
     * `note.deleted` and one `dependency.removed` (cause `cascade`) per row the foreign-key cascade removes, then one
     * `item.deleted` per item. All carry the item's pre-delete root; an edge is recorded once, under the first deleted
     * item that references it.
     */
    private suspend fun cascadeRows(doomed: List<WorkItem>): List<DomainEvent> {
        if (doomed.isEmpty()) return emptyList()
        val roots = doomed.associate { it.id to eventRootOf(it, hierarchy) }
        // Chunked: a level of a large subtree can exceed the bound-variable limit of one IN list (the edge query
        // binds every id twice).
        val notesByItem = HashMap<UUID, List<io.github.jpicklyk.mcptask.current.application.port.NoteRef>>()
        val edgesByItem = HashMap<UUID, List<io.github.jpicklyk.mcptask.current.domain.model.Dependency>>()
        for (chunk in doomed.map { it.id }.chunked(PRE_READ_CHUNK)) {
            val ids = chunk.toSet()
            notesByItem.putAll(repositoryProvider.noteRepository().findRefsByItemIds(ids))
            edgesByItem.putAll(repositoryProvider.dependencyRepository().findByItemIds(ids))
        }
        val seenEdges = HashSet<UUID>()
        val out = mutableListOf<DomainEvent>()
        for (item in doomed) {
            val root = roots.getValue(item.id)
            for (note in notesByItem[item.id].orEmpty()) out += noteDeletedEvent(note, root, DeleteCause.CASCADE)
            for (edge in edgesByItem[item.id].orEmpty()) {
                if (seenEdges.add(edge.id)) out += dependencyRemovedEvent(edge, root, DeleteCause.CASCADE)
            }
        }
        for (item in doomed) out += DomainEvent.ItemDeleted(item.id, roots.getValue(item.id))
        return out
    }

    /**
     * Groups [descendants] of [rootId] by traversal level (direct children are level 1, any other descendant is its
     * parent's level + 1) and returns the id sets deepest level first.
     */
    private fun descendantLevelsDeepestFirst(
        rootId: UUID,
        descendants: List<WorkItem>
    ): List<Set<UUID>> {
        val parentOf = descendants.associate { it.id to it.parentId }
        val levelCache = HashMap<UUID, Int>(descendants.size)

        fun levelOf(itemId: UUID): Int {
            levelCache[itemId]?.let { return it }
            val chain = ArrayList<UUID>()
            var current: UUID? = itemId
            var base = 0
            while (current != null && current != rootId) {
                val known = levelCache[current]
                if (known != null) {
                    base = known
                    break
                }
                chain.add(current)
                current = parentOf[current]
            }
            var level = base
            for (node in chain.asReversed()) {
                level += 1
                levelCache[node] = level
            }
            return levelCache.getValue(itemId)
        }

        return descendants
            .groupBy({ levelOf(it.id) }, { it.id })
            .toSortedMap(compareByDescending { it })
            .values
            .map { it.toSet() }
    }

    // -------------------------------------------------------------------------
    // shared
    // -------------------------------------------------------------------------

    /** Runs the structural [followUps] (the OLD parent [oldParentId]'s re-evaluation) in the current unit. */
    private suspend fun cascade(
        oldParentId: UUID?,
        followUps: List<FollowUp>
    ): List<AdvanceCascadeEvent> {
        if (oldParentId == null || followUps.isEmpty()) return emptyList()
        val oldParent = items.getById(oldParentId) ?: return emptyList()
        return advanceServiceFactory.forItem(oldParent).cascadeInUnit(followUps)
    }

    /**
     * The facts the closed-parent rule reads. The lifecycle is resolved only for a TERMINAL parent (the only role the
     * rule looks at), so the common path reads no per-root config.
     */
    private suspend fun parentFacts(parent: WorkItem): ParentFacts {
        val lifecycle =
            if (parent.role == Role.TERMINAL) {
                configResolver.resolveSchema(parent)?.lifecycleMode ?: LifecycleMode.AUTO
            } else {
                LifecycleMode.AUTO
            }
        return ParentFacts(parent.id, parent.role, lifecycle)
    }

    private fun snapshotOf(item: WorkItem): TransitionSnapshot =
        TransitionSnapshot(item = ItemFacts(id = item.id, role = item.role, previousRole = item.previousRole, parentId = item.parentId))

    private fun <T> fault(message: String): Outcome<T> = Outcome.Err(DomainError(ErrorCode.INTERNAL, message))

    /**
     * Runs [block] as one write unit labelled [op]. A per-root config fault rolls the unit back and is rethrown after
     * it returns (thrown out of the block, the outermost unit runner would translate it into a store fault); any
     * other exception the unit rethrows becomes its [LegacyFaults.fault] error. Cancellation is rethrown.
     */
    private suspend fun <T> guarded(
        op: String,
        block: suspend WriteScope.() -> Outcome<T>
    ): Outcome<T> {
        var configFault: PerRootConfigUnavailableException? = null
        val outcome =
            try {
                unitOfWork.write(op) {
                    configFault = null
                    try {
                        block()
                    } catch (e: PerRootConfigUnavailableException) {
                        configFault = e
                        Outcome.Err(DomainError(ErrorCode.INTERNAL, "Unit '$op' rolled back: ${e.message}"))
                    }
                }
            } catch (e: PerRootConfigUnavailableException) {
                throw e
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                Outcome.Err(LegacyFaults.fault(e))
            }
        configFault?.let { throw it }
        return outcome
    }

    companion object {
        private const val CREATE_OP = "ItemCommandService.create"
        private const val PATCH_OP = "ItemCommandService.patch"
        private const val DELETE_OP = "ItemCommandService.delete"

        /** Ids per pre-read query of a delete's cascade rows (the edge query binds each id twice). */
        private const val PRE_READ_CHUNK = 200
    }
}

/**
 * Builds and classifies the [DomainError]s [ItemCommandService] returns, so each adapter can map them to its 3.x
 * wire text without parsing messages. Codes: `not_found` (item or parent: compare [ErrorDetail.NotFound.id]),
 * `version_conflict`, `cycle_detected`, `invalid_transition` (closed parent; its detail's `itemId` is the parent),
 * `invalid_request` (self-parent, has-children, a domain validation failure), `internal` (a store fault).
 */
object ItemCommandErrors {
    private const val FIELD_PARENT = "parentId"
    private const val FIELD_RECURSIVE = "recursive"
    private const val FIELD_ITEM = "item"
    private const val HIERARCHY_LOOKUP_PREFIX = "failed to verify hierarchy for parent "

    fun itemNotFound(id: UUID): DomainError = notFound(id, "WorkItem not found with id: $id")

    fun parentNotFound(parentId: UUID): DomainError = notFound(parentId, "Parent item $parentId not found")

    private fun notFound(
        id: UUID,
        message: String
    ): DomainError =
        DomainError(
            code = ErrorCode.NOT_FOUND,
            message = message,
            detail = ErrorDetail.NotFound(EntityKind.ITEM, id.toString()),
            fixArgs = mapOf("kind" to "item", "id" to id.toString())
        )

    fun versionConflict(
        id: UUID,
        expected: Long,
        actual: Long
    ): DomainError =
        DomainError(
            code = ErrorCode.VERSION_CONFLICT,
            message = VersionConflictException.MESSAGE,
            detail = ErrorDetail.VersionConflict(EntityKind.ITEM, id.toString(), expected, actual),
            fixArgs = mapOf("kind" to "item", "id" to id.toString(), "actual" to actual.toString())
        )

    fun selfParent(id: UUID): DomainError =
        DomainError(
            code = ErrorCode.INVALID_REQUEST,
            message = "An item cannot be its own parent",
            detail = ErrorDetail.InvalidRequest(listOf(FieldViolation(FIELD_PARENT, "cannot be its own parent", id.toString())))
        )

    fun cycle(
        itemId: UUID,
        parentId: UUID
    ): DomainError =
        DomainError(
            code = ErrorCode.CYCLE_DETECTED,
            message = "Cannot re-parent an item under its own descendant",
            detail = ErrorDetail.CycleDetected(listOf(itemId, parentId))
        )

    /** D6: `invalid_request` until the error catalog assigns a code; the count rides in the `recursive` field. */
    fun hasChildren(
        id: UUID,
        childCount: Int
    ): DomainError =
        DomainError(
            code = ErrorCode.INVALID_REQUEST,
            message = "Item $id has $childCount child item(s)",
            detail =
                ErrorDetail.InvalidRequest(
                    listOf(FieldViolation(FIELD_RECURSIVE, "item has children; delete recursively", childCount.toString()))
                )
        )

    fun invalid(message: String): DomainError =
        DomainError(
            code = ErrorCode.INVALID_REQUEST,
            message = message,
            detail = ErrorDetail.InvalidRequest(listOf(FieldViolation(FIELD_ITEM, message)))
        )

    fun hierarchyLookupFailed(
        parentId: UUID,
        message: String
    ): DomainError = DomainError(ErrorCode.INTERNAL, "$HIERARCHY_LOOKUP_PREFIX'$parentId': $message")

    /** The direct-child count of a non-recursive delete refused for children, or null for any other error. */
    fun childCount(error: DomainError): Int? = fieldOf(error, FIELD_RECURSIVE)?.received?.toIntOrNull()

    fun isSelfParent(error: DomainError): Boolean = fieldOf(error, FIELD_PARENT) != null

    fun isHierarchyLookupFailure(error: DomainError): Boolean =
        error.code == ErrorCode.INTERNAL && error.message.startsWith(HIERARCHY_LOOKUP_PREFIX)

    /** True when [error] is the closed-parent rejection (a create or reparent under a TERMINAL, AUTO parent). */
    fun isClosedParent(error: DomainError): Boolean = error.code == ErrorCode.INVALID_TRANSITION

    /** The id a `not_found` error names, or null. */
    fun notFoundId(error: DomainError): String? = (error.detail as? ErrorDetail.NotFound)?.takeIf { error.code == ErrorCode.NOT_FOUND }?.id

    private fun fieldOf(
        error: DomainError,
        field: String
    ): FieldViolation? =
        (error.detail as? ErrorDetail.InvalidRequest)
            ?.takeIf { error.code == ErrorCode.INVALID_REQUEST }
            ?.fields
            ?.firstOrNull { it.field == field }
}
