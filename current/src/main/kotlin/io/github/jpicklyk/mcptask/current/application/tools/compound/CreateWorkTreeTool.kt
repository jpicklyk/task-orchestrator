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
 * Depth enforcement: root item depth must be < [MAX_DEPTH]; children are depth+1.
 */
class CreateWorkTreeTool :
    BaseToolDefinition(),
    ActorAware {
    companion object {
        /** Maximum allowed nesting depth (shared with ManageItemsTool). */
        const val MAX_DEPTH = 3

        /** Logical ref name reserved for the root item in dep specs. */
        const val ROOT_REF = "root"
    }

    override val name = "create_work_tree"

    override val description =
        """
Atomically create a hierarchical work tree: root item, child items, dependencies, and optional notes.

**Idempotency:** Pass `requestId` (client-generated UUID) together with a top-level `actor.id` to enable idempotent retries. Repeated calls with the same (actor, requestId) within ~10 minutes return the cached response without re-executing.

**Parameters:**
- `root` (required): Root item spec `{ title (required), priority?, tags?, summary?, description?, requiresVerification?, type? }`
- `parentId` (optional): UUID of existing parent item. If provided, root depth = parent.depth + 1; otherwise depth = 0.
- `children` (optional): Array of child item specs `[{ ref (required), title (required), priority?, tags?, summary?, description?, requiresVerification?, type? }]`. `ref` is a local name used in `deps` to wire dependencies.
- `deps` (optional): Array of dependency specs `[{ from: ref, to: ref, type?: BLOCKS|IS_BLOCKED_BY|RELATES_TO, unblockAt?: queue|work|review|terminal }]`. Use `"root"` to reference the root item.
- `createNotes` (optional, default false): Auto-create blank notes for each item based on its tag schema.
- `notes` (optional): Notes to create with bodies: `[{ itemRef (required, "root" or child ref), key (required), role (required: queue|work|review), body (optional, defaults to empty string) }]`. Explicit notes win over `createNotes=true` blanks per `(itemRef, key)`.

**Depth cap:** Root item depth must be < $MAX_DEPTH. Children are always root.depth + 1, also < $MAX_DEPTH.

**Response:**
```json
{
  "root": { "id": "uuid", "title": "...", "role": "queue", "depth": 0, "tags": "..." },
  "children": [{ "ref": "t1", "id": "uuid", "title": "...", "role": "queue", "depth": 1 }],
  "dependencies": [{ "id": "uuid", "fromRef": "t1", "toRef": "t2", "type": "BLOCKS" }],
  "notes": [{ "itemRef": "t1", "key": "acceptance-criteria", "role": "queue", "id": "uuid" }]
}
```
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
                                    "Root item spec: { title (required), priority?, tags?, summary?, description?, requiresVerification?, type? }"
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
                                    "Child item specs: [{ ref (required local name), title (required), priority?, tags?, summary?, description?, requiresVerification?, type? }]"
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
                                JsonPrimitive("Auto-create blank notes from schema for each item based on its tags (default: false)")
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
                                    "Notes to create with bodies: [{ itemRef (required, \"root\" or child ref), key (required), " +
                                        "role (required: queue|work|review), body (optional, defaults to empty string) }]. " +
                                        "Explicit notes win over createNotes=true blanks per (itemRef, key)."
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
                                    "Client-generated UUID for idempotency. Repeated calls with the same (actor, requestId) " +
                                        "within ~10 minutes return the cached response without re-executing. " +
                                        "Requires a top-level actor parameter to function."
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
                                    "Top-level actor for idempotency key resolution: " +
                                        "{ id (required string), kind (required: orchestrator|subagent|user|external), " +
                                        "parent? (optional string), proof? (optional string) }"
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
        val rootTitle = (rootObj["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (rootTitle.isNullOrBlank()) {
            throw ToolValidationException("'root.title' is required and must be a non-blank string")
        }

        // Validate children if provided
        val childrenElement = paramsObj["children"]
        if (childrenElement != null && childrenElement !is JsonNull) {
            val children =
                childrenElement as? JsonArray
                    ?: throw ToolValidationException("'children' must be a JSON array")
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
        noteActorClaim: ActorClaim? = null,
        noteVerification: VerificationResult? = null
    ): JsonElement {
        // ── 1. Parse parentId (optional) ──────────────────────────────────────
        val (parentId, parentIdError) = resolveItemId(params, "parentId", context, required = false)
        if (parentIdError != null) return parentIdError

        // ── 2. Compute root depth from parent ──────────────────────────────────
        val rootDepth =
            if (parentId != null) {
                val parentResult = context.workItemRepository().getById(parentId)
                when (parentResult) {
                    is Result.Success -> {
                        val computedDepth = parentResult.data.depth + 1
                        if (computedDepth > MAX_DEPTH) {
                            return errorResponse(
                                "Root item would be at depth $computedDepth which exceeds the maximum depth of $MAX_DEPTH",
                                ErrorCodes.VALIDATION_ERROR
                            )
                        }
                        computedDepth
                    }
                    is Result.Error -> return errorResponse(
                        "Parent item '$parentId' not found: ${parentResult.error.message}",
                        ErrorCodes.RESOURCE_NOT_FOUND
                    )
                }
            } else {
                0
            }

        // ── 3. Parse root ──────────────────────────────────────────────────────
        val rootObj = paramsObj["root"] as JsonObject
        val rootItem =
            buildWorkItem(
                obj = rootObj,
                parentId = parentId,
                depth = rootDepth,
                contextLabel = "root"
            ) ?: return errorResponse("Failed to build root item", ErrorCodes.VALIDATION_ERROR)

        // ── 4. Parse children ──────────────────────────────────────────────────
        val childDepth = rootDepth + 1
        if (childDepth > MAX_DEPTH) {
            val childrenArray = paramsObj["children"] as? JsonArray
            if (childrenArray != null && childrenArray.isNotEmpty()) {
                return errorResponse(
                    "Children would be at depth $childDepth which exceeds the maximum depth of $MAX_DEPTH",
                    ErrorCodes.VALIDATION_ERROR
                )
            }
        }

        val childrenArray = paramsObj["children"] as? JsonArray ?: JsonArray(emptyList())
        val refToItem = mutableMapOf<String, WorkItem>()
        refToItem[ROOT_REF] = rootItem

        for ((index, childElement) in childrenArray.withIndex()) {
            val childObj = childElement as JsonObject
            val ref = (childObj["ref"] as? JsonPrimitive)!!.content // validated already

            val childItem =
                buildWorkItem(
                    obj = childObj,
                    parentId = rootItem.id,
                    depth = childDepth,
                    contextLabel = "children[$index]"
                ) ?: return errorResponse("Failed to build child item at index $index", ErrorCodes.VALIDATION_ERROR)

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
                val resolvedSchema = context.resolveSchema(item) ?: continue
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

        // ── 7. Build ordered item list (root first, children in insertion order) ─
        val orderedItems = mutableListOf(rootItem)
        for (childElement in childrenArray) {
            val childObj = childElement as JsonObject
            val ref = (childObj["ref"] as? JsonPrimitive)!!.content
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

        val rootResultItem = treeResult.items.first()
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

        val childrenJson =
            JsonArray(
                treeResult.items.drop(1).map { item ->
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
