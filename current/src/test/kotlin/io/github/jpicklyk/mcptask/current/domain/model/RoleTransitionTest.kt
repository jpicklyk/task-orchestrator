package io.github.jpicklyk.mcptask.current.domain.model

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoleTransitionTest {

    private val testItemId = UUID.randomUUID()

    // --- Valid creation ---

    @Test
    fun `valid creation with required fields`() {
        val transition = RoleTransition(
            itemId = testItemId,
            fromRole = "queue",
            toRole = "work",
            trigger = "start"
        )
        assertEquals(testItemId, transition.itemId)
        assertEquals("queue", transition.fromRole)
        assertEquals("work", transition.toRole)
        assertEquals("start", transition.trigger)
        assertEquals(null, transition.summary)
        assertEquals(null, transition.fromStatusLabel)
        assertEquals(null, transition.toStatusLabel)
    }

    @Test
    fun `valid creation with all fields`() {
        val id = UUID.randomUUID()
        val transition = RoleTransition(
            id = id,
            itemId = testItemId,
            fromRole = "work",
            toRole = "review",
            fromStatusLabel = "in-progress",
            toStatusLabel = "in-review",
            trigger = "complete",
            summary = "Task implementation done"
        )
        assertEquals(id, transition.id)
        assertEquals("work", transition.fromRole)
        assertEquals("review", transition.toRole)
        assertEquals("in-progress", transition.fromStatusLabel)
        assertEquals("in-review", transition.toStatusLabel)
        assertEquals("complete", transition.trigger)
        assertEquals("Task implementation done", transition.summary)
    }

    @Test
    fun `creation with block trigger`() {
        val transition = RoleTransition(
            itemId = testItemId,
            fromRole = "work",
            toRole = "blocked",
            trigger = "block"
        )
        assertEquals("block", transition.trigger)
    }

    @Test
    fun `creation with hold trigger`() {
        val transition = RoleTransition(
            itemId = testItemId,
            fromRole = "work",
            toRole = "blocked",
            trigger = "hold"
        )
        assertEquals("hold", transition.trigger)
    }

    @Test
    fun `creation with resume trigger`() {
        val transition = RoleTransition(
            itemId = testItemId,
            fromRole = "blocked",
            toRole = "work",
            trigger = "resume"
        )
        assertEquals("resume", transition.trigger)
    }

    @Test
    fun `creation with cancel trigger`() {
        val transition = RoleTransition(
            itemId = testItemId,
            fromRole = "work",
            toRole = "terminal",
            trigger = "cancel"
        )
        assertEquals("cancel", transition.trigger)
    }

    // --- VALID_TRIGGERS ---

    @Test
    fun `VALID_TRIGGERS contains all expected triggers`() {
        val expected = setOf("start", "complete", "block", "hold", "resume", "cancel")
        assertEquals(expected, RoleTransition.VALID_TRIGGERS)
    }

    @Test
    fun `VALID_TRIGGERS has exactly 6 entries`() {
        assertEquals(6, RoleTransition.VALID_TRIGGERS.size)
    }

    @Test
    fun `VALID_TRIGGERS contains start`() {
        assertTrue("start" in RoleTransition.VALID_TRIGGERS)
    }

    @Test
    fun `VALID_TRIGGERS contains complete`() {
        assertTrue("complete" in RoleTransition.VALID_TRIGGERS)
    }

    @Test
    fun `VALID_TRIGGERS contains block`() {
        assertTrue("block" in RoleTransition.VALID_TRIGGERS)
    }

    @Test
    fun `VALID_TRIGGERS contains hold`() {
        assertTrue("hold" in RoleTransition.VALID_TRIGGERS)
    }

    @Test
    fun `VALID_TRIGGERS contains resume`() {
        assertTrue("resume" in RoleTransition.VALID_TRIGGERS)
    }

    @Test
    fun `VALID_TRIGGERS contains cancel`() {
        assertTrue("cancel" in RoleTransition.VALID_TRIGGERS)
    }
}
