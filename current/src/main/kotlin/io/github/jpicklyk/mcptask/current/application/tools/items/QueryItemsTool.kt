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

    override val description =
        """
Unified read operations for work items.

Operations: get, search, overview

**get** - Retrieve a single item by ID or short prefix
- Required: id (UUID or hex prefix, minimum 4 characters)
- Full 36-char UUID: exact match (existing behavior)
- Short hex prefix (4-35 chars): resolves to unique item; errors if ambiguous or not found
- Returns full item JSON
- includeAncestors (boolean, default false): When true, each item includes an `ancestors` array showing the full path from root to direct parent

**search** - Filter items with multiple criteria
- Optional: parentId, depth, role, priority, tags, query, createdAfter/Before, modifiedAfter/Before, sortBy, sortOrder, limit, offset
- Returns minimal fields: id, parentId, title, role, priority, depth, tags
- Wrapped in { items: [...], total: N, returned: N, limit: N, offset: N }
- total is the full count of all matching rows (regardless of limit/offset)
- returned is the count of items in this response page
- Use offset with limit for pagination (e.g., offset=50&limit=50 for page 2)
- includeAncestors (boolean, default false): When true, each item includes an `ancestors` array showing the full path from root to direct parent

**overview** - Hierarchical summary view
- With itemId: Item metadata + child counts by role + direct children list
- Without itemId: Global overview of all root items with per-root child counts
- Default limit: 20 root items
- includeChildren (boolean, default false): When true (global overview only), each root item includes a `children` array of its direct child items
        """.trimIndent()

    override val category = ToolCategory.ITEM_MANAGEMENT

    override val toolAnnotations =
        ToolAnnotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false
        )

    override val parameterSchema =
        ToolSchema(
            properties =
                buildJsonObject {
                    put(
                        "operation",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Operation: get, search, overview"))
                            put("enum", JsonArray(listOf("get", "search", "overview").map { JsonPrimitive(it) }))
                        }
                    )
                    put(
                        "id",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Item UUID or hex prefix (minimum 4 characters)"))
                        }
                    )
                    put(
                        "itemId",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Item UUID or hex prefix (4+ chars) for scoped overview"))
                        }
                    )
                    put(
                        "parentId",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Filter by parent ID (UUID or hex prefix 4+ chars)"))
                        }
                    )
                    put(
                        "depth",
                        buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put("description", JsonPrimitive("Filter by depth level"))
                        }
                    )
                    put(
                        "role",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Filter by role: queue, work, review, blocked, terminal"))
                        }
                    )
                    put(
                        "priority",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Filter by priority: high, medium, low"))
                        }
                    )
                    put(
                        "tags",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Comma-separated tags filter (OR logic)"))
                        }
                    )
                    put(
                        "query",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Text search in title and summary"))
                        }
                    )
                    put(
                        "createdAfter",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("ISO 8601 timestamp filter"))
                        }
                    )
                    put(
                        "createdBefore",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("ISO 8601 timestamp filter"))
                        }
                    )
                    put(
                        "modifiedAfter",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("ISO 8601 timestamp filter"))
                        }
                    )
                    put(
                        "modifiedBefore",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("ISO 8601 timestamp filter"))
                        }
                    )
                    put(
                        "roleChangedAfter",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("ISO 8601 timestamp — items whose role changed after this time"))
                        }
                    )
                    put(
                        "roleChangedBefore",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("ISO 8601 timestamp — items whose role changed before this time"))
                        }
                    )
                    put(
                        "sortBy",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Sort field: title, priority, complexity, createdAt, modifiedAt"))
                        }
                    )
                    put(
                        "sortOrder",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Sort order: asc, desc (default: desc)"))
                        }
                    )
                    put(
                        "limit",
                        buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put("description", JsonPrimitive("Max results (default: 50)"))
                        }
                    )
                    put(
                        "offset",
                        buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put("description", JsonPrimitive("Number of items to skip (for pagination). Use with limit. Default: 0."))
                        }
                    )
                    put(
                        "includeAncestors",
                        buildJsonObject {
                            put("type", JsonPrimitive("boolean"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "When true, each item in search/get results includes an `ancestors` array (get and search operations only)"
                                )
                            )
                        }
                    )
                    put(
                        "includeChildren",
                        buildJsonObject {
                            put("type", JsonPrimitive("boolean"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "When true, each root item in global overview includes a `children` array of direct child items (overview operation, global mode only)"
                                )
                            )
                        }
                    )
                    put(
                        "type",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Filter by type identifier (exact match)"))
                        }
                    )
                },
            required = listOf("operation")
        )

    override fun validateParams(params: JsonElement) {
        val operation = requireString(params, "operation")
        when (operation) {
            "get" -> validateIdOrPrefix(params, "id")
            "search", "overview" -> { /* all parameters are optional */ }
            else -> throw ToolValidationException("Invalid operation: $operation. Must be one of: get, search, overview")
        }
    }

    override suspend fun execute(
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement {
        val operation = requireString(params, "operation")
        return when (operation) {
            "get" -> executeGet(params, context)
            "search" -> executeSearch(params, context)
            "overview" -> executeOverview(params, context)
            else ->
                errorResponse(
                    "Invalid operation: $operation",
                    ErrorCodes.VALIDATION_ERROR
                )
        }
    }

    override fun userSummary(
        params: JsonElement,
        result: JsonElement,
        isError: Boolean
    ): String {
        if (isError) {
            val msg =
                (result as? JsonObject)
                    ?.get("error")
                    ?.let { (it as? JsonObject)?.get("message") }
                    ?.let { (it as? JsonPrimitive)?.content }
            return if (msg != null) "Query failed: $msg" else "Query failed"
        }

        val paramsObj = params as? JsonObject
        val operation =
            paramsObj?.get("operation")?.let {
                if (it is JsonPrimitive && it.isString) it.content else null
            } ?: return "Query completed"

        val data = (result as? JsonObject)?.get("data") as? JsonObject

        return when (operation) {
            "get" -> {
                val title =
                    data?.get("title")?.let {
                        if (it is JsonPrimitive && it.isString) it.content else null
                    } ?: "unknown"
                val id =
                    data?.get("id")?.let {
                        if (it is JsonPrimitive && it.isString) it.content.take(8) else null
                    } ?: "?"
                "Item: $title ($id)"
            }
            "search" -> {
                val total =
                    data?.get("total")?.let {
                        if (it is JsonPrimitive) it.intOrNull else null
                    } ?: 0
                "Found $total item(s)"
            }
            "overview" -> {
                val title =
                    data?.get("item")?.let { itemObj ->
                        (itemObj as? JsonObject)?.get("title")?.let {
                            if (it is JsonPrimitive && it.isString) it.content else null
                        }
                    }
                if (title != null) {
                    val children =
                        data?.get("children")?.let {
                            (it as? JsonArray)?.size
                        } ?: 0
                    "Overview: $title -- $children children"
                } else {
                    val total =
                        data?.get("total")?.let {
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

    private suspend fun executeGet(
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement {
        val (resolvedId, idError) = resolveItemId(params, "id", context)
        if (idError != null) return idError
        val itemId = resolvedId!!
        val includeAncestors = optionalBoolean(params, "includeAncestors", false)

        val item =
            when (val result = context.workItemRepository().getById(itemId)) {
                is Result.Success -> result.data
                is Result.Error -> return errorResponse(
                    "WorkItem not found",
                    ErrorCodes.RESOURCE_NOT_FOUND,
                    additionalData =
                        buildJsonObject {
                            put("requestedId", JsonPrimitive(itemId.toString()))
                        }
                )
            }

        val itemJson = item.toFullJson()

        return if (includeAncestors) {
            val chains = context.workItemRepository().findAncestorChains(setOf(item.id))
            val ancestors = (chains as? Result.Success)?.data?.get(item.id) ?: emptyList()
            val enriched =
                buildJsonObject {
                    itemJson.forEach { (k, v) -> put(k, v) }
                    put("ancestors", buildAncestorsArray(ancestors))
                }
            successResponse(enriched)
        } else {
            successResponse(itemJson)
        }
    }

    // ──────────────────────────────────────────────
    // Operation: search
    // ──────────────────────────────────────────────

    private suspend fun executeSearch(
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement {
        val (parentId, parentIdError) = resolveItemId(params, "parentId", context, required = false)
        if (parentIdError != null) return parentIdError
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
        val typeFilter = optionalString(params, "type")
        val sortBy = optionalString(params, "sortBy")
        val sortOrder = optionalString(params, "sortOrder")
        val limit = optionalInt(params, "limit") ?: 50
        val offset = (optionalInt(params, "offset") ?: 0).coerceAtLeast(0)
        val includeAncestors = optionalBoolean(params, "includeAncestors", false)

        // Validate time ranges — reject inverted ranges early
        if (createdAfter != null && createdBefore != null && createdAfter > createdBefore) {
            return errorResponse("createdAfter must be before createdBefore", ErrorCodes.VALIDATION_ERROR)
        }
        if (modifiedAfter != null && modifiedBefore != null && modifiedAfter > modifiedBefore) {
            return errorResponse("modifiedAfter must be before modifiedBefore", ErrorCodes.VALIDATION_ERROR)
        }
        if (roleChangedAfter != null && roleChangedBefore != null && roleChangedAfter > roleChangedBefore) {
            return errorResponse("roleChangedAfter must be before roleChangedBefore", ErrorCodes.VALIDATION_ERROR)
        }

        // Parse role
        val role =
            roleStr?.let {
                Role.fromString(it)
                    ?: return errorResponse(
                        "Invalid role: $it. Valid roles: ${Role.VALID_NAMES.joinToString()}",
                        ErrorCodes.VALIDATION_ERROR
                    )
            }

        // Parse priority
        val priority =
            priorityStr?.let {
                Priority.fromString(it)
                    ?: return errorResponse("Invalid priority: $it. Valid values: high, medium, low", ErrorCodes.VALIDATION_ERROR)
            }

        // Parse tags
        val tags = tagsStr?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }

        // Get full match count (no limit/offset) for accurate pagination metadata
        val totalCount =
            when (
                val countResult =
                    context.workItemRepository().countByFilters(
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
                        type = typeFilter
                    )
            ) {
                is Result.Success -> countResult.data
                is Result.Error -> return errorResponse(countResult.error.message, ErrorCodes.DATABASE_ERROR)
            }

        return when (
            val result =
                context.workItemRepository().findByFilters(
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
                    limit = limit,
                    offset = offset,
                    type = typeFilter
                )
        ) {
            is Result.Success -> {
                val items = result.data

                val chains: Map<java.util.UUID, List<WorkItem>> =
                    if (includeAncestors && items.isNotEmpty()) {
                        val chainResult = context.workItemRepository().findAncestorChains(items.map { it.id }.toSet())
                        (chainResult as? Result.Success)?.data ?: emptyMap()
                    } else {
                        emptyMap()
                    }

                val data =
                    buildJsonObject {
                        put(
                            "items",
                            JsonArray(
                                items.map { item ->
                                    if (includeAncestors) {
                                        val ancestors = chains[item.id] ?: emptyList()
                                        val minimalJson = item.toMinimalJson()
                                        buildJsonObject {
                                            minimalJson.forEach { (k, v) -> put(k, v) }
                                            put("ancestors", buildAncestorsArray(ancestors))
                                        }
                                    } else {
                                        item.toMinimalJson()
                                    }
                                }
                            )
                        )
                        put("total", JsonPrimitive(totalCount))
                        put("returned", JsonPrimitive(items.size))
                        put("limit", JsonPrimitive(limit))
                        put("offset", JsonPrimitive(offset))
                    }
                successResponse(data)
            }
            is Result.Error ->
                errorResponse(
                    result.error.message,
                    ErrorCodes.DATABASE_ERROR
                )
        }
    }

    // ──────────────────────────────────────────────
    // Operation: overview
    // ──────────────────────────────────────────────

    private suspend fun executeOverview(
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement {
        val (itemId, itemIdError) = resolveItemId(params, "itemId", context, required = false)
        if (itemIdError != null) return itemIdError

        return if (itemId != null) {
            executeScopedOverview(itemId, context)
        } else {
            executeGlobalOverview(params, context)
        }
    }

    private suspend fun executeScopedOverview(
        itemId: java.util.UUID,
        context: ToolExecutionContext
    ): JsonElement {
        // Fetch the item itself
        val item =
            when (val result = context.workItemRepository().getById(itemId)) {
                is Result.Success -> result.data
                is Result.Error -> return errorResponse(
                    result.error.message,
                    ErrorCodes.RESOURCE_NOT_FOUND
                )
            }

        // Count children by role
        val childCounts =
            when (val result = context.workItemRepository().countChildrenByRole(itemId)) {
                is Result.Success -> result.data
                is Result.Error -> return errorResponse(
                    result.error.message,
                    ErrorCodes.DATABASE_ERROR
                )
            }

        // Fetch direct children
        val children =
            when (val result = context.workItemRepository().findChildren(itemId)) {
                is Result.Success -> result.data
                is Result.Error -> return errorResponse(
                    result.error.message,
                    ErrorCodes.DATABASE_ERROR
                )
            }

        val data =
            buildJsonObject {
                put("item", item.toFullJson())
                put("childCounts", roleCountToJson(childCounts))
                put(
                    "children",
                    JsonArray(
                        children.map { child ->
                            val grandchildCounts =
                                when (val result = context.workItemRepository().countChildrenByRole(child.id)) {
                                    is Result.Success -> result.data
                                    is Result.Error -> emptyMap()
                                }
                            val childTraits = PropertiesHelper.extractTraits(child.properties)
                            buildJsonObject {
                                child.toMinimalJson().forEach { (k, v) -> put(k, v) }
                                put("childCounts", roleCountToJson(grandchildCounts))
                                if (childTraits.isNotEmpty()) {
                                    put("traits", JsonArray(childTraits.map { JsonPrimitive(it) }))
                                }
                            }
                        }
                    )
                )
            }

        return successResponse(data)
    }

    private suspend fun executeGlobalOverview(
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement {
        val limit = optionalInt(params, "limit") ?: 20
        val includeChildren = params.jsonObject["includeChildren"]?.jsonPrimitive?.booleanOrNull ?: false

        // Fetch root items
        val rootItems =
            when (val result = context.workItemRepository().findRootItems(limit)) {
                is Result.Success -> result.data
                is Result.Error -> return errorResponse(
                    result.error.message,
                    ErrorCodes.DATABASE_ERROR
                )
            }

        // For each root item, get child counts by role and optionally children
        val itemsWithCounts =
            rootItems.map { item ->
                val childCounts =
                    when (val result = context.workItemRepository().countChildrenByRole(item.id)) {
                        is Result.Success -> result.data
                        is Result.Error -> emptyMap()
                    }

                buildJsonObject {
                    item.toMinimalJson().forEach { (k, v) -> put(k, v) }
                    val rootTraits = PropertiesHelper.extractTraits(item.properties)
                    if (rootTraits.isNotEmpty()) {
                        put("traits", JsonArray(rootTraits.map { JsonPrimitive(it) }))
                    }
                    put("childCounts", roleCountToJson(childCounts))
                    if (includeChildren) {
                        val children =
                            when (val result = context.workItemRepository().findChildren(item.id)) {
                                is Result.Success -> result.data
                                is Result.Error -> emptyList()
                            }
                        put(
                            "children",
                            JsonArray(
                                children.map { child ->
                                    val grandchildCounts =
                                        when (val result = context.workItemRepository().countChildrenByRole(child.id)) {
                                            is Result.Success -> result.data
                                            is Result.Error -> emptyMap()
                                        }
                                    val childTraits = PropertiesHelper.extractTraits(child.properties)
                                    buildJsonObject {
                                        child.toMinimalJson().forEach { (k, v) -> put(k, v) }
                                        put("childCounts", roleCountToJson(grandchildCounts))
                                        if (childTraits.isNotEmpty()) {
                                            put("traits", JsonArray(childTraits.map { JsonPrimitive(it) }))
                                        }
                                    }
                                }
                            )
                        )
                    }
                }
            }

        val data =
            buildJsonObject {
                put("items", JsonArray(itemsWithCounts))
                put("total", JsonPrimitive(rootItems.size))
            }

        return successResponse(data)
    }
}
