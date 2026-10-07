package io.github.jpicklyk.mcptask.current.domain.graph

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/** Oracle: task-scope Graph: Kahn, lowest input index among ready nodes first, out-of-set edges ignored, unsortable nodes in cyclic (input order). */
class TopoOrderTest {
    private fun e(
        blocker: UUID,
        blocked: UUID
    ) = BlockingEdge(blocker, blocked)

    // S15: A IBB B means B blocks A, so B comes before A.
    @Test
    fun `S15 blocker is ordered before blocked`() {
        val edge = DependencyEdges.normalize(listOf(isBlockedBy(Ids.a, Ids.b))).blocking
        val r = TopoOrder.order(listOf(Ids.a, Ids.b), edge)
        assertEquals(listOf(Ids.b, Ids.a), r.ordered)
        assertEquals(emptyList(), r.cyclic)
    }

    @Test
    fun `S15 no edges keeps input order`() {
        val r = TopoOrder.order(listOf(Ids.c, Ids.a, Ids.b), emptyList())
        assertEquals(listOf(Ids.c, Ids.a, Ids.b), r.ordered)
        assertEquals(emptyList(), r.cyclic)
    }

    @Test
    fun `S15 ties among ready nodes go to the lowest input index`() {
        // c blocks a. Ready first: b (idx 1), c (idx 2). b, then c, then a.
        val r = TopoOrder.order(listOf(Ids.a, Ids.b, Ids.c), listOf(e(Ids.c, Ids.a)))
        assertEquals(listOf(Ids.b, Ids.c, Ids.a), r.ordered)
    }

    @Test
    fun `S15 a node released mid-run competes by input index with other ready nodes`() {
        // a blocks d: a, b, c ready (idx 0,1,2); d (idx 3) released after a but loses to b and c.
        val r = TopoOrder.order(listOf(Ids.a, Ids.b, Ids.c, Ids.d), listOf(e(Ids.a, Ids.d)))
        assertEquals(listOf(Ids.a, Ids.b, Ids.c, Ids.d), r.ordered)
        // b blocks a: ready b (1), c (2). b first; then a (0) is released and beats c.
        val r2 = TopoOrder.order(listOf(Ids.a, Ids.b, Ids.c), listOf(e(Ids.b, Ids.a)))
        assertEquals(listOf(Ids.b, Ids.a, Ids.c), r2.ordered)
    }

    @Test
    fun `S15 edges with an endpoint outside the node set are ignored`() {
        val r = TopoOrder.order(listOf(Ids.a, Ids.b), listOf(e(Ids.x1, Ids.a), e(Ids.b, Ids.x2), e(Ids.x1, Ids.x2)))
        assertEquals(listOf(Ids.a, Ids.b), r.ordered)
        assertEquals(emptyList(), r.cyclic)
    }

    @Test
    fun `S15 two-cycle nodes land in cyclic and the rest is ordered`() {
        val r = TopoOrder.order(listOf(Ids.a, Ids.b, Ids.c), listOf(e(Ids.a, Ids.b), e(Ids.b, Ids.a)))
        assertEquals(listOf(Ids.c), r.ordered)
        assertEquals(listOf(Ids.a, Ids.b), r.cyclic)
    }

    @Test
    fun `S15 node downstream of a cycle is unsortable and reported in input order`() {
        val r = TopoOrder.order(listOf(Ids.d, Ids.a, Ids.b), listOf(e(Ids.a, Ids.b), e(Ids.b, Ids.a), e(Ids.b, Ids.d)))
        assertEquals(emptyList(), r.ordered)
        assertEquals(listOf(Ids.d, Ids.a, Ids.b), r.cyclic)
    }

    @Test
    fun `S15 duplicate edges do not change the order`() {
        val r = TopoOrder.order(listOf(Ids.a, Ids.b), listOf(e(Ids.b, Ids.a), e(Ids.b, Ids.a)))
        assertEquals(listOf(Ids.b, Ids.a), r.ordered)
        assertEquals(emptyList(), r.cyclic)
    }

    @Test
    fun `S15 empty nodes give empty result`() {
        val r = TopoOrder.order(emptyList(), listOf(e(Ids.a, Ids.b)))
        assertEquals(emptyList(), r.ordered)
        assertEquals(emptyList(), r.cyclic)
    }

    @Test
    fun `S15 replay gives identical result`() {
        val nodes = listOf(Ids.a, Ids.b, Ids.c)
        val edges = listOf(e(Ids.c, Ids.a))
        assertEquals(TopoOrder.order(nodes, edges), TopoOrder.order(nodes, edges))
    }
}
