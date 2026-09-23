package io.github.jpicklyk.mcptask.current.domain.model

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Blind test-author coverage for item 2aa67b28 (inverted IS_BLOCKED_BY cycle-check fix),
 * NEW-SURFACE scenario S14: [Dependency.blockerId] / [Dependency.blockedId].
 *
 * Oracles: [T] `X IS_BLOCKED_BY Y` means Y blocks X; [Da] diagnosis decision (a) — BLOCKS maps
 * blocker/blocked to from/to, IS_BLOCKED_BY maps them to to/from, RELATES_TO maps both to null.
 */
class DependencyDirectionAccessorTest {
    private val a = UUID.randomUUID()
    private val b = UUID.randomUUID()

    @Test
    fun `S14 BLOCKS blocker is fromItemId and blocked is toItemId`() {
        val dep = Dependency(fromItemId = a, toItemId = b, type = DependencyType.BLOCKS)
        assertEquals(a, dep.blockerId())
        assertEquals(b, dep.blockedId())
    }

    @Test
    fun `S14 IS_BLOCKED_BY blocker is toItemId and blocked is fromItemId`() {
        val dep = Dependency(fromItemId = a, toItemId = b, type = DependencyType.IS_BLOCKED_BY)
        assertEquals(b, dep.blockerId())
        assertEquals(a, dep.blockedId())
    }

    @Test
    fun `S14 RELATES_TO has no blocker and no blocked side`() {
        val dep = Dependency(fromItemId = a, toItemId = b, type = DependencyType.RELATES_TO)
        assertNull(dep.blockerId())
        assertNull(dep.blockedId())
    }
}
