package io.github.jpicklyk.mcptask.current.application.tools.compound

import io.github.jpicklyk.mcptask.current.application.service.DocRefSpec
import io.github.jpicklyk.mcptask.current.application.service.MarkdownSectionSplitter
import io.github.jpicklyk.mcptask.current.application.service.TreeDepSpec
import io.github.jpicklyk.mcptask.current.application.service.WorkTreeInput
import io.github.jpicklyk.mcptask.current.application.service.WorkTreeResult
import io.github.jpicklyk.mcptask.current.application.service.buildSchemaResponseFields
import io.github.jpicklyk.mcptask.current.application.tools.*
import io.github.jpicklyk.mcptask.current.domain.model.*
import io.github.jpicklyk.mcptask.current.domain.repository.ChildPlacement
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.util.UUID

/**
 * MCP tool that atomically creates a hierarchical work tree in a single operation.
 *
 * Given a root item spec and optional child specs, creates all items plus optional
 * dependencies and notes in one atomic call. Eliminates the round-trips required
 * by calling manage_items + manage_dependencies + manage_notes separately.
 *
 * No application-layer depth cap is enforced. Cycle protection is delegated to the
 * DB BEFORE-UPDATE trigger on work_items.parent_id introduced in V7.
 */
class CreateWorkTreeTool :
    BaseToolDefinition(),
    ActorAware {
    companion object {
        /** Logical ref name reserved for the root item in dep specs. */
        const val ROOT_REF = "root"
    }

    override val name = "create_work_tree"

    override val description =
        """
Atomically create a hierarchical work tree: root item, child items, dependencies, and optional notes.
Eliminates the round-trips of calling manage_items + manage_dependencies + manage_notes separately.

**Attach mode:** pass `root.id` to attach children/deps/notes to an existing item instead of creating
a new root; `root.title` is then optional. `root.id` and `parentId` cannot both be given (contradictory
— attach vs. new root under a parent). The existing item is NOT re-inserted; children are created
under it at existing.depth + 1.

**Depth:** root depth = parent.depth + 1 when `parentId` is given, otherwise 0 (or the existing root's
depth in attach mode). No depth cap.

**Materialize-from-document:** pass `docRef` (`{ rootId?, slug }`) plus per-item `noteAnchors`
(`root` and/or any `children` entry) to source note bodies from a stashed plan document
(see `manage_plan_documents`) instead of inlining them. Each anchor is sliced from the document and
written as that item's note in the SAME transaction as the item/dependency/note inserts, then the
document is marked adopted by the created/attached root. Precedence on `(itemRef, key)` collision:
explicit `notes` win over `noteAnchors`; `noteAnchors` win over `createNotes=true` blanks. Any anchor
miss, an unresolved document, a `docRef.rootId` mismatch, or an already-adopted document fails the
WHOLE call atomically — no items are created. Without `docRef`, behavior is unchanged.

Call when materializing a planned hierarchy — one atomic call instead of per-item create sequences.
        """.trimIndent()

    override val category = ToolCategory.ITEM_MANAGEMENT

    override val toolAnnotations =
        ToolAnnotations(
            readOnlyHint = false,
            destructiveHint = false,
            idempotentHint = false,
            openWorldHint = false
        )

    override val parameterSchema =
        ToolSchema(
            properties =
                buildJsonObject {
                    put(
                        "root",
                        buildJsonObject {
                            put("type", JsonPrimitive("object"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Root item spec: { id? (attach mode: UUID or hex prefix of an existing item — " +
                                        "makes title optional/ignored), title (required unless id given), priority?, " +
                                        "tags?, traits? (comma-separated, merged into properties), summary?, " +
                                        "description?, requiresVerification?, type?, noteAnchors? ([{ noteKey, role, " +
                                        "anchor }] — sources this item's note bodies from the docRef document; " +
                                        "requires top-level docRef) }"
                                )
                            )
                        }
                    )
                    put(
                        "docRef",
                        buildJsonObject {
                            put("type", JsonPrimitive("object"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Materialize-from-document source: { rootId? (defaults to the created/attached " +
                                        "root's own rootId; validated for consistency if given — UUID or hex prefix), " +
                                        "slug (required, the stashed plan document's slug) }. Required whenever any " +
                                        "item spec's noteAnchors is used."
                                )
                            )
                        }
                    )
                    put(
                        "parentId",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "UUID or hex prefix (4+ chars) of existing parent item. Root depth = parent.depth + 1 if provided."
                                )
                            )
                        }
                    )
                    put(
                        "children",
                        buildJsonObject {
                            put("type", JsonPrimitive("array"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Child item specs: [{ ref (required local name, used to wire deps/notes/parentRef), " +
                                        "title (required), parentRef? (parent's ref or \"root\", default \"root\"), " +
                                        "priority?, tags?, traits? (comma-separated, merged into properties), summary?, " +
                                        "description?, requiresVerification?, type?, noteAnchors? ([{ noteKey, role, " +
                                        "anchor }] — sources this item's note bodies from the docRef document; " +
                                        "requires top-level docRef) }]. Order-independent (topologically " +
                                        "sorted); nesting is expressed via parentRef only — a nested 'children' key " +
                                        "inside an item spec is rejected, not silently dropped."
                                )
                            )
                        }
                    )
                    put(
                        "deps",
                        buildJsonObject {
                            put("type", JsonPrimitive("array"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Dependencies: [{ from: ref, to: ref, type?: BLOCKS|IS_BLOCKED_BY|RELATES_TO, unblockAt?: queue|work|review|terminal }]. Use \"root\" to reference the root item."
                                )
                            )
                        }
                    )
                    put(
                        "createNotes",
                        buildJsonObject {
                            put("type", JsonPrimitive("boolean"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Auto-create blank notes from each item's resolved schema (looked up by type first, " +
                                        "then by tags). Default: false."
                                )
                            )
                        }
                    )
                    put(
                        "notes",
                        buildJsonObject {
                            put("type", JsonPrimitive("array"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Notes to create with bodies: [{ itemRef (required, \"root\" or child ref), " +
                                        "key (required), role (required: queue|work|review), body? (default empty) }]. " +
                                        "Wins over createNotes=true blanks per (itemRef, key). When a key is declared " +
                                        "in the item's resolved schema, role must match the schema role (mismatch " +
                                        "rejected); off-schema keys and schema-free items are unconstrained."
                                )
                            )
                        }
                    )
                    put(
                        "requestId",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Client-generated UUID for idempotency (10 min cache, keyed by actor+requestId); " +
                                        "requires actor; malformed values rejected."
                                )
                            )
                        }
                    )
                    put(
                        "actor",
                        buildJsonObject {
                            put("type", JsonPrimitive("object"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Top-level actor for (1) idempotency and (2) note attribution across the whole " +
                                        "tree (explicit and createNotes=true blanks alike) — unlike manage_notes, no " +
                                        "per-note actor. If malformed, idempotency is disabled and attribution drops " +
                                        "to null; the call still succeeds. Shape: { id (required), " +
                                        "kind (required: orchestrator|subagent|user|external), parent?, proof? }"
                                )
                            )
                        }
                    )
                },
            required = listOf("root")
        )

    override fun validateParams(params: JsonElement) {
        validateRequestIdParam(params)
        val paramsObj =
            params as? JsonObject
                ?: throw ToolValidationException("Parameters must be a JSON object")

        val rootObj = validateRootSpec(paramsObj)
        val docRefPresent = validateDocRefSpec(paramsObj)
        var anyNoteAnchors = validateNoteAnchorsField(rootObj, "root")
        if (validateChildrenSpec(paramsObj)) anyNoteAnchors = true
        validateDepsSpec(paramsObj)
        validateNotesSpec(paramsObj)

        if (anyNoteAnchors && !docRefPresent) {
            throw ToolValidationException("'noteAnchors' requires top-level 'docRef' to be provided")
        }
    }

    /**
     * Validates the top-level `root` spec: presence/shape, attach-vs-create mode (`root.id` vs
     * `root.title` + the `root.id`+`parentId` conflict), its `priority` field, and that it does
     * not carry a nested `children` array. Returns the parsed root object for reuse by the caller
     * (its `noteAnchors` are validated separately, after `docRef`, matching original precedence).
     */
    private fun validateRootSpec(paramsObj: JsonObject): JsonObject {
        val rootElement =
            paramsObj["root"]
                ?: throw ToolValidationException("'root' is required")
        val rootObj =
            rootElement as? JsonObject
                ?: throw ToolValidationException("'root' must be a JSON object")

        val rootId = (rootObj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val rootTitle = (rootObj["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content

        if (rootId != null && !rootId.isBlank()) {
            // Attach mode: root.id provided — root.title is optional.
            // Reject root.id combined with parentId (contradictory: attach vs new-root-under-parent).
            val parentIdElement = paramsObj["parentId"]
            if (parentIdElement != null && parentIdElement !is JsonNull) {
                val parentIdStr = (parentIdElement as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (!parentIdStr.isNullOrBlank()) {
                    throw ToolValidationException(
                        "'root.id' and 'parentId' cannot both be provided: 'root.id' attaches to an existing " +
                            "item (which already has its own parent), while 'parentId' creates a new root under a parent."
                    )
                }
            }
        } else {
            // Create mode: root.title is required.
            if (rootTitle.isNullOrBlank()) {
                throw ToolValidationException("'root.title' is required and must be a non-blank string")
            }
        }

        validatePriorityField(rootObj, "root")
        rejectNestedChildren(rootObj, "root")

        return rootObj
    }

    /**
     * Validates the optional top-level `docRef` (materialize-from-document source). Returns
     * whether `docRef` was provided at all — used by the caller to require it whenever any item
     * spec carries `noteAnchors`.
     */
    private fun validateDocRefSpec(paramsObj: JsonObject): Boolean {
        val docRefElement = paramsObj["docRef"]
        val docRefPresent = docRefElement != null && docRefElement !is JsonNull
        if (docRefPresent) {
            val docRefObj =
                docRefElement as? JsonObject
                    ?: throw ToolValidationException("'docRef' must be a JSON object")
            val slug = (docRefObj["slug"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (slug.isNullOrBlank()) {
                throw ToolValidationException("'docRef.slug' is required and must be a non-blank string")
            }
            val rootIdElement = docRefObj["rootId"]
            if (rootIdElement != null && rootIdElement !is JsonNull) {
                val rootIdStr = (rootIdElement as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (rootIdStr.isNullOrBlank()) {
                    throw ToolValidationException("'docRef.rootId' must be a non-blank string when provided")
                }
            }
        }
        return docRefPresent
    }

    /**
     * Validates the optional `children` array: per-child ref/reserved-ref/title/priority/nested-
     * children/noteAnchors checks (in that order, matching original precedence), then `parentRef`
     * resolution and parentRef-chain cycle detection across the whole array. Returns true if any
     * child carries a non-empty `noteAnchors`.
     */
    private fun validateChildrenSpec(paramsObj: JsonObject): Boolean {
        val childrenElement = paramsObj["children"]
        if (childrenElement == null || childrenElement is JsonNull) return false

        val children =
            childrenElement as? JsonArray
                ?: throw ToolValidationException("'children' must be a JSON array")

        var anyNoteAnchors = false
        val allRefs = mutableListOf<String>()
        for ((index, child) in children.withIndex()) {
            val childObj =
                child as? JsonObject
                    ?: throw ToolValidationException("children[$index] must be a JSON object")
            val ref = (childObj["ref"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (ref.isNullOrBlank()) {
                throw ToolValidationException("children[$index]: 'ref' is required")
            }
            if (ref == ROOT_REF) {
                throw ToolValidationException("children[$index]: ref '$ROOT_REF' is reserved for the root item")
            }
            val title = (childObj["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (title.isNullOrBlank()) {
                throw ToolValidationException("children[$index]: 'title' is required")
            }
            validatePriorityField(childObj, "children[$index]")
            rejectNestedChildren(childObj, "children[$index]")
            if (validateNoteAnchorsField(childObj, "children[$index]")) anyNoteAnchors = true
            allRefs.add(ref)
        }

        validateParentRefsAndCycles(children, allRefs)

        return anyNoteAnchors
    }

    /**
     * Validates each child's `parentRef` resolves to a known ref (or `"root"`), then detects
     * cycles by walking the `parentRef` chain from every child.
     */
    private fun validateParentRefsAndCycles(
        children: JsonArray,
        allRefs: List<String>
    ) {
        val validParentRefs = setOf(ROOT_REF) + allRefs.toSet()
        val refToParentRef = mutableMapOf<String, String>()
        for ((index, child) in children.withIndex()) {
            val childObj = child as JsonObject
            val ref = (childObj["ref"] as? JsonPrimitive)!!.content
            val parentRef = (childObj["parentRef"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ROOT_REF
            if (parentRef !in validParentRefs) {
                throw ToolValidationException(
                    "children[$index]: 'parentRef' '$parentRef' is not defined. Valid refs: ${validParentRefs.joinToString()}"
                )
            }
            refToParentRef[ref] = parentRef
        }
        // Cycle detection: for each ref, walk the parentRef chain
        for (startRef in allRefs) {
            val visited = mutableSetOf<String>()
            var current = startRef
            while (current != ROOT_REF) {
                if (!visited.add(current)) {
                    throw ToolValidationException(
                        "children: cycle detected in parentRef chain involving '$current'"
                    )
                }
                current = refToParentRef[current] ?: break
            }
        }
    }

    /** Validates the optional top-level `deps` array: each entry requires non-blank `from`/`to`. */
    private fun validateDepsSpec(paramsObj: JsonObject) {
        val depsElement = paramsObj["deps"]
        if (depsElement == null || depsElement is JsonNull) return

        val deps =
            depsElement as? JsonArray
                ?: throw ToolValidationException("'deps' must be a JSON array")
        for ((index, dep) in deps.withIndex()) {
            val depObj =
                dep as? JsonObject
                    ?: throw ToolValidationException("deps[$index] must be a JSON object")
            val from = (depObj["from"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (from.isNullOrBlank()) {
                throw ToolValidationException("deps[$index]: 'from' is required")
            }
            val to = (depObj["to"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (to.isNullOrBlank()) {
                throw ToolValidationException("deps[$index]: 'to' is required")
            }
        }
    }

    /**
     * Validates the optional top-level `notes` array: `itemRef`/`key`/`role` required, `role`
     * must be one of queue/work/review, and `body` (if present) must be a string.
     */
    private fun validateNotesSpec(paramsObj: JsonObject) {
        val notesElement = paramsObj["notes"]
        if (notesElement == null || notesElement is JsonNull) return

        val notes =
            notesElement as? JsonArray
                ?: throw ToolValidationException("'notes' must be a JSON array")
        val validRoles = setOf("queue", "work", "review")
        for ((index, noteElement) in notes.withIndex()) {
            val noteObj =
                noteElement as? JsonObject
                    ?: throw ToolValidationException("notes[$index] must be a JSON object")
            val itemRef = (noteObj["itemRef"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (itemRef.isNullOrBlank()) {
                throw ToolValidationException("notes[$index]: 'itemRef' is required and must be a non-blank string")
            }
            val key = (noteObj["key"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (key.isNullOrBlank()) {
                throw ToolValidationException("notes[$index]: 'key' is required and must be a non-blank string")
            }
            val role = (noteObj["role"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (role.isNullOrBlank()) {
                throw ToolValidationException("notes[$index]: 'role' is required and must be a non-blank string")
            }
            if (role !in validRoles) {
                throw ToolValidationException("notes[$index]: invalid role '$role'. Valid: queue, work, review")
            }
            val bodyElement = noteObj["body"]
            if (bodyElement != null && bodyElement !is JsonNull) {
                val isStringPrim = (bodyElement as? JsonPrimitive)?.isString == true
                if (!isStringPrim) {
                    throw ToolValidationException("notes[$index]: 'body' must be a string")
                }
            }
        }
    }

    /**
     * Validates an item spec's optional `priority` field the same way `manage_items` does
     * ([io.github.jpicklyk.mcptask.current.application.tools.items.CreateItemHandler]): a blank or
     * non-string value means "absent" and defaults to [Priority.MEDIUM] downstream in
     * [buildWorkItem]; a non-blank string that fails case-insensitive [Priority.fromString] is a
     * validation error rather than a silent coercion to MEDIUM.
     */
    private fun validatePriorityField(
        itemObj: JsonObject,
        contextLabel: String
    ) {
        val priorityStr = (itemObj["priority"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (priorityStr.isNullOrBlank()) return
        if (Priority.fromString(priorityStr) == null) {
            throw ToolValidationException(
                "$contextLabel: invalid priority '$priorityStr'. Valid: high, medium, low"
            )
        }
    }

    /**
     * Validates an item spec's optional `noteAnchors` array (`[{ noteKey, role, anchor }]`).
     * Returns true if [itemObj] carries at least one anchor — used by the caller to require a
     * top-level `docRef` whenever any item spec uses this field.
     */
    private fun validateNoteAnchorsField(
        itemObj: JsonObject,
        contextLabel: String
    ): Boolean {
        val element = itemObj["noteAnchors"]
        if (element == null || element is JsonNull) return false
        val anchors =
            element as? JsonArray
                ?: throw ToolValidationException("$contextLabel: 'noteAnchors' must be a JSON array")
        if (anchors.isEmpty()) return false

        val validRoles = setOf("queue", "work", "review")
        for ((index, anchorElement) in anchors.withIndex()) {
            val anchorObj =
                anchorElement as? JsonObject
                    ?: throw ToolValidationException("$contextLabel: noteAnchors[$index] must be a JSON object")
            val noteKey = (anchorObj["noteKey"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (noteKey.isNullOrBlank()) {
                throw ToolValidationException(
                    "$contextLabel: noteAnchors[$index]: 'noteKey' is required and must be a non-blank string"
                )
            }
            val role = (anchorObj["role"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (role.isNullOrBlank() || role !in validRoles) {
                throw ToolValidationException(
                    "$contextLabel: noteAnchors[$index]: 'role' is required and must be one of queue, work, review"
                )
            }
            val anchor = (anchorObj["anchor"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (anchor.isNullOrBlank()) {
                throw ToolValidationException(
                    "$contextLabel: noteAnchors[$index]: 'anchor' is required and must be a non-blank string"
                )
            }
        }
        return true
    }

    override suspend fun execute(
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement {
        val paramsObj = params as JsonObject

        // Defence-in-depth: unreachable via MCP (validateParams already ran validateRequestIdParam),
        // but guards a direct in-process call to execute() that skipped validateParams.
        validateRequestIdParam(params)
        val requestIdStr = optionalString(params, "requestId")
        val requestId = requestIdStr?.let { UUID.fromString(it.trim()) }

        // Resolve trusted actor identity from the top-level actor for the idempotency key.
        // Must be done BEFORE the cache lookup so the cache is keyed on the verified identity,
        // not the self-reported actor.id (bug 3a fix).
        // Capture the parsed claim and verification so they can be propagated as
        // attribution metadata on every persisted Note (matches ManageNotesTool semantics).
        val actorObj = paramsObj["actor"] as? JsonObject
        val parsedActor: ActorParseResult =
            if (actorObj != null) parseActorClaim(actorObj, context) else ActorParseResult.Absent
        val noteActorClaim: ActorClaim? =
            (parsedActor as? ActorParseResult.Success)?.claim
        val noteVerification: VerificationResult? =
            (parsedActor as? ActorParseResult.Success)?.verification
        val trustedActorId: String? =
            when (parsedActor) {
                is ActorParseResult.Success -> {
                    when (
                        val r =
                            ActorAware.resolveTrustedActorId(
                                parsedActor.claim,
                                parsedActor.verification,
                                context.degradedModePolicy
                            )
                    ) {
                        is PolicyResolution.Trusted -> r.trustedId
                        is PolicyResolution.Rejected -> null
                    }
                }
                else -> null
            }

        // Atomic getOrCompute: check-compute-store under a single lock to prevent TOCTOU races.
        // kotlinx.coroutines.runBlocking bridges the suspend execution into the lock-held lambda.
        // This is safe because the tree creation logic only accesses DB repositories and never
        // re-acquires the IdempotencyCache lock.
        if (requestId != null && trustedActorId != null) {
            return context.idempotencyCache.getOrCompute(trustedActorId, requestId) {
                runBlocking {
                    executeCreateWorkTree(paramsObj, params, context, noteActorClaim, noteVerification)
                }
            }
        }

        return executeCreateWorkTree(paramsObj, params, context, noteActorClaim, noteVerification)
    }

    private suspend fun executeCreateWorkTree(
        paramsObj: JsonObject,
        params: JsonElement,
        context: ToolExecutionContext,
        noteActorClaim: ActorClaim?,
        noteVerification: VerificationResult?
    ): JsonElement {
        val rootObj = paramsObj["root"] as JsonObject

        val (rootResolution, rootError) = resolveRootAndMode(rootObj, params, context)
        if (rootError != null) return rootError
        val (rootItem, isAttachMode, isExistingRoot, rootIdStr, parentId) = rootResolution!!

        val (effectiveRootId, effectiveRootIdError) =
            resolveEffectiveRootId(isAttachMode, rootItem, rootIdStr, context)
        if (effectiveRootIdError != null) return effectiveRootIdError

        val (docRefSource, docRefError) = resolveDocRefSource(paramsObj, effectiveRootId!!, context)
        if (docRefError != null) return docRefError
        val (docSlug, docRootId) = docRefSource!!

        val (childrenResult, childrenError) = buildChildren(paramsObj, rootItem)
        if (childrenError != null) return childrenError
        val (refToItem, sortedChildRefs) = childrenResult!!

        val (depSpecsResult, depsError) = buildDependencySpecs(paramsObj, refToItem)
        if (depsError != null) return depsError
        val depSpecs = depSpecsResult!!

        val childrenArray = paramsObj["children"] as? JsonArray ?: JsonArray(emptyList())
        val (notesListResult, notesError) =
            buildNotes(
                paramsObj,
                params,
                rootObj,
                childrenArray,
                refToItem,
                effectiveRootId,
                docSlug,
                docRootId,
                context,
                noteActorClaim,
                noteVerification
            )
        if (notesError != null) return notesError
        val notesList = notesListResult!!

        // Build ordered item list. In attach mode the existing root is NOT inserted (it already
        // exists in the DB). In create mode the root leads the list (root first, children in
        // topological order).
        val orderedItems = mutableListOf<WorkItem>()
        if (!isExistingRoot) {
            orderedItems.add(rootItem)
        }
        for (ref in sortedChildRefs) {
            orderedItems.add(refToItem[ref]!!)
        }

        val input =
            WorkTreeInput(
                items = orderedItems,
                refToItem = refToItem,
                deps = depSpecs,
                notes = notesList,
                docRef = docSlug?.let { slug -> DocRefSpec(rootItemId = docRootId!!, slug = slug, adoptingItemId = rootItem.id) }
            )

        val (transactionOutcome, transactionError) =
            runWorkTreeTransaction(context, isAttachMode, rootItem, rootIdStr, parentId, input, orderedItems)
        if (transactionError != null) return transactionError
        val (treeResult, finalRootPlacement) = transactionOutcome!!

        // In attach mode, correct the root's depth/rootId to the SAME authoritative values just
        // stamped onto its children by the transaction (placement.depth - 1 / placement.rootId),
        // rather than the value fetched back earlier — the root row itself is not rewritten by
        // this call, so this is response-shaping only, not a second write.
        val finalRootItem =
            finalRootPlacement?.let { placement ->
                rootItem.copy(depth = placement.depth - 1, rootId = placement.rootId)
            } ?: rootItem
        // In attach mode the root was not inserted — use finalRootItem for the response. In
        // create mode the root is treeResult.items.first() (already the persisted, restamped row).
        val rootResultItem = if (isExistingRoot) finalRootItem else treeResult.items.first()

        return buildTreeResponse(context, treeResult, rootResultItem, isExistingRoot, depSpecs)
    }

    private data class RootResolution(
        val rootItem: WorkItem,
        val isAttachMode: Boolean,
        val isExistingRoot: Boolean,
        val rootIdStr: String?,
        val parentId: UUID?
    )

    /**
     * Detects attach-vs-create mode from `root.id`, resolves `parentId` (create mode only), and
     * resolves the root item: fetches the existing item in attach mode, or builds a new one in
     * create mode using the parent's depth/rootId (or depth 0 / a fresh id at top level).
     */
    private suspend fun resolveRootAndMode(
        rootObj: JsonObject,
        params: JsonElement,
        context: ToolExecutionContext
    ): Pair<RootResolution?, JsonElement?> {
        val rootIdStr = (rootObj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        val isAttachMode = rootIdStr != null

        // validateParams already rejects root.id + parentId together, so if we reach here in
        // attach mode, parentId is absent.
        val (parentId, parentIdError) = resolveItemId(params, "parentId", context, required = false)
        if (parentIdError != null) return null to parentIdError

        if (isAttachMode) {
            // Resolve the existing item via id (supports hex prefix like parentId)
            val (resolvedRootId, rootIdError) = resolveIdString(rootIdStr!!, context)
            if (rootIdError != null) return null to rootIdError
            return when (val fetchResult = context.workItemRepository().getById(resolvedRootId!!)) {
                is Result.Success ->
                    RootResolution(
                        rootItem = fetchResult.data,
                        isAttachMode = true,
                        isExistingRoot = true,
                        rootIdStr = rootIdStr,
                        parentId = parentId
                    ) to null
                is Result.Error ->
                    null to
                        errorResponse(
                            "Root item '$rootIdStr' not found: ${fetchResult.error.message}",
                            ErrorCodes.RESOURCE_NOT_FOUND
                        )
            }
        }

        // Create mode: compute root depth + rootId from parent, then build a new WorkItem.
        // rootId: a new root with no parentId is its own root; a new root created under
        // parentId inherits that parent's root (or the parent's own id, if the parent predates
        // the root_id backfill and has no rootId yet) — same idiom as CreateItemHandler.
        val rootItemId = UUID.randomUUID()
        val rootDepth: Int
        val rootRootId: UUID
        if (parentId != null) {
            when (val parentResult = context.workItemRepository().getById(parentId)) {
                is Result.Success -> {
                    rootDepth = parentResult.data.depth + 1
                    rootRootId = parentResult.data.rootId ?: parentResult.data.id
                }
                is Result.Error ->
                    return null to
                        errorResponse(
                            "Parent item '$parentId' not found: ${parentResult.error.message}",
                            ErrorCodes.RESOURCE_NOT_FOUND
                        )
            }
        } else {
            rootDepth = 0
            rootRootId = rootItemId
        }
        val rootItem =
            buildWorkItem(
                obj = rootObj,
                parentId = parentId,
                depth = rootDepth,
                rootId = rootRootId,
                contextLabel = "root",
                id = rootItemId
            ) ?: return null to errorResponse("Failed to build root item", ErrorCodes.VALIDATION_ERROR)

        return RootResolution(
            rootItem = rootItem,
            isAttachMode = false,
            isExistingRoot = false,
            rootIdStr = rootIdStr,
            parentId = parentId
        ) to null
    }

    /**
     * Best-effort refresh of the tree's effective rootId, feeding schema resolution and the
     * docRef root default below — NOT the correctness-critical read. In create mode, [rootItem]
     * was just built from a parent read taken moments ago (or has no anchor at all) — already as
     * fresh as anything pre-transaction can be. In attach mode, [rootItem] was fetched
     * independently and its own rootId may already be stale relative to a concurrent reparent of
     * the root itself, so it is re-read via resolveChildPlacement (its rootId component is
     * exactly the queried item's own effective rootId, independent of the +1 depth offset that
     * method computes for a hypothetical child). The AUTHORITATIVE read that actually gates the
     * write happens again inside the write transaction (see [runWorkTreeTransaction]) and is what
     * restamps the rows actually written — a race in the narrow window between this read and that
     * one could only affect which schema/document was selected, never the depth/rootId values
     * persisted (AR-19).
     */
    private suspend fun resolveEffectiveRootId(
        isAttachMode: Boolean,
        rootItem: WorkItem,
        rootIdStr: String?,
        context: ToolExecutionContext
    ): Pair<UUID?, JsonElement?> {
        if (!isAttachMode) return (rootItem.rootId ?: rootItem.id) to null
        return when (val placementResult = context.workItemRepository().resolveChildPlacement(rootItem.id)) {
            is Result.Success -> placementResult.data.rootId to null
            is Result.Error ->
                null to
                    errorResponse(
                        "Root item '$rootIdStr' not found: ${placementResult.error.message}",
                        ErrorCodes.RESOURCE_NOT_FOUND
                    )
        }
    }

    private data class DocRefSource(
        val docSlug: String?,
        val docRootId: UUID?
    )

    /**
     * Resolves the optional `docRef` (materialize-from-document source), if provided. Purely
     * in-memory — no DB writes have happened yet for either mode, so any failure here returns
     * before create/attach even reaches the executor.
     */
    private suspend fun resolveDocRefSource(
        paramsObj: JsonObject,
        effectiveRootId: UUID,
        context: ToolExecutionContext
    ): Pair<DocRefSource?, JsonElement?> {
        val docRefElement = paramsObj["docRef"]
        if (docRefElement == null || docRefElement is JsonNull) return DocRefSource(null, null) to null

        val docRefObj = docRefElement as JsonObject
        val docSlug = (docRefObj["slug"] as JsonPrimitive).content
        val explicitRootIdStr =
            (docRefObj["rootId"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        if (explicitRootIdStr == null) {
            return DocRefSource(docSlug, effectiveRootId) to null
        }
        val (parsedRootId, rootIdError) = resolveIdString(explicitRootIdStr, context)
        if (rootIdError != null) return null to rootIdError
        if (parsedRootId != effectiveRootId) {
            return null to
                errorResponse(
                    "'docRef.rootId' ($parsedRootId) does not match the created/attached root's own " +
                        "rootId ($effectiveRootId)",
                    ErrorCodes.VALIDATION_ERROR
                )
        }
        return DocRefSource(docSlug, parsedRootId) to null
    }

    private data class ChildrenBuildResult(
        val refToItem: MutableMap<String, WorkItem>,
        val sortedChildRefs: List<String>
    )

    /** Topologically sorts `children` by their `parentRef` chain (Kahn's algorithm), root-first. */
    private fun topoSortChildRefs(childrenArray: JsonArray): Pair<List<String>, Map<String, String>> {
        // Build ref→parentRef map (default "root" if absent)
        val refToParentRef = mutableMapOf<String, String>()
        for (childElement in childrenArray) {
            val childObj = childElement as JsonObject
            val ref = (childObj["ref"] as JsonPrimitive).content
            val parentRef = (childObj["parentRef"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ROOT_REF
            refToParentRef[ref] = parentRef
        }

        val inDegree = mutableMapOf<String, Int>()
        val adjacency = mutableMapOf<String, MutableList<String>>()
        for (ref in refToParentRef.keys) {
            inDegree[ref] = 0
            adjacency[ref] = mutableListOf()
        }
        for ((ref, parentRef) in refToParentRef) {
            if (parentRef != ROOT_REF) {
                inDegree[ref] = (inDegree[ref] ?: 0) + 1
                adjacency.getOrPut(parentRef) { mutableListOf() }.add(ref)
            }
        }
        val topoQueue = ArrayDeque<String>()
        for ((ref, degree) in inDegree) {
            if (degree == 0) topoQueue.add(ref)
        }
        val sortedChildRefs = mutableListOf<String>()
        while (topoQueue.isNotEmpty()) {
            val current = topoQueue.removeFirst()
            sortedChildRefs.add(current)
            for (neighbor in adjacency[current] ?: emptyList()) {
                val newDegree = (inDegree[neighbor] ?: 1) - 1
                inDegree[neighbor] = newDegree
                if (newDegree == 0) topoQueue.add(neighbor)
            }
        }
        // Append any remaining (shouldn't happen after validateParams cycle check, but safety net)
        sortedChildRefs.addAll(refToParentRef.keys - sortedChildRefs.toSet())

        return sortedChildRefs to refToParentRef
    }

    /**
     * Builds every child [WorkItem] in topological (parent-before-child) order, seeding
     * `refToItem` with the root under [ROOT_REF]. Depth/rootId are inherited from each item's
     * already-built parent (root or sibling) — a placement later re-stamped by delta once the
     * authoritative anchor is re-read inside the write transaction (see [runWorkTreeTransaction]).
     */
    private fun buildChildren(
        paramsObj: JsonObject,
        rootItem: WorkItem
    ): Pair<ChildrenBuildResult?, JsonElement?> {
        val childrenArray = paramsObj["children"] as? JsonArray ?: JsonArray(emptyList())
        val refToItem = mutableMapOf<String, WorkItem>()
        refToItem[ROOT_REF] = rootItem

        val (sortedChildRefs, refToParentRef) = topoSortChildRefs(childrenArray)

        for (ref in sortedChildRefs) {
            val childObj =
                childrenArray
                    .map { it as JsonObject }
                    .first { (it["ref"] as JsonPrimitive).content == ref }
            val parentRef = refToParentRef[ref]!!
            val parentItem = refToItem[parentRef]!! // root or already-built sibling
            val depth = parentItem.depth + 1
            // rootId: inherit the parent's root (or the parent's own id, if the parent predates
            // the root_id backfill and has no rootId yet). Works uniformly in both modes — in
            // attach mode, refToItem[ROOT_REF] is the fetched *existing* root item, so its
            // direct children correctly inherit its rootId ?: its id; deeper descendants inherit
            // through their already-built parent, which by then carries the resolved rootId.
            val childRootId = parentItem.rootId ?: parentItem.id
            val childItem =
                buildWorkItem(
                    obj = childObj,
                    parentId = parentItem.id,
                    depth = depth,
                    rootId = childRootId,
                    contextLabel = "child '$ref'"
                ) ?: return null to errorResponse("Failed to build child item '$ref'", ErrorCodes.VALIDATION_ERROR)
            refToItem[ref] = childItem
        }

        return ChildrenBuildResult(refToItem, sortedChildRefs) to null
    }

    /** Parses `deps` and validates each entry's `from`/`to` refs resolve within [refToItem]. */
    private fun buildDependencySpecs(
        paramsObj: JsonObject,
        refToItem: Map<String, WorkItem>
    ): Pair<List<TreeDepSpec>?, JsonElement?> {
        val depsArray = paramsObj["deps"] as? JsonArray ?: JsonArray(emptyList())
        val depSpecs = mutableListOf<TreeDepSpec>()

        for ((index, depElement) in depsArray.withIndex()) {
            val depObj = depElement as JsonObject
            val fromRef = (depObj["from"] as? JsonPrimitive)!!.content
            val toRef = (depObj["to"] as? JsonPrimitive)!!.content

            if (!refToItem.containsKey(fromRef)) {
                return null to
                    errorResponse(
                        "deps[$index]: 'from' ref '$fromRef' is not defined. Valid refs: ${refToItem.keys.joinToString()}",
                        ErrorCodes.VALIDATION_ERROR
                    )
            }
            if (!refToItem.containsKey(toRef)) {
                return null to
                    errorResponse(
                        "deps[$index]: 'to' ref '$toRef' is not defined. Valid refs: ${refToItem.keys.joinToString()}",
                        ErrorCodes.VALIDATION_ERROR
                    )
            }

            val typeStr = (depObj["type"] as? JsonPrimitive)?.content ?: "BLOCKS"
            val depType =
                DependencyType.fromString(typeStr)
                    ?: return null to
                        errorResponse(
                            "deps[$index]: invalid type '$typeStr'. Valid: BLOCKS, IS_BLOCKED_BY, RELATES_TO",
                            ErrorCodes.VALIDATION_ERROR
                        )

            val unblockAt = (depObj["unblockAt"] as? JsonPrimitive)?.content

            depSpecs.add(TreeDepSpec(fromRef = fromRef, toRef = toRef, type = depType, unblockAt = unblockAt))
        }

        return depSpecs to null
    }

    /**
     * Resolves each item's schema once, keyed by ref — reused for strict role enforcement on
     * explicit/anchor notes AND for the createNotes=true schema-blank fill. Per-root config
     * selection ([ToolExecutionContext.resolveSchema]) keys off `item.rootId`; every item in this
     * tree shares ONE root, so resolve against [effectiveRootId] rather than each item's own
     * possibly-stale rootId field.
     */
    private suspend fun resolveItemSchemas(
        refToItem: Map<String, WorkItem>,
        effectiveRootId: UUID,
        context: ToolExecutionContext
    ): Map<String, WorkItemSchema?> = refToItem.mapValues { (_, item) -> context.resolveSchema(item.copy(rootId = effectiveRootId)) }

    private data class ExplicitNotesResult(
        val notesList: MutableList<Note>,
        val explicitByRefKey: MutableMap<Pair<String, String>, Int>
    )

    /**
     * Parses the explicit `notes` array, enforcing strict schema-role matching per (itemRef, key)
     * and last-wins dedup within the array itself. Off-schema keys and schema-free items are
     * unconstrained.
     */
    private fun buildExplicitNotes(
        explicitNotesArray: JsonArray,
        refToItem: Map<String, WorkItem>,
        itemSchemas: Map<String, WorkItemSchema?>,
        noteActorClaim: ActorClaim?,
        noteVerification: VerificationResult?
    ): Pair<ExplicitNotesResult?, JsonElement?> {
        val notesList = mutableListOf<Note>()
        val explicitByRefKey = mutableMapOf<Pair<String, String>, Int>()

        for ((index, noteElement) in explicitNotesArray.withIndex()) {
            val noteObj = noteElement as JsonObject
            val itemRef = (noteObj["itemRef"] as JsonPrimitive).content
            val key = (noteObj["key"] as JsonPrimitive).content
            val role = (noteObj["role"] as JsonPrimitive).content
            // Only string primitives are accepted as body; JsonNull and omitted both default to "".
            // (validateParams already rejects non-string non-null primitives.)
            val body = (noteObj["body"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""

            val targetItem =
                refToItem[itemRef]
                    ?: return null to
                        errorResponse(
                            "notes[$index]: 'itemRef' '$itemRef' is not defined. Valid refs: ${refToItem.keys.joinToString()}",
                            ErrorCodes.VALIDATION_ERROR
                        )

            // Strict role enforcement: when the resolved schema for this item declares the same
            // key, the explicit note's role must match the schema role. The DB has a
            // UNIQUE(itemId, key) constraint, so only one note per key can exist; allowing a role
            // mismatch would silently leave the gate-required role unfilled.
            val schema = itemSchemas[itemRef]
            if (schema != null) {
                val schemaEntry = schema.notes.firstOrNull { it.key == key }
                if (schemaEntry != null) {
                    val expectedRole = schemaEntry.role.toJsonString()
                    if (role != expectedRole) {
                        return null to
                            errorResponse(
                                "notes[$index]: key '$key' is declared in the schema for itemRef " +
                                    "'$itemRef' with role '$expectedRole', but the explicit note has " +
                                    "role '$role'. Schema-declared keys must use the schema role; " +
                                    "off-schema keys may use any valid role.",
                                ErrorCodes.VALIDATION_ERROR
                            )
                    }
                }
            }

            val note =
                Note(
                    itemId = targetItem.id,
                    key = key,
                    role = role,
                    body = body,
                    actorClaim = noteActorClaim,
                    verification = noteVerification
                )
            val refKey = itemRef to key
            val existingIndex = explicitByRefKey[refKey]
            if (existingIndex != null) {
                // Last wins: replace the earlier note in notesList
                notesList[existingIndex] = note
            } else {
                explicitByRefKey[refKey] = notesList.size
                notesList.add(note)
            }
        }

        return ExplicitNotesResult(notesList, explicitByRefKey) to null
    }

    /**
     * Resolves noteAnchors (materialize-from-document): fetches the referenced PENDING document,
     * slices each anchor, and appends into [notesList] — skipping any (itemRef, key) already
     * covered by an explicit note (explicit wins). Everything here is read-only against the
     * document (the atomic adopt happens later, inside the executor's transaction) — an anchor
     * miss, missing document, or already-adopted document returns an error here, before any DB
     * write, so zero items are created.
     */
    private suspend fun resolveAnchorNotes(
        docSlug: String?,
        docRootId: UUID?,
        rootObj: JsonObject,
        childrenArray: JsonArray,
        refToItem: Map<String, WorkItem>,
        itemSchemas: Map<String, WorkItemSchema?>,
        explicitByRefKey: Map<Pair<String, String>, Int>,
        notesList: MutableList<Note>,
        context: ToolExecutionContext,
        noteActorClaim: ActorClaim?,
        noteVerification: VerificationResult?
    ): Pair<MutableMap<Pair<String, String>, Int>?, JsonElement?> {
        val anchorByRefKey = mutableMapOf<Pair<String, String>, Int>()
        if (docSlug == null) return anchorByRefKey to null

        val docResult = context.repositoryProvider.planDocumentRepository().get(docRootId!!, docSlug)
        val doc =
            when (docResult) {
                is Result.Success -> docResult.data
                is Result.Error ->
                    return null to
                        errorResponse(
                            "Failed to read plan document '$docSlug' (root $docRootId): ${docResult.error.message}",
                            ErrorCodes.INTERNAL_ERROR
                        )
            } ?: return null to
                errorResponse(
                    "Plan document not found: rootId=$docRootId, slug='$docSlug'",
                    ErrorCodes.RESOURCE_NOT_FOUND
                )
        if (doc.status == PlanDocumentStatus.ADOPTED) {
            return null to
                errorResponse(
                    "Plan document '$docSlug' (root $docRootId) is already adopted (by item ${doc.adoptedByItemId})",
                    ErrorCodes.VALIDATION_ERROR
                )
        }

        for (anchor in collectNoteAnchors(rootObj, childrenArray)) {
            val refKey = anchor.itemRef to anchor.noteKey
            if (explicitByRefKey.containsKey(refKey)) continue // explicit notes win

            val targetItem =
                refToItem[anchor.itemRef]
                    ?: return null to
                        errorResponse(
                            "noteAnchors: itemRef '${anchor.itemRef}' is not defined. Valid refs: " +
                                refToItem.keys.joinToString(),
                            ErrorCodes.VALIDATION_ERROR
                        )

            val schema = itemSchemas[anchor.itemRef]
            if (schema != null) {
                val schemaEntry = schema.notes.firstOrNull { it.key == anchor.noteKey }
                if (schemaEntry != null) {
                    val expectedRole = schemaEntry.role.toJsonString()
                    if (anchor.role != expectedRole) {
                        return null to
                            errorResponse(
                                "noteAnchors: key '${anchor.noteKey}' is declared in the schema for itemRef " +
                                    "'${anchor.itemRef}' with role '$expectedRole', but the anchor has role " +
                                    "'${anchor.role}'. Schema-declared keys must use the schema role.",
                                ErrorCodes.VALIDATION_ERROR
                            )
                    }
                }
            }

            val sliced =
                MarkdownSectionSplitter.slice(doc.body, anchor.anchor)
                    ?: return null to
                        errorResponse(
                            "noteAnchors: anchor '${anchor.anchor}' not found in plan document '$docSlug' " +
                                "(itemRef '${anchor.itemRef}', noteKey '${anchor.noteKey}')",
                            ErrorCodes.VALIDATION_ERROR
                        )

            val note =
                Note(
                    itemId = targetItem.id,
                    key = anchor.noteKey,
                    role = anchor.role,
                    body = sliced,
                    actorClaim = noteActorClaim,
                    verification = noteVerification
                )
            val existingIndex = anchorByRefKey[refKey]
            if (existingIndex != null) {
                notesList[existingIndex] = note
            } else {
                anchorByRefKey[refKey] = notesList.size
                notesList.add(note)
            }
        }

        return anchorByRefKey to null
    }

    /**
     * If `createNotes=true`, fills schema-required notes that aren't already explicit- or
     * anchor-derived (with empty bodies) — noteAnchors win over these blanks.
     */
    private fun fillCreateNotesBlanks(
        createNotes: Boolean,
        refToItem: Map<String, WorkItem>,
        itemSchemas: Map<String, WorkItemSchema?>,
        explicitByRefKey: Map<Pair<String, String>, Int>,
        anchorByRefKey: Map<Pair<String, String>, Int>,
        notesList: MutableList<Note>,
        noteActorClaim: ActorClaim?,
        noteVerification: VerificationResult?
    ) {
        if (!createNotes) return
        for ((ref, item) in refToItem) {
            val resolvedSchema = itemSchemas[ref] ?: continue
            for (entry in resolvedSchema.notes) {
                val refKey = ref to entry.key
                if (explicitByRefKey.containsKey(refKey) || anchorByRefKey.containsKey(refKey)) continue
                notesList.add(
                    Note(
                        itemId = item.id,
                        key = entry.key,
                        role = entry.role.toJsonString(),
                        body = "",
                        actorClaim = noteActorClaim,
                        verification = noteVerification
                    )
                )
            }
        }
    }

    /** Builds the tree's notes: merges explicit notes, then noteAnchors, then createNotes blanks. */
    private suspend fun buildNotes(
        paramsObj: JsonObject,
        params: JsonElement,
        rootObj: JsonObject,
        childrenArray: JsonArray,
        refToItem: Map<String, WorkItem>,
        effectiveRootId: UUID,
        docSlug: String?,
        docRootId: UUID?,
        context: ToolExecutionContext,
        noteActorClaim: ActorClaim?,
        noteVerification: VerificationResult?
    ): Pair<List<Note>?, JsonElement?> {
        val createNotes = optionalBoolean(params, "createNotes", defaultValue = false)
        val explicitNotesArray = paramsObj["notes"] as? JsonArray ?: JsonArray(emptyList())
        val itemSchemas = resolveItemSchemas(refToItem, effectiveRootId, context)

        val (explicitResult, explicitError) =
            buildExplicitNotes(explicitNotesArray, refToItem, itemSchemas, noteActorClaim, noteVerification)
        if (explicitError != null) return null to explicitError
        val (notesList, explicitByRefKey) = explicitResult!!

        val (anchorByRefKey, anchorError) =
            resolveAnchorNotes(
                docSlug,
                docRootId,
                rootObj,
                childrenArray,
                refToItem,
                itemSchemas,
                explicitByRefKey,
                notesList,
                context,
                noteActorClaim,
                noteVerification
            )
        if (anchorError != null) return null to anchorError

        fillCreateNotesBlanks(
            createNotes,
            refToItem,
            itemSchemas,
            explicitByRefKey,
            anchorByRefKey!!,
            notesList,
            noteActorClaim,
            noteVerification
        )

        return notesList to null
    }

    private data class TransactionOutcome(
        val treeResult: WorkTreeResult,
        val finalRootPlacement: ChildPlacement?
    )

    /**
     * Re-resolves the anchor's placement (parent in create mode, root itself in attach mode)
     * INSIDE the write transaction and executes the tree write. This is the #339 invariant: the
     * authoritative placement read, the depth/rootId restamp by delta, and
     * `workTreeExecutor().execute` must all happen inside this ONE `inTransaction` lambda — every
     * item built earlier carries a depth/rootId computed from an EARLIER, possibly-stale read of
     * the anchor, so a concurrent reparent/delete of the anchor between that earlier read and this
     * write must not leave the new tree stamped with stale placement (AR-19). Root-level create
     * (no parentId, not attach mode) has no anchor to re-read — the new tree's own root is
     * unaffected by any concurrent write.
     */
    private suspend fun runWorkTreeTransaction(
        context: ToolExecutionContext,
        isAttachMode: Boolean,
        rootItem: WorkItem,
        rootIdStr: String?,
        parentId: UUID?,
        input: WorkTreeInput,
        orderedItems: List<WorkItem>
    ): Pair<TransactionOutcome?, JsonElement?> {
        val anchorId = if (isAttachMode) rootItem.id else parentId
        val workItemRepo = context.workItemRepository()
        var treeResultVar: WorkTreeResult? = null
        var anchorNotFoundMessage: String? = null
        // Attach mode only: the root itself is not re-inserted, so it is never in orderedItems /
        // treeResult.items — capture the SAME authoritative placement resolved below so the
        // response can report the root's current depth/rootId instead of the possibly-stale
        // fetched value (review obs 4).
        var finalRootPlacementVar: ChildPlacement? = null

        try {
            workItemRepo.inTransaction {
                var finalInput = input
                if (anchorId != null) {
                    when (val placementResult = workItemRepo.resolveChildPlacement(anchorId)) {
                        is Result.Success -> {
                            val placement = placementResult.data
                            if (isAttachMode) finalRootPlacementVar = placement
                            // The base depth each item's chain was originally built from: for a
                            // new root (create mode) that is the root's own depth; for attach
                            // mode (root not re-inserted) that is the depth its DIRECT children
                            // were built with, i.e. the root's stale depth + 1 — exactly what
                            // resolveChildPlacement(rootItem.id) recomputes fresh as placement.depth.
                            val staleBaseDepth = if (isAttachMode) rootItem.depth + 1 else rootItem.depth
                            val delta = placement.depth - staleBaseDepth
                            val restampedItems =
                                orderedItems.map { item ->
                                    item.copy(depth = item.depth + delta, rootId = placement.rootId)
                                }
                            finalInput = input.copy(items = restampedItems)
                        }
                        is Result.Error -> {
                            anchorNotFoundMessage =
                                if (isAttachMode) {
                                    "Root item '$rootIdStr' not found: ${placementResult.error.message}"
                                } else {
                                    "Parent item '$parentId' not found: ${placementResult.error.message}"
                                }
                            return@inTransaction
                        }
                    }
                }
                treeResultVar = context.workTreeExecutor().execute(finalInput)
            }
        } catch (e: Exception) {
            return null to
                errorResponse(
                    "Work tree creation failed: ${e.message}",
                    ErrorCodes.INTERNAL_ERROR
                )
        }

        if (anchorNotFoundMessage != null) {
            return null to errorResponse(anchorNotFoundMessage!!, ErrorCodes.RESOURCE_NOT_FOUND)
        }

        return TransactionOutcome(treeResultVar!!, finalRootPlacementVar) to null
    }

    /** Builds the `{ root, children, dependencies, notes }` success response payload. */
    private suspend fun buildTreeResponse(
        context: ToolExecutionContext,
        treeResult: WorkTreeResult,
        rootResultItem: WorkItem,
        isExistingRoot: Boolean,
        depSpecs: List<TreeDepSpec>
    ): JsonElement {
        // Build ref-to-result-item map for note lookup
        val idToRef = treeResult.refToId.entries.associate { (ref, id) -> id to ref }

        // The tree is ALREADY PERSISTED at this point — per D7, a per-root config read failure
        // resolving this response-only decoration must never be reported as a failure of the
        // already-committed create. schemaMatch/expectedNotes are simply omitted and a WARN
        // is logged.
        val rootSchemaFields =
            omitOnConfigUnavailable(logger, "schema", rootResultItem.id) {
                buildSchemaResponseFields(context.resolveSchema(rootResultItem))
            }
        val rootJson =
            buildJsonObject {
                put("id", JsonPrimitive(rootResultItem.id.toString()))
                put("title", JsonPrimitive(rootResultItem.title))
                put("role", JsonPrimitive(rootResultItem.role.toJsonString()))
                put("depth", JsonPrimitive(rootResultItem.depth))
                rootResultItem.tags?.let { put("tags", JsonPrimitive(it)) }
                if (rootSchemaFields != null) {
                    put("schemaMatch", JsonPrimitive(rootSchemaFields.schemaMatch))
                    put("expectedNotes", rootSchemaFields.expectedNotes)
                }
            }

        // In attach mode treeResult.items contains only children.
        // In create mode children start at index 1 (after the root).
        val childItems = if (isExistingRoot) treeResult.items else treeResult.items.drop(1)
        val childrenJson =
            JsonArray(
                childItems.map { item ->
                    val ref = idToRef[item.id] ?: "unknown"
                    val childSchemaFields =
                        omitOnConfigUnavailable(logger, "schema", item.id) {
                            buildSchemaResponseFields(context.resolveSchema(item))
                        }
                    buildJsonObject {
                        put("ref", JsonPrimitive(ref))
                        put("id", JsonPrimitive(item.id.toString()))
                        put("title", JsonPrimitive(item.title))
                        put("role", JsonPrimitive(item.role.toJsonString()))
                        put("depth", JsonPrimitive(item.depth))
                        item.tags?.let { put("tags", JsonPrimitive(it)) }
                        if (childSchemaFields != null) {
                            put("schemaMatch", JsonPrimitive(childSchemaFields.schemaMatch))
                            put("expectedNotes", childSchemaFields.expectedNotes)
                        }
                    }
                }
            )

        val depsJson =
            JsonArray(
                treeResult.deps.mapIndexed { index, dep ->
                    val spec = depSpecs[index]
                    buildJsonObject {
                        put("id", JsonPrimitive(dep.id.toString()))
                        put("fromRef", JsonPrimitive(spec.fromRef))
                        put("toRef", JsonPrimitive(spec.toRef))
                        put("type", JsonPrimitive(dep.type.name))
                        dep.unblockAt?.let { put("unblockAt", JsonPrimitive(it)) }
                    }
                }
            )

        val notesJson =
            JsonArray(
                treeResult.notes.map { note ->
                    val ref = idToRef[note.itemId] ?: "unknown"
                    buildJsonObject {
                        put("itemRef", JsonPrimitive(ref))
                        put("key", JsonPrimitive(note.key))
                        put("role", JsonPrimitive(note.role))
                        put("id", JsonPrimitive(note.id.toString()))
                    }
                }
            )

        val data =
            buildJsonObject {
                put("root", rootJson)
                put("children", childrenJson)
                put("dependencies", depsJson)
                put("notes", notesJson)
            }

        return successResponse(data)
    }

    override fun userSummary(
        params: JsonElement,
        result: JsonElement,
        isError: Boolean
    ): String {
        if (isError) {
            val errorDetail =
                (result as? JsonObject)
                    ?.get("error")
                    ?.let { it as? JsonObject }
                    ?.get("message")
                    ?.let { (it as? JsonPrimitive)?.content }
            return if (errorDetail != null) "create_work_tree failed: $errorDetail" else "create_work_tree failed"
        }
        val data = (result as? JsonObject)?.get("data") as? JsonObject ?: return "create_work_tree completed"
        val rootTitle =
            (data["root"] as? JsonObject)
                ?.get("title")
                ?.let { (it as? JsonPrimitive)?.content } ?: "?"
        val childCount = (data["children"] as? JsonArray)?.size ?: 0
        val depCount = (data["dependencies"] as? JsonArray)?.size ?: 0
        return if (childCount > 0) {
            "Created work tree: '$rootTitle' + $childCount child(ren), $depCount dep(s)"
        } else {
            "Created work tree: '$rootTitle'"
        }
    }

    // ──────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────

    /** One `noteAnchors` entry resolved against its owning item's ref. */
    private data class PendingAnchor(
        val itemRef: String,
        val noteKey: String,
        val role: String,
        val anchor: String
    )

    /**
     * Collects every `noteAnchors` entry declared on [rootObj] and each spec in [childrenArray],
     * tagging each with its owning item's ref. `validateParams` has already verified shape (object,
     * required non-blank fields, valid role) — this just extracts.
     */
    private fun collectNoteAnchors(
        rootObj: JsonObject,
        childrenArray: JsonArray
    ): List<PendingAnchor> {
        fun fromItem(
            itemObj: JsonObject,
            itemRef: String
        ): List<PendingAnchor> {
            val anchors = itemObj["noteAnchors"] as? JsonArray ?: return emptyList()
            return anchors.map { element ->
                val obj = element as JsonObject
                PendingAnchor(
                    itemRef = itemRef,
                    noteKey = (obj["noteKey"] as JsonPrimitive).content,
                    role = (obj["role"] as JsonPrimitive).content,
                    anchor = (obj["anchor"] as JsonPrimitive).content
                )
            }
        }

        val result = mutableListOf<PendingAnchor>()
        result.addAll(fromItem(rootObj, ROOT_REF))
        for (childElement in childrenArray) {
            val childObj = childElement as JsonObject
            val ref = (childObj["ref"] as JsonPrimitive).content
            result.addAll(fromItem(childObj, ref))
        }
        return result
    }

    /**
     * Rejects a nested `children` key inside an item spec. create_work_tree expresses nesting via
     * the top-level flat `children` array plus each child's `parentRef`; a `children` array embedded
     * inside a root or child spec was previously dropped silently, losing those items (bug 1248af0f).
     */
    private fun rejectNestedChildren(
        itemObj: JsonObject,
        contextLabel: String
    ) {
        val nested = itemObj["children"]
        if (nested != null && nested !is JsonNull) {
            throw ToolValidationException(
                "$contextLabel must not contain a nested 'children' array — it would be silently dropped. " +
                    "Build nested trees by listing every item in the top-level 'children' array and setting " +
                    "each child's 'parentRef' to its parent's 'ref'."
            )
        }
    }

    /**
     * Builds a [WorkItem] from a JSON object spec.
     * Returns null only if there is an unexpected internal error; all validation errors
     * are expected to have been caught in [validateParams].
     */
    private fun buildWorkItem(
        obj: JsonObject,
        parentId: UUID?,
        depth: Int,
        rootId: UUID,
        contextLabel: String,
        id: UUID = UUID.randomUUID()
    ): WorkItem? {
        val title =
            (obj["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: return null

        val priorityStr = (obj["priority"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val priority =
            if (priorityStr != null) {
                Priority.fromString(priorityStr) ?: Priority.MEDIUM
            } else {
                Priority.MEDIUM
            }

        val tags = (obj["tags"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val summary = (obj["summary"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
        val description = (obj["description"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val requiresVerification = (obj["requiresVerification"] as? JsonPrimitive)?.booleanOrNull ?: false
        val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val traitsStr = (obj["traits"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val properties = PropertiesHelper.mergeTraitsFromString(null, traitsStr)

        return try {
            WorkItem(
                id = id,
                parentId = parentId,
                rootId = rootId,
                title = title,
                description = description,
                summary = summary,
                role = Role.QUEUE,
                priority = priority,
                requiresVerification = requiresVerification,
                depth = depth,
                tags = tags,
                type = type,
                properties = properties
            )
        } catch (e: Exception) {
            logger.warn("Failed to build WorkItem for $contextLabel: ${e.message}")
            null
        }
    }
}
