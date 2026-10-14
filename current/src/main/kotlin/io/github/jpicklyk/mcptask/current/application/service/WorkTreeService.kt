package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.config.EffectiveConfigResolver
import io.github.jpicklyk.mcptask.current.application.knowledge.link.LinkService
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.EntityKind
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.FieldViolation
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.graph.BlockingEdge
import io.github.jpicklyk.mcptask.current.domain.graph.TopoOrder
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException
import io.github.jpicklyk.mcptask.current.domain.model.PlanDocumentStatus
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.validation.ValidationException
import java.util.UUID

/** One note of a tree item cut from the tree's plan document: the section under [anchor] becomes note [noteKey]. */
data class TreeAnchorInput(
    val noteKey: String,
    val role: String,
    val anchor: String
)

/**
 * One item of a work tree. [ref] names it inside the request ([WorkTreeService.ROOT_REF] for the root); [parentRef] is
 * the ref of its parent (null for the root). The fields mirror [ItemCreateCommand].
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
    val anchors: List<TreeAnchorInput> = emptyList()
)

/** One dependency between two refs of a work tree. */
data class TreeDepInput(
    val fromRef: String,
    val toRef: String,
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
 * are fenced for ([FenceGuard]); new items are unclaimed, so only an attached root can be fenced.
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
    val caller: ClaimHolder?
)

/** One item a tree wrote (or attached to): its [ref], [id] and stored [depth]. */
data class TreeItemResult(
    val ref: String,
    val id: UUID,
    val depth: Int
)

/** One stored dependency of a tree, with the refs the request named it by. */
data class TreeDepResult(
    val dependency: Dependency,
    val fromRef: String,
    val toRef: String
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
 * children in parent-before-child order, the normalized dependencies in request order, the prepared notes and the
 * document adoption.
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
    internal val caller: ClaimHolder?
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
 * ([DependencyCommandService.validateTreeEdges]), the note write policy ([NoteCommandService.prepare]) and the document.
 * [write] writes, joining the caller's unit: items root first through [ItemCommandService.createInUnit] (each
 * placement is read from the parent row inside the unit), the dependencies, the notes, the `derived_from` links of the
 * anchored notes and, LAST, the adoption. Any [Outcome.Err] returned from it must roll the caller's unit back; nothing
 * of the tree then remains and the document stays PENDING.
 *
 * Note precedence on `(ref, key)`: explicit notes win over anchors, anchors over `createNotes` blanks; among explicit
 * notes (and among anchors) the last one wins. A [PerRootConfigUnavailableException] propagates.
 */
class WorkTreeService(
    private val repositoryProvider: RepositoryProvider,
    private val unitOfWork: UnitOfWork,
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
            when (val checked = planDeps(request.deps, refToId)) {
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
                caller = request.caller
            )
        )
    }

    /**
     * Writes [plan] in ONE write unit, joining the caller's: items root first, then the dependencies, the notes, the
     * `derived_from` links and, last, the adoption. The first failure is returned; the caller's unit must roll back.
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

            val storedDeps =
                when (val stored = dependencyCommandService.createInUnit(plan.deps.map { it.dependency }, plan.caller)) {
                    is Outcome.Err -> return@write stored
                    is Outcome.Ok -> stored.value
                }

            val notes = ArrayList<TreeNoteResult>()
            for (note in plan.notes) {
                val upserted =
                    noteCommandService.upsert(
                        NoteUpsertCommand(note.itemId, note.key, note.role, note.body, plan.actorClaim, plan.verification, plan.caller)
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
     * The tree's dependencies through the edge policy for unstored items: normalized, a restated pair `duplicate`, a
     * cycle `cycle_detected` (path: ids; the message names the first ref on it). Request order is kept.
     */
    private fun planDeps(
        specs: List<TreeDepInput>,
        refToId: Map<String, UUID>
    ): Outcome<List<TreeDepResult>> {
        if (specs.isEmpty()) return Outcome.Ok(emptyList())
        val deps = ArrayList<Dependency>(specs.size)
        for ((index, spec) in specs.withIndex()) {
            val from = refToId[spec.fromRef] ?: return Outcome.Err(invalid("/deps/$index/from", "is not a ref of this tree", spec.fromRef))
            val to = refToId[spec.toRef] ?: return Outcome.Err(invalid("/deps/$index/to", "is not a ref of this tree", spec.toRef))
            deps +=
                try {
                    Dependency(fromItemId = from, toItemId = to, type = spec.type, unblockAt = spec.unblockAt)
                } catch (e: ValidationException) {
                    return Outcome.Err(invalid("/deps/$index", e.message ?: "is not a valid dependency"))
                }
        }
        val idToRef = refToId.entries.associate { (ref, id) -> id to ref }

        fun withRefs(message: String): String = idToRef.entries.fold(message) { text, (id, ref) -> text.replace(id.toString(), "'$ref'") }
        return when (val checked = dependencyCommandService.validateTreeEdges(deps)) {
            is Outcome.Ok ->
                Outcome.Ok(
                    checked.value.map { dep -> TreeDepResult(dep, idToRef.getValue(dep.fromItemId), idToRef.getValue(dep.toItemId)) }
                )
            is Outcome.Err -> {
                val error = checked.error
                val message =
                    when (val detail = error.detail) {
                        is ErrorDetail.CycleDetected -> {
                            val ref = idToRef[detail.path.first()] ?: "unknown"
                            "Circular dependency detected involving ref '$ref'"
                        }
                        else -> withRefs(error.message)
                    }
                Outcome.Err(error.copy(message = message))
            }
        }
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
