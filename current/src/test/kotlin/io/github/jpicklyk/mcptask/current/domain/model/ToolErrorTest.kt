package io.github.jpicklyk.mcptask.current.domain.model

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ToolErrorTest {
    // -------------------------------------------------------------------------
    // ErrorKind
    // -------------------------------------------------------------------------

    @Test
    fun `ErrorKind has all three expected variants`() {
        assertEquals(3, ErrorKind.entries.size)
        assertNotNull(ErrorKind.TRANSIENT)
        assertNotNull(ErrorKind.PERMANENT)
        assertNotNull(ErrorKind.SHEDDING)
    }

    @Test
    fun `ErrorKind fromString round-trip all values`() {
        assertEquals(ErrorKind.TRANSIENT, ErrorKind.fromString("TRANSIENT"))
        assertEquals(ErrorKind.PERMANENT, ErrorKind.fromString("PERMANENT"))
        assertEquals(ErrorKind.SHEDDING, ErrorKind.fromString("SHEDDING"))
    }

    @Test
    fun `ErrorKind fromString is case insensitive`() {
        assertEquals(ErrorKind.TRANSIENT, ErrorKind.fromString("transient"))
        assertEquals(ErrorKind.PERMANENT, ErrorKind.fromString("permanent"))
        assertEquals(ErrorKind.SHEDDING, ErrorKind.fromString("shedding"))
        assertEquals(ErrorKind.TRANSIENT, ErrorKind.fromString("Transient"))
    }

    @Test
    fun `ErrorKind fromString invalid value throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            ErrorKind.fromString("UNKNOWN")
        }
    }

    @Test
    fun `ErrorKind toJsonString returns lowercase`() {
        assertEquals("transient", ErrorKind.TRANSIENT.toJsonString())
        assertEquals("permanent", ErrorKind.PERMANENT.toJsonString())
        assertEquals("shedding", ErrorKind.SHEDDING.toJsonString())
    }

    // -------------------------------------------------------------------------
    // ToolError construction
    // -------------------------------------------------------------------------

    @Test
    fun `ToolError direct construction with all required fields`() {
        val err =
            ToolError(
                kind = ErrorKind.PERMANENT,
                code = "VALIDATION_ERROR",
                message = "field 'x' is required"
            )
        assertEquals(ErrorKind.PERMANENT, err.kind)
        assertEquals("VALIDATION_ERROR", err.code)
        assertEquals("field 'x' is required", err.message)
        assertNull(err.retryAfterMs)
        assertNull(err.contendedItemId)
    }

    @Test
    fun `ToolError retryAfterMs and contendedItemId default to null`() {
        val err = ToolError(ErrorKind.TRANSIENT, "LOCK_CONTENTION", "item locked")
        assertNull(err.retryAfterMs)
        assertNull(err.contendedItemId)
    }

    @Test
    fun `ToolError all fields populated`() {
        val itemId = UUID.randomUUID()
        val err =
            ToolError(
                kind = ErrorKind.TRANSIENT,
                code = "LOCK_CONTENTION",
                message = "item is locked by another agent",
                retryAfterMs = 500L,
                contendedItemId = itemId
            )
        assertEquals(ErrorKind.TRANSIENT, err.kind)
        assertEquals("LOCK_CONTENTION", err.code)
        assertEquals("item is locked by another agent", err.message)
        assertEquals(500L, err.retryAfterMs)
        assertEquals(itemId, err.contendedItemId)
    }

    @Test
    fun `ToolError is a data class with value equality`() {
        val id = UUID.randomUUID()
        val a = ToolError(ErrorKind.TRANSIENT, "CODE", "msg", 100L, id)
        val b = ToolError(ErrorKind.TRANSIENT, "CODE", "msg", 100L, id)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    // -------------------------------------------------------------------------
    // Factory helpers
    // -------------------------------------------------------------------------

    @Test
    fun `ToolError transient factory without contendedItemId`() {
        val err = ToolError.transient("LOCK_CONTENTION", "item locked")
        assertEquals(ErrorKind.TRANSIENT, err.kind)
        assertEquals("LOCK_CONTENTION", err.code)
        assertEquals("item locked", err.message)
        assertNull(err.retryAfterMs)
        assertNull(err.contendedItemId)
    }

    @Test
    fun `ToolError transient factory with contendedItemId`() {
        val itemId = UUID.randomUUID()
        val err = ToolError.transient("LOCK_CONTENTION", "item locked", contendedItemId = itemId)
        assertEquals(ErrorKind.TRANSIENT, err.kind)
        assertEquals(itemId, err.contendedItemId)
        assertNull(err.retryAfterMs)
    }

    @Test
    fun `ToolError permanent factory produces PERMANENT kind`() {
        val err = ToolError.permanent("VALIDATION_ERROR", "missing required field")
        assertEquals(ErrorKind.PERMANENT, err.kind)
        assertEquals("VALIDATION_ERROR", err.code)
        assertEquals("missing required field", err.message)
        assertNull(err.retryAfterMs)
        assertNull(err.contendedItemId)
    }

    @Test
    fun `ToolError shedding factory without retryAfterMs`() {
        val err = ToolError.shedding("CAPACITY_EXCEEDED", "writer queue saturated")
        assertEquals(ErrorKind.SHEDDING, err.kind)
        assertEquals("CAPACITY_EXCEEDED", err.code)
        assertNull(err.retryAfterMs)
        assertNull(err.contendedItemId)
    }

    @Test
    fun `ToolError shedding factory with retryAfterMs`() {
        val err = ToolError.shedding("CAPACITY_EXCEEDED", "writer queue saturated", retryAfterMs = 3000L)
        assertEquals(ErrorKind.SHEDDING, err.kind)
        assertEquals(3000L, err.retryAfterMs)
    }

    @Test
    fun `ToolError shedding factory never sets contendedItemId`() {
        val err = ToolError.shedding("CODE", "msg", retryAfterMs = 1000L)
        assertNull(err.contendedItemId)
    }

    @Test
    fun `ToolError transient factory never sets retryAfterMs`() {
        val err = ToolError.transient("CODE", "msg")
        assertNull(err.retryAfterMs)
    }
}
