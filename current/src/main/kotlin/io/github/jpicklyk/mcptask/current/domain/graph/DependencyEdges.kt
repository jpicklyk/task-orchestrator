package io.github.jpicklyk.mcptask.current.domain.graph

import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.Role
import java.util.UUID

/** The result of [DependencyEdges.normalize]: blocking edges and relates links, in first-appearance order. */
data class NormalizedEdges(
    val blocking: List<BlockingEdge>,
    val relates: List<RelatesEdge>
)

/** Converts stored [Dependency] rows into normalized graph edges. */
object DependencyEdges {
    /**
     * BLOCKS a->b becomes (a, b); IS_BLOCKED_BY a->b becomes (b, a); a null unblockAt means
     * TERMINAL. Rows naming the same (blocker, blocked) pair collapse into one edge that keeps the
     * stricter (later) threshold, because both had to hold. Each edge keeps the position of the
     * first row that named its pair. RELATES_TO rows go to [NormalizedEdges.relates] unchanged.
     */
    fun normalize(deps: List<Dependency>): NormalizedEdges {
        val blocking = LinkedHashMap<Pair<UUID, UUID>, BlockingEdge>()
        val relates = mutableListOf<RelatesEdge>()
        for (dep in deps) {
            if (dep.type == DependencyType.RELATES_TO) {
                relates += RelatesEdge(dep.fromItemId, dep.toItemId)
                continue
            }
            val edge = toBlockingEdge(dep) ?: continue
            val key = edge.blocker to edge.blocked
            val existing = blocking[key]
            blocking[key] =
                if (existing == null || stricter(edge.unblockAt, existing.unblockAt)) {
                    edge
                } else {
                    existing
                }
        }
        return NormalizedEdges(blocking.values.toList(), relates)
    }

    /** The blocking edge for one row (null unblockAt = TERMINAL), or null for RELATES_TO. */
    fun toBlockingEdge(dep: Dependency): BlockingEdge? {
        val (blocker, blocked) = dep.blockingEdge() ?: return null
        val threshold = dep.unblockAt?.let { Role.fromString(it) } ?: Role.TERMINAL
        return BlockingEdge(blocker, blocked, threshold)
    }

    private fun stricter(
        candidate: Role,
        current: Role
    ): Boolean = Role.PROGRESSION.indexOf(candidate) > Role.PROGRESSION.indexOf(current)
}
