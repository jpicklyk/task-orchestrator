package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.IdRef
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.api.deps.DepType
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.application.service.RuleService
import io.github.jpicklyk.mcptask.current.application.service.TreeAnchorInput
import io.github.jpicklyk.mcptask.current.application.service.TreeDepInput
import io.github.jpicklyk.mcptask.current.application.service.TreeItemInput
import io.github.jpicklyk.mcptask.current.application.service.TreeNoteInput
import io.github.jpicklyk.mcptask.current.application.service.TreeRootInput
import io.github.jpicklyk.mcptask.current.application.service.WorkTreeRequest
import io.github.jpicklyk.mcptask.current.application.service.WorkTreeResult
import io.github.jpicklyk.mcptask.current.application.service.WorkTreeService
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.FieldViolation
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.invalidFields
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.orchestration
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
 * `items_write` `create_tree`: a root (created, or attached with `root.id`), its children, the dependencies between
 * them and their notes, written as ONE unit through [WorkTreeService]. Check order: the request's shape
 * (`invalid_request`), then scope on the one anchor (`parentId`, `root.id`, or a new top-level root), then the plan,
 * then the writes. Every rejection precedes any write; a failed write rolls the whole tree back.
 */
internal fun createTreeOp(): OpSpec<CreateTreeRequest, ItemWriteResult> =
    OpSpec(
        name = CreateTreeOp.NAME,
        doc = "Create a root (or attach to root.id) with children, dependencies and notes, all or nothing.",
        // Amendment U23: orchestration-only, like complete_tree (the knowledge profile hides it).
        profiles = orchestration(),
        capability = ApiCapability.WRITE_ITEMS,
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
                ErrorCode.UNAVAILABLE
            ),
        execute = { env, ctx, req -> itemWriteBody(ctx) { createTree(env, ctx, req) } }
    )

private suspend fun WriteScope.createTree(
    env: ToolExecutionContext,
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
    val request =
        WorkTreeRequest(
            root =
                if (attachId != null) {
                    TreeRootInput.Attach(attachId, anchors(req.root.noteAnchors))
                } else {
                    TreeRootInput.Create(rootInput(req.root), parentId)
                },
            children = req.children.map(::childInput),
            deps = req.deps.map { TreeDepInput(it.from, it.to, (it.type ?: DepType.BLOCKS).domain, it.unblockAt?.wire) },
            notes = req.notes.map { TreeNoteInput(it.itemRef, it.key, it.role.wire, it.body) },
            createNotes = req.createNotes,
            docSlug = req.docRef?.slug,
            // Matched against the tree's own root, never looked up: a foreign id is invalid_request, not an existence oracle.
            docRootRef = req.docRef?.rootId?.raw,
            actorClaim = ctx.principal.claim,
            verification = ctx.principal.verification
        )
    val service = workTreeService(env)
    val plan =
        when (val planned = service.plan(request)) {
            is Outcome.Err -> return planned
            is Outcome.Ok -> planned.value
        }
    return when (val written = service.write(plan)) {
        is Outcome.Err -> written
        is Outcome.Ok -> Outcome.Ok(result(written.value))
    }
}

/** Resolves [ref] (under a restricted scope a miss is `forbidden`) and checks the scope admits it. */
private suspend fun resolveInScope(
    env: ToolExecutionContext,
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
internal fun workTreeService(env: ToolExecutionContext): WorkTreeService =
    WorkTreeService(
        env.repositoryProvider,
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
        if (dep.from !in known) out += FieldViolation("/deps/$i/from", "is not a ref of this tree", dep.from)
        if (dep.to !in known) out += FieldViolation("/deps/$i/to", "is not a ref of this tree", dep.to)
        if (dep.from == dep.to) out += FieldViolation("/deps/$i/to", "must differ from from", dep.to)
        if (dep.type == DepType.RELATES_TO && dep.unblockAt != null) {
            out += FieldViolation("/deps/$i/unblockAt", "is not allowed with relates_to", dep.unblockAt.wire)
        }
    }
    for ((i, note) in req.notes.withIndex()) {
        if (note.itemRef !in known) out += FieldViolation("/notes/$i/itemRef", "is not a ref of this tree", note.itemRef)
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

private fun childInput(child: TreeChildSpec): TreeItemInput =
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
        anchors = anchors(child.noteAnchors)
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
                                put("fromRef", it.fromRef)
                                put("toRef", it.toRef)
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
