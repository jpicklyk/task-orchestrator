package io.github.jpicklyk.mcptask.current.domain.graph

import io.github.jpicklyk.mcptask.current.domain.model.Role
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Oracle: task-scope Graph (closed path [blocker, blocked, ..., blocker], BFS shortest, ties by ascending UUID,
 * relates ignored); AR-03/AR-12/AR-20 mixed-direction cases.
 */
class CycleDetectorTest {
    private fun e(
        blocker: UUID,
        blocked: UUID
    ) = BlockingEdge(blocker, blocked, Role.TERMINAL)

    private fun assertClosed(
        path: List<UUID>?,
        nodes: Set<UUID>
    ) {
        val p = assertNotNull(path)
        assertEquals(p.first(), p.last(), "path must be closed")
        assertTrue(p.size >= 3, "a cycle path has at least 2 distinct nodes plus the closing repeat: $p")
        assertEquals(nodes, p.toSet())
    }

    // S14 AR-03: A IBB B (edge B->A) existing; candidate B IBB A (edge A->B) closes it.
    @Test
    fun `S14 A IBB B existing and B IBB A candidate is a cycle`() {
        val path = CycleDetector.cycleIfAdded(listOf(isBlockedBy(Ids.a, Ids.b)), isBlockedBy(Ids.b, Ids.a))
        assertEquals(listOf(Ids.a, Ids.b, Ids.a), path)
    }

    // S14: BLOCKS and IBB spellings of the same edge: restating an existing pair is not a cycle.
    @Test
    fun `S14 B BLOCKS A existing and A IBB B candidate restates the same pair and is null`() {
        assertNull(CycleDetector.cycleIfAdded(listOf(blocks(Ids.b, Ids.a)), isBlockedBy(Ids.a, Ids.b)))
    }

    @Test
    fun `S14 mixed directions A BLOCKS B existing and A IBB B candidate is a true reverse and cycles`() {
        // A BLOCKS B = edge A->B; A IBB B = edge B->A.
        val path = CycleDetector.cycleIfAdded(listOf(blocks(Ids.a, Ids.b)), isBlockedBy(Ids.a, Ids.b))
        assertEquals(listOf(Ids.b, Ids.a, Ids.b), path)
    }

    @Test
    fun `S14 chain A to B to C with candidate C IBB A adds a forward shortcut and is null`() {
        val existing = listOf(blocks(Ids.a, Ids.b), blocks(Ids.b, Ids.c))
        assertNull(CycleDetector.cycleIfAdded(existing, isBlockedBy(Ids.c, Ids.a)))
    }

    @Test
    fun `S14 chain A to B to C with candidate A IBB C closes a three-node cycle`() {
        val existing = listOf(blocks(Ids.a, Ids.b), blocks(Ids.b, Ids.c))
        // A IBB C = edge C->A; closed path [C, A, B, C].
        assertEquals(listOf(Ids.c, Ids.a, Ids.b, Ids.c), CycleDetector.cycleIfAdded(existing, isBlockedBy(Ids.a, Ids.c)))
    }

    @Test
    fun `S14 RELATES candidate closing a blocking path is null`() {
        val existing = listOf(blocks(Ids.a, Ids.b), blocks(Ids.b, Ids.c))
        assertNull(CycleDetector.cycleIfAdded(existing, relates(Ids.c, Ids.a)))
    }

    @Test
    fun `S14 RELATES edges in existing never complete a cycle`() {
        val existing = listOf(blocks(Ids.a, Ids.b), relates(Ids.b, Ids.c))
        assertNull(CycleDetector.cycleIfAdded(existing, blocks(Ids.c, Ids.a)))
    }

    @Test
    fun `S14 BlockingEdge overload restating an existing edge is null`() {
        assertNull(CycleDetector.cycleIfAdded(listOf(e(Ids.a, Ids.b)), e(Ids.a, Ids.b)))
    }

    @Test
    fun `S14 BlockingEdge overload with no existing edges is null`() {
        assertNull(CycleDetector.cycleIfAdded(emptyList<BlockingEdge>(), e(Ids.a, Ids.b)))
    }

    @Test
    fun `S14 shortest path wins over a longer one`() {
        // candidate edge a->b; existing b->c->a (short) and b->x1->c->a (longer).
        val existing = listOf(e(Ids.b, Ids.c), e(Ids.c, Ids.a), e(Ids.b, Ids.x1), e(Ids.x1, Ids.c))
        assertEquals(listOf(Ids.a, Ids.b, Ids.c, Ids.a), CycleDetector.cycleIfAdded(existing, e(Ids.a, Ids.b)))
    }

    // Declared guarantee: neighbors visited in ascending UUID order, so a tie goes through the smaller UUID (x1 < x2).
    @Test
    fun `S14 equal-length paths tie-break by ascending UUID regardless of input order`() {
        val one = listOf(e(Ids.b, Ids.x1), e(Ids.b, Ids.x2), e(Ids.x1, Ids.a), e(Ids.x2, Ids.a))
        val two = one.reversed()
        val expected = listOf(Ids.a, Ids.b, Ids.x1, Ids.a)
        assertEquals(expected, CycleDetector.cycleIfAdded(one, e(Ids.a, Ids.b)))
        assertEquals(expected, CycleDetector.cycleIfAdded(two, e(Ids.a, Ids.b)))
    }

    @Test
    fun `S14 replay gives identical result`() {
        val existing = listOf(blocks(Ids.a, Ids.b), blocks(Ids.b, Ids.c))
        val cand = isBlockedBy(Ids.a, Ids.c)
        assertEquals(CycleDetector.cycleIfAdded(existing, cand), CycleDetector.cycleIfAdded(existing, cand))
    }

    @Test
    fun `S14 findCycle on A BLOCKS B plus A IBB B finds the two-node cycle`() {
        assertClosed(CycleDetector.findCycle(listOf(blocks(Ids.a, Ids.b), isBlockedBy(Ids.a, Ids.b))), setOf(Ids.a, Ids.b))
    }

    @Test
    fun `S14 findCycle on an acyclic graph is null and ignores relates`() {
        assertNull(CycleDetector.findCycle(listOf(blocks(Ids.a, Ids.b), blocks(Ids.b, Ids.c), relates(Ids.c, Ids.a))))
        assertNull(CycleDetector.findCycle(emptyList<BlockingEdge>()))
    }

    @Test
    fun `S14 findCycle on edges finds a three-node cycle`() {
        assertClosed(CycleDetector.findCycle(listOf(e(Ids.a, Ids.b), e(Ids.b, Ids.c), e(Ids.c, Ids.a))), setOf(Ids.a, Ids.b, Ids.c))
    }

    @Test
    fun `S14 findCycle ignores acyclic tail and returns only the cycle nodes`() {
        val path = CycleDetector.findCycle(listOf(e(Ids.d, Ids.a), e(Ids.a, Ids.b), e(Ids.b, Ids.a)))
        assertClosed(path, setOf(Ids.a, Ids.b))
    }
}
