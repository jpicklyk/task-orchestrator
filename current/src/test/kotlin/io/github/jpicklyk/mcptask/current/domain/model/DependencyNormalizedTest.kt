package io.github.jpicklyk.mcptask.current.domain.model

import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Independent test authorship for item a19fcf0a (P12, needs-test-author): [Dependency.normalized].
 *
 * NEW-SURFACE (`normalized()` is introduced by the item). Narrowest-revert recipe: keep `normalized()` and make it
 * return `this`; the IS_BLOCKED_BY scenarios then go behaviorally red.
 *
 * Oracle (frozen task-scope item 1, plan 3.4): IS_BLOCKED_BY from a to b returns BLOCKS from b to a keeping the same
 * id, unblockAt and createdAt; BLOCKS and RELATES_TO are returned unchanged. `a IS_BLOCKED_BY b` means b blocks a.
 * Fixtures satisfy the declared validation rules by construction: from != to, RELATES_TO carries no unblockAt, and
 * unblockAt is one of queue, work, review, terminal.
 */
class DependencyNormalizedTest {
    private val a = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val b = UUID.fromString("00000000-0000-0000-0000-00000000000b")
    private val createdAt = Instant.parse("2026-03-01T10:15:30.123Z")

    @Test
    fun `IS_BLOCKED_BY a to b normalizes to BLOCKS b to a keeping id unblockAt and createdAt`() {
        val input =
            Dependency(
                id = UUID.fromString("11111111-1111-1111-1111-111111111111"),
                fromItemId = a,
                toItemId = b,
                type = DependencyType.IS_BLOCKED_BY,
                unblockAt = "review",
                createdAt = createdAt
            )

        val result = input.normalized()

        assertEquals(
            Dependency(
                id = UUID.fromString("11111111-1111-1111-1111-111111111111"),
                fromItemId = b,
                toItemId = a,
                type = DependencyType.BLOCKS,
                unblockAt = "review",
                createdAt = createdAt
            ),
            result
        )
    }

    @Test
    fun `IS_BLOCKED_BY without unblockAt keeps it null`() {
        val result = Dependency(fromItemId = a, toItemId = b, type = DependencyType.IS_BLOCKED_BY, createdAt = createdAt).normalized()

        assertNull(result.unblockAt)
        assertEquals(DependencyType.BLOCKS, result.type)
        assertEquals(b, result.fromItemId)
        assertEquals(a, result.toItemId)
    }

    @Test
    fun `BLOCKS is returned unchanged`() {
        val input = Dependency(fromItemId = a, toItemId = b, type = DependencyType.BLOCKS, unblockAt = "work", createdAt = createdAt)

        assertEquals(input, input.normalized())
    }

    @Test
    fun `RELATES_TO is returned unchanged and is not swapped`() {
        val input = Dependency(fromItemId = a, toItemId = b, type = DependencyType.RELATES_TO, createdAt = createdAt)

        val result = input.normalized()

        assertEquals(input, result)
        assertEquals(a, result.fromItemId)
        assertEquals(b, result.toItemId)
    }

    @Test
    fun `normalizing twice gives the same result as normalizing once`() {
        val input =
            Dependency(fromItemId = a, toItemId = b, type = DependencyType.IS_BLOCKED_BY, unblockAt = "queue", createdAt = createdAt)

        val once = input.normalized()

        assertEquals(once, once.normalized())
    }

    @Test
    fun `an IS_BLOCKED_BY dependency and its swapped BLOCKS restatement normalize to equal edges`() {
        val alias = Dependency(fromItemId = a, toItemId = b, type = DependencyType.IS_BLOCKED_BY, createdAt = createdAt).normalized()
        val direct = Dependency(fromItemId = b, toItemId = a, type = DependencyType.BLOCKS, createdAt = createdAt).normalized()

        assertEquals(Triple(direct.fromItemId, direct.toItemId, direct.type), Triple(alias.fromItemId, alias.toItemId, alias.type))
        assertSame(DependencyType.BLOCKS, alias.type)
    }
}
