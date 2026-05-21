package io.github.jpicklyk.mcptask.current.application.tools.dependency

import io.github.jpicklyk.mcptask.current.application.tools.*
import io.github.jpicklyk.mcptask.current.domain.model.BacklinkRow
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*
import java.util.LinkedList
import java.util.UUID

/**
 * Read-only MCP tool for querying dependency relationships between WorkItems.
 *
 * Supports directional filtering (incoming/outgoing/all), type filtering,
 * optional WorkItem detail inclusion, and BFS graph traversal.
 */
class QueryDependenciesTool : BaseToolDefinition() {
    override val name = "query_dependencies"

    override val description =
        """
Read-only dependency queries with filtering support.

## Operations

### get (default)
Query outgoing/incoming/all dependencies for a WorkItem.

Parameters:
- itemId (required UUID): WorkItem to query dependencies for
- direction (optional): "incoming", "outgoing", "all" (default: "all")
  - incoming: deps where this item is the toItemId (things that block this item)
  - outgoing: deps where this item is the fromItemId (things this item blocks)
  - all: both directions
- type (optional): filter by dependency type — "BLOCKS", "IS_BLOCKED_BY", "RELATES_TO"
- includeItemInfo (optional boolean, default false): include WorkItem details (title, role, priority)
- neighborsOnly (optional boolean, default true): when false, perform BFS graph traversal

Returns dependencies with counts breakdown and optional graph traversal data.

### backlinks
Find all items that reference (point at) the given item — i.e., reverse-direction edges.
Practical use: "find all items that block REQ-42", "what references FEAT-7?".
A backlink row means another item has an edge with toItemId = your itemId.

Parameters:
- itemId (required UUID): target item to find backlinks for
- type (optional): narrow to one dependency type — "BLOCKS", "IS_BLOCKED_BY", "RELATES_TO"

Returns: { backlinks: [{ fromItemId, type, fromTitle }], total: N }
        """.trimIndent()

    override val category = ToolCategory.DEPENDENCY_MANAGEMENT

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
                            put(
                                "description",
                                JsonPrimitive(
                                    "Operation: \"get\" (default, query outgoing/incoming/all deps) or " +
                                        "\"backlinks\" (find items pointing AT the given item)"
                                )
                            )
                            put("enum", JsonArray(listOf("get", "backlinks").map { JsonPrimitive(it) }))
                        }
                    )
                    put(
                        "itemId",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "WorkItem UUID or hex prefix (4+ chars). " +
                                        "For 'get': query deps for this item. " +
                                        "For 'backlinks': the item whose incoming edges you want to find " +
                                        "(i.e., other items that point AT this item)."
                                )
                            )
                        }
                    )
                    put(
                        "direction",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("(get only) Direction filter: incoming, outgoing, all (default: all)"))
                            put("enum", JsonArray(listOf("incoming", "outgoing", "all").map { JsonPrimitive(it) }))
                        }
                    )
                    put(
                        "type",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Filter by dependency type: BLOCKS, IS_BLOCKED_BY, RELATES_TO"))
                            put("enum", JsonArray(listOf("BLOCKS", "IS_BLOCKED_BY", "RELATES_TO").map { JsonPrimitive(it) }))
                        }
                    )
                    put(
                        "includeItemInfo",
                        buildJsonObject {
                            put("type", JsonPrimitive("boolean"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "(get only) Include WorkItem details (title, role, priority) for related items (default: false)"
                                )
                            )
                        }
                    )
                    put(
                        "neighborsOnly",
                        buildJsonObject {
                            put("type", JsonPrimitive("boolean"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "(get only) When false, perform BFS graph traversal returning chain and depth (default: true)"
                                )
                            )
                        }
                    )
                },
            required = listOf("operation", "itemId")
        )

    override fun validateParams(params: JsonElement) {
        val operation = requireString(params, "operation")
        if (operation !in listOf("get", "backlinks")) {
            throw ToolValidationException("Invalid operation: $operation. Must be one of: get, backlinks")
        }

        validateIdOrPrefix(params, "itemId", required = true)

        val type = optionalString(params, "type")
        if (type != null) {
            DependencyType.fromString(type)
                ?: throw ToolValidationException("Invalid type: $type. Must be one of: BLOCKS, IS_BLOCKED_BY, RELATES_TO")
        }

        if (operation == "get") {
            val direction = optionalString(params, "direction")
            if (direction != null && direction !in listOf("incoming", "outgoing", "all")) {
                throw ToolValidationException("Invalid direction: $direction. Must be one of: incoming, outgoing, all")
            }
        }
    }

    override suspend fun execute(
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement {
        val operation = optionalString(params, "operation") ?: "get"

        return when (operation) {
            "backlinks" -> executeBacklinks(params, context)
            else -> executeGet(params, context)
        }
    }

    private suspend fun executeBacklinks(
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement {
        val (resolvedItemId, idError) = resolveItemId(params, "itemId", context)
        if (idError != null) return idError
        val itemId = resolvedItemId!!

        val typeFilter = optionalString(params, "type")?.let { DependencyType.fromString(it) }
        val depRepo = context.dependencyRepository()

        val rows: List<BacklinkRow> = depRepo.backlinks(itemId, typeFilter)

        val backlinksArray =
            JsonArray(
                rows.map { row ->
                    buildJsonObject {
                        put("fromItemId", JsonPrimitive(row.fromItemId.toString()))
                        put("type", JsonPrimitive(row.type.name))
                        put("fromTitle", JsonPrimitive(row.fromTitle))
                    }
                }
            )

        val data =
            buildJsonObject {
                put("backlinks", backlinksArray)
                put("total", JsonPrimitive(rows.size))
            }

        return successResponse(data)
    }

    private suspend fun executeGet(
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement {
        val (resolvedItemId, idError) = resolveItemId(params, "itemId", context)
        if (idError != null) return idError
        val itemId = resolvedItemId!!
        val direction = optionalString(params, "direction") ?: "all"
        val typeFilter = optionalString(params, "type")?.let { DependencyType.fromString(it) }
        val includeItemInfo = optionalBoolean(params, "includeItemInfo", defaultValue = false)
        val neighborsOnly = optionalBoolean(params, "neighborsOnly", defaultValue = true)

        // Fetch dependencies by direction (non-suspend calls)
        val depRepo = context.dependencyRepository()
        val allDeps =
            when (direction) {
                "incoming" -> depRepo.findByToItemId(itemId)
                "outgoing" -> depRepo.findByFromItemId(itemId)
                "all" -> depRepo.findByItemId(itemId)
                else -> return errorResponse("Invalid direction: $direction", ErrorCodes.VALIDATION_ERROR)
            }

        // Apply type filter
        val filteredDeps =
            if (typeFilter != null) {
                allDeps.filter { it.type == typeFilter }
            } else {
                allDeps
            }

        // Build dependency JSON array, optionally including item info
        val depJsonArray =
            JsonArray(
                filteredDeps.map { dep ->
                    buildDependencyJson(dep, includeItemInfo, context)
                }
            )

        // Compute counts breakdown from the full (unfiltered by type) dep list for this item
        val allDepsForCounts = if (direction == "all") allDeps else depRepo.findByItemId(itemId)
        val incomingCount = allDepsForCounts.count { it.toItemId == itemId && it.type != DependencyType.RELATES_TO }
        val outgoingCount = allDepsForCounts.count { it.fromItemId == itemId && it.type != DependencyType.RELATES_TO }
        val relatesToCount = allDepsForCounts.count { it.type == DependencyType.RELATES_TO }

        val countsJson =
            buildJsonObject {
                put("incoming", JsonPrimitive(incomingCount))
                put("outgoing", JsonPrimitive(outgoingCount))
                put("relatesTo", JsonPrimitive(relatesToCount))
            }

        // Build response
        val data =
            buildJsonObject {
                put("dependencies", depJsonArray)
                put("counts", countsJson)

                // Graph traversal if requested
                if (!neighborsOnly) {
                    put("graph", buildGraphJson(itemId, depRepo))
                }
            }

        return successResponse(data)
    }

    override fun userSummary(
        params: JsonElement,
        result: JsonElement,
        isError: Boolean
    ): String {
        if (isError) return "Dependency query failed"

        val operation = optionalString(params, "operation") ?: "get"
        val data = (result as? JsonObject)?.get("data") as? JsonObject

        return if (operation == "backlinks") {
            val count = (data?.get("total") as? JsonPrimitive)?.intOrNull ?: 0
            "Found $count backlink${if (count == 1) "" else "s"}"
        } else {
            val deps = data?.get("dependencies") as? JsonArray
            val count = deps?.size ?: 0
            "Found $count dependenc${if (count == 1) "y" else "ies"}"
        }
    }

    // ──────────────────────────────────────────────
    // Helper methods
    // ──────────────────────────────────────────────

    private suspend fun buildDependencyJson(
        dep: Dependency,
        includeItemInfo: Boolean,
        context: ToolExecutionContext
    ): JsonObject =
        buildJsonObject {
            put("id", JsonPrimitive(dep.id.toString()))
            put("fromItemId", JsonPrimitive(dep.fromItemId.toString()))
            put("toItemId", JsonPrimitive(dep.toItemId.toString()))
            put("type", JsonPrimitive(dep.type.name))
            dep.unblockAt?.let { put("unblockAt", JsonPrimitive(it)) }
            val effectiveRole = dep.effectiveUnblockRole()
            if (effectiveRole != null) {
                put("effectiveUnblockRole", JsonPrimitive(effectiveRole))
            }

            if (includeItemInfo) {
                // Fetch "from" item details
                when (val fromResult = context.workItemRepository().getById(dep.fromItemId)) {
                    is Result.Success -> {
                        val item = fromResult.data
                        put(
                            "fromItem",
                            buildJsonObject {
                                put("title", JsonPrimitive(item.title))
                                put("role", JsonPrimitive(item.role.toJsonString()))
                                put("priority", JsonPrimitive(item.priority.toJsonString()))
                            }
                        )
                    }
                    is Result.Error -> { /* item may have been deleted; skip */ }
                }

                // Fetch "to" item details
                when (val toResult = context.workItemRepository().getById(dep.toItemId)) {
                    is Result.Success -> {
                        val item = toResult.data
                        put(
                            "toItem",
                            buildJsonObject {
                                put("title", JsonPrimitive(item.title))
                                put("role", JsonPrimitive(item.role.toJsonString()))
                                put("priority", JsonPrimitive(item.priority.toJsonString()))
                            }
                        )
                    }
                    is Result.Error -> { /* item may have been deleted; skip */ }
                }
            }
        }

    /**
     * Performs frontier-based BFS traversal from the given item following BLOCKS edges in both directions.
     * Uses batch queries (findByItemIds) to eliminate N+1 query overhead.
     * Returns a topologically-ordered chain of item IDs and the maximum depth.
     */
    private fun buildGraphJson(
        startItemId: UUID,
        depRepo: DependencyRepository
    ): JsonObject {
        val visited = mutableSetOf<UUID>()
        val edges = mutableListOf<Pair<UUID, UUID>>()

        visited.add(startItemId)
        var frontier = setOf(startItemId)

        while (frontier.isNotEmpty()) {
            val depsByItem = depRepo.findByItemIds(frontier)
            val nextFrontier = mutableSetOf<UUID>()

            for (current in frontier) {
                val deps = depsByItem[current] ?: emptyList()
                for (dep in deps) {
                    if (dep.type == DependencyType.RELATES_TO) continue
                    val (from, to) =
                        when (dep.type) {
                            DependencyType.BLOCKS -> dep.fromItemId to dep.toItemId
                            DependencyType.IS_BLOCKED_BY -> dep.toItemId to dep.fromItemId
                            DependencyType.RELATES_TO -> continue
                        }
                    edges.add(from to to)
                    val neighbor = if (current == dep.fromItemId) dep.toItemId else dep.fromItemId
                    if (neighbor !in visited) {
                        visited.add(neighbor)
                        nextFrontier.add(neighbor)
                    }
                }
            }
            frontier = nextFrontier
        }

        val chain = topologicalSort(visited, edges)
        val depth = computeMaxDepth(chain, edges)
        return buildJsonObject {
            put("chain", JsonArray(chain.map { JsonPrimitive(it.toString()) }))
            put("depth", JsonPrimitive(depth))
        }
    }

    /**
     * Kahn's algorithm for topological sort.
     * Falls back to insertion order if the graph has issues.
     */
    private fun topologicalSort(
        nodes: Set<UUID>,
        edges: List<Pair<UUID, UUID>>
    ): List<UUID> {
        val inDegree = mutableMapOf<UUID, Int>()
        val adjacency = mutableMapOf<UUID, MutableList<UUID>>()

        for (node in nodes) {
            inDegree[node] = 0
            adjacency[node] = mutableListOf()
        }

        for ((from, to) in edges) {
            if (from in nodes && to in nodes) {
                adjacency.getOrPut(from) { mutableListOf() }.add(to)
                inDegree[to] = (inDegree[to] ?: 0) + 1
            }
        }

        // Deduplicate edges in adjacency lists
        for ((key, list) in adjacency) {
            adjacency[key] = list.distinct().toMutableList()
        }

        // Recompute in-degree after deduplication
        for (node in nodes) {
            inDegree[node] = 0
        }
        for ((_, targets) in adjacency) {
            for (target in targets) {
                inDegree[target] = (inDegree[target] ?: 0) + 1
            }
        }

        val queue: LinkedList<UUID> = LinkedList()
        for (node in nodes) {
            if ((inDegree[node] ?: 0) == 0) {
                queue.add(node)
            }
        }

        val result = mutableListOf<UUID>()
        while (queue.isNotEmpty()) {
            val node = queue.poll()
            result.add(node)
            for (neighbor in adjacency[node] ?: emptyList()) {
                val newDegree = (inDegree[neighbor] ?: 1) - 1
                inDegree[neighbor] = newDegree
                if (newDegree == 0) {
                    queue.add(neighbor)
                }
            }
        }

        // If not all nodes were sorted (cycle), append remaining
        if (result.size < nodes.size) {
            for (node in nodes) {
                if (node !in result) {
                    result.add(node)
                }
            }
        }

        return result
    }

    /**
     * Computes maximum depth (longest path) in the DAG.
     */
    private fun computeMaxDepth(
        topoOrder: List<UUID>,
        edges: List<Pair<UUID, UUID>>
    ): Int {
        if (topoOrder.isEmpty()) return 0

        val adjacency = mutableMapOf<UUID, MutableSet<UUID>>()
        for ((from, to) in edges) {
            adjacency.getOrPut(from) { mutableSetOf() }.add(to)
        }

        val depth = mutableMapOf<UUID, Int>()
        for (node in topoOrder) {
            depth[node] = 0
        }

        for (node in topoOrder) {
            val currentDepth = depth[node] ?: 0
            for (neighbor in adjacency[node] ?: emptySet()) {
                val newDepth = currentDepth + 1
                if (newDepth > (depth[neighbor] ?: 0)) {
                    depth[neighbor] = newDepth
                }
            }
        }

        return depth.values.maxOrNull() ?: 0
    }
}
