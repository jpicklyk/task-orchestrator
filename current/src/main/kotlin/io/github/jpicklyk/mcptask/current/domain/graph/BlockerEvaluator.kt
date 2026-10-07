package io.github.jpicklyk.mcptask.current.domain.graph

import io.github.jpicklyk.mcptask.current.domain.model.Role
import java.util.UUID

/** One blocker that has not yet reached its threshold. [role] is null when the blocker was unreadable. */
data class UnsatisfiedBlocker(
    val blockerId: UUID,
    val role: Role?,
    val threshold: Role
)

/** The single blocker evaluator. Fail-closed: an unknown blocker role never satisfies an edge. */
object BlockerEvaluator {
    /** True iff [blockerRole] is known and at or beyond the edge threshold (BLOCKED never satisfies). */
    fun isSatisfied(
        edge: BlockingEdge,
        blockerRole: Role?
    ): Boolean = blockerRole != null && Role.isAtOrBeyond(blockerRole, edge.unblockAt)

    /**
     * Batched evaluation. Returns one entry per requested id (in [itemIds] order, duplicates
     * collapsed), each listing the unsatisfied blockers of the edges whose blocked side is that id,
     * in [edges] order; an empty list means unblocked. A blocker absent from [roles] is unsatisfied.
     */
    fun unsatisfied(
        itemIds: Collection<UUID>,
        edges: List<BlockingEdge>,
        roles: Map<UUID, Role>
    ): Map<UUID, List<UnsatisfiedBlocker>> {
        val result = LinkedHashMap<UUID, MutableList<UnsatisfiedBlocker>>()
        for (id in itemIds) result.getOrPut(id) { mutableListOf() }
        for (edge in edges) {
            val bucket = result[edge.blocked] ?: continue
            val role = roles[edge.blocker]
            if (!isSatisfied(edge, role)) bucket += UnsatisfiedBlocker(edge.blocker, role, edge.unblockAt)
        }
        return result
    }
}
