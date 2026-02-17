package io.github.jpicklyk.mcptask.current.domain.model

import io.github.jpicklyk.mcptask.current.domain.validation.ValidationException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DependencyTest {

    private val itemA = UUID.randomUUID()
    private val itemB = UUID.randomUUID()

    // --- Valid creation ---

    @Test
    fun `valid creation with defaults`() {
        val dep = Dependency(fromItemId = itemA, toItemId = itemB)
        assertEquals(itemA, dep.fromItemId)
        assertEquals(itemB, dep.toItemId)
        assertEquals(DependencyType.BLOCKS, dep.type)
        assertNull(dep.unblockAt)
    }

    @Test
    fun `valid creation with BLOCKS type and unblockAt`() {
        val dep = Dependency(fromItemId = itemA, toItemId = itemB, type = DependencyType.BLOCKS, unblockAt = "work")
        assertEquals(DependencyType.BLOCKS, dep.type)
        assertEquals("work", dep.unblockAt)
    }

    @Test
    fun `valid creation with IS_BLOCKED_BY type`() {
        val dep = Dependency(fromItemId = itemA, toItemId = itemB, type = DependencyType.IS_BLOCKED_BY, unblockAt = "review")
        assertEquals(DependencyType.IS_BLOCKED_BY, dep.type)
        assertEquals("review", dep.unblockAt)
    }

    @Test
    fun `valid creation with RELATES_TO type no unblockAt`() {
        val dep = Dependency(fromItemId = itemA, toItemId = itemB, type = DependencyType.RELATES_TO)
        assertEquals(DependencyType.RELATES_TO, dep.type)
        assertNull(dep.unblockAt)
    }

    @Test
    fun `valid unblockAt values`() {
        Dependency(fromItemId = itemA, toItemId = itemB, unblockAt = "queue")
        Dependency(fromItemId = itemA, toItemId = itemB, unblockAt = "work")
        Dependency(fromItemId = itemA, toItemId = itemB, unblockAt = "review")
        Dependency(fromItemId = itemA, toItemId = itemB, unblockAt = "terminal")
    }

    // --- Validation failures ---

    @Test
    fun `self-referencing dependency throws ValidationException`() {
        val ex = assertFailsWith<ValidationException> {
            Dependency(fromItemId = itemA, toItemId = itemA)
        }
        assertTrue(ex.message!!.contains("cannot reference the same item"))
    }

    @Test
    fun `RELATES_TO with unblockAt throws ValidationException`() {
        val ex = assertFailsWith<ValidationException> {
            Dependency(fromItemId = itemA, toItemId = itemB, type = DependencyType.RELATES_TO, unblockAt = "work")
        }
        assertTrue(ex.message!!.contains("RELATES_TO dependencies cannot have an unblockAt"))
    }

    @Test
    fun `invalid unblockAt value throws ValidationException`() {
        val ex = assertFailsWith<ValidationException> {
            Dependency(fromItemId = itemA, toItemId = itemB, unblockAt = "invalid")
        }
        assertTrue(ex.message!!.contains("unblockAt must be one of"))
    }

    // --- effectiveUnblockRole ---

    @Test
    fun `effectiveUnblockRole returns terminal when unblockAt is null`() {
        val dep = Dependency(fromItemId = itemA, toItemId = itemB, type = DependencyType.BLOCKS)
        assertEquals("terminal", dep.effectiveUnblockRole())
    }

    @Test
    fun `effectiveUnblockRole returns work when unblockAt is work`() {
        val dep = Dependency(fromItemId = itemA, toItemId = itemB, unblockAt = "work")
        assertEquals("work", dep.effectiveUnblockRole())
    }

    @Test
    fun `effectiveUnblockRole returns null for RELATES_TO`() {
        val dep = Dependency(fromItemId = itemA, toItemId = itemB, type = DependencyType.RELATES_TO)
        assertNull(dep.effectiveUnblockRole())
    }

    @Test
    fun `effectiveUnblockRole for IS_BLOCKED_BY with unblockAt`() {
        val dep = Dependency(fromItemId = itemA, toItemId = itemB, type = DependencyType.IS_BLOCKED_BY, unblockAt = "review")
        assertEquals("review", dep.effectiveUnblockRole())
    }

    @Test
    fun `effectiveUnblockRole for IS_BLOCKED_BY without unblockAt defaults to terminal`() {
        val dep = Dependency(fromItemId = itemA, toItemId = itemB, type = DependencyType.IS_BLOCKED_BY)
        assertEquals("terminal", dep.effectiveUnblockRole())
    }
}
