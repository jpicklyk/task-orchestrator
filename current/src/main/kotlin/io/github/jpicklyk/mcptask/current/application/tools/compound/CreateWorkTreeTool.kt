package io.github.jpicklyk.mcptask.current.application.tools.compound

import io.github.jpicklyk.mcptask.current.application.service.TreeDepSpec
import io.github.jpicklyk.mcptask.current.application.service.WorkTreeInput
import io.github.jpicklyk.mcptask.current.application.service.WorkTreeService
import io.github.jpicklyk.mcptask.current.application.tools.*
import io.github.jpicklyk.mcptask.current.domain.model.*
import io.github.jpicklyk.mcptask.current.domain.repository.Result
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
 * Depth enforcement: root item depth must be < [MAX_DEPTH]; children are depth+1.
 */
class CreateWorkTreeTool : BaseToolDefinition() {

    companion object {
        /** Maximum allowed nesting depth (shared with ManageItemsTool). */
        const val MAX_DEPTH = 3

        /** Logical ref name reserved for the root item in dep specs. */
        const val ROOT_REF = "root"
    }

    override val name = "create_work_tree"

    override val description = """
Atomically create a hierarchical work tree: root item, child items, dependencies, and optional notes.

**Parameters:**
- `root` (required): Root item spec `{ title (required), priority?, tags?, summary?, description?, requiresVerification? }`
- `parentId` (optional): UUID of existing parent item. If provided, root depth = parent.depth + 1; otherwise depth = 0.
- `children` (optional): Array of child item specs `[{ ref (required), title (required), priority?, tags?, summary?, description?, requiresVerification? }]`. `ref` is a local name used in `deps` to wire dependencies.
- `deps` (optional): Array of dependency specs `[{ from: ref, to: ref, type?: BLOCKS|IS_BLOCKED_BY|RELATES_TO, unblockAt?: queue|work|review|terminal }]`. Use `"root"` to reference the root item.
- `createNotes` (optional, default false): Auto-create blank notes for each item based on its tag schema.

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

    override val toolAnnotations = ToolAnnotations(
        readOnlyHint = false,
        destructiveHint = false,
        idempotentHint = false,
        openWorldHint = false
    )

    override val parameterSchema = ToolSchema(
        properties = buildJsonObject {
            put("root", buildJsonObject {
                put("type", JsonPrimitive("object"))
                put(
                    "description", JsonPrimitive(
                        "Root item spec: { title (required), priority?, tags?, summary?, description?, requiresVerification? }"
                    )
                )
            })
            put("parentId", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("UUID of existing parent item. Root depth = parent.depth + 1 if provided."))
            })
            put("children", buildJsonObject {
                put("type", JsonPrimitive("array"))
                put(
                    "description", JsonPrimitive(
                        "Child item specs: [{ ref (required local name), title (required), priority?, tags?, summary?, description?, requiresVerification? }]"
                    )
                )
            })
            put("deps", buildJsonObject {
                put("type", JsonPrimitive("array"))
                put(
                    "description", JsonPrimitive(
                        "Dependencies: [{ from: ref, to: ref, type?: BLOCKS|IS_BLOCKED_BY|RELATES_TO, unblockAt?: queue|work|review|terminal }]. Use \"root\" to reference the root item."
                    )
                )
            })
            put("createNotes", buildJsonObject {
                put("type", JsonPrimitive("boolean"))
                put("description", JsonPrimitive("Auto-create blank notes from schema for each item based on its tags (default: false)"))
            })
        },
        required = listOf("root")
    )

    override fun validateParams(params: JsonElement) {
        val paramsObj = params as? JsonObject
            ?: throw ToolValidationException("Parameters must be a JSON object")

        // root is required
        val rootElement = paramsObj["root"]
            ?: throw ToolValidationException("'root' is required")
        val rootObj = rootElement as? JsonObject
            ?: throw ToolValidationException("'root' must be a JSON object")
        val rootTitle = (rootObj["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (rootTitle.isNullOrBlank()) {
            throw ToolValidationException("'root.title' is required and must be a non-blank string")
        }

        // Validate children if provided
        val childrenElement = paramsObj["children"]
        if (childrenElement != null && childrenElement !is JsonNull) {
            val children = childrenElement as? JsonArray
                ?: throw ToolValidationException("'children' must be a JSON array")
            for ((index, child) in children.withIndex()) {
                val childObj = child as? JsonObject
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
            val deps = depsElement as? JsonArray
                ?: throw ToolValidationException("'deps' must be a JSON array")
            for ((index, dep) in deps.withIndex()) {
                val depObj = dep as? JsonObject
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
    }

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        val paramsObj = params as JsonObject

        // ── 1. Parse parentId (optional) ──────────────────────────────────────
        val parentId = extractUUID(params, "parentId", required = false)

        // ── 2. Compute root depth from parent ──────────────────────────────────
        val rootDepth = if (parentId != null) {
            val parentResult = context.workItemRepository().getById(parentId)
            when (parentResult) {
                is Result.Success -> {
                    val computedDepth = parentResult.data.depth + 1
                    if (computedDepth >= MAX_DEPTH) {
                        return errorResponse(
                            "Root item would be at depth $computedDepth which equals or exceeds the maximum depth of $MAX_DEPTH",
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
        val rootItem = buildWorkItem(
            obj = rootObj,
            parentId = parentId,
            depth = rootDepth,
            contextLabel = "root"
        ) ?: return errorResponse("Failed to build root item", ErrorCodes.VALIDATION_ERROR)

        // ── 4. Parse children ──────────────────────────────────────────────────
        val childDepth = rootDepth + 1
        if (childDepth >= MAX_DEPTH) {
            val childrenArray = paramsObj["children"] as? JsonArray
            if (childrenArray != null && childrenArray.isNotEmpty()) {
                return errorResponse(
                    "Children would be at depth $childDepth which equals or exceeds the maximum depth of $MAX_DEPTH",
                    ErrorCodes.VALIDATION_ERROR
                )
            }
        }

        val childrenArray = paramsObj["children"] as? JsonArray ?: JsonArray(emptyList())
        val refToItem = mutableMapOf<String, WorkItem>()
        refToItem[ROOT_REF] = rootItem

        for ((index, childElement) in childrenArray.withIndex()) {
            val childObj = childElement as JsonObject
            val ref = (childObj["ref"] as? JsonPrimitive)!!.content  // validated already

            val childItem = buildWorkItem(
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
            val depType = DependencyType.fromString(typeStr)
                ?: return errorResponse(
                    "deps[$index]: invalid type '$typeStr'. Valid: BLOCKS, IS_BLOCKED_BY, RELATES_TO",
                    ErrorCodes.VALIDATION_ERROR
                )

            val unblockAt = (depObj["unblockAt"] as? JsonPrimitive)?.content

            depSpecs.add(TreeDepSpec(fromRef = fromRef, toRef = toRef, type = depType, unblockAt = unblockAt))
        }

        // ── 6. Build notes if createNotes=true ─────────────────────────────────
        val createNotes = optionalBoolean(params, "createNotes", defaultValue = false)
        val notesList = mutableListOf<Note>()

        if (createNotes) {
            val noteSchemaService = context.noteSchemaService()
            for ((ref, item) in refToItem) {
                val schemaEntries = noteSchemaService.getSchemaForTags(item.tagList()) ?: continue
                for (entry in schemaEntries) {
                    notesList.add(
                        Note(
                            itemId = item.id,
                            key = entry.key,
                            role = entry.role,
                            body = ""
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

        val input = WorkTreeInput(
            items = orderedItems,
            refToItem = refToItem,
            deps = depSpecs,
            notes = notesList
        )

        // ── 8. Execute atomically via WorkTreeService ───────────────────────────
        val workTreeService = WorkTreeService(
            workItemRepository = context.workItemRepository(),
            dependencyRepository = context.dependencyRepository(),
            noteRepository = context.noteRepository()
        )

        val treeResult = try {
            workTreeService.execute(input)
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
        val rootJson = buildJsonObject {
            put("id", JsonPrimitive(rootResultItem.id.toString()))
            put("title", JsonPrimitive(rootResultItem.title))
            put("role", JsonPrimitive(rootResultItem.role.name.lowercase()))
            put("depth", JsonPrimitive(rootResultItem.depth))
            rootResultItem.tags?.let { put("tags", JsonPrimitive(it)) }
        }

        val childrenJson = JsonArray(
            treeResult.items.drop(1).map { item ->
                val ref = idToRef[item.id] ?: "unknown"
                buildJsonObject {
                    put("ref", JsonPrimitive(ref))
                    put("id", JsonPrimitive(item.id.toString()))
                    put("title", JsonPrimitive(item.title))
                    put("role", JsonPrimitive(item.role.name.lowercase()))
                    put("depth", JsonPrimitive(item.depth))
                    item.tags?.let { put("tags", JsonPrimitive(it)) }
                }
            }
        )

        val depsJson = JsonArray(
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

        val notesJson = JsonArray(
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

        val data = buildJsonObject {
            put("root", rootJson)
            put("children", childrenJson)
            put("dependencies", depsJson)
            put("notes", notesJson)
        }

        return successResponse(data)
    }

    override fun userSummary(params: JsonElement, result: JsonElement, isError: Boolean): String {
        if (isError) return "create_work_tree failed"
        val data = (result as? JsonObject)?.get("data") as? JsonObject ?: return "create_work_tree completed"
        val rootTitle = (data["root"] as? JsonObject)
            ?.get("title")?.let { (it as? JsonPrimitive)?.content } ?: "?"
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
        val title = (obj["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return null

        val priorityStr = (obj["priority"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val priority = if (priorityStr != null) {
            Priority.fromString(priorityStr) ?: Priority.MEDIUM
        } else {
            Priority.MEDIUM
        }

        val tags = (obj["tags"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val summary = (obj["summary"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
        val description = (obj["description"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val requiresVerification = (obj["requiresVerification"] as? JsonPrimitive)?.booleanOrNull ?: false

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
                tags = tags
            )
        } catch (e: Exception) {
            logger.warn("Failed to build WorkItem for $contextLabel: ${e.message}")
            null
        }
    }
}
