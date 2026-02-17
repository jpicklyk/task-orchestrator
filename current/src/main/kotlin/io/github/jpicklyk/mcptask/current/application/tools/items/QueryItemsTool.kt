package io.github.jpicklyk.mcptask.current.application.tools.items

import io.github.jpicklyk.mcptask.current.application.tools.*
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*

/**
 * Read-only MCP tool for querying WorkItems.
 *
 * Supports three operations:
 * - **get**: Fetch a single WorkItem by ID (full JSON)
 * - **search**: Filter WorkItems with multiple criteria (minimal JSON for token efficiency)
 * - **overview**: Hierarchical summary view (scoped to an item, or global root items)
 */
class QueryItemsTool : BaseToolDefinition() {

    override val name = "query_items"

    override val description = """
Unified read operations for work items.

Operations: get, search, overview

**get** - Retrieve a single item by ID
- Required: id (UUID)
- Returns full item JSON

**search** - Filter items with multiple criteria
- Optional: parentId, depth, role, priority, tags, query, createdAfter/Before, modifiedAfter/Before, sortBy, sortOrder, limit
- Returns minimal fields: id, parentId, title, role, priority, depth, tags
- Wrapped in { items: [...], total: N }

**overview** - Hierarchical summary view
- With itemId: Item metadata + child counts by role + direct children list
- Without itemId: Global overview of all root items with per-root child counts
- Default limit: 20 root items
    """.trimIndent()

    override val category = ToolCategory.ITEM_MANAGEMENT

    override val toolAnnotations = ToolAnnotations(
        readOnlyHint = true,
        destructiveHint = false,
        idempotentHint = true,
        openWorldHint = false
    )

    override val parameterSchema = ToolSchema(
        properties = buildJsonObject {
            put("operation", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Operation: get, search, overview"))
                put("enum", JsonArray(listOf("get", "search", "overview").map { JsonPrimitive(it) }))
            })
            put("id", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Item UUID (required for get)"))
            })
            put("itemId", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Item UUID for scoped overview"))
            })
            put("parentId", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Filter by parent ID"))
            })
            put("depth", buildJsonObject {
                put("type", JsonPrimitive("integer"))
                put("description", JsonPrimitive("Filter by depth level"))
            })
            put("role", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Filter by role: queue, work, review, blocked, terminal"))
            })
            put("priority", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Filter by priority: high, medium, low"))
            })
            put("tags", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Comma-separated tags filter (OR logic)"))
            })
            put("query", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Text search in title and summary"))
            })
            put("createdAfter", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("ISO 8601 timestamp filter"))
            })
            put("createdBefore", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("ISO 8601 timestamp filter"))
            })
            put("modifiedAfter", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("ISO 8601 timestamp filter"))
            })
            put("modifiedBefore", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("ISO 8601 timestamp filter"))
            })
            put("roleChangedAfter", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("ISO 8601 timestamp — items whose role changed after this time"))
            })
            put("roleChangedBefore", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("ISO 8601 timestamp — items whose role changed before this time"))
            })
            put("sortBy", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Sort field: title, priority, complexity, createdAt, modifiedAt"))
            })
            put("sortOrder", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Sort order: asc, desc (default: desc)"))
            })
            put("limit", buildJsonObject {
                put("type", JsonPrimitive("integer"))
                put("description", JsonPrimitive("Max results (default: 50)"))
            })
        },
        required = listOf("operation")
    )

    override fun validateParams(params: JsonElement) {
        val operation = requireString(params, "operation")
        when (operation) {
            "get" -> extractUUID(params, "id", required = true)
            "search", "overview" -> { /* all parameters are optional */ }
            else -> throw ToolValidationException("Invalid operation: $operation. Must be one of: get, search, overview")
        }
    }

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        val operation = requireString(params, "operation")
        return when (operation) {
            "get" -> executeGet(params, context)
            "search" -> executeSearch(params, context)
            "overview" -> executeOverview(params, context)
            else -> errorResponse(
                "Invalid operation: $operation",
                ErrorCodes.VALIDATION_ERROR
            )
        }
    }

    override fun userSummary(params: JsonElement, result: JsonElement, isError: Boolean): String {
        if (isError) return "Query failed"

        val paramsObj = params as? JsonObject
        val operation = paramsObj?.get("operation")?.let {
            if (it is JsonPrimitive && it.isString) it.content else null
        } ?: return "Query completed"

        val data = (result as? JsonObject)?.get("data") as? JsonObject

        return when (operation) {
            "get" -> {
                val title = data?.get("title")?.let {
                    if (it is JsonPrimitive && it.isString) it.content else null
                } ?: "unknown"
                val id = data?.get("id")?.let {
                    if (it is JsonPrimitive && it.isString) it.content.take(8) else null
                } ?: "?"
                "Item: $title ($id)"
            }
            "search" -> {
                val total = data?.get("total")?.let {
                    if (it is JsonPrimitive) it.intOrNull else null
                } ?: 0
                "Found $total item(s)"
            }
            "overview" -> {
                val title = data?.get("item")?.let { itemObj ->
                    (itemObj as? JsonObject)?.get("title")?.let {
                        if (it is JsonPrimitive && it.isString) it.content else null
                    }
                }
                if (title != null) {
                    val children = data?.get("children")?.let {
                        (it as? JsonArray)?.size
                    } ?: 0
                    "Overview: $title -- $children children"
                } else {
                    val total = data?.get("total")?.let {
                        if (it is JsonPrimitive) it.intOrNull else null
                    } ?: 0
                    "Overview: root items -- $total items"
                }
            }
            else -> "Query completed"
        }
    }

    // ──────────────────────────────────────────────
    // Operation: get
    // ──────────────────────────────────────────────

    private suspend fun executeGet(params: JsonElement, context: ToolExecutionContext): JsonElement {
        val id = extractUUID(params, "id", required = true)!!

        return when (val result = context.workItemRepository().getById(id)) {
            is Result.Success -> successResponse(workItemToJson(result.data))
            is Result.Error -> errorResponse(
                result.error.message,
                ErrorCodes.RESOURCE_NOT_FOUND
            )
        }
    }

    // ──────────────────────────────────────────────
    // Operation: search
    // ──────────────────────────────────────────────

    private suspend fun executeSearch(params: JsonElement, context: ToolExecutionContext): JsonElement {
        val parentId = extractUUID(params, "parentId", required = false)
        val depth = optionalInt(params, "depth")
        val roleStr = optionalString(params, "role")
        val priorityStr = optionalString(params, "priority")
        val tagsStr = optionalString(params, "tags")
        val query = optionalString(params, "query")
        val createdAfter = parseInstant(params, "createdAfter")
        val createdBefore = parseInstant(params, "createdBefore")
        val modifiedAfter = parseInstant(params, "modifiedAfter")
        val modifiedBefore = parseInstant(params, "modifiedBefore")
        val roleChangedAfter = parseInstant(params, "roleChangedAfter")
        val roleChangedBefore = parseInstant(params, "roleChangedBefore")
        val sortBy = optionalString(params, "sortBy")
        val sortOrder = optionalString(params, "sortOrder")
        val limit = optionalInt(params, "limit") ?: 50

        // Parse role
        val role = roleStr?.let {
            Role.fromString(it)
                ?: return errorResponse("Invalid role: $it. Valid roles: ${Role.VALID_NAMES.joinToString()}", ErrorCodes.VALIDATION_ERROR)
        }

        // Parse priority
        val priority = priorityStr?.let {
            Priority.fromString(it)
                ?: return errorResponse("Invalid priority: $it. Valid values: high, medium, low", ErrorCodes.VALIDATION_ERROR)
        }

        // Parse tags
        val tags = tagsStr?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }

        return when (val result = context.workItemRepository().findByFilters(
            parentId = parentId,
            depth = depth,
            role = role,
            priority = priority,
            tags = tags,
            query = query,
            createdAfter = createdAfter,
            createdBefore = createdBefore,
            modifiedAfter = modifiedAfter,
            modifiedBefore = modifiedBefore,
            roleChangedAfter = roleChangedAfter,
            roleChangedBefore = roleChangedBefore,
            sortBy = sortBy,
            sortOrder = sortOrder,
            limit = limit
        )) {
            is Result.Success -> {
                val items = result.data
                val data = buildJsonObject {
                    put("items", JsonArray(items.map { workItemToMinimalJson(it) }))
                    put("total", JsonPrimitive(items.size))
                }
                successResponse(data)
            }
            is Result.Error -> errorResponse(
                result.error.message,
                ErrorCodes.DATABASE_ERROR
            )
        }
    }

    // ──────────────────────────────────────────────
    // Operation: overview
    // ──────────────────────────────────────────────

    private suspend fun executeOverview(params: JsonElement, context: ToolExecutionContext): JsonElement {
        val itemId = extractUUID(params, "itemId", required = false)

        return if (itemId != null) {
            executeScopedOverview(itemId, context)
        } else {
            executeGlobalOverview(params, context)
        }
    }

    private suspend fun executeScopedOverview(itemId: java.util.UUID, context: ToolExecutionContext): JsonElement {
        // Fetch the item itself
        val item = when (val result = context.workItemRepository().getById(itemId)) {
            is Result.Success -> result.data
            is Result.Error -> return errorResponse(
                result.error.message,
                ErrorCodes.RESOURCE_NOT_FOUND
            )
        }

        // Count children by role
        val childCounts = when (val result = context.workItemRepository().countChildrenByRole(itemId)) {
            is Result.Success -> result.data
            is Result.Error -> return errorResponse(
                result.error.message,
                ErrorCodes.DATABASE_ERROR
            )
        }

        // Fetch direct children
        val children = when (val result = context.workItemRepository().findChildren(itemId)) {
            is Result.Success -> result.data
            is Result.Error -> return errorResponse(
                result.error.message,
                ErrorCodes.DATABASE_ERROR
            )
        }

        val data = buildJsonObject {
            put("item", workItemToJson(item))
            put("childCounts", roleCounToJson(childCounts))
            put("children", JsonArray(children.map { workItemToMinimalJson(it) }))
        }

        return successResponse(data)
    }

    private suspend fun executeGlobalOverview(params: JsonElement, context: ToolExecutionContext): JsonElement {
        val limit = optionalInt(params, "limit") ?: 20

        // Fetch root items
        val rootItems = when (val result = context.workItemRepository().findRootItems(limit)) {
            is Result.Success -> result.data
            is Result.Error -> return errorResponse(
                result.error.message,
                ErrorCodes.DATABASE_ERROR
            )
        }

        // For each root item, get child counts by role
        val itemsWithCounts = rootItems.map { item ->
            val childCounts = when (val result = context.workItemRepository().countChildrenByRole(item.id)) {
                is Result.Success -> result.data
                is Result.Error -> emptyMap()
            }

            buildJsonObject {
                put("id", JsonPrimitive(item.id.toString()))
                put("title", JsonPrimitive(item.title))
                put("role", JsonPrimitive(item.role.name.lowercase()))
                put("priority", JsonPrimitive(item.priority.name.lowercase()))
                put("childCounts", roleCounToJson(childCounts))
            }
        }

        val data = buildJsonObject {
            put("items", JsonArray(itemsWithCounts))
            put("total", JsonPrimitive(rootItems.size))
        }

        return successResponse(data)
    }

    // ──────────────────────────────────────────────
    // JSON serialization helpers
    // ──────────────────────────────────────────────

    private fun workItemToJson(item: WorkItem): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(item.id.toString()))
        item.parentId?.let { put("parentId", JsonPrimitive(it.toString())) }
        put("title", JsonPrimitive(item.title))
        item.description?.let { put("description", JsonPrimitive(it)) }
        put("summary", JsonPrimitive(item.summary))
        put("role", JsonPrimitive(item.role.name.lowercase()))
        item.statusLabel?.let { put("statusLabel", JsonPrimitive(it)) }
        item.previousRole?.let { put("previousRole", JsonPrimitive(it.name.lowercase())) }
        put("priority", JsonPrimitive(item.priority.name.lowercase()))
        put("complexity", JsonPrimitive(item.complexity))
        put("depth", JsonPrimitive(item.depth))
        item.metadata?.let { put("metadata", JsonPrimitive(it)) }
        item.tags?.let { put("tags", JsonPrimitive(it)) }
        put("createdAt", JsonPrimitive(item.createdAt.toString()))
        put("modifiedAt", JsonPrimitive(item.modifiedAt.toString()))
        put("roleChangedAt", JsonPrimitive(item.roleChangedAt.toString()))
    }

    private fun workItemToMinimalJson(item: WorkItem): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(item.id.toString()))
        item.parentId?.let { put("parentId", JsonPrimitive(it.toString())) }
        put("title", JsonPrimitive(item.title))
        put("role", JsonPrimitive(item.role.name.lowercase()))
        put("priority", JsonPrimitive(item.priority.name.lowercase()))
        put("depth", JsonPrimitive(item.depth))
        item.tags?.let { put("tags", JsonPrimitive(it)) }
    }

    private fun roleCounToJson(counts: Map<Role, Int>): JsonObject = buildJsonObject {
        for (role in Role.entries) {
            put(role.name.lowercase(), JsonPrimitive(counts[role] ?: 0))
        }
    }
}
