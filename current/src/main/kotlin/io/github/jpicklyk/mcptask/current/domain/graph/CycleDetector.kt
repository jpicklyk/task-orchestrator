package io.github.jpicklyk.mcptask.current.domain.graph

import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import java.util.UUID

/**
 * The single blocking-cycle detector. Only blocking edges count; RELATES_TO links never form a
 * cycle. Paths are closed: the first node is repeated at the end. Traversal is deterministic:
 * neighbors are visited in ascending UUID order.
 */
object CycleDetector {
    /**
     * The cycle that adding [candidate] to [existing] would close, as
     * `[candidate.blocker, candidate.blocked, ..., candidate.blocker]` along the shortest path
     * (BFS, ties broken by UUID order), or null when no cycle forms. Restating a pair already in
     * [existing] (same blocker and blocked, any threshold) adds nothing and returns null.
     */
    fun cycleIfAdded(
        existing: List<BlockingEdge>,
        candidate: BlockingEdge
    ): List<UUID>? {
        if (existing.any { it.blocker == candidate.blocker && it.blocked == candidate.blocked }) return null
        val path = shortestPath(adjacency(existing), candidate.blocked, candidate.blocker) ?: return null
        return listOf(candidate.blocker) + path
    }

    /** [cycleIfAdded] over stored rows (normalized first); a RELATES_TO candidate never forms a cycle. */
    @JvmName("cycleIfAddedDependencies")
    fun cycleIfAdded(
        existing: List<Dependency>,
        candidate: Dependency
    ): List<UUID>? {
        val edge = DependencyEdges.toBlockingEdge(candidate) ?: return null
        return cycleIfAdded(DependencyEdges.normalize(existing).blocking, edge)
    }

    /**
     * Some cycle among [edges] as a closed path, or null when the graph is acyclic. Start nodes are
     * tried in first-appearance order (blocker before blocked within an edge).
     */
    fun findCycle(edges: List<BlockingEdge>): List<UUID>? {
        val adj = adjacency(edges)
        val nodes = LinkedHashSet<UUID>()
        edges.forEach {
            nodes += it.blocker
            nodes += it.blocked
        }
        val done = HashSet<UUID>()
        val onStack = HashSet<UUID>()
        val stack = ArrayList<UUID>()

        fun dfs(node: UUID): List<UUID>? {
            onStack += node
            stack += node
            for (next in adj[node].orEmpty()) {
                if (next in onStack) {
                    val start = stack.indexOf(next)
                    return stack.subList(start, stack.size).toList() + next
                }
                if (next !in done) dfs(next)?.let { return it }
            }
            stack.removeAt(stack.size - 1)
            onStack -= node
            done += node
            return null
        }

        for (node in nodes) {
            if (node !in done) dfs(node)?.let { return it }
        }
        return null
    }

    /** [findCycle] over stored rows (normalized first; RELATES_TO ignored). */
    @JvmName("findCycleDependencies")
    fun findCycle(deps: List<Dependency>): List<UUID>? = findCycle(DependencyEdges.normalize(deps).blocking)

    private fun adjacency(edges: List<BlockingEdge>): Map<UUID, List<UUID>> =
        edges
            .groupBy({ it.blocker }, { it.blocked })
            .mapValues { (_, targets) -> targets.distinct().sorted() }

    /** Shortest path from [from] to [to], both ends included, or null. */
    private fun shortestPath(
        adj: Map<UUID, List<UUID>>,
        from: UUID,
        to: UUID
    ): List<UUID>? {
        val parent = HashMap<UUID, UUID?>()
        parent[from] = null
        val queue = ArrayDeque<UUID>()
        queue.addLast(from)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node == to) {
                val path = ArrayList<UUID>()
                var cursor: UUID? = node
                while (cursor != null) {
                    path += cursor
                    cursor = parent[cursor]
                }
                return path.reversed()
            }
            for (next in adj[node].orEmpty()) {
                if (next !in parent) {
                    parent[next] = node
                    queue.addLast(next)
                }
            }
        }
        return null
    }
}
