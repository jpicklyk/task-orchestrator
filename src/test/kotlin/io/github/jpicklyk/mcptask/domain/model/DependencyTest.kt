package io.github.jpicklyk.mcptask.domain.model

import io.github.jpicklyk.mcptask.domain.validation.ValidationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.*

class DependencyTest {

    @Test
    fun `should create valid dependency`() {
        val fromTaskId = UUID.randomUUID()
        val toTaskId = UUID.randomUUID()
        val dependency = Dependency(
            fromTaskId = fromTaskId,
            toTaskId = toTaskId,
            type = DependencyType.BLOCKS
        )

        assertEquals(fromTaskId, dependency.fromTaskId)
        assertEquals(toTaskId, dependency.toTaskId)
        assertEquals(DependencyType.BLOCKS, dependency.type)
    }

    @Test
    fun `should throw exception when creating dependency with same source and target`() {
        val taskId = UUID.randomUUID()
        val exception = assertThrows<ValidationException> {
            Dependency(
                fromTaskId = taskId,
                toTaskId = taskId
            )
        }
        assertTrue(exception.message!!.contains("cannot depend on itself"))
    }

    @Test
    fun `should create all dependency types`() {
        val fromTaskId = UUID.randomUUID()
        val toTaskId = UUID.randomUUID()

        val blocks = Dependency(
            fromTaskId = fromTaskId,
            toTaskId = toTaskId,
            type = DependencyType.BLOCKS
        )
        assertEquals(DependencyType.BLOCKS, blocks.type)

        val isBlockedBy = Dependency(
            fromTaskId = fromTaskId,
            toTaskId = toTaskId,
            type = DependencyType.IS_BLOCKED_BY
        )
        assertEquals(DependencyType.IS_BLOCKED_BY, isBlockedBy.type)

        val relatesTo = Dependency(
            fromTaskId = fromTaskId,
            toTaskId = toTaskId,
            type = DependencyType.RELATES_TO
        )
        assertEquals(DependencyType.RELATES_TO, relatesTo.type)
    }

    @Test
    fun `should correctly parse dependency type from string`() {
        assertEquals(DependencyType.BLOCKS, DependencyType.fromString("BLOCKS"))
        assertEquals(DependencyType.IS_BLOCKED_BY, DependencyType.fromString("IS_BLOCKED_BY"))
        assertEquals(DependencyType.RELATES_TO, DependencyType.fromString("RELATES_TO"))

        // Case insensitive and with dashes
        assertEquals(DependencyType.IS_BLOCKED_BY, DependencyType.fromString("is-blocked-by"))

        val exception = assertThrows<ValidationException> {
            DependencyType.fromString("invalid_type")
        }
        assertTrue(exception.message!!.contains("Invalid dependency type"))
    }
}
