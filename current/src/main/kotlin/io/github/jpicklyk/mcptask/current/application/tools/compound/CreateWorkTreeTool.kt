package io.github.jpicklyk.mcptask.current.application.tools.compound

import io.github.jpicklyk.mcptask.current.application.config.withConfigSession
import io.github.jpicklyk.mcptask.current.application.port.ChildPlacement
import io.github.jpicklyk.mcptask.current.application.service.ItemCommandErrors
import io.github.jpicklyk.mcptask.current.application.service.ItemCreateCommand
import io.github.jpicklyk.mcptask.current.application.service.MarkdownSectionSplitter
import io.github.jpicklyk.mcptask.current.application.service.NoteCommandService
import io.github.jpicklyk.mcptask.current.application.service.NoteLengthWarning
import io.github.jpicklyk.mcptask.current.application.service.NoteUpsertCommand
import io.github.jpicklyk.mcptask.current.application.service.RuleService
import io.github.jpicklyk.mcptask.current.application.service.TreeDepSpec
import io.github.jpicklyk.mcptask.current.application.service.buildSchemaResponseFields
import io.github.jpicklyk.mcptask.current.application.service.withEventActor
import io.github.jpicklyk.mcptask.current.application.support.LegacyFaults
import io.github.jpicklyk.mcptask.current.application.support.legacyRead
import io.github.jpicklyk.mcptask.current.application.support.rethrowIfCancellation
import io.github.jpicklyk.mcptask.current.application.tools.*
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.*
import io.github.jpicklyk.mcptask.current.domain.validation.ValidationException
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
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
 *
 * The write is ONE unit over the write services: each item through `ItemCommandService.createInUnit` (root
 * first, so every placement is the in-unit read of the parent just inserted, and a TERMINAL parent under auto
 * lifecycle rejects the tree), then the dependencies through `DependencyCommandService.createInUnit`, the notes
 * through `NoteCommandService.upsert` and the document adoption through `PlanDocumentService.adoptInUnit`. Any
 * failure rolls the whole tree back: zero items, notes and dependencies, and the document stays PENDING.
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
                                        "slug (required, the stashed plan document's slug; a slug starting with rule/ is rejected) }. " +
                                        "Required whenever any " +
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
                                    "Client-generated UUID; the call runs once per 24h (keyed by actor+requestId, as element 0); " +
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
            // D1 guard (A3, 840e700a): rule documents are stashed/served through query_rules and
            // must never be adopted into a work item -- adoption is a one-way transition
            // (PlanDocumentService.kt), and adopting a rule/<key> slug would freeze it forever.
            if (slug.startsWith(RuleService.RULE_SLUG_PREFIX)) {
                throw ToolValidationException(
                    "'docRef.slug' cannot start with '${RuleService.RULE_SLUG_PREFIX}' -- rule documents " +
                        "are read-only via query_rules and cannot be adopted into a work item"
                )
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
     * must be one of queue/work/review in any letter case, and `body` (if present) must be a string.
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
            if (role.lowercase() !in validRoles) {
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
            if (role.isNullOrBlank() || role.lowercase() !in validRoles) {
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
    ): JsonElement =
        withConfigSession {
            executeWithSession(params, context)
        }

    private suspend fun executeWithSession(
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

        // A keyed call (requestId plus a trusted principal) is one atomic element 0: the whole tree commits
        // together with its record, or rolls back unrecorded. Unkeyed calls never touch the idempotency service.
        return withEventActor(noteActorClaim) {
            if (requestId != null && trustedActorId != null) {
                KeyedCall(
                    context.idempotency,
                    trustedActorId,
                    requestId,
                    KeyedCall.op(name),
                    KeyedCall.withoutKeyFields(params)
                ).whole(KeyedCall.withoutKeyFields(params)) {
                    executeCreateWorkTree(paramsObj, params, context, noteActorClaim, noteVerification)
                }
            } else {
                executeCreateWorkTree(paramsObj, params, context, noteActorClaim, noteVerification)
            }
        }
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

        val childrenArray = paramsObj["children"] as? JsonArray ?: JsonArray(emptyList())

        val (childrenResult, childrenError) = buildChildren(childrenArray, rootItem)
        if (childrenError != null) return childrenError
        val (refToItem, sortedChildRefs, refToCommand) = childrenResult!!

        val (depSpecsResult, depsError) = buildDependencySpecs(paramsObj, refToItem)
        if (depsError != null) return depsError
        val (depSpecs, normalizeError) = normalizeDependencySpecs(depSpecsResult!!, refToItem, context)
        if (normalizeError != null) return normalizeError
        depSpecs!!

        val treeBuildContext =
            TreeBuildContext(
                rootObj = rootObj,
                childrenArray = childrenArray,
                refToItem = refToItem,
                effectiveRootId = effectiveRootId,
                docSlug = docSlug,
                docRootId = docRootId,
                context = context,
                noteActorClaim = noteActorClaim,
                noteVerification = noteVerification
            )
        val (notesListResult, notesError) = buildNotes(paramsObj, params, treeBuildContext)
        if (notesError != null) return notesError
        val builtNotes = notesListResult!!
        val notesList = builtNotes.notes

        // Build the ordered create list. In attach mode the existing root is NOT inserted (it already
        // exists in the DB). In create mode the root leads the list (root first, children in
        // topological order), so each item's parent is inserted before it.
        val orderedCommands = mutableListOf<ItemCreateCommand>()
        if (!isExistingRoot) {
            orderedCommands.add(rootResolution.rootCommand!!)
        }
        for (ref in sortedChildRefs) {
            orderedCommands.add(refToCommand.getValue(ref))
        }

        val input =
            TreeInput(
                commands = orderedCommands,
                refToItem = refToItem,
                deps = depSpecs,
                notes = notesList,
                adoption = docSlug?.let { slug -> DocAdoption(rootItemId = docRootId!!, slug = slug, adoptingItemId = rootItem.id) }
            )

        val (transactionOutcome, transactionError) =
            runWorkTreeTransaction(context, isAttachMode, rootItem, rootIdStr, parentId, input)
        if (transactionError != null) return transactionError
        val (treeResult, finalRootPlacement) = transactionOutcome!!

        // In attach mode, report the root's depth/rootId from the SAME authoritative placement read
        // inside the unit (placement.depth - 1 / placement.rootId) rather than the value fetched back
        // earlier: the root row itself is not rewritten by this call, so this is response-shaping only.
        val finalRootItem =
            finalRootPlacement?.let { placement ->
                rootItem.copy(depth = placement.depth - 1, rootId = placement.rootId)
            } ?: rootItem
        // In attach mode the root was not inserted — use finalRootItem for the response. In
        // create mode the root is treeResult.items.first() (the persisted row).
        val rootResultItem = if (isExistingRoot) finalRootItem else treeResult.items.first()

        return buildTreeResponse(context, treeResult, rootResultItem, isExistingRoot, depSpecs, builtNotes.warnings)
    }

    /**
     * The resolved root: [rootItem] is the fetched existing item (attach mode) or a PREVIEW of the new root
     * (create mode: never persisted; it carries the tree's provisional rootId for schema and docRef resolution).
     * [rootCommand] is the create command of a new root, null in attach mode.
     */
    private data class RootResolution(
        val rootItem: WorkItem,
        val isAttachMode: Boolean,
        val isExistingRoot: Boolean,
        val rootIdStr: String?,
        val parentId: UUID?,
        val rootCommand: ItemCreateCommand? = null
    )

    /**
     * Detects attach-vs-create mode from `root.id`, resolves `parentId` (create mode only), and
     * resolves the root item: fetches the existing item in attach mode, or builds the create command
     * of a new one (and its preview) in create mode. Placement is NOT decided here: the item command
     * service derives it from the parent row read inside the write unit.
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
            val fetched =
                legacyRead({ return null to errorResponse("Failed to read root item '$rootIdStr': $it", ErrorCodes.DATABASE_ERROR) }) {
                    context.workItemRepository().getById(resolvedRootId!!)
                } ?: return null to
                    errorResponse(
                        "Root item '$rootIdStr' not found: WorkItem not found with id: $resolvedRootId",
                        ErrorCodes.RESOURCE_NOT_FOUND
                    )
            return RootResolution(
                rootItem = fetched,
                isAttachMode = true,
                isExistingRoot = true,
                rootIdStr = rootIdStr,
                parentId = parentId
            ) to null
        }

        // Create mode: the parent must exist (checked again, authoritatively, inside the write unit).
        // The provisional rootId (the parent's root, or the parent's own id when it predates the
        // root_id backfill, or the new root's own id at top level) only selects the schema and the
        // docRef root default; the persisted placement comes from the in-unit read.
        val rootItemId = UUID.randomUUID()
        val provisionalRootId: UUID
        if (parentId != null) {
            val parent =
                legacyRead({ return null to errorResponse("Failed to read parent item '$parentId': $it", ErrorCodes.DATABASE_ERROR) }) {
                    context.workItemRepository().getById(parentId)
                } ?: return null to
                    errorResponse(
                        "Parent item '$parentId' not found: WorkItem not found with id: $parentId",
                        ErrorCodes.RESOURCE_NOT_FOUND
                    )
            provisionalRootId = parent.rootId ?: parent.id
        } else {
            provisionalRootId = rootItemId
        }
        val rootCommand =
            buildItemCommand(obj = rootObj, parentId = parentId, id = rootItemId)
                ?: return null to errorResponse("Failed to build root item", ErrorCodes.VALIDATION_ERROR)
        val rootItem =
            previewOf(rootCommand, provisionalRootId, "root")
                ?: return null to errorResponse("Failed to build root item", ErrorCodes.VALIDATION_ERROR)

        return RootResolution(
            rootItem = rootItem,
            isAttachMode = false,
            isExistingRoot = false,
            rootIdStr = rootIdStr,
            parentId = parentId,
            rootCommand = rootCommand
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
     * method computes for a hypothetical child). The AUTHORITATIVE placement of every row written
     * is read inside the write unit (see [runWorkTreeTransaction]) — a race in the narrow window
     * between this read and that one could only affect which schema/document was selected, never
     * the depth/rootId values persisted (AR-19).
     */
    private suspend fun resolveEffectiveRootId(
        isAttachMode: Boolean,
        rootItem: WorkItem,
        rootIdStr: String?,
        context: ToolExecutionContext
    ): Pair<UUID?, JsonElement?> {
        if (!isAttachMode) return (rootItem.rootId ?: rootItem.id) to null
        val placement =
            legacyRead({ return null to errorResponse("Failed to read root item '$rootIdStr': $it", ErrorCodes.DATABASE_ERROR) }) {
                context.workItemRepository().resolveChildPlacement(rootItem.id)
            } ?: return null to
                errorResponse(
                    "Root item '$rootIdStr' not found: Parent item not found: ${rootItem.id}",
                    ErrorCodes.RESOURCE_NOT_FOUND
                )
        return placement.rootId to null
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

    /**
     * [refToItem] maps every ref (the root under [ROOT_REF]) to its item: the existing root in attach mode, otherwise
     * a never-persisted preview (ids, type, tags and traits for schema resolution and note targeting).
     * [refToCommand] holds each child's create command.
     */
    private data class ChildrenBuildResult(
        val refToItem: MutableMap<String, WorkItem>,
        val sortedChildRefs: List<String>,
        val refToCommand: Map<String, ItemCreateCommand>
    )

    /**
     * Bundles the per-tree-build values shared across [buildNotes] and [resolveAnchorNotes], built
     * once in [executeCreateWorkTree].
     */
    private data class TreeBuildContext(
        val rootObj: JsonObject,
        val childrenArray: JsonArray,
        val refToItem: Map<String, WorkItem>,
        val effectiveRootId: UUID,
        val docSlug: String?,
        val docRootId: UUID?,
        val context: ToolExecutionContext,
        val noteActorClaim: ActorClaim?,
        val noteVerification: VerificationResult?
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
     * Builds every child's create command (and its preview) in topological (parent-before-child) order, seeding
     * `refToItem` with the root under [ROOT_REF]. Each child's parent is the root or an already-built sibling; no
     * placement is computed here (the item command service derives it inside the write unit).
     */
    private fun buildChildren(
        childrenArray: JsonArray,
        rootItem: WorkItem
    ): Pair<ChildrenBuildResult?, JsonElement?> {
        val refToItem = mutableMapOf<String, WorkItem>()
        refToItem[ROOT_REF] = rootItem
        val refToCommand = mutableMapOf<String, ItemCreateCommand>()

        val (sortedChildRefs, refToParentRef) = topoSortChildRefs(childrenArray)

        for (ref in sortedChildRefs) {
            val childObj =
                childrenArray
                    .map { it as JsonObject }
                    .first { (it["ref"] as JsonPrimitive).content == ref }
            val parentRef = refToParentRef[ref]!!
            val parentItem = refToItem[parentRef]!! // root or already-built sibling
            val command =
                buildItemCommand(obj = childObj, parentId = parentItem.id)
                    ?: return null to errorResponse("Failed to build child item '$ref'", ErrorCodes.VALIDATION_ERROR)
            val childItem =
                previewOf(command, null, "child '$ref'")
                    ?: return null to errorResponse("Failed to build child item '$ref'", ErrorCodes.VALIDATION_ERROR)
            refToCommand[ref] = command
            refToItem[ref] = childItem
        }

        return ChildrenBuildResult(refToItem, sortedChildRefs, refToCommand) to null
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
     * Runs the dependency write policy over [specs] before anything is written: IS_BLOCKED_BY specs come back
     * as BLOCKS with their refs swapped, a restated pair is rejected as a duplicate, and a cycle is rejected
     * ("Circular dependency detected involving ref '<ref>'"). The returned specs keep the request order, so
     * they line up with the stored `deps` rows in the response.
     */
    private fun normalizeDependencySpecs(
        specs: List<TreeDepSpec>,
        refToItem: Map<String, WorkItem>,
        context: ToolExecutionContext
    ): Pair<List<TreeDepSpec>?, JsonElement?> {
        if (specs.isEmpty()) return specs to null
        val deps =
            try {
                specs.map { spec ->
                    Dependency(
                        fromItemId = refToItem.getValue(spec.fromRef).id,
                        toItemId = refToItem.getValue(spec.toRef).id,
                        type = spec.type,
                        unblockAt = spec.unblockAt
                    )
                }
            } catch (e: ValidationException) {
                // An invalid spec (self-edge, RELATES_TO with unblockAt, bad threshold) is rejected up front with the
                // domain message, before any write, so valid specs never reach the V22 CHECK un-normalized.
                return null to errorResponse(e.message ?: "Invalid dependency", ErrorCodes.VALIDATION_ERROR)
            }
        val idToRef = refToItem.entries.associate { (ref, item) -> item.id to ref }

        fun withRefs(message: String): String = idToRef.entries.fold(message) { text, (id, ref) -> text.replace(id.toString(), "'$ref'") }

        return when (val checked = context.dependencyCommandService.validateTreeEdges(deps)) {
            is Outcome.Ok ->
                checked.value.map { dep ->
                    TreeDepSpec(
                        fromRef = idToRef.getValue(dep.fromItemId),
                        toRef = idToRef.getValue(dep.toItemId),
                        type = dep.type,
                        unblockAt = dep.unblockAt
                    )
                } to null
            is Outcome.Err -> {
                val error = checked.error
                val message =
                    when (val detail = error.detail) {
                        is ErrorDetail.CycleDetected ->
                            "Circular dependency detected involving ref '${idToRef[detail.path.first()] ?: "unknown"}'"
                        else -> withRefs(error.message)
                    }
                null to errorResponse(message, ErrorCodes.VALIDATION_ERROR)
            }
        }
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
     * Maps a [NoteCommandService.prepare] rejection to this tool's `VALIDATION_ERROR` response, which fails
     * the WHOLE call (zero items created). A schema-role rejection keeps each call site's own legacy wording
     * ([schemaRoleMessage], given the schema's declared role); every other rejection is [prefix] plus the
     * service's message.
     */
    private fun noteRejection(
        error: DomainError,
        prefix: String,
        schema: WorkItemSchema?,
        key: String,
        schemaRoleMessage: (expectedRole: String) -> String
    ): JsonElement {
        val expectedRole =
            schema
                ?.notes
                ?.firstOrNull { it.key == key }
                ?.role
                ?.toJsonString()
        val message =
            if (error.code == ErrorCode.SCHEMA_VIOLATION && expectedRole != null) {
                schemaRoleMessage(expectedRole)
            } else {
                "$prefix${error.message}"
            }
        return errorResponse(message, ErrorCodes.VALIDATION_ERROR)
    }

    /** What the note-building steps produce: the notes to write, and any `maxLength` warnings (warn mode). */
    private data class BuiltNotes(
        val notes: List<Note>,
        val warnings: Map<Pair<UUID, String>, NoteLengthWarning>
    )

    /**
     * Parses the explicit `notes` array, enforcing strict schema-role matching per (itemRef, key)
     * and last-wins dedup within the array itself. Off-schema keys and schema-free items are
     * unconstrained.
     */
    private suspend fun buildExplicitNotes(
        explicitNotesArray: JsonArray,
        treeCtx: TreeBuildContext,
        itemSchemas: Map<String, WorkItemSchema?>,
        warnings: MutableMap<Pair<UUID, String>, NoteLengthWarning>
    ): Pair<ExplicitNotesResult?, JsonElement?> {
        val refToItem = treeCtx.refToItem
        val noteActorClaim = treeCtx.noteActorClaim
        val noteVerification = treeCtx.noteVerification
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

            // The write policy (role normalization, byte cap, CRLF, strict schema-role match, maxLength) is
            // NoteCommandService's: any rejection fails the whole call. Schema resolution keys off the tree's
            // single effective root, not the item's own possibly-stale rootId.
            val schema = itemSchemas[itemRef]
            val prepared =
                when (
                    val p =
                        treeCtx.context.noteCommandService
                            .prepare(targetItem.copy(rootId = treeCtx.effectiveRootId), key, role, body)
                ) {
                    is Outcome.Ok -> p.value
                    is Outcome.Err ->
                        return null to
                            noteRejection(p.error, "notes[$index]: ", schema, key) { expectedRole ->
                                "notes[$index]: key '$key' is declared in the schema for itemRef " +
                                    "'$itemRef' with role '$expectedRole', but the explicit note has " +
                                    "role '$role'. Schema-declared keys must use the schema role; " +
                                    "off-schema keys may use any valid role."
                            }
                }
            // Last-wins: the warning follows the note that is finally written for this (item, key).
            prepared.warning?.let { warnings[targetItem.id to key] = it } ?: warnings.remove(targetItem.id to key)

            val note =
                Note(
                    itemId = targetItem.id,
                    key = key,
                    role = prepared.role,
                    body = prepared.body,
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
     * document (the atomic adopt happens later, inside the tree's write unit) — an anchor
     * miss, missing document, or already-adopted document returns an error here, before any DB
     * write, so zero items are created.
     */
    private suspend fun resolveAnchorNotes(
        treeCtx: TreeBuildContext,
        itemSchemas: Map<String, WorkItemSchema?>,
        explicitByRefKey: Map<Pair<String, String>, Int>,
        notesList: MutableList<Note>,
        warnings: MutableMap<Pair<UUID, String>, NoteLengthWarning>
    ): Pair<MutableMap<Pair<String, String>, Int>?, JsonElement?> {
        val docSlug = treeCtx.docSlug
        val docRootId = treeCtx.docRootId
        val rootObj = treeCtx.rootObj
        val childrenArray = treeCtx.childrenArray
        val refToItem = treeCtx.refToItem
        val context = treeCtx.context
        val noteActorClaim = treeCtx.noteActorClaim
        val noteVerification = treeCtx.noteVerification

        val anchorByRefKey = mutableMapOf<Pair<String, String>, Int>()
        if (docSlug == null) return anchorByRefKey to null

        val docResult =
            legacyRead({
                return null to
                    errorResponse(
                        "Failed to read plan document '$docSlug' (root $docRootId): $it",
                        ErrorCodes.INTERNAL_ERROR
                    )
            }) {
                context.repositoryProvider.planDocumentRepository().get(docRootId!!, docSlug)
            }
        val doc =
            docResult ?: return null to
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

            // Schema-role mismatch is reported before a missing anchor (original precedence).
            schema?.notes?.firstOrNull { it.key == anchor.noteKey }?.role?.toJsonString()?.let { expectedRole ->
                if (anchor.role.lowercase() != expectedRole) {
                    return null to
                        errorResponse(
                            "noteAnchors: key '${anchor.noteKey}' is declared in the schema for itemRef " +
                                "'${anchor.itemRef}' with role '$expectedRole', but the anchor has role " +
                                "'${anchor.role}'. Schema-declared keys must use the schema role.",
                            ErrorCodes.VALIDATION_ERROR
                        )
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

            val prepared =
                when (
                    val p =
                        context.noteCommandService
                            .prepare(targetItem.copy(rootId = treeCtx.effectiveRootId), anchor.noteKey, anchor.role, sliced)
                ) {
                    is Outcome.Ok -> p.value
                    is Outcome.Err ->
                        return null to
                            noteRejection(p.error, "noteAnchors: ", schema, anchor.noteKey) { expectedRole ->
                                "noteAnchors: key '${anchor.noteKey}' is declared in the schema for itemRef " +
                                    "'${anchor.itemRef}' with role '$expectedRole', but the anchor has role " +
                                    "'${anchor.role}'. Schema-declared keys must use the schema role."
                            }
                }
            prepared.warning?.let { warnings[targetItem.id to anchor.noteKey] = it } ?: warnings.remove(targetItem.id to anchor.noteKey)

            val note =
                Note(
                    itemId = targetItem.id,
                    key = anchor.noteKey,
                    role = prepared.role,
                    body = prepared.body,
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
        treeCtx: TreeBuildContext
    ): Pair<BuiltNotes?, JsonElement?> {
        val refToItem = treeCtx.refToItem
        val noteActorClaim = treeCtx.noteActorClaim
        val noteVerification = treeCtx.noteVerification

        val createNotes = optionalBoolean(params, "createNotes", defaultValue = false)
        val explicitNotesArray = paramsObj["notes"] as? JsonArray ?: JsonArray(emptyList())
        val itemSchemas = resolveItemSchemas(refToItem, treeCtx.effectiveRootId, treeCtx.context)

        val warnings = mutableMapOf<Pair<UUID, String>, NoteLengthWarning>()
        val (explicitResult, explicitError) =
            buildExplicitNotes(explicitNotesArray, treeCtx, itemSchemas, warnings)
        if (explicitError != null) return null to explicitError
        val (notesList, explicitByRefKey) = explicitResult!!

        val (anchorByRefKey, anchorError) =
            resolveAnchorNotes(treeCtx, itemSchemas, explicitByRefKey, notesList, warnings)
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

        return BuiltNotes(notesList, warnings) to null
    }

    /** The stashed plan document a tree adopts as its LAST step, by [adoptingItemId] (the created/attached root). */
    private data class DocAdoption(
        val rootItemId: UUID,
        val slug: String,
        val adoptingItemId: UUID
    )

    /** Everything the tree's write unit writes: item commands root-first, ref-level deps, prepared notes, adoption. */
    private data class TreeInput(
        val commands: List<ItemCreateCommand>,
        val refToItem: Map<String, WorkItem>,
        val deps: List<TreeDepSpec>,
        val notes: List<Note>,
        val adoption: DocAdoption?
    )

    /** What the tree's write unit stored: the items (root-first, attach-mode root excluded), deps and notes. */
    private data class TreeResult(
        val items: List<WorkItem>,
        val refToId: Map<String, UUID>,
        val deps: List<Dependency>,
        val notes: List<Note>
    )

    private data class TransactionOutcome(
        val treeResult: TreeResult,
        val finalRootPlacement: ChildPlacement?
    )

    /**
     * Writes the whole tree in ONE write unit over the write services (joining a keyed call's element unit): every
     * item through `ItemCommandService.createInUnit`, root first, so each placement is read from its parent's row
     * inside this unit (AR-19) and a TERMINAL parent under auto lifecycle rejects the tree; then the dependencies,
     * the notes and the document adoption. Any failure returns an error response and rolls everything back. In
     * attach mode the existing root's placement is re-read in the unit for the response.
     */
    private suspend fun runWorkTreeTransaction(
        context: ToolExecutionContext,
        isAttachMode: Boolean,
        rootItem: WorkItem,
        rootIdStr: String?,
        parentId: UUID?,
        input: TreeInput
    ): Pair<TransactionOutcome?, JsonElement?> {
        val workItemRepo = context.workItemRepository()
        val refToId = input.refToItem.mapValues { (_, item) -> item.id }
        var treeResultVar: TreeResult? = null
        var failureVar: JsonElement? = null
        var configFault: PerRootConfigUnavailableException? = null
        // Attach mode only: the root itself is not re-inserted, so capture its authoritative placement
        // read in the unit for the response (review obs 4).
        var finalRootPlacementVar: ChildPlacement? = null
        val rollback = DomainError(ErrorCode.INTERNAL, "Unit 'CreateWorkTreeTool.execute' rolled back by its caller")

        fun itemFailure(
            error: DomainError,
            command: ItemCreateCommand
        ): JsonElement =
            when {
                ItemCommandErrors.isClosedParent(error) ->
                    if (isAttachMode && command.parentId == rootItem.id) {
                        errorResponse(
                            "Root item '$rootIdStr' is terminal under auto lifecycle; reopen it before adding children",
                            ErrorCodes.VALIDATION_ERROR
                        )
                    } else {
                        errorResponse(
                            "Parent item '${command.parentId}' is terminal under auto lifecycle; reopen it before adding children",
                            ErrorCodes.VALIDATION_ERROR
                        )
                    }
                error.code == ErrorCode.NOT_FOUND ->
                    if (isAttachMode) {
                        errorResponse(
                            "Root item '$rootIdStr' not found: Parent item not found: ${rootItem.id}",
                            ErrorCodes.RESOURCE_NOT_FOUND
                        )
                    } else {
                        errorResponse("Parent item '$parentId' not found: Parent item not found: $parentId", ErrorCodes.RESOURCE_NOT_FOUND)
                    }
                error.code == ErrorCode.INVALID_REQUEST -> errorResponse(error.message, ErrorCodes.VALIDATION_ERROR)
                else -> errorResponse("Work tree creation failed: ${LegacyFaults.message(error)}", ErrorCodes.INTERNAL_ERROR)
            }

        fun treeFailure(error: DomainError): JsonElement =
            errorResponse("Work tree creation failed: ${LegacyFaults.message(error)}", ErrorCodes.INTERNAL_ERROR)

        val unit =
            try {
                context.unitOfWork.write("CreateWorkTreeTool.execute") {
                    // Reset per attempt: a BUSY retry re-runs this block from scratch.
                    treeResultVar = null
                    failureVar = null
                    configFault = null
                    finalRootPlacementVar = null
                    try {
                        if (isAttachMode) {
                            val placement = workItemRepo.resolveChildPlacement(rootItem.id)
                            if (placement == null) {
                                failureVar =
                                    errorResponse(
                                        "Root item '$rootIdStr' not found: Parent item not found: ${rootItem.id}",
                                        ErrorCodes.RESOURCE_NOT_FOUND
                                    )
                                return@write Outcome.Err(rollback)
                            }
                            finalRootPlacementVar = placement
                        }

                        val createdItems = mutableListOf<WorkItem>()
                        for (command in input.commands) {
                            when (val created = context.itemCommandService.createInUnit(command)) {
                                is Outcome.Ok -> createdItems += created.value
                                is Outcome.Err -> {
                                    failureVar = itemFailure(created.error, command)
                                    return@write Outcome.Err(rollback)
                                }
                            }
                        }

                        // The specs arrive normalized and cycle-checked (validateTreeEdges); the service re-checks
                        // them against stored rows in this unit and records dependency.added.
                        val deps =
                            input.deps.map { spec ->
                                Dependency(
                                    fromItemId = refToId.getValue(spec.fromRef),
                                    toItemId = refToId.getValue(spec.toRef),
                                    type = spec.type,
                                    unblockAt = spec.unblockAt
                                )
                            }
                        val createdDeps =
                            when (val stored = context.dependencyCommandService.createInUnit(deps)) {
                                is Outcome.Ok -> stored.value
                                is Outcome.Err -> {
                                    failureVar = treeFailure(stored.error)
                                    return@write Outcome.Err(rollback)
                                }
                            }

                        val createdNotes = mutableListOf<Note>()
                        for (note in input.notes) {
                            val written =
                                context.noteCommandService.upsert(
                                    NoteUpsertCommand(note.itemId, note.key, note.role, note.body, note.actorClaim, note.verification)
                                )
                            when (written) {
                                is Outcome.Ok -> createdNotes += written.value.note
                                is Outcome.Err -> {
                                    failureVar = treeFailure(written.error)
                                    return@write Outcome.Err(rollback)
                                }
                            }
                        }

                        // LAST: a concurrent adopt or a vanished document rolls the whole tree back.
                        val adoption = input.adoption
                        if (adoption != null) {
                            val adopted =
                                context.planDocumentService.adoptInUnit(
                                    adoption.rootItemId,
                                    adoption.slug,
                                    adoption.adoptingItemId
                                )
                            if (adopted is Outcome.Err) {
                                failureVar = treeFailure(adopted.error)
                                return@write Outcome.Err(rollback)
                            }
                        }

                        treeResultVar = TreeResult(createdItems, refToId, createdDeps, createdNotes)
                        Outcome.Ok(Unit)
                    } catch (e: PerRootConfigUnavailableException) {
                        // Caught inside the unit and rethrown after it: thrown out of the block, the outermost
                        // runner would translate it into a store fault instead of config_unavailable.
                        configFault = e
                        Outcome.Err(rollback)
                    }
                }
            } catch (e: PerRootConfigUnavailableException) {
                throw e
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                return null to
                    errorResponse(
                        "Work tree creation failed: ${e.message}",
                        ErrorCodes.INTERNAL_ERROR
                    )
            }
        configFault?.let { throw it }
        failureVar?.let { return null to it }
        if (unit is Outcome.Err) {
            return null to
                errorResponse(
                    "Work tree creation failed: ${LegacyFaults.message(unit.error)}",
                    ErrorCodes.INTERNAL_ERROR
                )
        }

        return TransactionOutcome(treeResultVar!!, finalRootPlacementVar) to null
    }

    /** Builds the `{ root, children, dependencies, notes }` success response payload. */
    private suspend fun buildTreeResponse(
        context: ToolExecutionContext,
        treeResult: TreeResult,
        rootResultItem: WorkItem,
        isExistingRoot: Boolean,
        depSpecs: List<TreeDepSpec>,
        noteWarnings: Map<Pair<UUID, String>, NoteLengthWarning>
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
                        noteWarnings[note.itemId to note.key]?.let { put("warning", JsonPrimitive(it.message())) }
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
     * Builds the [ItemCreateCommand] for a JSON object spec (no role, depth or rootId: the item command service
     * decides them). Returns null only when the title is missing; all validation errors are expected to have been
     * caught in [validateParams].
     */
    private fun buildItemCommand(
        obj: JsonObject,
        parentId: UUID?,
        id: UUID = UUID.randomUUID()
    ): ItemCreateCommand? {
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

        return ItemCreateCommand(
            id = id,
            parentId = parentId,
            title = title,
            description = description,
            summary = summary,
            priority = priority,
            requiresVerification = requiresVerification,
            tags = tags,
            type = type,
            properties = properties
        )
    }

    /**
     * A never-persisted preview of [command] (QUEUE, the provisional [rootId]) used before the write unit for schema
     * resolution and note targeting. Its depth is a placeholder that satisfies validation only: the persisted depth
     * comes from the in-unit placement read. Null when the spec fails domain validation.
     */
    private fun previewOf(
        command: ItemCreateCommand,
        rootId: UUID?,
        contextLabel: String
    ): WorkItem? =
        try {
            WorkItem(
                id = command.id,
                parentId = command.parentId,
                rootId = rootId,
                title = command.title,
                description = command.description,
                summary = command.summary,
                role = Role.QUEUE,
                priority = command.priority,
                requiresVerification = command.requiresVerification,
                depth = if (command.parentId == null) 0 else 1,
                tags = command.tags,
                type = command.type,
                properties = command.properties
            )
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            logger.warn("Failed to build WorkItem for $contextLabel: ${e.message}")
            null
        }
}
