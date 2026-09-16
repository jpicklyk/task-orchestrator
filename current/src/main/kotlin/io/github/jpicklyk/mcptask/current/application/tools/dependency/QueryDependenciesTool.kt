package io.github.jpicklyk.mcptask.current.application.tools.dependency

import io.github.jpicklyk.mcptask.current.application.tools.*
import io.github.jpicklyk.mcptask.current.domain.model.BacklinkRow
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
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
    companion object {
        /**
         * Hard cap on distinct nodes visited during BFS graph traversal ([buildGraphJson]).
         * Public with the same visibility convention as
         * [io.github.jpicklyk.mcptask.current.domain.repository.MAX_TRAVERSAL_DEPTH] so a
         * boundary test can import it. On hit, traversal stops early and the response sets
         * `graph.truncated = true` instead of failing — this is a read-only query tool, not a
         * cascade/subtree-mutation path, so a soft truncation signal is used instead of the
         * hard `Result.Error` that `findDescendants` returns.
         */
        const val MAX_DEPENDENCY_GRAPH_NODES: Int = 1000
    }

    override val name = "query_dependencies"

    override val description =
        """
Read-only dependency queries with filtering support.

Operations: get, backlinks

**get** — query outgoing/incoming/all dependencies for a WorkItem. `direction=incoming` means deps
where this item is the toItemId (things blocking it); `outgoing` means it's the fromItemId (things
it blocks); `all` is both. Returns a counts breakdown and, optionally, BFS graph traversal (see
`neighborsOnly`).

**backlinks** — find items that reference (point at) the given item, i.e. reverse-direction edges:
a backlink row means another item has an edge with toItemId = your itemId. E.g. "what blocks REQ-42?".
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
                                    "WorkItem UUID or hex prefix (4+ chars). For 'get': the item to query deps for. " +
                                        "For 'backlinks': the item whose incoming edges to find."
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
                    put(
                        "limit",
                        buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "(get only) Maximum number of dependency edges to return, applied after type filtering " +
                                        "(default: unbounded — all matching edges returned). Must be >= 1."
                                )
                            )
                        }
                    )
                    put(
                        "offset",
                        buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "(get only) Number of matching edges to skip before applying limit (default: 0). Must be >= 0."
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

            val limitVal = optionalInt(params, "limit")
            if (limitVal != null && limitVal < 1) {
                throw ToolValidationException("limit must be at least 1")
            }
            val offsetVal = optionalInt(params, "offset")
            if (offsetVal != null && offsetVal < 0) {
                throw ToolValidationException("offset must be non-negative")
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
        val limitParam = optionalInt(params, "limit")
        val offsetParam = optionalInt(params, "offset")
        val effectiveOffset = offsetParam ?: 0

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

        // Apply limit/offset paging. Opt-in: when neither param is supplied, `pagedDeps` is
        // exactly `filteredDeps` and the response omits the paging block entirely, keeping the
        // pre-fix (unbounded) response byte-identical for callers that never asked to page.
        val pagedDeps =
            filteredDeps
                .drop(effectiveOffset)
                .let { if (limitParam != null) it.take(limitParam) else it }

        // Batch-fetch related WorkItem info in a single findByIds call, eliminating the prior
        // 2-getById-per-edge N+1 pattern. Fetched only for the page actually returned.
        val itemInfoMap: Map<UUID, WorkItem> =
            if (includeItemInfo && pagedDeps.isNotEmpty()) {
                val relatedIds = mutableSetOf<UUID>()
                pagedDeps.forEach {
                    relatedIds.add(it.fromItemId)
                    relatedIds.add(it.toItemId)
                }
                when (val result = context.workItemRepository().findByIds(relatedIds)) {
                    is Result.Success -> result.data.associateBy { it.id }
                    is Result.Error -> emptyMap()
                }
            } else {
                emptyMap()
            }

        // Build dependency JSON array, optionally including item info
        val depJsonArray =
            JsonArray(
                pagedDeps.map { dep ->
                    buildDependencyJson(dep, includeItemInfo, itemInfoMap)
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

                // Paging metadata only appears when the caller opted into limit/offset — keeps
                // the default (unpaged) response byte-identical to the pre-fix shape.
                if (limitParam != null || offsetParam != null) {
                    put("total", JsonPrimitive(filteredDeps.size))
                    put("limit", limitParam?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("offset", JsonPrimitive(effectiveOffset))
                }

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

    private fun buildDependencyJson(
        dep: Dependency,
        includeItemInfo: Boolean,
        itemInfoMap: Map<UUID, WorkItem>
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
                // "from"/"to" item details, looked up from the batch findByIds() map built once
                // per executeGet() call. An item that has been deleted since the dependency was
                // created is simply absent from the map (findByIds omits missing ids per its
                // KDoc) — the field is skipped, matching the prior getById-per-edge behavior.
                itemInfoMap[dep.fromItemId]?.let { item ->
                    put(
                        "fromItem",
                        buildJsonObject {
                            put("title", JsonPrimitive(item.title))
                            put("role", JsonPrimitive(item.role.toJsonString()))
                            put("priority", JsonPrimitive(item.priority.toJsonString()))
                        }
                    )
                }

                itemInfoMap[dep.toItemId]?.let { item ->
                    put(
                        "toItem",
                        buildJsonObject {
                            put("title", JsonPrimitive(item.title))
                            put("role", JsonPrimitive(item.role.toJsonString()))
                            put("priority", JsonPrimitive(item.priority.toJsonString()))
                        }
                    )
                }
            }
        }

    /**
     * Performs frontier-based BFS traversal from the given item following BLOCKS edges in both directions.
     * Uses batch queries (findByItemIds) to eliminate N+1 query overhead.
     * Returns a topologically-ordered chain of item IDs and the maximum depth.
     *
     * Visited-node count is capped at [MAX_DEPENDENCY_GRAPH_NODES]. On hit, traversal stops
     * expanding further and `truncated` is set true in the returned JSON — a soft signal (like
     * `AncestorChain.truncated`), not a thrown error, since this is a read-only query tool.
     *
     * `suspend` because [DependencyRepository.findByItemIds] becomes suspend under 33e96efd
     * (this stream only marks the caller; the callee's own suspend modifier is that stream's edit).
     */
    private suspend fun buildGraphJson(
        startItemId: UUID,
        depRepo: DependencyRepository
    ): JsonObject {
        val visited = mutableSetOf<UUID>()
        val edges = mutableListOf<Pair<UUID, UUID>>()
        var truncated = false

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
                        if (visited.size >= MAX_DEPENDENCY_GRAPH_NODES) {
                            truncated = true
                        } else {
                            visited.add(neighbor)
                            nextFrontier.add(neighbor)
                        }
                    }
                }
            }
            if (truncated) break
            frontier = nextFrontier
        }

        val chain = topologicalSort(visited, edges)
        val depth = computeMaxDepth(chain, edges)
        return buildJsonObject {
            put("chain", JsonArray(chain.map { JsonPrimitive(it.toString()) }))
            put("depth", JsonPrimitive(depth))
            put("truncated", JsonPrimitive(truncated))
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
