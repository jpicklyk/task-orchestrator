package io.github.jpicklyk.mcptask.application.service

import io.github.jpicklyk.mcptask.domain.model.Dependency
import io.github.jpicklyk.mcptask.domain.model.DependencyType
import io.github.jpicklyk.mcptask.domain.model.Task
import io.github.jpicklyk.mcptask.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.domain.repository.TaskRepository
import java.util.*

/**
 * Result of a full dependency graph traversal and analysis.
 *
 * @property chain Topologically sorted list of task IDs in the traversed subgraph
 * @property depth Maximum depth (longest chain length) in the graph
 * @property criticalPath Task IDs forming the longest path through the graph
 * @property bottlenecks Tasks with high fan-out (blocking many downstream tasks)
 * @property parallelizable Groups of tasks at the same depth level that can run concurrently
 * @property warnings Any warnings encountered during traversal (e.g., unexpected cycles)
 */
data class GraphAnalysisResult(
    val chain: List<UUID>,
    val depth: Int,
    val criticalPath: List<UUID>,
    val bottlenecks: List<BottleneckInfo>,
    val parallelizable: List<ParallelGroup>,
    val warnings: List<String> = emptyList()
)

/**
 * A task identified as a bottleneck due to high fan-out.
 *
 * @property taskId The bottleneck task ID
 * @property fanOut Number of downstream tasks directly blocked by this task
 * @property title Optional task title (included when includeTaskInfo is true)
 */
data class BottleneckInfo(
    val taskId: UUID,
    val fanOut: Int,
    val title: String? = null
)

/**
 * A group of tasks at the same depth level that can be executed in parallel.
 *
 * @property depth The depth level (distance from the start node)
 * @property tasks Task IDs at this depth level with no inter-dependencies
 */
data class ParallelGroup(
    val depth: Int,
    val tasks: List<UUID>
)

/**
 * Service for performing graph traversal and analysis on task dependency graphs.
 *
 * Provides BFS-based traversal from a starting task, topological sorting,
 * critical path identification, bottleneck detection, and parallelizable
 * task group discovery.
 *
 * This service is stateless and should be created inline where needed
 * (following the StatusValidator pattern).
 */
class DependencyGraphService(
    private val dependencyRepository: DependencyRepository,
    private val taskRepository: TaskRepository
) {

    /**
     * Performs a full graph analysis starting from the given task.
     *
     * The analysis includes:
     * 1. BFS traversal to discover all connected nodes
     * 2. Topological sort of the discovered subgraph
     * 3. Critical path computation (longest path, optionally weighted by complexity)
     * 4. Bottleneck identification (tasks with high fan-out)
     * 5. Parallelizable group detection (tasks at the same depth with no inter-dependencies)
     *
     * @param startTaskId The task ID to start traversal from
     * @param direction "incoming" (upstream), "outgoing" (downstream), or "all" (both)
     * @param type Optional dependency type filter (e.g., "BLOCKS"). Null or "all" means no filter
     * @param includeTaskInfo Whether to include task titles in results
     * @return A [GraphAnalysisResult] with the full analysis
     */
    suspend fun analyzeGraph(
        startTaskId: UUID,
        direction: String,
        type: String?,
        includeTaskInfo: Boolean
    ): GraphAnalysisResult {
        // Step 1: BFS to discover all connected nodes and build adjacency lists
        val adjacency = mutableMapOf<UUID, MutableSet<UUID>>()   // node -> set of successors
        val reverseAdj = mutableMapOf<UUID, MutableSet<UUID>>()  // node -> set of predecessors
        val visited = mutableSetOf<UUID>()
        val queue: Queue<UUID> = LinkedList()
        val warnings = mutableListOf<String>()

        queue.add(startTaskId)
        visited.add(startTaskId)

        while (queue.isNotEmpty()) {
            val current = queue.poll()

            // Initialize adjacency entries
            adjacency.getOrPut(current) { mutableSetOf() }
            reverseAdj.getOrPut(current) { mutableSetOf() }

            // Get edges based on direction
            val edges = getEdges(current, direction, type)

            for ((from, to) in edges) {
                // Normalize edge direction: from -> to in the adjacency list
                // "from" is the predecessor, "to" is the successor
                adjacency.getOrPut(from) { mutableSetOf() }.add(to)
                reverseAdj.getOrPut(to) { mutableSetOf() }.add(from)

                // Enqueue newly discovered nodes
                if (to !in visited) {
                    visited.add(to)
                    queue.add(to)
                }
                if (from !in visited) {
                    visited.add(from)
                    queue.add(from)
                }
            }
        }

        val allNodes = visited.toSet()

        // Handle empty graph (no dependencies)
        if (allNodes.size <= 1) {
            return GraphAnalysisResult(
                chain = listOf(startTaskId),
                depth = 0,
                criticalPath = listOf(startTaskId),
                bottlenecks = emptyList(),
                parallelizable = emptyList()
            )
        }

        // Step 2: Topological sort using Kahn's algorithm
        val topoResult = topologicalSort(allNodes, adjacency, reverseAdj)
        val chain = topoResult.first
        val hasCycle = topoResult.second

        if (hasCycle) {
            warnings.add("Cycle detected in dependency graph. Topological order may be incomplete.")
        }

        // Step 3: Compute depth of each node (longest path from any root)
        val depthMap = computeDepths(allNodes, adjacency, reverseAdj)
        val maxDepth = depthMap.values.maxOrNull() ?: 0

        // Step 4: Critical path (longest path through the DAG)
        val criticalPath = computeCriticalPath(allNodes, adjacency, reverseAdj, depthMap, includeTaskInfo)

        // Step 5: Bottleneck identification (fan-out analysis)
        val bottlenecks = identifyBottlenecks(adjacency, includeTaskInfo)

        // Step 6: Parallelizable groups
        val parallelizable = findParallelGroups(depthMap, adjacency)

        return GraphAnalysisResult(
            chain = chain,
            depth = maxDepth,
            criticalPath = criticalPath,
            bottlenecks = bottlenecks,
            parallelizable = parallelizable,
            warnings = warnings
        )
    }

    /**
     * Gets directed edges from a node based on direction and type filter.
     * Returns pairs of (predecessor, successor) — normalized so "from" blocks "to".
     */
    private fun getEdges(
        taskId: UUID,
        direction: String,
        type: String?
    ): List<Pair<UUID, UUID>> {
        val edges = mutableListOf<Pair<UUID, UUID>>()
        val typeFilter = if (type != null && type != "all") DependencyType.fromString(type) else null

        if (direction == "outgoing" || direction == "all") {
            // Outgoing: this task blocks other tasks
            val outgoing = dependencyRepository.findByFromTaskId(taskId)
            for (dep in outgoing) {
                if (typeFilter != null && dep.type != typeFilter) continue
                when (dep.type) {
                    DependencyType.BLOCKS -> edges.add(Pair(dep.fromTaskId, dep.toTaskId))
                    DependencyType.IS_BLOCKED_BY -> edges.add(Pair(dep.toTaskId, dep.fromTaskId))
                    DependencyType.RELATES_TO -> edges.add(Pair(dep.fromTaskId, dep.toTaskId))
                }
            }
        }

        if (direction == "incoming" || direction == "all") {
            // Incoming: other tasks block this task
            val incoming = dependencyRepository.findByToTaskId(taskId)
            for (dep in incoming) {
                if (typeFilter != null && dep.type != typeFilter) continue
                when (dep.type) {
                    DependencyType.BLOCKS -> edges.add(Pair(dep.fromTaskId, dep.toTaskId))
                    DependencyType.IS_BLOCKED_BY -> edges.add(Pair(dep.toTaskId, dep.fromTaskId))
                    DependencyType.RELATES_TO -> edges.add(Pair(dep.fromTaskId, dep.toTaskId))
                }
            }
        }

        return edges
    }

    /**
     * Topological sort using Kahn's algorithm.
     * Returns a pair of (sorted list, hasCycle flag).
     */
    private fun topologicalSort(
        nodes: Set<UUID>,
        adjacency: Map<UUID, Set<UUID>>,
        reverseAdj: Map<UUID, Set<UUID>>
    ): Pair<List<UUID>, Boolean> {
        // Calculate in-degree for each node (within this subgraph only)
        val inDegree = mutableMapOf<UUID, Int>()
        for (node in nodes) {
            val predecessors = reverseAdj[node] ?: emptySet()
            inDegree[node] = predecessors.count { it in nodes }
        }

        val queue: Queue<UUID> = LinkedList()
        for (node in nodes) {
            if ((inDegree[node] ?: 0) == 0) {
                queue.add(node)
            }
        }

        val sorted = mutableListOf<UUID>()
        while (queue.isNotEmpty()) {
            val node = queue.poll()
            sorted.add(node)

            val successors = adjacency[node] ?: emptySet()
            for (successor in successors) {
                if (successor !in nodes) continue
                inDegree[successor] = (inDegree[successor] ?: 1) - 1
                if (inDegree[successor] == 0) {
                    queue.add(successor)
                }
            }
        }

        val hasCycle = sorted.size < nodes.size

        // If there was a cycle, append remaining nodes (those stuck with in-degree > 0)
        if (hasCycle) {
            for (node in nodes) {
                if (node !in sorted) {
                    sorted.add(node)
                }
            }
        }

        return Pair(sorted, hasCycle)
    }

    /**
     * Computes the depth (longest path from any root) for each node using dynamic programming.
     * Roots (nodes with no predecessors in the subgraph) start at depth 0.
     */
    private fun computeDepths(
        nodes: Set<UUID>,
        adjacency: Map<UUID, Set<UUID>>,
        reverseAdj: Map<UUID, Set<UUID>>
    ): Map<UUID, Int> {
        val depthMap = mutableMapOf<UUID, Int>()

        // Use topological order for DP
        val (topoOrder, _) = topologicalSort(nodes, adjacency, reverseAdj)

        for (node in topoOrder) {
            val predecessors = reverseAdj[node] ?: emptySet()
            val maxPredDepth = predecessors
                .filter { it in nodes }
                .mapNotNull { depthMap[it] }
                .maxOrNull() ?: -1
            depthMap[node] = maxPredDepth + 1
        }

        return depthMap
    }

    /**
     * Computes the critical path (longest path through the DAG).
     * Uses depth values to trace back from the deepest node.
     */
    private suspend fun computeCriticalPath(
        nodes: Set<UUID>,
        adjacency: Map<UUID, Set<UUID>>,
        reverseAdj: Map<UUID, Set<UUID>>,
        depthMap: Map<UUID, Int>,
        includeTaskInfo: Boolean
    ): List<UUID> {
        if (nodes.isEmpty()) return emptyList()

        // Find the node with maximum depth
        val maxDepth = depthMap.values.maxOrNull() ?: 0
        var current = depthMap.entries.firstOrNull { it.value == maxDepth }?.key ?: return emptyList()

        // Trace back from deepest node to a root
        val path = mutableListOf(current)
        var currentDepth = maxDepth

        while (currentDepth > 0) {
            val predecessors = reverseAdj[current] ?: emptySet()
            val predecessor = predecessors
                .filter { it in nodes && depthMap[it] == currentDepth - 1 }
                .firstOrNull() ?: break
            path.add(0, predecessor)
            current = predecessor
            currentDepth--
        }

        return path
    }

    /**
     * Identifies bottleneck tasks (those with high fan-out).
     * Returns tasks sorted by fan-out descending, filtered to fan-out > 1.
     */
    private suspend fun identifyBottlenecks(
        adjacency: Map<UUID, Set<UUID>>,
        includeTaskInfo: Boolean
    ): List<BottleneckInfo> {
        val bottlenecks = adjacency
            .map { (taskId, successors) -> Pair(taskId, successors.size) }
            .filter { it.second > 1 }
            .sortedByDescending { it.second }

        return bottlenecks.map { (taskId, fanOut) ->
            val title = if (includeTaskInfo) {
                getTaskTitle(taskId)
            } else null
            BottleneckInfo(taskId = taskId, fanOut = fanOut, title = title)
        }
    }

    /**
     * Finds groups of tasks at the same depth level that can be executed in parallel.
     * Only returns groups with 2+ tasks. Filters out tasks that have
     * inter-dependencies within the same depth level.
     */
    private fun findParallelGroups(
        depthMap: Map<UUID, Int>,
        adjacency: Map<UUID, Set<UUID>>
    ): List<ParallelGroup> {
        // Group tasks by depth
        val byDepth = depthMap.entries.groupBy({ it.value }, { it.key })

        return byDepth
            .filter { (_, tasks) -> tasks.size >= 2 }
            .mapNotNull { (depth, tasks) ->
                // Filter out tasks that have inter-dependencies at this depth level
                val tasksAtDepth = tasks.toSet()
                val independent = tasks.filter { task ->
                    val successors = adjacency[task] ?: emptySet()
                    // A task is independent at this level if none of its successors are at the same depth
                    successors.none { it in tasksAtDepth }
                }
                if (independent.size >= 2) {
                    ParallelGroup(depth = depth, tasks = independent)
                } else null
            }
            .sortedBy { it.depth }
    }

    /**
     * Retrieves a task's title from the repository.
     */
    private suspend fun getTaskTitle(taskId: UUID): String? {
        return when (val result = taskRepository.getById(taskId)) {
            is Result.Success -> result.data.title
            is Result.Error -> null
        }
    }
}
