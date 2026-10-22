package io.github.jpicklyk.mcptask.current.domain.graph

import io.github.jpicklyk.mcptask.current.domain.model.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Oracle: task-scope Graph: isSatisfied = role != null && role at-or-beyond unblockAt (Q < W < R < T); BLOCKED never satisfies. */
class BlockerEvaluatorTest {
    private val order = listOf(Role.QUEUE, Role.WORK, Role.REVIEW, Role.TERMINAL)

    // S12: full grid of threshold x role, expectation is arithmetic on the declared order.
    @Test
    fun `S12 isSatisfied matches at-or-beyond for every threshold and role`() {
        for (threshold in order) {
            val edge = BlockingEdge(Ids.a, Ids.b, threshold)
            for (role in Role.entries) {
                val expected = role != Role.BLOCKED && order.indexOf(role) >= order.indexOf(threshold)
                assertEquals(expected, BlockerEvaluator.isSatisfied(edge, role), "threshold=$threshold role=$role")
            }
        }
    }

    @Test
    fun `S12 BLOCKED never satisfies any threshold`() {
        for (threshold in order) {
            assertFalse(BlockerEvaluator.isSatisfied(BlockingEdge(Ids.a, Ids.b, threshold), Role.BLOCKED), "$threshold")
        }
    }

    @Test
    fun `S12 role equal to threshold satisfies and one below does not`() {
        val edge = BlockingEdge(Ids.a, Ids.b, Role.REVIEW)
        assertTrue(BlockerEvaluator.isSatisfied(edge, Role.REVIEW))
        assertFalse(BlockerEvaluator.isSatisfied(edge, Role.WORK))
        assertTrue(BlockerEvaluator.isSatisfied(edge, Role.TERMINAL))
    }

    @Test
    fun `S12 null role is unsatisfied for every threshold`() {
        for (threshold in order) {
            assertFalse(BlockerEvaluator.isSatisfied(BlockingEdge(Ids.a, Ids.b, threshold), null), "$threshold")
        }
    }

    // S17
    @Test
    fun `S17 every requested id is keyed and a fully satisfied id maps to empty`() {
        val edges = listOf(BlockingEdge(Ids.a, Ids.b), BlockingEdge(Ids.c, Ids.d))
        val result =
            BlockerEvaluator.unsatisfied(
                listOf(Ids.b, Ids.d, Ids.x1),
                edges,
                mapOf(Ids.a to Role.TERMINAL, Ids.c to Role.WORK)
            )
        assertEquals(setOf(Ids.b, Ids.d, Ids.x1), result.keys)
        assertEquals(emptyList(), result[Ids.b])
        assertEquals(listOf(UnsatisfiedBlocker(Ids.c, Role.WORK, Role.TERMINAL)), result[Ids.d])
        assertEquals(emptyList(), result[Ids.x1])
    }

    @Test
    fun `S12 absent role is reported unsatisfied with null role (fail closed)`() {
        val result = BlockerEvaluator.unsatisfied(listOf(Ids.b), listOf(BlockingEdge(Ids.a, Ids.b, Role.WORK)), emptyMap())
        assertEquals(listOf(UnsatisfiedBlocker(Ids.a, null, Role.WORK)), result[Ids.b])
    }

    @Test
    fun `S12 BLOCKED blocker role is reported unsatisfied with its role`() {
        val result =
            BlockerEvaluator.unsatisfied(listOf(Ids.b), listOf(BlockingEdge(Ids.a, Ids.b, Role.QUEUE)), mapOf(Ids.a to Role.BLOCKED))
        assertEquals(listOf(UnsatisfiedBlocker(Ids.a, Role.BLOCKED, Role.QUEUE)), result[Ids.b])
    }

    @Test
    fun `S17 multiple blockers of one item are all evaluated and satisfied ones omitted`() {
        val edges = listOf(BlockingEdge(Ids.a, Ids.d, Role.WORK), BlockingEdge(Ids.b, Ids.d), BlockingEdge(Ids.c, Ids.d, Role.QUEUE))
        val result =
            BlockerEvaluator.unsatisfied(
                listOf(Ids.d),
                edges,
                mapOf(Ids.a to Role.REVIEW, Ids.b to Role.REVIEW, Ids.c to Role.QUEUE)
            )
        assertEquals(listOf(UnsatisfiedBlocker(Ids.b, Role.REVIEW, Role.TERMINAL)), result[Ids.d])
    }

    @Test
    fun `S17 edges whose blocked side is not requested are ignored`() {
        val result = BlockerEvaluator.unsatisfied(listOf(Ids.b), listOf(BlockingEdge(Ids.a, Ids.c)), emptyMap())
        assertEquals(mapOf(Ids.b to emptyList<UnsatisfiedBlocker>()), result)
    }

    @Test
    fun `S17 empty id collection gives empty map`() {
        assertEquals(emptyMap(), BlockerEvaluator.unsatisfied(emptyList(), listOf(BlockingEdge(Ids.a, Ids.b)), emptyMap()))
    }
}
