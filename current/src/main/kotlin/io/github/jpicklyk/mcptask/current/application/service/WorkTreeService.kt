package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.port.WorkflowStoreProvider
import io.github.jpicklyk.mcptask.current.config.EffectiveConfigResolver
import io.github.jpicklyk.mcptask.current.dependency.DependencyCommandService
import io.github.jpicklyk.mcptask.current.dependency.TopoOrder
import io.github.jpicklyk.mcptask.current.knowledge.ItemDerivedFromTarget
import io.github.jpicklyk.mcptask.current.knowledge.LinkService
import io.github.jpicklyk.mcptask.current.knowledge.MarkdownSectionSplitter
import io.github.jpicklyk.mcptask.current.knowledge.PlanDocumentService
import io.github.jpicklyk.mcptask.current.knowledge.ResolvedItemDerivedFrom
import io.github.jpicklyk.mcptask.current.model.claim.ActorClaim
import io.github.jpicklyk.mcptask.current.model.claim.ClaimHolder
import io.github.jpicklyk.mcptask.current.model.config.PerRootConfigUnavailableException
import io.github.jpicklyk.mcptask.current.model.dependency.BlockingEdge
import io.github.jpicklyk.mcptask.current.model.dependency.Dependency
import io.github.jpicklyk.mcptask.current.model.dependency.DependencyType
import io.github.jpicklyk.mcptask.current.model.error.DomainError
import io.github.jpicklyk.mcptask.current.model.error.EntityKind
import io.github.jpicklyk.mcptask.current.model.error.ErrorCode
import io.github.jpicklyk.mcptask.current.model.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.model.error.FieldViolation
import io.github.jpicklyk.mcptask.current.model.error.Outcome
import io.github.jpicklyk.mcptask.current.model.error.ValidationException
import io.github.jpicklyk.mcptask.current.model.item.Priority
import io.github.jpicklyk.mcptask.current.model.item.Role
import io.github.jpicklyk.mcptask.current.model.item.WorkItem
import io.github.jpicklyk.mcptask.current.model.knowledge.PlanDocumentStatus
import io.github.jpicklyk.mcptask.current.model.note.Note
import io.github.jpicklyk.mcptask.current.model.proof.VerificationResult
import io.github.jpicklyk.mcptask.current.model.schema.SeatCaller
import io.github.jpicklyk.mcptask.current.note.NoteCommandService
import io.github.jpicklyk.mcptask.current.note.NoteLengthWarning
import io.github.jpicklyk.mcptask.current.note.NoteUpsertCommand
import io.github.jpicklyk.mcptask.current.platform.port.UnitOfWork
import java.util.UUID

/** One note of a tree item cut from the tree's plan document: the section under [anchor] becomes note [noteKey]. */
data class TreeAnchorInput(
    val noteKey: String,
    val role: String,
    val anchor: String
)

/**
 * One item of a work tree. [ref] names it inside the request ([WorkTreeService.ROOT_REF] for the root); [parentRef] is
 * the ref of its parent (null for the root). The fields mirror [ItemCreateCommand]; [links] are the item's declared
 * sources (note items already scope-checked by the caller), stored as `derived_from` links from the new item.
 */
data class TreeItemInput(
    val ref: String,
    val parentRef: String?,
    val title: String,
    val type: String?,
    val priority: Priority = Priority.MEDIUM,
    val tags: String? = null,
    val traits: List<String> = emptyList(),
    val summary: String = "",
    val description: String? = null,
    val statusLabel: String? = null,
    val complexity: Int? = null,
    val requiresVerification: Boolean = false,
    val metadata: String? = null,
    val properties: String? = null,
    val anchors: List<TreeAnchorInput> = emptyList(),
    val links: List<ItemDerivedFromTarget> = emptyList()
)

/** One end of a work-tree dependency: a [Ref] of the tree, or an existing [Item] outside it (or the attached root). */
sealed interface TreeDepEnd {
    /** A ref of the tree ([WorkTreeService.ROOT_REF] or a child ref). */
    data class Ref(
        val ref: String
    ) : TreeDepEnd

    /** An existing item, already resolved and scope-checked by the caller. */
    data class Item(
        val id: UUID
    ) : TreeDepEnd
}

/** One dependency of a work tree; at least one end is a [TreeDepEnd.Ref]. */
data class TreeDepInput(
    val from: TreeDepEnd,
    val to: TreeDepEnd,
    val type: DependencyType = DependencyType.BLOCKS,
    val unblockAt: String? = null
)

/** One explicit note of a work tree, on the item named [itemRef]. */
data class TreeNoteInput(
    val itemRef: String,
    val key: String,
    val role: String,
    val body: String = ""
)

/** How a work tree gets its root. */
sealed interface TreeRootInput {
    /** A new root from [spec] (its ref is [WorkTreeService.ROOT_REF]), under [parentId] or at top level. */
    data class Create(
        val spec: TreeItemInput,
        val parentId: UUID?
    ) : TreeRootInput

    /** The existing item [itemId] is the root: no root row is written; [anchors] are its document-cut notes. */
    data class Attach(
        val itemId: UUID,
        val anchors: List<TreeAnchorInput> = emptyList()
    ) : TreeRootInput
}

/**
 * A whole work tree request. [docSlug] names the plan document every anchor is cut from (the root adopts it last);
 * [docRootRef] is its project root as the caller wrote it (a UUID or hex prefix), matched against the tree's own root
 * and never looked up, so a foreign or unknown id is the same `invalid_request` and reveals nothing. The note actor is
 * [actorClaim] / [verification] for every note the tree writes. [caller] is who the tree's dependency and note writes
 * are fenced for (`FenceGuard`); new items are unclaimed, so only an attached root can be fenced.
 */
data class WorkTreeRequest(
    val root: TreeRootInput,
    val children: List<TreeItemInput> = emptyList(),
    val deps: List<TreeDepInput> = emptyList(),
    val notes: List<TreeNoteInput> = emptyList(),
    val createNotes: Boolean = false,
    val docSlug: String? = null,
    val docRootRef: String? = null,
    val actorClaim: ActorClaim? = null,
    val verification: VerificationResult? = null,
    val caller: ClaimHolder?,
    /** The seat of the call's seat token, null without one: the owner seat of every note the tree writes. */
    val callerSeat: String? = null,
    /** Who writes the tree's notes, for note-ownership rule 2 ([NoteSeatRule]); an agent without a token by default. */
    val seatCaller: SeatCaller = SeatCaller.NONE
)

/** One item a tree wrote (or attached to): its [ref], [id] and stored [depth]. */
data class TreeItemResult(
    val ref: String,
    val id: UUID,
    val depth: Int
)

/** One stored dependency of a tree, with the refs the request named it by; a ref is null for an existing-item end. */
data class TreeDepResult(
    val dependency: Dependency,
    val fromRef: String?,
    val toRef: String?
)

/** One stored note of a tree, with its item's ref and any soft-limit [warning]. */
data class TreeNoteResult(
    val itemRef: String,
    val note: Note,
    val warning: NoteLengthWarning?
)

/**
 * A committed work tree: the root ([rootId], at [rootVersion]), every item root first and then the children in the
 * order they were inserted, the dependencies in request order and the notes in write order.
 */
data class WorkTreeResult(
    val rootId: UUID,
    val rootVersion: Long,
    val items: List<TreeItemResult>,
    val deps: List<TreeDepResult>,
    val notes: List<TreeNoteResult>
)

/**
 * Everything a tree will write, decided before the first write: the create commands root first (create mode) and the
 * children in parent-before-child order, the resolved item sources, the normalized dependencies in request order, the
 * prepared notes and the document adoption.
 */
class WorkTreePlan internal constructor(
    internal val rootId: UUID,
    internal val attach: Boolean,
    internal val refToId: Map<String, UUID>,
    internal val commands: List<ItemCreateCommand>,
    internal val deps: List<TreeDepResult>,
    internal val notes: List<PlannedTreeNote>,
    internal val adoption: TreeAdoption?,
    internal val actorClaim: ActorClaim?,
    internal val verification: VerificationResult?,
    internal val caller: ClaimHolder?,
    internal val callerSeat: String? = null,
    internal val seatCaller: SeatCaller = SeatCaller.NONE,
    internal val itemLinks: List<PlannedItemLinks> = emptyList()
)

/** The resolved sources of one new item [itemId], stamped with the tree's root [rootId]. */
internal data class PlannedItemLinks(
    val itemId: UUID,
    val rootId: UUID,
    val links: List<ResolvedItemDerivedFrom>
)

/** A note that passed the write policy, ready to upsert. [derivedFrom] is the anchor its body was cut at, if any. */
internal data class PlannedTreeNote(
    val itemRef: String,
    val itemId: UUID,
    val key: String,
    val role: String,
    val body: String,
    val warning: NoteLengthWarning?,
    val derivedFrom: String?
)

/** The plan document a tree adopts as its LAST step. */
internal data class TreeAdoption(
    val documentRootId: UUID,
    val slug: String,
    val adoptingItemId: UUID
)

/**
 * The work-tree algorithm (AR-96), shared by the `items_write` `create_tree` op: a root (new or attached), its
 * children ordered parent-before-child by [TopoOrder], the dependencies between them, their notes (explicit, cut from a
 * plan document, or schema blanks) and the adoption of that document, all in ONE write unit.
 *
 * [plan] reads and validates (no write): the anchor rows, the edge policy over the new edges
 * ([DependencyCommandService.validateTreeEdges]; with an existing-item end or an attached root also the stored edges:
 * lifecycle-free endpoints, stored duplicates and cycles), the note write policy ([NoteCommandService.prepare]), the
 * document and the children's declared sources ([LinkService.resolveItemDerivedFrom]).
 * [write] writes, joining the caller's unit: items root first through [ItemCommandService.createInUnit] (each
 * placement is read from the parent row inside the unit), the children's `derived_from` links, the dependencies, the
 * notes, the `derived_from` links of the anchored notes and, LAST, the adoption. Any [Outcome.Err] returned from it
 * must roll the caller's unit back; nothing of the tree then remains and the document stays PENDING.
 *
 * Note precedence on `(ref, key)`: explicit notes win over anchors, anchors over `createNotes` blanks; among explicit
 * notes (and among anchors) the last one wins. A [PerRootConfigUnavailableException] propagates.
 */
class WorkTreeService(
    private val repositoryProvider: WorkflowStoreProvider,
    private val unitOfWork: UnitOfWork<WorkflowStoreProvider>,
    private val configResolver: EffectiveConfigResolver,
    private val itemCommandService: ItemCommandService,
    private val dependencyCommandService: DependencyCommandService,
    private val noteCommandService: NoteCommandService,
    private val planDocumentService: PlanDocumentService
) {
    companion object {
        /** The ref that names the root. */
        const val ROOT_REF = "root"

        /** True when [ref] (a canonical UUID or a dash-free hex prefix, any case) names [id]. */
        fun namesId(
            ref: String,
            id: UUID
        ): Boolean =
            if (ref.contains('-')) {
                ref.equals(id.toString(), ignoreCase = true)
            } else {
                id.toString().replace("-", "").startsWith(ref.lowercase())
            }

        /**
         * The children's refs in parent-before-child order ([TopoOrder] over the parentRef edges, ties in request
         * order), and the refs on or below a parentRef cycle. [children] holds `(ref, parentRef)` pairs.
         */
        fun orderChildRefs(children: List<Pair<String, String>>): Pair<List<String>, List<String>> {
            val ids = LinkedHashMap<String, UUID>()
            children.forEach { (ref, _) -> ids.getOrPut(ref) { UUID.randomUUID() } }
            val refOf = ids.entries.associate { (ref, id) -> id to ref }
            // A child that names itself as parent is a cycle of length one (never an edge: an edge joins two items).
            val selfParented = children.filter { (ref, parentRef) -> ref == parentRef }.map { it.first }.toSet()
            val edges =
                children.mapNotNull { (ref, parentRef) ->
                    val parent = ids[parentRef]
                    if (parentRef == ROOT_REF || parent == null || ref == parentRef) null else BlockingEdge(parent, ids.getValue(ref))
                }
            val order = TopoOrder.order(ids.values.toList(), edges)
            val cyclic = order.cyclic.map { refOf.getValue(it) }.toSet() + selfParented
            val ordered = order.ordered.map { refOf.getValue(it) }.filter { it !in cyclic }
            return ordered to ids.keys.filter { it in cyclic }
        }

        private fun invalid(
            field: String,
            reason: String,
            received: String? = null
        ): DomainError =
            DomainError(
                code = ErrorCode.INVALID_REQUEST,
                message = "The request has 1 invalid field(s): $field.",
                detail = ErrorDetail.InvalidRequest(listOf(FieldViolation(field, reason, received)))
            )
    }

    /** [plan] then [write] in one write unit (joining the caller's): a convenience for callers that hold no unit. */
    suspend fun create(request: WorkTreeRequest): Outcome<WorkTreeResult> {
        var configFault: PerRootConfigUnavailableException? = null
        val outcome =
            unitOfWork.write("WorkTreeService.create") {
                configFault = null
                try {
                    when (val planned = plan(request)) {
                        is Outcome.Err -> planned
                        is Outcome.Ok -> write(planned.value)
                    }
                } catch (e: PerRootConfigUnavailableException) {
                    // Caught inside the unit and rethrown after it, so an outermost unit rolls back instead of translating it.
                    configFault = e
                    Outcome.Err(DomainError(ErrorCode.INTERNAL, "Unit 'WorkTreeService.create' rolled back: ${e.message}"))
                }
            }
        configFault?.let { throw it }
        return outcome
    }

    /** Reads and validates the whole tree; writes nothing. */
    suspend fun plan(request: WorkTreeRequest): Outcome<WorkTreePlan> {
        val items = repositoryProvider.workItemRepository()

        // The root: the attached row, or a preview of the new one with its provisional root (the parent's root, or itself).
        val rootId: UUID
        val effectiveRootId: UUID
        val rootPreview: WorkItem
        var rootCommand: ItemCreateCommand? = null
        when (val root = request.root) {
            is TreeRootInput.Attach -> {
                val existing = items.getById(root.itemId) ?: return Outcome.Err(ItemCommandErrors.itemNotFound(root.itemId))
                val placement = items.resolveChildPlacement(root.itemId) ?: return Outcome.Err(ItemCommandErrors.itemNotFound(root.itemId))
                rootId = existing.id
                effectiveRootId = placement.rootId
                rootPreview = existing.copy(rootId = effectiveRootId)
            }
            is TreeRootInput.Create -> {
                rootId = UUID.randomUUID()
                effectiveRootId =
                    when (val parentId = root.parentId) {
                        null -> rootId
                        else -> {
                            val parent = items.getById(parentId) ?: return Outcome.Err(ItemCommandErrors.parentNotFound(parentId))
                            parent.rootId ?: parent.id
                        }
                    }
                val command = commandOf(root.spec, rootId, root.parentId)
                rootCommand = command
                rootPreview =
                    when (val preview = previewOf(command, effectiveRootId)) {
                        is Outcome.Err -> return preview
                        is Outcome.Ok -> preview.value
                    }
            }
        }

        // The children, parent before child (TopoOrder over the parentRef edges, ties in request order). A cycle never
        // reaches here (the caller's shape check rejects it first), but an unsortable remainder is still refused.
        val childIds = LinkedHashMap<String, UUID>()
        for (child in request.children) childIds[child.ref] = UUID.randomUUID()
        val refToId = LinkedHashMap<String, UUID>()
        refToId[ROOT_REF] = rootId
        refToId.putAll(childIds)
        val (orderedRefs, cyclicRefs) = orderChildRefs(request.children.map { it.ref to (it.parentRef ?: ROOT_REF) })
        if (cyclicRefs.isNotEmpty()) return Outcome.Err(invalid("/children", "parentRef chain is cyclic: ${cyclicRefs.joinToString(", ")}"))
        val childByRef = request.children.associateBy { it.ref }
        val previews = LinkedHashMap<String, WorkItem>()
        previews[ROOT_REF] = rootPreview
        val commands = ArrayList<ItemCreateCommand>()
        rootCommand?.let { commands += it }
        for (ref in orderedRefs) {
            val child = childByRef.getValue(ref)
            val id = childIds.getValue(ref)
            val parentId =
                refToId[child.parentRef ?: ROOT_REF]
                    ?: return Outcome.Err(invalid("/children", "parentRef '${child.parentRef}' is not defined"))
            val command = commandOf(child, id, parentId)
            commands += command
            previews[child.ref] =
                when (val preview = previewOf(command, effectiveRootId)) {
                    is Outcome.Err -> return preview
                    is Outcome.Ok -> preview.value
                }
        }

        val deps =
            when (val checked = planDeps(request.deps, refToId, (request.root as? TreeRootInput.Attach)?.itemId)) {
                is Outcome.Err -> return checked
                is Outcome.Ok -> checked.value
            }

        // The document: resolved and checked before any write (adoption is the write unit's last step).
        val adoption: TreeAdoption?
        var documentBody: String? = null
        val slug = request.docSlug
        if (slug == null) {
            adoption = null
        } else {
            val docRootRef = request.docRootRef
            if (docRootRef != null && !namesId(docRootRef, effectiveRootId)) {
                return Outcome.Err(invalid("/docRef/rootId", "must be the tree's own root", docRootRef))
            }
            val doc =
                repositoryProvider.planDocumentRepository().get(effectiveRootId, slug)
                    ?: return Outcome.Err(documentNotFound(slug, effectiveRootId))
            if (doc.status ==
                PlanDocumentStatus.ADOPTED
            ) {
                return Outcome.Err(documentAdopted(slug, effectiveRootId, doc.id, doc.adoptedByItemId))
            }
            documentBody = doc.body
            adoption = TreeAdoption(effectiveRootId, slug, rootId)
        }

        val notes =
            when (val planned = planNotes(request, previews, refToId, effectiveRootId, documentBody)) {
                is Outcome.Err -> return planned
                is Outcome.Ok -> planned.value
            }

        // The children's declared sources, resolved in the tree's root (pointers in request order).
        val itemLinks = ArrayList<PlannedItemLinks>()
        for ((index, child) in request.children.withIndex()) {
            if (child.links.isEmpty()) continue
            val resolved =
                when (
                    val r =
                        LinkService(
                            repositoryProvider,
                            unitOfWork
                        ).resolveItemDerivedFrom(effectiveRootId, child.links, "/children/$index/links")
                ) {
                    is Outcome.Err -> return r
                    is Outcome.Ok -> r.value
                }
            itemLinks += PlannedItemLinks(childIds.getValue(child.ref), effectiveRootId, resolved)
        }

        return Outcome.Ok(
            WorkTreePlan(
                rootId = rootId,
                attach = request.root is TreeRootInput.Attach,
                refToId = refToId,
                commands = commands,
                deps = deps,
                notes = notes,
                adoption = adoption,
                actorClaim = request.actorClaim,
                verification = request.verification,
                caller = request.caller,
                callerSeat = request.callerSeat,
                seatCaller = request.seatCaller,
                itemLinks = itemLinks
            )
        )
    }

    /**
     * Writes [plan] in ONE write unit, joining the caller's: items root first, then the items' `derived_from` links,
     * the dependencies, the notes, the notes' `derived_from` links and, last, the adoption. The first failure is
     * returned; the caller's unit must roll back.
     */
    suspend fun write(plan: WorkTreePlan): Outcome<WorkTreeResult> =
        unitOfWork.write("WorkTreeService.write") {
            val items = repositoryProvider.workItemRepository()
            if (plan.attach) {
                // The attached root must still exist inside the unit (a concurrent delete would orphan the children).
                items.resolveChildPlacement(plan.rootId) ?: return@write Outcome.Err(ItemCommandErrors.itemNotFound(plan.rootId))
            }
            val idToRef = plan.refToId.entries.associate { (ref, id) -> id to ref }
            val written = ArrayList<TreeItemResult>()
            for (command in plan.commands) {
                when (val created = itemCommandService.createInUnit(command)) {
                    is Outcome.Err -> return@write created
                    is Outcome.Ok -> written += TreeItemResult(idToRef.getValue(created.value.id), created.value.id, created.value.depth)
                }
            }
            if (plan.itemLinks.isNotEmpty()) {
                val links = LinkService(repositoryProvider, unitOfWork)
                for (planned in plan.itemLinks) links.recordItemDerivedFrom(planned.itemId, planned.rootId, planned.links, now)
            }

            val storedDeps =
                when (val stored = dependencyCommandService.createInUnit(plan.deps.map { it.dependency }, plan.caller)) {
                    is Outcome.Err -> return@write stored
                    is Outcome.Ok -> stored.value
                }

            val notes = ArrayList<TreeNoteResult>()
            for (note in plan.notes) {
                val upserted =
                    noteCommandService.upsert(
                        NoteUpsertCommand(
                            note.itemId,
                            note.key,
                            note.role,
                            note.body,
                            plan.actorClaim,
                            plan.verification,
                            plan.caller,
                            callerSeat = plan.callerSeat,
                            seatCaller = plan.seatCaller
                        )
                    )
                when (upserted) {
                    is Outcome.Err -> return@write upserted
                    is Outcome.Ok -> notes += TreeNoteResult(note.itemRef, upserted.value.note, note.warning)
                }
            }

            // Provenance: each note whose final body was cut from the plan document is derived from it.
            val adoption = plan.adoption
            if (adoption != null) {
                val links = LinkService(repositoryProvider, unitOfWork)
                for ((index, note) in plan.notes.withIndex()) {
                    val anchor = note.derivedFrom ?: continue
                    links.recordDerivedFrom(adoption.documentRootId, notes[index].note.id, adoption.slug, anchor, now)
                }
                // LAST: a concurrent adopt or a vanished document rolls the whole tree back.
                val adopted = planDocumentService.adoptInUnit(adoption.documentRootId, adoption.slug, adoption.adoptingItemId)
                if (adopted is Outcome.Err) return@write adopted
            }

            val root = items.getById(plan.rootId) ?: return@write Outcome.Err(ItemCommandErrors.itemNotFound(plan.rootId))
            val rootEntry = TreeItemResult(WorkTreeService.ROOT_REF, root.id, root.depth)
            val itemsOut = if (plan.attach) listOf(rootEntry) + written else listOf(rootEntry) + written.drop(1)
            Outcome.Ok(
                WorkTreeResult(
                    rootId = root.id,
                    rootVersion = root.version,
                    items = itemsOut,
                    deps = storedDeps.mapIndexed { index, dep -> plan.deps[index].copy(dependency = dep) },
                    notes = notes
                )
            )
        }

    private fun commandOf(
        spec: TreeItemInput,
        id: UUID,
        parentId: UUID?
    ): ItemCreateCommand =
        ItemCreateCommand(
            id = id,
            parentId = parentId,
            title = spec.title,
            description = spec.description,
            summary = spec.summary,
            statusLabel = spec.statusLabel,
            priority = spec.priority,
            complexity = spec.complexity,
            requiresVerification = spec.requiresVerification,
            metadata = spec.metadata,
            tags = spec.tags,
            type = spec.type,
            properties = spec.properties,
            traits = spec.traits
        )

    /**
     * A never-persisted preview of [command] (QUEUE, the provisional [rootId]) for schema resolution and note targeting;
     * its depth is a placeholder (the stored depth comes from the in-unit placement read). A spec the domain rejects is
     * the item service's own `invalid_request`.
     */
    private fun previewOf(
        command: ItemCreateCommand,
        rootId: UUID
    ): Outcome<WorkItem> =
        try {
            Outcome.Ok(
                WorkItem(
                    id = command.id,
                    parentId = command.parentId,
                    rootId = rootId,
                    title = command.title,
                    description = command.description,
                    summary = command.summary,
                    role = Role.QUEUE,
                    statusLabel = command.statusLabel,
                    priority = command.priority,
                    complexity = command.complexity,
                    requiresVerification = command.requiresVerification,
                    depth = if (command.parentId == null) 0 else 1,
                    metadata = command.metadata,
                    tags = command.tags,
                    type = command.type,
                    properties = command.properties,
                    traits = command.traits
                )
            )
        } catch (e: ValidationException) {
            Outcome.Err(ItemCommandErrors.invalid(e.message ?: "Validation failed"))
        }

    /**
     * The tree's dependencies through the edge policy: normalized, a restated pair `duplicate`, a cycle `cycle_detected`
     * (path: ids; the message names the first ref on it). When an end names an existing item, or the root is the
     * attached [attachedRootId], the stored edges are checked too ([DependencyCommandService.validateTreeEdges] over the
     * stored endpoints): a lifecycle-free endpoint is `invalid_request` at the request's own pointer, a stored pair
     * `duplicate`, a cycle through stored edges `cycle_detected`. Request order is kept.
     */
    private suspend fun planDeps(
        specs: List<TreeDepInput>,
        refToId: Map<String, UUID>,
        attachedRootId: UUID?
    ): Outcome<List<TreeDepResult>> {
        if (specs.isEmpty()) return Outcome.Ok(emptyList())
        val deps = ArrayList<Dependency>(specs.size)
        val external = LinkedHashSet<UUID>()
        for ((index, spec) in specs.withIndex()) {
            val from =
                when (val end = spec.from) {
                    is TreeDepEnd.Ref ->
                        refToId[end.ref]
                            ?: return Outcome.Err(invalid("/deps/$index/from", "is not a ref of this tree", end.ref))
                    is TreeDepEnd.Item -> end.id.also { external += it }
                }
            val to =
                when (val end = spec.to) {
                    is TreeDepEnd.Ref ->
                        refToId[end.ref]
                            ?: return Outcome.Err(invalid("/deps/$index/to", "is not a ref of this tree", end.ref))
                    is TreeDepEnd.Item -> end.id.also { external += it }
                }
            deps +=
                try {
                    Dependency(fromItemId = from, toItemId = to, type = spec.type, unblockAt = spec.unblockAt)
                } catch (e: ValidationException) {
                    return Outcome.Err(invalid("/deps/$index", e.message ?: "is not a valid dependency"))
                }
        }
        val idToRef = refToId.entries.associate { (ref, id) -> id to ref }
        val storedEndpoints = if (attachedRootId == null) external else external + attachedRootId
        val checked =
            if (storedEndpoints.isEmpty()) {
                dependencyCommandService.validateTreeEdges(deps)
            } else {
                dependencyCommandService.validateTreeEdges(deps, storedEndpoints)
            }

        fun withRefs(message: String): String = idToRef.entries.fold(message) { text, (id, ref) -> text.replace(id.toString(), "'$ref'") }

        fun refOf(end: TreeDepEnd): String? = (end as? TreeDepEnd.Ref)?.ref
        return when (checked) {
            is Outcome.Ok ->
                // Tree edge types (blocks, relates_to) are never swapped by normalization, so request order maps 1:1.
                Outcome.Ok(checked.value.mapIndexed { index, dep -> TreeDepResult(dep, refOf(specs[index].from), refOf(specs[index].to)) })
            is Outcome.Err -> {
                val error = checked.error
                val detail = error.detail
                when {
                    detail is ErrorDetail.CycleDetected -> {
                        // Every cycle has a tree edge, so a ref is on the path; "unknown" only keeps the message total.
                        val ref = detail.path.firstNotNullOfOrNull { idToRef[it] } ?: "unknown"
                        Outcome.Err(error.copy(message = "Circular dependency detected involving ref '$ref'"))
                    }
                    detail is ErrorDetail.InvalidRequest && detail.fields.any { it.reason == Dependency.LIFECYCLE_FREE_ENDPOINT_REASON } ->
                        Outcome.Err(lifecycleFreeAtPointer(error, detail, specs, deps, withRefs(error.message)))
                    else -> Outcome.Err(error.copy(message = withRefs(error.message)))
                }
            }
        }
    }

    /**
     * Re-points a lifecycle-free endpoint violation (which names `fromItemId` or `toItemId`) at the request's own end,
     * `/deps/<i>/from|fromId|to|toId`. The first blocking edge naming the id is the one the policy judged: it scans in
     * request order, from end first.
     */
    private fun lifecycleFreeAtPointer(
        error: DomainError,
        detail: ErrorDetail.InvalidRequest,
        specs: List<TreeDepInput>,
        deps: List<Dependency>,
        message: String
    ): DomainError {
        val violation = detail.fields.first { it.reason == Dependency.LIFECYCLE_FREE_ENDPOINT_REASON }
        val index =
            deps.indexOfFirst { dep ->
                dep.type == DependencyType.BLOCKS &&
                    violation.received in setOf(dep.fromItemId.toString(), dep.toItemId.toString())
            }
        if (index < 0) return error.copy(message = message)
        val atFrom = deps[index].fromItemId.toString() == violation.received
        val end = if (atFrom) specs[index].from else specs[index].to
        val field = (if (atFrom) "from" else "to") + (if (end is TreeDepEnd.Item) "Id" else "")
        return error.copy(
            message = message,
            detail = ErrorDetail.InvalidRequest(listOf(FieldViolation("/deps/$index/$field", violation.reason, violation.received)))
        )
    }

    /** Explicit notes, then document-cut notes, then `createNotes` blanks, each through the note write policy. */
    private suspend fun planNotes(
        request: WorkTreeRequest,
        previews: Map<String, WorkItem>,
        refToId: Map<String, UUID>,
        effectiveRootId: UUID,
        documentBody: String?
    ): Outcome<List<PlannedTreeNote>> {
        val byRefKey = LinkedHashMap<Pair<String, String>, PlannedTreeNote>()
        val explicit = HashSet<Pair<String, String>>()

        for ((index, note) in request.notes.withIndex()) {
            val item =
                previews[note.itemRef]
                    ?: return Outcome.Err(invalid("/notes/$index/itemRef", "is not a ref of this tree", note.itemRef))
            val prepared =
                when (val p = noteCommandService.prepare(item.copy(rootId = effectiveRootId), note.key, note.role, note.body)) {
                    is Outcome.Err -> return p
                    is Outcome.Ok -> p.value
                }
            val refKey = note.itemRef to note.key
            explicit += refKey
            byRefKey[refKey] =
                PlannedTreeNote(
                    note.itemRef,
                    refToId.getValue(note.itemRef),
                    note.key,
                    prepared.role,
                    prepared.body,
                    prepared.warning,
                    null
                )
        }

        if (documentBody != null) {
            for ((itemRef, anchors, pointer) in anchorsOf(request)) {
                val item = previews.getValue(itemRef).copy(rootId = effectiveRootId)
                for ((j, anchor) in anchors.withIndex()) {
                    val refKey = itemRef to anchor.noteKey
                    if (refKey in explicit) continue
                    // A schema-role mismatch is reported before a missing anchor.
                    (noteCommandService.prepare(item, anchor.noteKey, anchor.role, "") as? Outcome.Err)?.let { return it }
                    val sliced =
                        MarkdownSectionSplitter.slice(documentBody, anchor.anchor)
                            ?: return Outcome.Err(
                                invalid("$pointer/noteAnchors/$j/anchor", "is not a heading of the document", anchor.anchor)
                            )
                    val prepared =
                        when (val p = noteCommandService.prepare(item, anchor.noteKey, anchor.role, sliced)) {
                            is Outcome.Err -> return p
                            is Outcome.Ok -> p.value
                        }
                    byRefKey[refKey] =
                        PlannedTreeNote(
                            itemRef,
                            refToId.getValue(itemRef),
                            anchor.noteKey,
                            prepared.role,
                            prepared.body,
                            prepared.warning,
                            anchor.anchor
                        )
                }
            }
        }

        if (request.createNotes) {
            for ((ref, preview) in previews) {
                val schema = configResolver.resolveSchema(preview.copy(rootId = effectiveRootId)) ?: continue
                for (entry in schema.notes) {
                    val refKey = ref to entry.key
                    if (refKey in byRefKey) continue
                    byRefKey[refKey] = PlannedTreeNote(ref, refToId.getValue(ref), entry.key, entry.role.name.lowercase(), "", null, null)
                }
            }
        }
        return Outcome.Ok(byRefKey.values.toList())
    }

    /** Every item's anchors with its ref and the request pointer of its spec, root first. */
    private fun anchorsOf(request: WorkTreeRequest): List<Triple<String, List<TreeAnchorInput>, String>> {
        val rootAnchors =
            when (val root = request.root) {
                is TreeRootInput.Attach -> root.anchors
                is TreeRootInput.Create -> root.spec.anchors
            }
        return listOf(Triple(ROOT_REF, rootAnchors, "/root")) +
            request.children.mapIndexed { i, child -> Triple(child.ref, child.anchors, "/children/$i") }
    }

    private fun documentNotFound(
        slug: String,
        rootId: UUID
    ): DomainError =
        DomainError(
            code = ErrorCode.NOT_FOUND,
            message = "Plan document '$slug' (root $rootId) not found.",
            detail = ErrorDetail.NotFound(EntityKind.DOCUMENT, slug),
            fixArgs = mapOf("kind" to "document", "id" to slug)
        )

    private fun documentAdopted(
        slug: String,
        rootId: UUID,
        documentId: UUID,
        adoptedBy: UUID?
    ): DomainError =
        DomainError(
            code = ErrorCode.DUPLICATE,
            message = "Plan document '$slug' (root $rootId) is already adopted (by item $adoptedBy).",
            detail = ErrorDetail.Duplicate(kind = EntityKind.DOCUMENT, id = slug, existingId = documentId.toString()),
            fixArgs = mapOf("kind" to "document", "existingId" to documentId.toString())
        )
}
