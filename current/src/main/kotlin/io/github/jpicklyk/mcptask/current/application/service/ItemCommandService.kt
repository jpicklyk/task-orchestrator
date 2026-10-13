@file:OptIn(StoreWrite::class)

package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.config.EffectiveConfigResolver
import io.github.jpicklyk.mcptask.current.application.knowledge.document.DocumentService
import io.github.jpicklyk.mcptask.current.application.knowledge.link.LinkService
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.StoreWrite
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.application.support.LegacyFaults
import io.github.jpicklyk.mcptask.current.application.support.rethrowIfCancellation
import io.github.jpicklyk.mcptask.current.application.support.runCatchingNonCancellation
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.EntityKind
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.FieldViolation
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.error.VersionConflictException
import io.github.jpicklyk.mcptask.current.domain.event.DeleteCause
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import io.github.jpicklyk.mcptask.current.domain.event.PinReason
import io.github.jpicklyk.mcptask.current.domain.event.ReparentSide
import io.github.jpicklyk.mcptask.current.domain.lifecycle.Decision
import io.github.jpicklyk.mcptask.current.domain.lifecycle.FollowUp
import io.github.jpicklyk.mcptask.current.domain.lifecycle.ItemFacts
import io.github.jpicklyk.mcptask.current.domain.lifecycle.ParentFacts
import io.github.jpicklyk.mcptask.current.domain.lifecycle.SchemaFacts
import io.github.jpicklyk.mcptask.current.domain.lifecycle.TransitionPolicy
import io.github.jpicklyk.mcptask.current.domain.lifecycle.TransitionSnapshot
import io.github.jpicklyk.mcptask.current.domain.lifecycle.Trigger
import io.github.jpicklyk.mcptask.current.domain.lifecycle.wireOrNone
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.LifecycleMode
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.SchemaPin
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.validation.ValidationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
    val properties: String? = null,
    /** The item's trait names, in order; stored in `item_traits`, never in [properties]. */
    val traits: List<String> = emptyList()
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
    val properties: String?,
    /** The FINAL trait list, like every other patch field. */
    val traits: List<String>
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

/** A committed `repin`: the stored [item] and whether its pin moved ([repinned] false: nothing was written). */
data class ItemRepinResult(
    val item: WorkItem,
    val repinned: Boolean
)

/** A committed delete of [id] and [descendantsDeleted] descendants, plus the cascades it ran on the old parent. */
data class ItemDeleteResult(
    val id: UUID,
    val descendantsDeleted: Int,
    val cascadeEvents: List<AdvanceCascadeEvent>
)

/**
 * The single owner of item creates, patches and deletes, shared by `items_write` (`create`, `update`, `delete`,
 * `create_tree`) (plan sections 3.3, 3.5, 3.6). JSON-free and transport-free: failures are [DomainError]s the
 * adapters map to their wire shapes ([ItemCommandErrors] classifies them).
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
 *   foreign-key cascade implies. The same delete also removes, through [LinkService.onItemsDeleted], the links the removed
 *   notes and items were the source of and the resolved links into the removed items (except `adopted_by`); no event.
 *   Every document anchored to a removed item (by its `root_item_id`, archived and adopted ones included) is deleted
 *   first through [DocumentService.deleteAnchoredTo], with its link cleanup and one `document.deleted` row each,
 *   recorded before that item's `item.deleted`.
 * - **Link roots** follow a move to another root: the links sourced from the notes of the moved item and of every
 *   descendant are restamped to the new root ([LinkService.onItemsMoved]), in the same unit, with no event, so the
 *   old root's delete cascade no longer removes them.
 *
 * - **Pins** (plan section 3.8) are taken through [schemaPinService] at the pin points this service owns: a create, a
 *   patch that changes the type, moves the item to another root, or changes its traits (one `item.pinned` row per
 *   patch, reason precedence type_change > reparent > trait_change), and [repin]. A move to another root also re-pins
 *   every descendant against the new root (one `reparent` row each, batched). A delete first re-points any surviving
 *   pin naming a config version the delete cascades away. A title-only patch or a same-root move leaves the pin
 *   untouched. With pins off ([SchemaPinService.enabled] false) nothing is pinned.
 *
 * A [PerRootConfigUnavailableException] (resolving a TERMINAL parent's lifecycle, or taking a pin) rolls the unit back
 * and is rethrown for the caller's `config_unavailable` mapping; one on a cascade target skips that cascade only.
 */
class ItemCommandService(
    private val repositoryProvider: RepositoryProvider,
    private val configResolver: EffectiveConfigResolver,
    private val unitOfWork: UnitOfWork,
    private val advanceServiceFactory: AdvanceServiceFactory,
    private val claimService: ClaimService,
    private val policy: TransitionPolicy = TransitionPolicy(),
    private val schemaPinService: SchemaPinService = SchemaPinService(configResolver)
) {
    // The composite work-item store: every item, placement and hierarchy call goes through the one instance a
    // provider hands out (a provider that wraps it, such as a fault-injecting test double, sees every call).
    private val items get() = repositoryProvider.workItemRepository()
    private val hierarchy get() = repositoryProvider.workItemRepository()
    private val links = LinkService(repositoryProvider, unitOfWork)
    private val documents = DocumentService(repositoryProvider, unitOfWork)

    /** Creates one item in its own write unit (joining an ambient one). */
    suspend fun create(cmd: ItemCreateCommand): Outcome<WorkItem> = guarded(CREATE_OP) { createBody(cmd) }

    /**
     * Creates one item inside the CALLER's unit (an atomic composite such as `items_write` `create_tree`): the caller's unit
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

    /**
     * Re-pins [itemId] to the current config heads in one write unit (the `repin` pin point). A non-null
     * [expectedVersion] must equal the row's version (`version_conflict` otherwise). When the recomputed pin equals the
     * stored one nothing is written and no event is recorded ([ItemRepinResult.repinned] false); otherwise the row is
     * updated (version + 1) and one `item.pinned` row (reason `repin`) is recorded.
     */
    suspend fun repin(
        itemId: UUID,
        expectedVersion: Long?
    ): Outcome<ItemRepinResult> = guarded(REPIN_OP) { repinBody(itemId, expectedVersion) }

    // -------------------------------------------------------------------------
    // create
    // -------------------------------------------------------------------------

    private suspend fun WriteScope.createBody(cmd: ItemCreateCommand): Outcome<WorkItem> {
        val parentId = cmd.parentId
        val depth: Int
        val rootId: UUID
        // A missing type needs no config read; the undeclared check below needs the placement's root.
        if (cmd.type.isNullOrBlank()) return Outcome.Err(ItemCommandErrors.typeRequired(configResolver.availableTypes(rootOfCreate(cmd))))
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
        undeclaredTypeError(cmd.type, rootId)?.let { return Outcome.Err(it) }
        propertiesCarryTraits(cmd.properties)?.let { return Outcome.Err(it) }
        if (cmd.traits.isNotEmpty()) undeclaredTraitError(cmd.traits, rootId)?.let { return Outcome.Err(it) }
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
                    properties = cmd.properties,
                    traits = cmd.traits
                )
            } catch (e: ValidationException) {
                return Outcome.Err(ItemCommandErrors.invalid(e.message ?: "Validation failed"))
            }
        // A type whose base schema is `lifecycle: none` creates a lifecycle-free item (no role). Lifecycle-free needs
        // pins: with pins off every item is created in QUEUE.
        val pin = if (schemaPinService.enabled) schemaPinService.pinFor(item, rootId, now) else null
        val pinned =
            when {
                pin == null -> item
                pin.lifecycle == LifecycleMode.NONE -> item.copy(role = null, pin = pin)
                else -> item.copy(pin = pin)
            }
        val created = items.create(pinned)
        val eventRoot = eventRootOf(created, hierarchy)
        events.record(DomainEvent.ItemCreated(created.id, eventRoot, created.parentId))
        created.pin?.let { events.record(SchemaPinService.pinnedEvent(created.id, eventRoot, null, it, PinReason.CREATE)) }
        return Outcome.Ok(created)
    }

    /** The root a create lands in, for the missing-type rejection's `availableTypes` (parent row read, no policy). */
    private suspend fun WriteScope.rootOfCreate(cmd: ItemCreateCommand): UUID {
        val parentId = cmd.parentId ?: return cmd.id
        return hierarchy.resolveChildPlacement(parentId)?.rootId ?: cmd.id
    }

    /**
     * The U7 undeclared-type rejection: [type] (non-blank) must be one of the root's declared schema keys whenever any
     * is declared; an empty set accepts any type (nothing can validate it). Exact, case-sensitive match.
     */
    private suspend fun undeclaredTypeError(
        type: String?,
        rootId: UUID?
    ): DomainError? {
        if (type == null) return null
        val available = configResolver.availableTypes(rootId)
        return if (available.isNotEmpty() && type !in available) ItemCommandErrors.undeclaredType(type, available) else null
    }

    /**
     * The U7 analogue for traits: every name must be one of the root's declared traits whenever any is declared; an empty
     * declared set accepts any well-formed name. Exact, case-sensitive match. Names are checked in the given order.
     */
    private suspend fun undeclaredTraitError(
        traits: List<String>,
        rootId: UUID?
    ): DomainError? {
        val available = configResolver.availableTraits(listOfNotNull(rootId))
        if (available.isEmpty()) return null
        val missing = traits.firstOrNull { it !in available } ?: return null
        return ItemCommandErrors.undeclaredTrait(missing, available)
    }

    /**
     * `traits` is a typed field: a `properties` string that parses to a JSON object holding a `traits` key is rejected. An
     * unparseable or non-object string is not inspected (it is stored as-is, as before).
     */
    private fun propertiesCarryTraits(properties: String?): DomainError? {
        if (properties.isNullOrBlank()) return null
        val parsed = runCatchingNonCancellation { Json.parseToJsonElement(properties) }.getOrNull()
        return if (parsed is JsonObject && "traits" in parsed) ItemCommandErrors.propertiesCarryTraits() else null
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

        // The type rule applies only when the patch changes or clears the type (D2): `untyped` and stale pre-4.0
        // values stay editable. Rejection precedes the write, so version and modifiedAt are untouched.
        if (cmd.type != existing.type) {
            if (cmd.type.isNullOrBlank()) {
                return Outcome.Err(ItemCommandErrors.typeRequired(configResolver.availableTypes(newRootId ?: existing.id)))
            }
            undeclaredTypeError(cmd.type, newRootId ?: existing.id)?.let { return Outcome.Err(it) }
        }

        // A changed `properties` value holding a traits key is rejected (an unchanged legacy residue stays editable).
        if (cmd.properties != existing.properties) propertiesCarryTraits(cmd.properties)?.let { return Outcome.Err(it) }
        // Declared-set rule only when the patch changes the list (a stale trait name already on the item stays editable).
        if (cmd.traits != existing.traits) {
            undeclaredTraitError(cmd.traits, newRootId ?: existing.id)?.let { return Outcome.Err(it) }
        }

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
                            properties = cmd.properties,
                            traits = cmd.traits
                        )
                    }.also { it.validate() }
            } catch (e: ValidationException) {
                return Outcome.Err(ItemCommandErrors.invalid(e.message ?: "Validation failed"))
            }

        // One pin point per patch, by precedence: type change, then a move to another root, then a trait change.
        val pinReason =
            when {
                !schemaPinService.enabled -> null
                cmd.type != existing.type -> PinReason.TYPE_CHANGE
                reparented && newRootId != existing.rootId -> PinReason.REPARENT
                cmd.traits != existing.traits -> PinReason.TRAIT_CHANGE
                else -> null
            }
        val toStore =
            if (pinReason != null) {
                when (val applied = applyPin(existing, updated, schemaPinService.pinFor(updated, newRootId, now), pinReason)) {
                    is Outcome.Err -> return applied
                    is Outcome.Ok -> applied.value
                }
            } else {
                updated
            }

        // A move to another root re-pins every descendant that changes root. Their pins are taken, and checked for a
        // lifecycle class crossing, BEFORE the first write: a rejection leaves nothing written.
        val newRoot = newRootId ?: existing.id
        var descendants: List<WorkItem> = emptyList()
        var descendantPins: Map<UUID, SchemaPin> = emptyMap()
        if (reparented) {
            descendants = hierarchy.findDescendants(existing.id)
            val moved = descendants.filter { it.rootId != newRoot }
            if (schemaPinService.enabled && moved.isNotEmpty()) {
                descendantPins = schemaPinService.pinsFor(moved, newRoot, now)
                moved
                    .firstOrNull { SchemaPinService.crossesLifecycleClass(it.role, descendantPins.getValue(it.id)) }
                    ?.let { return Outcome.Err(ItemCommandErrors.lifecycleCrossing(it.id, it.role, PinReason.REPARENT)) }
            }
        }

        val oldRoot = if (reparented) eventRootOf(existing, hierarchy) else existing.id
        val stored = items.update(toStore) ?: return Outcome.Err(ItemCommandErrors.itemNotFound(existing.id))
        afterFlip(existing, stored)?.let { return Outcome.Err(it) }

        if (!reparented) {
            val changed = itemChangedFields(existing, stored)
            val eventRoot = eventRootOf(stored, hierarchy)
            if (changed.isNotEmpty()) events.record(DomainEvent.ItemUpdated(stored.id, eventRoot, changed))
            recordPinned(stored, eventRoot, existing.pin, pinReason)
            return Outcome.Ok(ItemPatchResult(stored, reparented = false, descendantsRestamped = 0, cascadeEvents = emptyList()))
        }

        val enteredRoot = eventRootOf(stored, hierarchy)
        events.record(
            listOf(
                DomainEvent.ItemReparented(stored.id, oldRoot, ReparentSide.LEFT, existing.parentId, stored.parentId),
                DomainEvent.ItemReparented(
                    stored.id,
                    enteredRoot,
                    ReparentSide.ENTERED,
                    existing.parentId,
                    stored.parentId
                )
            )
        )
        // A move reports no item.updated for the moved item itself; a lifecycle flip in the same patch still does.
        if (stored.role != existing.role) events.record(DomainEvent.ItemUpdated(stored.id, enteredRoot, listOf("role")))
        recordPinned(stored, enteredRoot, existing.pin, pinReason)

        val depthDelta = newDepth - existing.depth
        var restamped = 0
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
            repinMovedDescendants(descendants, newRoot, descendantPins)
        }
        if (newRoot != oldRoot) restampLinkRoots(listOf(stored.id) + descendants.map { it.id }, newRoot)

        val cascades = cascade(existing.parentId, followUps)
        return Outcome.Ok(ItemPatchResult(stored, reparented = true, descendantsRestamped = restamped, cascadeEvents = cascades))
    }

    /**
     * Restamps to [newRoot] the links sourced from the notes of [movedIds] (the moved item and its descendants) and from
     * the items themselves. Note ids are read in chunks (a large subtree can exceed one IN list's bound-variable limit).
     */
    private suspend fun restampLinkRoots(
        movedIds: List<UUID>,
        newRoot: UUID
    ) {
        val noteIds = ArrayList<UUID>()
        for (chunk in movedIds.chunked(PRE_READ_CHUNK)) {
            repositoryProvider
                .noteRepository()
                .findRefsByItemIds(chunk.toSet())
                .values
                .forEach { refs -> refs.mapTo(noteIds) { it.id } }
        }
        links.onItemsMoved(movedIds, noteIds, newRoot)
    }

    /**
     * A move to another root re-pins every descendant that changed root against [newRoot]'s heads (reason `reparent`;
     * the moved item's own precedence is unaffected), in one batched write, so no row keeps a pin naming the old root's
     * config versions (which that root's delete cascades away). The restamp already bumped each row's version. [pins]
     * were taken, and checked (none crosses its row's lifecycle class), before the patch's first write.
     */
    private suspend fun WriteScope.repinMovedDescendants(
        descendants: List<WorkItem>,
        newRoot: UUID,
        pins: Map<UUID, SchemaPin>
    ) {
        if (!schemaPinService.enabled) return
        val moved = descendants.filter { it.rootId != newRoot }
        if (moved.isEmpty()) return
        items.writePins(pins)
        events.record(moved.map { SchemaPinService.pinnedEvent(it.id, newRoot, it.pin, pins.getValue(it.id), PinReason.REPARENT) })
    }

    /**
     * Before [doomed] are deleted (their `config_versions` rows cascade with them), re-points every SURVIVING item whose
     * root pin names one of those versions at its own root's heads (reason `repin`; no version bump), so the delete never
     * trips the pin foreign key. Unreachable with consistent placement (a cross-root move re-pins its subtree); it covers
     * rows whose `root_id` disagrees with the tree. A survivor whose root is itself doomed pins against the server only.
     * A re-point never changes a survivor's lifecycle class: a crossing pin returns the `invalid_transition` (trigger
     * `repin`) for that survivor before anything is written; null when the re-point went through.
     */
    private suspend fun WriteScope.repointSurvivingPins(doomed: Set<UUID>): DomainError? {
        if (!schemaPinService.enabled) return null
        val survivors = items.findPinnedToConfigOf(doomed)
        if (survivors.isEmpty()) return null
        val pins = HashMap<UUID, SchemaPin>()
        for ((root, group) in survivors.groupBy { it.rootId?.takeUnless { r -> r in doomed } }) {
            pins.putAll(schemaPinService.pinsFor(group, root, now))
        }
        survivors
            .firstOrNull { SchemaPinService.crossesLifecycleClass(it.role, pins.getValue(it.id)) }
            ?.let { return ItemCommandErrors.lifecycleCrossing(it.id, it.role, PinReason.REPIN) }
        items.writePins(pins)
        events.record(
            survivors.map { SchemaPinService.pinnedEvent(it.id, eventRootOf(it, hierarchy), it.pin, pins.getValue(it.id), PinReason.REPIN) }
        )
        return null
    }

    /**
     * [candidate] (the item as the write would store it) carrying [pin], taken for [reason] on [existing]. A pin in the
     * item's own lifecycle class is simply attached. A crossing pin (U13b flip rule) is allowed only for a type change
     * or `repin`:
     * - into lifecycle-free: only from QUEUE or TERMINAL and only with no BLOCKS edge in either direction; the role,
     *   previous role and status label are cleared and the role-change time stamped (the claim is cleared after the
     *   write, [afterFlip]);
     * - out of lifecycle-free: the item re-enters QUEUE, subject to the create closed-parent rule.
     * Anything else is `invalid_transition` for [existing] with `fromRole` its role (or `none`) and trigger the
     * [reason]'s wire value. Reads only: nothing is written here.
     */
    private suspend fun WriteScope.applyPin(
        existing: WorkItem,
        candidate: WorkItem,
        pin: SchemaPin,
        reason: PinReason
    ): Outcome<WorkItem> {
        if (!SchemaPinService.crossesLifecycleClass(existing.role, pin)) return Outcome.Ok(candidate.copy(pin = pin))
        val crossing = ItemCommandErrors.lifecycleCrossing(existing.id, existing.role, reason)
        if (reason != PinReason.TYPE_CHANGE && reason != PinReason.REPIN) return Outcome.Err(crossing)
        if (existing.role != null) {
            if (existing.role != Role.QUEUE && existing.role != Role.TERMINAL) return Outcome.Err(crossing)
            if (hasBlocksEdge(existing.id)) return Outcome.Err(crossing)
            return Outcome.Ok(candidate.copy(pin = pin, role = null, previousRole = null, statusLabel = null, roleChangedAt = now))
        }
        val parentId = candidate.parentId
        if (parentId != null) {
            val parent = items.getById(parentId) ?: return Outcome.Err(ItemCommandErrors.parentNotFound(parentId))
            val facts = parentFacts(parent)
            val decision = policy.evaluate(TransitionSnapshot.forCreate(existing.id, facts), Trigger.Structural.Create(facts))
            if (decision is Decision.Reject) return Outcome.Err(decision.error)
        }
        return Outcome.Ok(candidate.copy(pin = pin, role = Role.QUEUE, previousRole = null, roleChangedAt = now))
    }

    /** True when [itemId] is either endpoint of a BLOCKS edge (an IS_BLOCKED_BY input is stored as BLOCKS). */
    private suspend fun hasBlocksEdge(itemId: UUID): Boolean =
        repositoryProvider.dependencyRepository().findByItemId(itemId).any { it.type != DependencyType.RELATES_TO }

    /**
     * After a write that moved [existing] into lifecycle-free ([stored] has no role), clears its claim (the item write
     * never touches the claim columns). Returns the failure, or null.
     */
    private suspend fun afterFlip(
        existing: WorkItem,
        stored: WorkItem
    ): DomainError? {
        if (existing.role == null || stored.role != null || existing.claimedBy == null) return null
        return (claimService.clearClaim(existing.id) as? Outcome.Err)?.error
    }

    /** Records the one `item.pinned` row of a patch pin point ([reason] null: the patch fired none). */
    private suspend fun WriteScope.recordPinned(
        stored: WorkItem,
        eventRoot: UUID,
        previous: SchemaPin?,
        reason: PinReason?
    ) {
        if (reason == null) return
        val pin = stored.pin ?: return
        events.record(SchemaPinService.pinnedEvent(stored.id, eventRoot, previous, pin, reason))
    }

    // -------------------------------------------------------------------------
    // repin
    // -------------------------------------------------------------------------

    private suspend fun WriteScope.repinBody(
        itemId: UUID,
        expectedVersion: Long?
    ): Outcome<ItemRepinResult> {
        val existing = items.getById(itemId) ?: return Outcome.Err(ItemCommandErrors.itemNotFound(itemId))
        if (expectedVersion != null && expectedVersion != existing.version) {
            return Outcome.Err(ItemCommandErrors.versionConflict(existing.id, expectedVersion, existing.version))
        }
        if (!schemaPinService.enabled) return Outcome.Ok(ItemRepinResult(existing, repinned = false))
        val pin = schemaPinService.pinFor(existing, existing.rootId, now)
        if (SchemaPinService.samePin(existing.pin, pin)) return Outcome.Ok(ItemRepinResult(existing, repinned = false))
        val target =
            when (val applied = applyPin(existing, existing, pin, PinReason.REPIN)) {
                is Outcome.Err -> return applied
                is Outcome.Ok -> applied.value
            }
        val stored =
            items.update(existing.update { target })
                ?: return Outcome.Err(ItemCommandErrors.itemNotFound(itemId))
        afterFlip(existing, stored)?.let { return Outcome.Err(it) }
        val eventRoot = eventRootOf(stored, hierarchy)
        // A lifecycle flip (no transition row, no cascade) is reported as an item.updated naming the changed fields.
        if (stored.role != existing.role) events.record(DomainEvent.ItemUpdated(stored.id, eventRoot, itemChangedFields(existing, stored)))
        events.record(SchemaPinService.pinnedEvent(stored.id, eventRoot, existing.pin, pin, PinReason.REPIN))
        return Outcome.Ok(ItemRepinResult(stored, repinned = true))
    }

    // -------------------------------------------------------------------------
    // delete
    // -------------------------------------------------------------------------

    /**
     * The runs on root [rootId] that are not closed. A provider that serves no runs (its `runStore()` throws
     * [UnsupportedOperationException], as the interface default does) has none.
     */
    private suspend fun openRunsOnRoot(rootId: UUID): Int {
        val runStore =
            try {
                repositoryProvider.runStore()
            } catch (e: UnsupportedOperationException) {
                return 0
            }
        return runStore.countNotClosedByRoot(rootId)
    }

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
        if (root.parentId == null) {
            val openRuns = openRunsOnRoot(itemId)
            if (openRuns > 0) return Outcome.Err(ItemCommandErrors.rootHasOpenRuns(itemId, openRuns))
        }

        var descendantsDeleted = 0
        var descendants: List<WorkItem> = emptyList()
        if (recursive) {
            descendants =
                try {
                    hierarchy.findDescendants(itemId)
                } catch (e: Exception) {
                    e.rethrowIfCancellation()
                    return fault("Failed to find descendants: ${LegacyFaults.message(e)}")
                }
        }
        repointSurvivingPins(descendants.mapTo(HashSet()) { it.id } + itemId)?.let { return Outcome.Err(it) }
        if (recursive) {
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
                        val noteIds = ArrayList<UUID>()
                        val rows = cascadeRows(levelItems, noteIds)
                        val docs = documents.deleteAnchoredTo(levelIds)
                        if (docs is Outcome.Err) return docs
                        links.onItemsDeleted(levelIds, noteIds)
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
        val rootNoteIds = ArrayList<UUID>()
        val rows = cascadeRows(listOf(root), rootNoteIds)
        val deleted =
            try {
                val docs = documents.deleteAnchoredTo(listOf(itemId))
                if (docs is Outcome.Err) return docs
                links.onItemsDeleted(listOf(itemId), rootNoteIds)
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
    private suspend fun cascadeRows(
        doomed: List<WorkItem>,
        noteIdsOut: MutableCollection<UUID>? = null
    ): List<DomainEvent> {
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
            for (note in notesByItem[item.id].orEmpty()) {
                noteIdsOut?.add(note.id)
                out += noteDeletedEvent(note, root, DeleteCause.CASCADE)
            }
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
                configResolver.resolveSchema(parent)?.lifecycleMode?.let { SchemaFacts.roleBearing(it) } ?: LifecycleMode.AUTO
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
        private const val REPIN_OP = "ItemCommandService.repin"

        /** Ids per pre-read query of a delete's cascade rows (the edge query binds each id twice). */
        private const val PRE_READ_CHUNK = 200
    }
}

/**
 * Builds and classifies the [DomainError]s [ItemCommandService] returns, so each adapter can map them to its 3.x
 * wire text without parsing messages. Codes: `not_found` (item or parent: compare [ErrorDetail.NotFound.id]),
 * `version_conflict`, `cycle_detected`, `invalid_transition` (closed parent; its detail's `itemId` is the parent),
 * `invalid_request` (self-parent, has-children, a domain validation failure), `schema_violation` (a missing, cleared or
 * undeclared `type`: [ItemCommandErrors.isTypeViolation]), `internal` (a store fault).
 */
object ItemCommandErrors {
    private const val FIELD_PARENT = "parentId"
    private const val FIELD_RECURSIVE = "recursive"
    private const val FIELD_ITEM = "item"
    private const val FIELD_ITEM_ID = "itemId"
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

    /** A root delete refused because [openRuns] runs on it are still open or paused (close them first). */
    fun rootHasOpenRuns(
        rootId: UUID,
        openRuns: Int
    ): DomainError =
        DomainError(
            code = ErrorCode.INVALID_REQUEST,
            message = "Root $rootId has $openRuns open run(s); close them before deleting the root",
            detail = ErrorDetail.InvalidRequest(listOf(FieldViolation(FIELD_ITEM_ID, "root has $openRuns open runs", rootId.toString())))
        )

    fun invalid(message: String): DomainError =
        DomainError(
            code = ErrorCode.INVALID_REQUEST,
            message = message,
            detail = ErrorDetail.InvalidRequest(listOf(FieldViolation(FIELD_ITEM, message)))
        )

    /** A create without a type, or a patch that clears it. [availableTypes] is the root's declared set (maybe empty). */
    fun typeRequired(availableTypes: List<String>): DomainError = typeViolation(null, "type is required", availableTypes)

    /** A create or retype to [type], which the root's non-empty declared set [availableTypes] does not contain. */
    fun undeclaredType(
        type: String,
        availableTypes: List<String>
    ): DomainError = typeViolation(type, "type '$type' is not declared", availableTypes)

    private fun typeViolation(
        type: String?,
        reason: String,
        availableTypes: List<String>
    ): DomainError =
        DomainError(
            code = ErrorCode.SCHEMA_VIOLATION,
            message = if (availableTypes.isEmpty()) reason else "$reason; available types: ${availableTypes.joinToString(", ")}",
            detail = ErrorDetail.SchemaViolation(type = type, reason = reason, availableTypes = availableTypes.takeIf { it.isNotEmpty() })
        )

    /** A create or trait change naming [trait], which the root's non-empty declared set [availableTraits] does not contain. */
    fun undeclaredTrait(
        trait: String,
        availableTraits: List<String>
    ): DomainError =
        DomainError(
            code = ErrorCode.SCHEMA_VIOLATION,
            message = "trait '$trait' is not declared; available traits: ${availableTraits.joinToString(", ")}",
            detail = ErrorDetail.SchemaViolation(type = null, reason = "trait '$trait' is not declared", availableTraits = availableTraits)
        )

    /** A `properties` value that carries a `traits` key (traits is a typed field). */
    fun propertiesCarryTraits(): DomainError =
        DomainError(
            code = ErrorCode.INVALID_REQUEST,
            message = "traits is a typed field; pass traits",
            detail = ErrorDetail.InvalidRequest(listOf(FieldViolation("properties", "traits is a typed field; pass traits")))
        )

    /** The declared traits a trait rejection lists, or null. */
    fun availableTraits(error: DomainError): List<String>? = (error.detail as? ErrorDetail.SchemaViolation)?.availableTraits

    /** True when [error] is a type rule rejection (missing, cleared or undeclared `type`). */
    fun isTypeViolation(error: DomainError): Boolean =
        error.code == ErrorCode.SCHEMA_VIOLATION && error.detail is ErrorDetail.SchemaViolation

    /** The declared types a type rejection lists, or null when it carries none (schema-free) or [error] is another error. */
    fun availableTypes(error: DomainError): List<String>? = (error.detail as? ErrorDetail.SchemaViolation)?.availableTypes

    fun hierarchyLookupFailed(
        parentId: UUID,
        message: String
    ): DomainError = DomainError(ErrorCode.INTERNAL, "$HIERARCHY_LOOKUP_PREFIX'$parentId': $message")

    /** The direct-child count of a non-recursive delete refused for children, or null for any other error. */
    fun childCount(error: DomainError): Int? = fieldOf(error, FIELD_RECURSIVE)?.received?.toIntOrNull()

    fun isSelfParent(error: DomainError): Boolean = fieldOf(error, FIELD_PARENT) != null

    fun isHierarchyLookupFailure(error: DomainError): Boolean =
        error.code == ErrorCode.INTERNAL && error.message.startsWith(HIERARCHY_LOOKUP_PREFIX)

    /**
     * A pin point that would move [itemId] across the lifecycle class boundary where it may not (U13b): any pin point
     * other than a type change or `repin`, or a flip those two do not allow. `fromRole` is [role]'s wire value (`none`
     * when lifecycle-free), trigger the [reason]'s wire value, nothing allowed.
     */
    fun lifecycleCrossing(
        itemId: UUID,
        role: Role?,
        reason: PinReason
    ): DomainError {
        val from = role.wireOrNone()
        return DomainError(
            code = ErrorCode.INVALID_TRANSITION,
            message = "Item $itemId cannot change lifecycle class at '${reason.wire}' from role $from.",
            detail = ErrorDetail.InvalidTransition(itemId, from, reason.wire, emptyList()),
            fixArgs = mapOf("itemId" to itemId.toString(), "trigger" to reason.wire, "fromRole" to from)
        )
    }

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
