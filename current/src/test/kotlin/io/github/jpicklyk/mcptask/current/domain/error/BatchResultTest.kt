package io.github.jpicklyk.mcptask.current.domain.error

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BatchResultTest {
    private val notFound = ErrorFixtures.error(ErrorCode.NOT_FOUND)
    private val gateBlocked = ErrorFixtures.error(ErrorCode.GATE_BLOCKED)

    // S8 Outcome (oracle: task-scope D7)
    @Test
    fun `S8 map on Ok transforms the value`() {
        assertEquals(Outcome.Ok(6), Outcome.Ok(2).map { it * 3 })
    }

    @Test
    fun `S8 map on Err returns the same Err and never runs the transform`() {
        val err: Outcome<Int> = Outcome.Err(notFound)
        var called = false
        val mapped =
            err.map {
                called = true
                it * 3
            }
        assertSame(err, mapped)
        assertEquals(notFound, (mapped as Outcome.Err).error)
        assertFalse(called)
    }

    @Test
    fun `S8 flatMap from Ok to Err yields that Err`() {
        val result = Outcome.Ok(1).flatMap { Outcome.Err(gateBlocked) }
        assertEquals(Outcome.Err(gateBlocked), result)
    }

    @Test
    fun `S8 flatMap from Ok to Ok yields the inner Ok`() {
        assertEquals(Outcome.Ok("2"), Outcome.Ok(2).flatMap { Outcome.Ok(it.toString()) })
    }

    @Test
    fun `S8 flatMap on Err keeps the original error and never runs the transform`() {
        val err: Outcome<Int> = Outcome.Err(notFound)
        var called = false
        val result =
            err.flatMap {
                called = true
                Outcome.Ok(it)
            }
        assertEquals(notFound, (result as Outcome.Err).error)
        assertFalse(called)
    }

    @Test
    fun `S8 getOrNull returns the value for Ok and null for Err`() {
        assertEquals(5, Outcome.Ok(5).getOrNull())
        val err: Outcome<Int> = Outcome.Err(notFound)
        assertNull(err.getOrNull())
    }

    // S9 (oracle: envelope section 2 batch results)
    @Test
    fun `S9 all Ok batch is ok with full summary and no failure`() {
        val batch = BatchResult(listOf(Outcome.Ok(1), Outcome.Ok(2), Outcome.Ok(3)))
        assertTrue(batch.ok)
        assertEquals(BatchSummary(3, 0), batch.summary)
        assertNull(batch.failure())
    }

    @Test
    fun `S9 atomic all Ok batch is ok with no failure`() {
        val batch = BatchResult(listOf(Outcome.Ok(1), Outcome.Ok(2)), atomic = true)
        assertTrue(batch.ok)
        assertNull(batch.failure())
    }

    // S10 non-atomic partial failure (oracle: envelope section 2 lines 93-96)
    @Test
    fun `S10 non-atomic mixed batch keeps positions and reports partial_failure`() {
        val batch = BatchResult(listOf(Outcome.Ok("a"), Outcome.Err(notFound)))
        assertEquals(Outcome.Ok("a"), batch.results[0])
        assertEquals(Outcome.Err(notFound), batch.results[1])
        assertEquals(BatchSummary(1, 1), batch.summary)
        assertFalse(batch.ok)
        val failure = assertNotNull(batch.failure())
        assertEquals(ErrorCode.PARTIAL_FAILURE, failure.error.code)
        assertEquals(ErrorKind.PERMANENT, failure.error.kind)
        assertNull(failure.error.fix)
        assertNull(failure.error.detail)
        assertNull(failure.index)
        assertTrue(failure.error.message.isNotBlank())
    }

    @Test
    fun `S10 non-atomic batch where every element fails counts all as failed`() {
        val batch = BatchResult(listOf(Outcome.Err(notFound), Outcome.Err(gateBlocked)))
        assertEquals(BatchSummary(0, 2), batch.summary)
        assertFalse(batch.ok)
        assertEquals(ErrorCode.PARTIAL_FAILURE, assertNotNull(batch.failure()).error.code)
    }

    @Test
    fun `S10 atomic defaults to false`() {
        assertFalse(BatchResult(listOf(Outcome.Ok(1))).atomic)
    }

    // S11 atomic (oracle: envelope section 2 lines 97-99, task-scope D8)
    @Test
    fun `S11 atomic batch failure is the lowest index error unchanged with its index`() {
        val batch = BatchResult(listOf(Outcome.Ok(1), Outcome.Err(gateBlocked), Outcome.Err(notFound)), atomic = true)
        assertFalse(batch.ok)
        val failure = assertNotNull(batch.failure())
        assertEquals(gateBlocked, failure.error)
        assertEquals(ErrorCode.GATE_BLOCKED, failure.error.code)
        assertEquals(1, failure.index)
        assertEquals(BatchSummary(1, 2), batch.summary)
    }

    @Test
    fun `S11 atomic batch failing at index zero reports index zero`() {
        val batch = BatchResult(listOf(Outcome.Err(notFound), Outcome.Ok(1)), atomic = true)
        val failure = assertNotNull(batch.failure())
        assertEquals(0, failure.index)
        assertEquals(notFound, failure.error)
    }

    @Test
    fun `S11 atomic batch with the only failure last reports the last index`() {
        val batch = BatchResult(listOf(Outcome.Ok(1), Outcome.Ok(2), Outcome.Err(notFound)), atomic = true)
        assertEquals(2, assertNotNull(batch.failure()).index)
    }

    // S14 (oracle: task-scope D8)
    @Test
    fun `S14 empty results are rejected`() {
        assertFailsWith<IllegalArgumentException> { BatchResult<Int>(emptyList()) }
        assertFailsWith<IllegalArgumentException> { BatchResult<Int>(emptyList(), atomic = true) }
    }
}
