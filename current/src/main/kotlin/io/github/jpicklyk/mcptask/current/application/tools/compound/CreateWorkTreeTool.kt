package io.github.jpicklyk.mcptask.current.application.tools.compound

import io.github.jpicklyk.mcptask.current.application.service.TreeDepSpec
import io.github.jpicklyk.mcptask.current.application.service.WorkTreeInput
import io.github.jpicklyk.mcptask.current.application.service.buildSchemaResponseFields
import io.github.jpicklyk.mcptask.current.application.tools.*
import io.github.jpicklyk.mcptask.current.domain.model.*
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
                                        "description?, requiresVerification?, type? }"
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
                                        "description?, requiresVerification?, type? }]. Order-independent (topologically " +
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
                                    "Client-generated UUID for idempotency (10 min cache, keyed by actor+requestId). " +
                                        "Requires actor."
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
        val paramsObj =
            params as? JsonObject
                ?: throw ToolValidationException("Parameters must be a JSON object")

        // root is required
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

        rejectNestedChildren(rootObj, "root")

        // Validate children if provided
        val childrenElement = paramsObj["children"]
        if (childrenElement != null && childrenElement !is JsonNull) {
            val children =
                childrenElement as? JsonArray
                    ?: throw ToolValidationException("'children' must be a JSON array")
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
                rejectNestedChildren(childObj, "children[$index]")
                allRefs.add(ref)
            }
            // Validate parentRef values and detect cycles
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

        // Validate deps if provided
        val depsElement = paramsObj["deps"]
        if (depsElement != null && depsElement !is JsonNull) {
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

        // Validate notes if provided
        val notesElement = paramsObj["notes"]
        if (notesElement != null && notesElement !is JsonNull) {
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
    }

    override suspend fun execute(
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement {
        val paramsObj = params as JsonObject

        val requestIdStr = optionalString(params, "requestId")
        val requestId =
            requestIdStr?.let {
                try {
                    UUID.fromString(it)
                } catch (_: IllegalArgumentException) {
                    null
                }
            }

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
        // ── 1. Detect mode: attach (root.id present) vs create ────────────────
        val rootObj = paramsObj["root"] as JsonObject
        val rootIdStr = (rootObj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        val isAttachMode = rootIdStr != null

        // ── 2. Parse parentId (only valid in create mode) ────────────────────
        //   validateParams already rejects root.id + parentId together, so if we
        //   reach here in attach mode, parentId is absent.
        val (parentId, parentIdError) = resolveItemId(params, "parentId", context, required = false)
        if (parentIdError != null) return parentIdError

        // ── 3. Resolve root (attach mode: fetch existing; create mode: build new) ──
        val rootItem: WorkItem
        val isExistingRoot: Boolean

        if (isAttachMode) {
            // Resolve the existing item via id (supports hex prefix like parentId)
            val (resolvedRootId, rootIdError) = resolveIdString(rootIdStr!!, context)
            if (rootIdError != null) return rootIdError
            val fetchResult = context.workItemRepository().getById(resolvedRootId!!)
            when (fetchResult) {
                is Result.Success -> {
                    rootItem = fetchResult.data
                    isExistingRoot = true
                }
                is Result.Error -> return errorResponse(
                    "Root item '$rootIdStr' not found: ${fetchResult.error.message}",
                    ErrorCodes.RESOURCE_NOT_FOUND
                )
            }
        } else {
            // Create mode: compute root depth from parent, then build a new WorkItem.
            val rootDepth =
                if (parentId != null) {
                    val parentResult = context.workItemRepository().getById(parentId)
                    when (parentResult) {
                        is Result.Success -> parentResult.data.depth + 1
                        is Result.Error -> return errorResponse(
                            "Parent item '$parentId' not found: ${parentResult.error.message}",
                            ErrorCodes.RESOURCE_NOT_FOUND
                        )
                    }
                } else {
                    0
                }
            rootItem =
                buildWorkItem(
                    obj = rootObj,
                    parentId = parentId,
                    depth = rootDepth,
                    contextLabel = "root"
                ) ?: return errorResponse("Failed to build root item", ErrorCodes.VALIDATION_ERROR)
            isExistingRoot = false
        }

        // ── 4. Parse children ──────────────────────────────────────────────────
        val childrenArray = paramsObj["children"] as? JsonArray ?: JsonArray(emptyList())
        val refToItem = mutableMapOf<String, WorkItem>()
        refToItem[ROOT_REF] = rootItem

        // Build ref→parentRef map (default "root" if absent)
        val refToParentRef = mutableMapOf<String, String>()
        for (childElement in childrenArray) {
            val childObj = childElement as JsonObject
            val ref = (childObj["ref"] as JsonPrimitive).content
            val parentRef = (childObj["parentRef"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ROOT_REF
            refToParentRef[ref] = parentRef
        }

        // Topological sort: Kahn's algorithm on parentRef DAG
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

        // Build children in topological order
        for (ref in sortedChildRefs) {
            val childObj =
                childrenArray
                    .map { it as JsonObject }
                    .first { (it["ref"] as JsonPrimitive).content == ref }
            val parentRef = refToParentRef[ref]!!
            val parentItem = refToItem[parentRef]!! // root or already-built sibling
            val depth = parentItem.depth + 1
            val childItem =
                buildWorkItem(
                    obj = childObj,
                    parentId = parentItem.id,
                    depth = depth,
                    contextLabel = "child '$ref'"
                ) ?: return errorResponse("Failed to build child item '$ref'", ErrorCodes.VALIDATION_ERROR)
            refToItem[ref] = childItem
        }

        // ── 5. Parse deps and validate refs ────────────────────────────────────
        val depsArray = paramsObj["deps"] as? JsonArray ?: JsonArray(emptyList())
        val depSpecs = mutableListOf<TreeDepSpec>()

        for ((index, depElement) in depsArray.withIndex()) {
            val depObj = depElement as JsonObject
            val fromRef = (depObj["from"] as? JsonPrimitive)!!.content
            val toRef = (depObj["to"] as? JsonPrimitive)!!.content

            // Validate refs
            if (!refToItem.containsKey(fromRef)) {
                return errorResponse(
                    "deps[$index]: 'from' ref '$fromRef' is not defined. Valid refs: ${refToItem.keys.joinToString()}",
                    ErrorCodes.VALIDATION_ERROR
                )
            }
            if (!refToItem.containsKey(toRef)) {
                return errorResponse(
                    "deps[$index]: 'to' ref '$toRef' is not defined. Valid refs: ${refToItem.keys.joinToString()}",
                    ErrorCodes.VALIDATION_ERROR
                )
            }

            val typeStr = (depObj["type"] as? JsonPrimitive)?.content ?: "BLOCKS"
            val depType =
                DependencyType.fromString(typeStr)
                    ?: return errorResponse(
                        "deps[$index]: invalid type '$typeStr'. Valid: BLOCKS, IS_BLOCKED_BY, RELATES_TO",
                        ErrorCodes.VALIDATION_ERROR
                    )

            val unblockAt = (depObj["unblockAt"] as? JsonPrimitive)?.content

            depSpecs.add(TreeDepSpec(fromRef = fromRef, toRef = toRef, type = depType, unblockAt = unblockAt))
        }

        // ── 6. Build notes: merge explicit notes + createNotes blanks ─────────
        val createNotes = optionalBoolean(params, "createNotes", defaultValue = false)
        val explicitNotesArray = paramsObj["notes"] as? JsonArray ?: JsonArray(emptyList())
        val notesList = mutableListOf<Note>()

        // Resolve schemas once per item; reused for strict role enforcement on
        // explicit notes AND for createNotes=true schema-blank fill.
        val itemSchemas: Map<String, WorkItemSchema?> =
            refToItem.mapValues { (_, item) -> context.resolveSchema(item) }

        // Parse explicit notes; track (itemRef, key) -> index in notesList for last-wins dedup
        val explicitByRefKey = mutableMapOf<Pair<String, String>, Int>()
        for ((index, noteElement) in explicitNotesArray.withIndex()) {
            val noteObj = noteElement as JsonObject
            val itemRef = (noteObj["itemRef"] as JsonPrimitive).content
            val key = (noteObj["key"] as JsonPrimitive).content
            val role = (noteObj["role"] as JsonPrimitive).content
            // Only string primitives are accepted as body; JsonNull and omitted both default to "".
            // (validateParams already rejects non-string non-null primitives.)
            val body = (noteObj["body"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""

            // Validate ref exists in refToItem (resolved after step 4)
            val targetItem =
                refToItem[itemRef]
                    ?: return errorResponse(
                        "notes[$index]: 'itemRef' '$itemRef' is not defined. Valid refs: ${refToItem.keys.joinToString()}",
                        ErrorCodes.VALIDATION_ERROR
                    )

            // Strict role enforcement: when the resolved schema for this item declares the
            // same key, the explicit note's role must match the schema role. The DB has a
            // UNIQUE(itemId, key) constraint, so only one note per key can exist; allowing
            // a role mismatch would silently leave the gate-required role unfilled.
            // Off-schema keys are unconstrained — callers may add arbitrary auxiliary notes.
            // Items with no schema match are also unconstrained.
            val schema = itemSchemas[itemRef]
            if (schema != null) {
                val schemaEntry = schema.notes.firstOrNull { it.key == key }
                if (schemaEntry != null) {
                    val expectedRole = schemaEntry.role.toJsonString()
                    if (role != expectedRole) {
                        return errorResponse(
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

        // If createNotes=true, fill schema-required notes that aren't already explicit (with empty bodies)
        if (createNotes) {
            for ((ref, item) in refToItem) {
                val resolvedSchema = itemSchemas[ref] ?: continue
                for (entry in resolvedSchema.notes) {
                    if (explicitByRefKey.containsKey(ref to entry.key)) continue
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

        // ── 7. Build ordered item list ────────────────────────────────────────
        // In attach mode the existing root is NOT inserted (it already exists in the DB).
        // In create mode the root leads the list (root first, children in topological order).
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
                notes = notesList
            )

        // ── 8. Execute atomically via WorkTreeExecutor ──────────────────────────
        val treeResult =
            try {
                context.workTreeExecutor().execute(input)
            } catch (e: Exception) {
                return errorResponse(
                    "Work tree creation failed: ${e.message}",
                    ErrorCodes.INTERNAL_ERROR
                )
            }

        // ── 9. Build response ──────────────────────────────────────────────────
        // Build ref-to-result-item map for note lookup
        val idToRef = treeResult.refToId.entries.associate { (ref, id) -> id to ref }

        // In attach mode the root was not inserted — use the fetched existing item for the response.
        // In create mode the root is treeResult.items.first().
        val rootResultItem = if (isExistingRoot) rootItem else treeResult.items.first()
        val rootSchemaFields = buildSchemaResponseFields(context.resolveSchema(rootResultItem))
        val rootJson =
            buildJsonObject {
                put("id", JsonPrimitive(rootResultItem.id.toString()))
                put("title", JsonPrimitive(rootResultItem.title))
                put("role", JsonPrimitive(rootResultItem.role.toJsonString()))
                put("depth", JsonPrimitive(rootResultItem.depth))
                rootResultItem.tags?.let { put("tags", JsonPrimitive(it)) }
                put("schemaMatch", JsonPrimitive(rootSchemaFields.schemaMatch))
                put("expectedNotes", rootSchemaFields.expectedNotes)
            }

        // In attach mode treeResult.items contains only children.
        // In create mode children start at index 1 (after the root).
        val childItems = if (isExistingRoot) treeResult.items else treeResult.items.drop(1)
        val childrenJson =
            JsonArray(
                childItems.map { item ->
                    val ref = idToRef[item.id] ?: "unknown"
                    val childSchemaFields = buildSchemaResponseFields(context.resolveSchema(item))
                    buildJsonObject {
                        put("ref", JsonPrimitive(ref))
                        put("id", JsonPrimitive(item.id.toString()))
                        put("title", JsonPrimitive(item.title))
                        put("role", JsonPrimitive(item.role.toJsonString()))
                        put("depth", JsonPrimitive(item.depth))
                        item.tags?.let { put("tags", JsonPrimitive(it)) }
                        put("schemaMatch", JsonPrimitive(childSchemaFields.schemaMatch))
                        put("expectedNotes", childSchemaFields.expectedNotes)
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

        val response = successResponse(data)

        return response
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
        contextLabel: String
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
                id = UUID.randomUUID(),
                parentId = parentId,
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
