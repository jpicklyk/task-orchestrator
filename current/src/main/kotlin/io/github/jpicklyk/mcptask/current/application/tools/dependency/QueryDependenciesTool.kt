package io.github.jpicklyk.mcptask.current.application.tools.dependency

import io.github.jpicklyk.mcptask.current.application.tools.*
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
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

    override val description = """
Read-only dependency queries with filtering support.

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
    """.trimIndent()

    override val category = ToolCategory.DEPENDENCY_MANAGEMENT

    override val toolAnnotations = ToolAnnotations(
        readOnlyHint = true,
        destructiveHint = false,
        idempotentHint = true,
        openWorldHint = false
    )

    override val parameterSchema = ToolSchema(
        properties = buildJsonObject {
            put("itemId", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("WorkItem UUID to query dependencies for (required)"))
            })
            put("direction", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Direction filter: incoming, outgoing, all (default: all)"))
                put("enum", JsonArray(listOf("incoming", "outgoing", "all").map { JsonPrimitive(it) }))
            })
            put("type", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Filter by dependency type: BLOCKS, IS_BLOCKED_BY, RELATES_TO"))
                put("enum", JsonArray(listOf("BLOCKS", "IS_BLOCKED_BY", "RELATES_TO").map { JsonPrimitive(it) }))
            })
            put("includeItemInfo", buildJsonObject {
                put("type", JsonPrimitive("boolean"))
                put("description", JsonPrimitive("Include WorkItem details (title, role, priority) for related items (default: false)"))
            })
            put("neighborsOnly", buildJsonObject {
                put("type", JsonPrimitive("boolean"))
                put("description", JsonPrimitive("When false, perform BFS graph traversal returning chain and depth (default: true)"))
            })
        },
        required = listOf("itemId")
    )

    override fun validateParams(params: JsonElement) {
        extractUUID(params, "itemId", required = true)

        val direction = optionalString(params, "direction")
        if (direction != null && direction !in listOf("incoming", "outgoing", "all")) {
            throw ToolValidationException("Invalid direction: $direction. Must be one of: incoming, outgoing, all")
        }

        val type = optionalString(params, "type")
        if (type != null) {
            DependencyType.fromString(type)
                ?: throw ToolValidationException("Invalid type: $type. Must be one of: BLOCKS, IS_BLOCKED_BY, RELATES_TO")
        }
    }

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        val itemId = extractUUID(params, "itemId", required = true)!!
        val direction = optionalString(params, "direction") ?: "all"
        val typeFilter = optionalString(params, "type")?.let { DependencyType.fromString(it) }
        val includeItemInfo = optionalBoolean(params, "includeItemInfo", defaultValue = false)
        val neighborsOnly = optionalBoolean(params, "neighborsOnly", defaultValue = true)

        // Fetch dependencies by direction (non-suspend calls)
        val depRepo = context.dependencyRepository()
        val allDeps = when (direction) {
            "incoming" -> depRepo.findByToItemId(itemId)
            "outgoing" -> depRepo.findByFromItemId(itemId)
            "all" -> depRepo.findByItemId(itemId)
            else -> return errorResponse("Invalid direction: $direction", ErrorCodes.VALIDATION_ERROR)
        }

        // Apply type filter
        val filteredDeps = if (typeFilter != null) {
            allDeps.filter { it.type == typeFilter }
        } else {
            allDeps
        }

        // Build dependency JSON array, optionally including item info
        val depJsonArray = JsonArray(filteredDeps.map { dep ->
            buildDependencyJson(dep, includeItemInfo, context)
        })

        // Compute counts breakdown from the full (unfiltered by type) dep list for this item
        val allDepsForCounts = depRepo.findByItemId(itemId)
        val incomingCount = allDepsForCounts.count { it.toItemId == itemId && it.type != DependencyType.RELATES_TO }
        val outgoingCount = allDepsForCounts.count { it.fromItemId == itemId && it.type != DependencyType.RELATES_TO }
        val relatesToCount = allDepsForCounts.count { it.type == DependencyType.RELATES_TO }

        val countsJson = buildJsonObject {
            put("incoming", JsonPrimitive(incomingCount))
            put("outgoing", JsonPrimitive(outgoingCount))
            put("relatesTo", JsonPrimitive(relatesToCount))
        }

        // Build response
        val data = buildJsonObject {
            put("dependencies", depJsonArray)
            put("counts", countsJson)

            // Graph traversal if requested
            if (!neighborsOnly) {
                put("graph", buildGraphJson(itemId, depRepo))
            }
        }

        return successResponse(data)
    }

    override fun userSummary(params: JsonElement, result: JsonElement, isError: Boolean): String {
        if (isError) return "Dependency query failed"

        val data = (result as? JsonObject)?.get("data") as? JsonObject
        val deps = data?.get("dependencies") as? JsonArray
        val count = deps?.size ?: 0

        return "Found $count dependenc${if (count == 1) "y" else "ies"}"
    }

    // ──────────────────────────────────────────────
    // Helper methods
    // ──────────────────────────────────────────────

    private suspend fun buildDependencyJson(
        dep: Dependency,
        includeItemInfo: Boolean,
        context: ToolExecutionContext
    ): JsonObject = buildJsonObject {
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
                    put("fromItem", buildJsonObject {
                        put("title", JsonPrimitive(item.title))
                        put("role", JsonPrimitive(item.role.name.lowercase()))
                        put("priority", JsonPrimitive(item.priority.name.lowercase()))
                    })
                }
                is Result.Error -> { /* item may have been deleted; skip */ }
            }

            // Fetch "to" item details
            when (val toResult = context.workItemRepository().getById(dep.toItemId)) {
                is Result.Success -> {
                    val item = toResult.data
                    put("toItem", buildJsonObject {
                        put("title", JsonPrimitive(item.title))
                        put("role", JsonPrimitive(item.role.name.lowercase()))
                        put("priority", JsonPrimitive(item.priority.name.lowercase()))
                    })
                }
                is Result.Error -> { /* item may have been deleted; skip */ }
            }
        }
    }

    /**
     * Performs BFS traversal from the given item following BLOCKS edges in both directions.
     * Returns a topologically-ordered chain of item IDs and the maximum depth.
     */
    private fun buildGraphJson(startItemId: UUID, depRepo: io.github.jpicklyk.mcptask.current.domain.repository.DependencyRepository): JsonObject {
        // BFS to discover all connected items via BLOCKS/IS_BLOCKED_BY edges
        val visited = mutableSetOf<UUID>()
        val queue: LinkedList<UUID> = LinkedList()
        val edges = mutableListOf<Pair<UUID, UUID>>() // fromItemId -> toItemId (BLOCKS direction)

        queue.add(startItemId)
        visited.add(startItemId)

        while (queue.isNotEmpty()) {
            val current = queue.poll()
            val deps = depRepo.findByItemId(current)

            for (dep in deps) {
                // Only follow blocking edges for graph traversal
                if (dep.type == DependencyType.RELATES_TO) continue

                // Normalize to BLOCKS direction: from blocks to
                val (from, to) = when (dep.type) {
                    DependencyType.BLOCKS -> dep.fromItemId to dep.toItemId
                    DependencyType.IS_BLOCKED_BY -> dep.toItemId to dep.fromItemId
                    DependencyType.RELATES_TO -> continue
                }

                edges.add(from to to)

                // Visit neighbor nodes
                val neighbor = if (current == dep.fromItemId) dep.toItemId else dep.fromItemId
                if (neighbor !in visited) {
                    visited.add(neighbor)
                    queue.add(neighbor)
                }
            }
        }

        // Topological sort (Kahn's algorithm)
        val chain = topologicalSort(visited, edges)

        // Compute depth: longest path from any source node
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
    private fun topologicalSort(nodes: Set<UUID>, edges: List<Pair<UUID, UUID>>): List<UUID> {
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
    private fun computeMaxDepth(topoOrder: List<UUID>, edges: List<Pair<UUID, UUID>>): Int {
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
