package io.github.jpicklyk.mcptask.current.domain.graph

import java.util.PriorityQueue
import java.util.UUID

/** [ordered] lists blockers first; [cyclic] holds the nodes that could not be sorted, in input order. */
data class TopoResult(
    val ordered: List<UUID>,
    val cyclic: List<UUID>
)

/** The single topological sort over blocking edges. */
object TopoOrder {
    /**
     * Kahn's algorithm over [nodes] (duplicates collapsed, first wins): a blocker precedes what it
     * blocks. Among ready nodes the one with the lowest input index goes first. Edges with an
     * endpoint outside [nodes] are ignored. Nodes on, or downstream of, a cycle go to
     * [TopoResult.cyclic].
     */
    fun order(
        nodes: List<UUID>,
        edges: List<BlockingEdge>
    ): TopoResult {
        val distinct = nodes.distinct()
        val index = distinct.withIndex().associate { (i, id) -> id to i }
        val pairs =
            edges
                .filter { it.blocker in index && it.blocked in index }
                .map { index.getValue(it.blocker) to index.getValue(it.blocked) }
                .distinct()
        val inDegree = IntArray(distinct.size)
        val out = HashMap<Int, MutableList<Int>>()
        for ((b, d) in pairs) {
            inDegree[d]++
            out.getOrPut(b) { mutableListOf() } += d
        }
        val ready = PriorityQueue<Int>()
        for (i in distinct.indices) if (inDegree[i] == 0) ready += i
        val ordered = ArrayList<UUID>(distinct.size)
        val emitted = BooleanArray(distinct.size)
        while (ready.isNotEmpty()) {
            val i = ready.poll()
            ordered += distinct[i]
            emitted[i] = true
            for (d in out[i].orEmpty()) {
                inDegree[d]--
                if (inDegree[d] == 0) ready += d
            }
        }
        val cyclic = distinct.filterIndexed { i, _ -> !emitted[i] }
        return TopoResult(ordered, cyclic)
    }
}
