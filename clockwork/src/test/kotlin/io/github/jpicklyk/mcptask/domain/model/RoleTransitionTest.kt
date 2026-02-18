package io.github.jpicklyk.mcptask.domain.model

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RoleTransitionTest {

    @Test
    fun `valid construction with all fields`() {
        val id = UUID.randomUUID()
        val entityId = UUID.randomUUID()
        val transitionedAt = Instant.now()

        val transition = RoleTransition(
            id = id,
            entityId = entityId,
            entityType = "task",
            fromRole = "queue",
            toRole = "work",
            fromStatus = "pending",
            toStatus = "in-progress",
            transitionedAt = transitionedAt,
            trigger = "start",
            summary = "Started work on task"
        )

        assertEquals(id, transition.id)
        assertEquals(entityId, transition.entityId)
        assertEquals("task", transition.entityType)
        assertEquals("queue", transition.fromRole)
        assertEquals("work", transition.toRole)
        assertEquals("pending", transition.fromStatus)
        assertEquals("in-progress", transition.toStatus)
        assertEquals(transitionedAt, transition.transitionedAt)
        assertEquals("start", transition.trigger)
        assertEquals("Started work on task", transition.summary)
    }

    @Test
    fun `valid construction with minimal fields uses defaults`() {
        val entityId = UUID.randomUUID()
        val beforeCreation = Instant.now()

        val transition = RoleTransition(
            entityId = entityId,
            entityType = "feature",
            fromRole = "work",
            toRole = "review",
            fromStatus = "in-progress",
            toStatus = "testing"
        )

        // ID should be generated
        assertNotNull(transition.id)

        // transitionedAt should be close to now
        assertTrue(transition.transitionedAt >= beforeCreation)
        assertTrue(transition.transitionedAt <= Instant.now().plusSeconds(1))

        // Optional fields should be null
        assertEquals(null, transition.trigger)
        assertEquals(null, transition.summary)
    }

    @Test
    fun `invalid entityType throws IllegalArgumentException`() {
        val exception = assertThrows<IllegalArgumentException> {
            RoleTransition(
                entityId = UUID.randomUUID(),
                entityType = "invalid",
                fromRole = "queue",
                toRole = "work",
                fromStatus = "pending",
                toStatus = "in-progress"
            )
        }
        assertTrue(exception.message!!.contains("entityType must be 'task', 'feature', or 'project'"))
    }

    @Test
    fun `blank fromRole throws IllegalArgumentException`() {
        val exception = assertThrows<IllegalArgumentException> {
            RoleTransition(
                entityId = UUID.randomUUID(),
                entityType = "task",
                fromRole = "",
                toRole = "work",
                fromStatus = "pending",
                toStatus = "in-progress"
            )
        }
        assertTrue(exception.message!!.contains("fromRole must not be blank"))
    }

    @Test
    fun `blank toRole throws IllegalArgumentException`() {
        val exception = assertThrows<IllegalArgumentException> {
            RoleTransition(
                entityId = UUID.randomUUID(),
                entityType = "task",
                fromRole = "queue",
                toRole = "  ",
                fromStatus = "pending",
                toStatus = "in-progress"
            )
        }
        assertTrue(exception.message!!.contains("toRole must not be blank"))
    }

    @Test
    fun `blank fromStatus throws IllegalArgumentException`() {
        val exception = assertThrows<IllegalArgumentException> {
            RoleTransition(
                entityId = UUID.randomUUID(),
                entityType = "task",
                fromRole = "queue",
                toRole = "work",
                fromStatus = "",
                toStatus = "in-progress"
            )
        }
        assertTrue(exception.message!!.contains("fromStatus must not be blank"))
    }

    @Test
    fun `blank toStatus throws IllegalArgumentException`() {
        val exception = assertThrows<IllegalArgumentException> {
            RoleTransition(
                entityId = UUID.randomUUID(),
                entityType = "task",
                fromRole = "queue",
                toRole = "work",
                fromStatus = "pending",
                toStatus = ""
            )
        }
        assertTrue(exception.message!!.contains("toStatus must not be blank"))
    }

    @Test
    fun `fromRole equals toRole throws IllegalArgumentException`() {
        val exception = assertThrows<IllegalArgumentException> {
            RoleTransition(
                entityId = UUID.randomUUID(),
                entityType = "task",
                fromRole = "work",
                toRole = "work",
                fromStatus = "in-progress",
                toStatus = "implementing"
            )
        }
        assertTrue(exception.message!!.contains("fromRole and toRole must be different"))
        assertTrue(exception.message!!.contains("both are 'work'"))
    }

    @Test
    fun `valid entityType task is accepted`() {
        val transition = RoleTransition(
            entityId = UUID.randomUUID(),
            entityType = "task",
            fromRole = "queue",
            toRole = "work",
            fromStatus = "pending",
            toStatus = "in-progress"
        )
        assertEquals("task", transition.entityType)
    }

    @Test
    fun `valid entityType feature is accepted`() {
        val transition = RoleTransition(
            entityId = UUID.randomUUID(),
            entityType = "feature",
            fromRole = "queue",
            toRole = "work",
            fromStatus = "planned",
            toStatus = "in-progress"
        )
        assertEquals("feature", transition.entityType)
    }

    @Test
    fun `valid entityType project is accepted`() {
        val transition = RoleTransition(
            entityId = UUID.randomUUID(),
            entityType = "project",
            fromRole = "queue",
            toRole = "work",
            fromStatus = "planned",
            toStatus = "active"
        )
        assertEquals("project", transition.entityType)
    }
}
