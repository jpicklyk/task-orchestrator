package io.github.jpicklyk.mcptask.current.domain.graph

import io.github.jpicklyk.mcptask.current.domain.model.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Oracle: task-scope Graph section and plan v4-phase1-core 3.4 step 1 (normalization and collapse rule). */
class DependencyEdgesTest {
    // S13 oracle: BLOCKS a->b is blocker a, blocked b.
    @Test
    fun `S13 BLOCKS from a to b makes a the blocker of b`() {
        val n = DependencyEdges.normalize(listOf(blocks(Ids.a, Ids.b)))
        assertEquals(listOf(BlockingEdge(Ids.a, Ids.b, Role.TERMINAL)), n.blocking)
        assertEquals(emptyList(), n.relates)
    }

    // S13 oracle: IS_BLOCKED_BY a->b means b blocks a, same as BLOCKS b->a.
    @Test
    fun `S13 IS_BLOCKED_BY from a to b makes b the blocker of a`() {
        val n = DependencyEdges.normalize(listOf(isBlockedBy(Ids.a, Ids.b)))
        assertEquals(listOf(BlockingEdge(Ids.b, Ids.a, Role.TERMINAL)), n.blocking)
    }

    @Test
    fun `S13 unblockAt strings map to roles and null maps to TERMINAL`() {
        val n =
            DependencyEdges.normalize(
                listOf(
                    blocks(Ids.a, Ids.b, "queue"),
                    blocks(Ids.a, Ids.c, "work"),
                    blocks(Ids.a, Ids.d, "review"),
                    blocks(Ids.b, Ids.c, "terminal"),
                    blocks(Ids.b, Ids.d, null)
                )
            )
        assertEquals(
            listOf(
                BlockingEdge(Ids.a, Ids.b, Role.QUEUE),
                BlockingEdge(Ids.a, Ids.c, Role.WORK),
                BlockingEdge(Ids.a, Ids.d, Role.REVIEW),
                BlockingEdge(Ids.b, Ids.c, Role.TERMINAL),
                BlockingEdge(Ids.b, Ids.d, Role.TERMINAL)
            ),
            n.blocking
        )
    }

    // S13 oracle: plan 3.4 step 1: same pair collapses to the later unblockAt, Q < W < R < T.
    @Test
    fun `S13 twin edges work and review collapse to review in either order`() {
        val fwd = DependencyEdges.normalize(listOf(blocks(Ids.a, Ids.b, "work"), blocks(Ids.a, Ids.b, "review")))
        val rev = DependencyEdges.normalize(listOf(blocks(Ids.a, Ids.b, "review"), blocks(Ids.a, Ids.b, "work")))
        assertEquals(listOf(BlockingEdge(Ids.a, Ids.b, Role.REVIEW)), fwd.blocking)
        assertEquals(listOf(BlockingEdge(Ids.a, Ids.b, Role.REVIEW)), rev.blocking)
    }

    @Test
    fun `S13 twin edges with null unblockAt and queue collapse to TERMINAL`() {
        val n = DependencyEdges.normalize(listOf(blocks(Ids.a, Ids.b, "queue"), blocks(Ids.a, Ids.b, null)))
        assertEquals(listOf(BlockingEdge(Ids.a, Ids.b, Role.TERMINAL)), n.blocking)
    }

    @Test
    fun `S13 opposite-direction spellings of one pair collapse`() {
        // BLOCKS b->a and IS_BLOCKED_BY a->b both mean b blocks a.
        val n = DependencyEdges.normalize(listOf(blocks(Ids.b, Ids.a, "work"), isBlockedBy(Ids.a, Ids.b, "review")))
        assertEquals(listOf(BlockingEdge(Ids.b, Ids.a, Role.REVIEW)), n.blocking)
    }

    @Test
    fun `S13 output keeps first-appearance order of pairs`() {
        val n =
            DependencyEdges.normalize(
                listOf(
                    blocks(Ids.c, Ids.d),
                    blocks(Ids.a, Ids.b, "work"),
                    blocks(Ids.c, Ids.d, "review"),
                    blocks(Ids.b, Ids.c)
                )
            )
        assertEquals(
            listOf(
                BlockingEdge(Ids.c, Ids.d, Role.TERMINAL),
                BlockingEdge(Ids.a, Ids.b, Role.WORK),
                BlockingEdge(Ids.b, Ids.c, Role.TERMINAL)
            ),
            n.blocking
        )
    }

    @Test
    fun `S13 RELATES_TO appears only in relates`() {
        val n = DependencyEdges.normalize(listOf(relates(Ids.a, Ids.b), blocks(Ids.b, Ids.c)))
        assertEquals(listOf(RelatesEdge(Ids.a, Ids.b)), n.relates)
        assertEquals(listOf(BlockingEdge(Ids.b, Ids.c, Role.TERMINAL)), n.blocking)
    }

    @Test
    fun `S13 empty input yields empty output`() {
        val n = DependencyEdges.normalize(emptyList())
        assertEquals(emptyList(), n.blocking)
        assertEquals(emptyList(), n.relates)
    }

    @Test
    fun `S13 toBlockingEdge maps BLOCKS and IS_BLOCKED_BY and returns null for RELATES_TO`() {
        assertEquals(BlockingEdge(Ids.a, Ids.b, Role.WORK), DependencyEdges.toBlockingEdge(blocks(Ids.a, Ids.b, "work")))
        assertEquals(BlockingEdge(Ids.b, Ids.a, Role.TERMINAL), DependencyEdges.toBlockingEdge(isBlockedBy(Ids.a, Ids.b)))
        assertNull(DependencyEdges.toBlockingEdge(relates(Ids.a, Ids.b)))
    }

    // Structural rules of the spec: BlockingEdge init blocker != blocked, unblockAt != BLOCKED.
    @Test
    fun `S13 BlockingEdge rejects self edge and BLOCKED threshold`() {
        assertFailsWith<IllegalArgumentException> { BlockingEdge(Ids.a, Ids.a) }
        assertFailsWith<IllegalArgumentException> { BlockingEdge(Ids.a, Ids.b, Role.BLOCKED) }
    }

    @Test
    fun `S13 replay normalize twice gives equal results`() {
        val deps = listOf(blocks(Ids.a, Ids.b, "work"), isBlockedBy(Ids.c, Ids.a), relates(Ids.b, Ids.c))
        assertEquals(DependencyEdges.normalize(deps), DependencyEdges.normalize(deps))
    }
}
