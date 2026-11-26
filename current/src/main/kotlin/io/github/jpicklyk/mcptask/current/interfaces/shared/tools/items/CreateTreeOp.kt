package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.application.port.WorkflowStoreProvider
import io.github.jpicklyk.mcptask.current.application.service.ItemCommandErrors
import io.github.jpicklyk.mcptask.current.application.service.TreeAnchorInput
import io.github.jpicklyk.mcptask.current.application.service.TreeDepEnd
import io.github.jpicklyk.mcptask.current.application.service.TreeDepInput
import io.github.jpicklyk.mcptask.current.application.service.TreeItemInput
import io.github.jpicklyk.mcptask.current.application.service.TreeNoteInput
import io.github.jpicklyk.mcptask.current.application.service.TreeRootInput
import io.github.jpicklyk.mcptask.current.application.service.WorkTreeRequest
import io.github.jpicklyk.mcptask.current.application.service.WorkTreeResult
import io.github.jpicklyk.mcptask.current.application.service.WorkTreeService
import io.github.jpicklyk.mcptask.current.config.RuleService
import io.github.jpicklyk.mcptask.current.dependency.DepType
import io.github.jpicklyk.mcptask.current.item.checkItemScope
import io.github.jpicklyk.mcptask.current.item.resolveItemId
import io.github.jpicklyk.mcptask.current.knowledge.ItemDerivedFromTarget
import io.github.jpicklyk.mcptask.current.model.claim.ClaimHolder
import io.github.jpicklyk.mcptask.current.model.error.DomainError
import io.github.jpicklyk.mcptask.current.model.error.ErrorCode
import io.github.jpicklyk.mcptask.current.model.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.model.error.FieldViolation
import io.github.jpicklyk.mcptask.current.model.error.Outcome
import io.github.jpicklyk.mcptask.current.model.schema.SeatCaller
import io.github.jpicklyk.mcptask.current.platform.call.ApiCapability
import io.github.jpicklyk.mcptask.current.platform.call.CallContext
import io.github.jpicklyk.mcptask.current.platform.call.of
import io.github.jpicklyk.mcptask.current.platform.port.WriteScope
import io.github.jpicklyk.mcptask.current.platform.support.IdRef
import io.github.jpicklyk.mcptask.current.platform.support.ScopeGuard
import io.github.jpicklyk.mcptask.current.platform.tool.OpSpec
import io.github.jpicklyk.mcptask.current.platform.tool.invalidFields
import io.github.jpicklyk.mcptask.current.platform.tool.orchestration
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import java.util.UUID

/** The wire name of the `create_tree` op. */
internal object CreateTreeOp {
    const val NAME = "create_tree"
}

/**
 * `items_write` `create_tree`: a root (created, or attached with `root.id`), its children, the dependencies among
 * them or to existing items (`deps[].fromId`/`toId`), their notes and the children's declared sources (`links`),
 * written as ONE unit through [WorkTreeService]. Check order: the request's shape (`invalid_request`, links parsed),
 * then scope on the one anchor (`parentId`, `root.id`, or a new top-level root), then scope and existence of every
 * existing-item dependency end, then scope of every link's note item, then the plan (which also checks those ends'
 * stored edges and resolves the links), then the writes. Every rejection precedes any write, except a fence on an existing end
 * (`not_claim_holder`, judged in the write unit); a failed write rolls the whole tree back. Under a restricted scope a
 * `cycle_detected` path omits the stored items the scope does not admit.
 */
internal fun createTreeOp(): OpSpec<WorkflowToolServices, CreateTreeRequest, ItemWriteResult, WorkflowStoreProvider> =
    OpSpec(
        name = CreateTreeOp.NAME,
        doc = "Create a root (or attach to root.id) with children, dependencies and notes, all or nothing.",
        // Amendment U23: orchestration-only, like complete_tree (the knowledge profile hides it).
        profiles = orchestration(),
        capability = ApiCapability.WRITE_ITEMS,
        services = WorkflowToolServices::class,
        stores = WorkflowStoreProvider::class,
        request = serializer<CreateTreeRequest>(),
        errors =
            setOf(
                ErrorCode.NOT_FOUND,
                ErrorCode.AMBIGUOUS_ID,
                ErrorCode.FORBIDDEN,
                ErrorCode.SCHEMA_VIOLATION,
                ErrorCode.INVALID_TRANSITION,
                ErrorCode.CYCLE_DETECTED,
                ErrorCode.DUPLICATE,
                ErrorCode.PAYLOAD_TOO_LARGE,
                ErrorCode.NOTE_TOO_LONG,
                ErrorCode.UNAVAILABLE,
                ErrorCode.NOT_CLAIM_HOLDER,
                ErrorCode.UNAUTHENTICATED
            ),
        execute = { env, ctx, req -> itemWriteBody(ctx) { createTree(env, ctx, req) } }
    )

private suspend fun WriteScope<WorkflowStoreProvider>.createTree(
    env: WorkflowToolServices,
    ctx: CallContext,
    req: CreateTreeRequest
): Outcome<ItemWriteResult> {
    shapeViolations(req).takeIf { it.isNotEmpty() }?.let { return Outcome.Err(invalidFields(it)) }

    // Scope: the whole tree hangs under one anchor. Every rejection precedes any write.
    var attachId: UUID? = null
    var parentId: UUID? = null
    val rootRef = req.root.id
    val parentRef = req.parentId
    when {
        rootRef != null ->
            attachId =
                when (val r = resolveInScope(env, ctx, rootRef, "/root/id")) {
                    is Outcome.Err -> return r
                    is Outcome.Ok -> r.value
                }
        parentRef != null ->
            parentId =
                when (val r = resolveInScope(env, ctx, parentRef, "/parentId")) {
                    is Outcome.Err -> return r
                    is Outcome.Ok -> r.value
                }
        else -> {
            // A new top-level root founds a new project: unrestricted scope or ADMIN only.
            if (!ctx.rootWritesAllowed) return Outcome.Err(rootWriteForbidden(ctx))
        }
    }
    // Existing-item dependency ends: each in scope (a restricted miss is forbidden), then every one must exist.
    val endIds = HashMap<Pair<Int, Boolean>, UUID>()
    for ((i, dep) in req.deps.withIndex()) {
        for ((isFrom, ref) in listOf(true to dep.fromId, false to dep.toId)) {
            ref ?: continue
            endIds[i to isFrom] =
                when (val r = resolveInScope(env, ctx, ref, "/deps/$i/" + if (isFrom) "fromId" else "toId")) {
                    is Outcome.Err -> return r
                    is Outcome.Ok -> r.value
                }
        }
    }
    if (endIds.isNotEmpty()) {
        val ordered = req.deps.indices.flatMap { i -> listOfNotNull(endIds[i to true], endIds[i to false]) }
        val found = env.workItemRepository().findByIds(ordered.toSet()).mapTo(HashSet()) { it.id }
        ordered.firstOrNull { it !in found }?.let { return Outcome.Err(ItemCommandErrors.itemNotFound(it)) }
    }
    // Each child's declared sources: every note item resolved in scope (a restricted miss is forbidden).
    val childLinks = ArrayList<List<ItemDerivedFromTarget>>(req.children.size)
    for ((c, child) in req.children.withIndex()) {
        childLinks +=
            if (child.links.isEmpty()) {
                emptyList()
            } else {
                val refs = parseItemLinks(child.links, "/children/$c/links").first
                when (val r = linkTargetsInScope(env, ctx, refs, "/children/$c/links")) {
                    is Outcome.Err -> return r
                    is Outcome.Ok -> r.value
                }
            }
    }
    // Every row the tree creates must stay inside the tag scope; an Attach root is an existing row, scope-checked above.
    if (attachId == null) {
        (ScopeGuard.checkTagWrite(ctx.scope, null, joinTags(req.root.tags)) as? Outcome.Err)?.let { return it }
    }
    for (child in req.children) {
        (ScopeGuard.checkTagWrite(ctx.scope, null, joinTags(child.tags)) as? Outcome.Err)?.let { return it }
    }
    val caller = ClaimHolder.resolve(ctx.principal.claim, ctx.seat?.assignmentId)
    val request =
        WorkTreeRequest(
            root =
                if (attachId != null) {
                    TreeRootInput.Attach(attachId, anchors(req.root.noteAnchors))
                } else {
                    TreeRootInput.Create(rootInput(req.root), parentId)
                },
            children = req.children.mapIndexed { c, child -> childInput(child, childLinks[c]) },
            deps =
                req.deps.mapIndexed { i, dep ->
                    TreeDepInput(
                        endIds[i to true]?.let { TreeDepEnd.Item(it) } ?: TreeDepEnd.Ref(dep.from.orEmpty()),
                        endIds[i to false]?.let { TreeDepEnd.Item(it) } ?: TreeDepEnd.Ref(dep.to.orEmpty()),
                        (dep.type ?: DepType.BLOCKS).domain,
                        dep.unblockAt?.wire
                    )
                },
            notes = req.notes.map { TreeNoteInput(it.itemRef, it.key, it.role.wire, it.body) },
            createNotes = req.createNotes,
            docSlug = req.docRef?.slug,
            // Matched against the tree's own root, never looked up: a foreign id is invalid_request, not an existence oracle.
            docRootRef = req.docRef?.rootId?.raw,
            actorClaim = ctx.principal.claim,
            verification = ctx.principal.verification,
            caller = caller,
            callerSeat = ctx.seat?.seat,
            seatCaller = SeatCaller.of(ctx)
        )
    val service = workTreeService(env)
    val named = endIds.values.toSet() + listOfNotNull(attachId)
    val plan =
        when (val planned = service.plan(request)) {
            is Outcome.Err -> return Outcome.Err(redactCyclePath(env, ctx, planned.error, named))
            is Outcome.Ok -> planned.value
        }
    return when (val written = service.write(plan)) {
        is Outcome.Err -> Outcome.Err(redactCyclePath(env, ctx, written.error, named))
        is Outcome.Ok -> Outcome.Ok(result(written.value))
    }
}

/**
 * Under a restricted scope, a `cycle_detected` path keeps only the ids the caller may see, in path order: the [named]
 * ids (existing ends and the attached root, admitted already), the ids the scope admits, and ids with no row (the
 * tree's own new items). A stored hop outside the scope is omitted, as `deps_write` does. Any other error, or an
 * unrestricted caller, is returned unchanged.
 */
private suspend fun redactCyclePath(
    env: WorkflowToolServices,
    ctx: CallContext,
    error: DomainError,
    named: Set<UUID>
): DomainError {
    val detail = error.detail as? ErrorDetail.CycleDetected ?: return error
    if (ctx.scope.isUnrestricted) return error
    val others = detail.path.filterTo(LinkedHashSet()) { it !in named }
    val items = env.workItemRepository()
    val stored = items.findByIds(others).mapTo(HashSet()) { it.id }
    val admitted = ScopeGuard.admitted(ctx.scope, stored, items)
    val visible = detail.path.filter { it in named || it !in stored || it in admitted }
    // the path always holds a tree edge, whose new end has no row, so visible is never empty; the fallback keeps the detail valid
    return error.copy(detail = ErrorDetail.CycleDetected(visible.ifEmpty { detail.path.take(1) }))
}

/** Resolves [ref] (under a restricted scope a miss is `forbidden`) and checks the scope admits it. */
private suspend fun resolveInScope(
    env: WorkflowToolServices,
    ctx: CallContext,
    ref: IdRef,
    field: String
): Outcome<UUID> {
    val id =
        when (val r = resolveItemId(env, ctx.scope, ref, field)) {
            is Outcome.Err -> return r
            is Outcome.Ok -> r.value
        }
    (checkItemScope(env, ctx, id) as? Outcome.Err)?.let { return it }
    return Outcome.Ok(id)
}

/** The tree service over the call's composed services. */
internal fun workTreeService(env: WorkflowToolServices): WorkTreeService =
    WorkTreeService(
        env.stores,
        env.unitOfWork,
        env.configResolver,
        env.itemCommandService,
        env.dependencyCommandService,
        env.noteCommandService,
        env.planDocumentService
    )

/**
 * The request's own faults, all of them, with element-relative pointers: the root's mode, the child refs and their
 * parentRef chain, the refs deps and notes name, and the document reference.
 */
private fun shapeViolations(req: CreateTreeRequest): List<FieldViolation> {
    val out = ArrayList<FieldViolation>()
    if (req.root.id != null) {
        if (req.parentId != null) out += FieldViolation("/parentId", "cannot be given with root.id")
    } else {
        if (req.root.title == null) out += FieldViolation("/root/title", "is required")
        if (req.root.type == null) out += FieldViolation("/root/type", "is required")
    }

    val refs = LinkedHashMap<String, Int>()
    for ((i, child) in req.children.withIndex()) {
        when {
            child.ref.isBlank() -> out += FieldViolation("/children/$i/ref", "must not be blank")
            child.ref == WorkTreeService.ROOT_REF -> out += FieldViolation("/children/$i/ref", "is reserved for the root", child.ref)
            child.ref in refs -> out += FieldViolation("/children/$i/ref", "repeats children/${refs.getValue(child.ref)}", child.ref)
            else -> refs[child.ref] = i
        }
    }
    val known = refs.keys + WorkTreeService.ROOT_REF
    var parentRefsKnown = true
    for ((i, child) in req.children.withIndex()) {
        if (child.parentRef !in known) {
            out += FieldViolation("/children/$i/parentRef", "is not a ref of this tree", child.parentRef)
            parentRefsKnown = false
        }
    }
    if (parentRefsKnown && out.isEmpty()) {
        val (_, cyclic) = WorkTreeService.orderChildRefs(req.children.map { it.ref to it.parentRef })
        if (cyclic.isNotEmpty()) out += FieldViolation("/children", "parentRef chain is cyclic: ${cyclic.joinToString(", ")}")
    }

    for ((i, dep) in req.deps.withIndex()) {
        for ((name, ref, id) in listOf(Triple("from", dep.from, dep.fromId), Triple("to", dep.to, dep.toId))) {
            when {
                ref != null && id != null -> out += FieldViolation("/deps/$i/${name}Id", "cannot be given with $name")
                ref == null && id == null -> out += FieldViolation("/deps/$i/$name", "is required without ${name}Id")
                ref != null && ref !in known -> out += FieldViolation("/deps/$i/$name", "is not a ref of this tree", ref)
            }
        }
        if (dep.from == null && dep.to == null && dep.fromId != null && dep.toId != null) {
            out += FieldViolation("/deps/$i", "needs a ref of this tree at one end")
        }
        if (dep.from != null && dep.from == dep.to) out += FieldViolation("/deps/$i/to", "must differ from from", dep.to)
        if (dep.type == DepType.RELATES_TO && dep.unblockAt != null) {
            out += FieldViolation("/deps/$i/unblockAt", "is not allowed with relates_to", dep.unblockAt.wire)
        }
    }
    for ((i, note) in req.notes.withIndex()) {
        if (note.itemRef !in known) out += FieldViolation("/notes/$i/itemRef", "is not a ref of this tree", note.itemRef)
    }
    for ((c, child) in req.children.withIndex()) {
        if (child.links.isNotEmpty()) out += parseItemLinks(child.links, "/children/$c/links").second
    }

    val docRef = req.docRef
    val anchored = req.root.noteAnchors.isNotEmpty() || req.children.any { it.noteAnchors.isNotEmpty() }
    if (docRef == null) {
        if (anchored) out += FieldViolation("/docRef", "is required with noteAnchors")
    } else if (docRef.slug.isBlank()) {
        out += FieldViolation("/docRef/slug", "must not be blank")
    } else if (docRef.slug.startsWith(RuleService.RULE_SLUG_PREFIX)) {
        out += FieldViolation("/docRef/slug", "cannot name a rule document", docRef.slug)
    }
    return out
}

private fun anchors(list: List<TreeNoteAnchor>): List<TreeAnchorInput> = list.map { TreeAnchorInput(it.noteKey, it.role.wire, it.anchor) }

private fun rootInput(root: TreeRootSpec): TreeItemInput =
    TreeItemInput(
        ref = WorkTreeService.ROOT_REF,
        parentRef = null,
        title = root.title.orEmpty(),
        type = root.type,
        priority = root.priority.priority,
        tags = joinTags(root.tags),
        traits = root.traits,
        summary = root.summary,
        description = root.description,
        statusLabel = root.statusLabel,
        complexity = root.complexity,
        requiresVerification = root.requiresVerification,
        metadata = root.metadata,
        properties = root.properties?.toString(),
        anchors = anchors(root.noteAnchors)
    )

private fun childInput(
    child: TreeChildSpec,
    links: List<ItemDerivedFromTarget>
): TreeItemInput =
    TreeItemInput(
        ref = child.ref,
        parentRef = child.parentRef,
        title = child.title,
        type = child.type,
        priority = child.priority.priority,
        tags = joinTags(child.tags),
        traits = child.traits,
        summary = child.summary,
        description = child.description,
        statusLabel = child.statusLabel,
        complexity = child.complexity,
        requiresVerification = child.requiresVerification,
        metadata = child.metadata,
        properties = child.properties?.toString(),
        anchors = anchors(child.noteAnchors),
        links = links
    )

/** The element: the root's id and version, then `items`, `deps` and `notes`. */
private fun result(tree: WorkTreeResult): ItemWriteResult =
    ItemWriteResult(
        id = tree.rootId,
        version = tree.rootVersion,
        payload =
            buildJsonObject {
                put(
                    "items",
                    JsonArray(
                        tree.items.map {
                            buildJsonObject {
                                put("ref", it.ref)
                                put("id", it.id.toString())
                                put("depth", it.depth)
                            }
                        }
                    )
                )
                put(
                    "deps",
                    JsonArray(
                        tree.deps.map {
                            val dep = it.dependency
                            buildJsonObject {
                                put("id", dep.id.toString())
                                // An existing-item end reports its id instead of a ref.
                                val fromRef = it.fromRef
                                if (fromRef != null) put("fromRef", fromRef) else put("fromId", dep.fromItemId.toString())
                                val toRef = it.toRef
                                if (toRef != null) put("toRef", toRef) else put("toId", dep.toItemId.toString())
                                put("type", (DepType.of(dep.type) ?: DepType.BLOCKS).wire)
                                dep.unblockAt?.let { at -> put("unblockAt", at.lowercase()) }
                            }
                        }
                    )
                )
                put(
                    "notes",
                    JsonArray(
                        tree.notes.map {
                            buildJsonObject {
                                put("itemRef", it.itemRef)
                                put("key", it.note.key)
                                put("role", it.note.role)
                                put("id", it.note.id.toString())
                                it.warning?.let { w -> put("warning", w.message()) }
                            }
                        }
                    )
                )
            }
    )
