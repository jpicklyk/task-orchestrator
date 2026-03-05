package io.github.jpicklyk.mcptask.current.domain.repository

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class ResultTest {

    private val testError = RepositoryError.NotFound(UUID.randomUUID(), "Not found")

    // ──────────────────────────────────────────────
    // getOrElse
    // ──────────────────────────────────────────────

    @Test
    fun `getOrElse returns data on success`() {
        val result: Result<String> = Result.Success("hello")
        assertEquals("hello", result.getOrElse("default"))
    }

    @Test
    fun `getOrElse returns default on error`() {
        val result: Result<String> = Result.Error(testError)
        assertEquals("default", result.getOrElse("default"))
    }

    @Test
    fun `getOrElse returns empty list on error for list types`() {
        val result: Result<List<Int>> = Result.Error(testError)
        assertEquals(emptyList<Int>(), result.getOrElse(emptyList()))
    }

    @Test
    fun `getOrElse returns populated list on success`() {
        val result: Result<List<Int>> = Result.Success(listOf(1, 2, 3))
        assertEquals(listOf(1, 2, 3), result.getOrElse(emptyList()))
    }

    // ──────────────────────────────────────────────
    // getOrNull
    // ──────────────────────────────────────────────

    @Test
    fun `getOrNull returns data on success`() {
        val result: Result<String> = Result.Success("hello")
        assertEquals("hello", result.getOrNull())
    }

    @Test
    fun `getOrNull returns null on error`() {
        val result: Result<String> = Result.Error(testError)
        assertNull(result.getOrNull())
    }

    // ──────────────────────────────────────────────
    // map
    // ──────────────────────────────────────────────

    @Test
    fun `map transforms success data`() {
        val result: Result<Int> = Result.Success(5)
        val mapped = result.map { it * 2 }
        assertEquals(10, (mapped as Result.Success).data)
    }

    @Test
    fun `map passes error through unchanged`() {
        val result: Result<Int> = Result.Error(testError)
        val mapped = result.map { it * 2 }
        assertTrue(mapped.isError())
        assertEquals(testError, (mapped as Result.Error).error)
    }

    // ──────────────────────────────────────────────
    // isSuccess / isError
    // ──────────────────────────────────────────────

    @Test
    fun `isSuccess returns true for success`() {
        assertTrue(Result.Success("data").isSuccess())
    }

    @Test
    fun `isSuccess returns false for error`() {
        assertFalse(Result.Error(testError).isSuccess())
    }

    @Test
    fun `isError returns true for error`() {
        assertTrue(Result.Error(testError).isError())
    }

    @Test
    fun `isError returns false for success`() {
        assertFalse(Result.Success("data").isError())
    }

    // ──────────────────────────────────────────────
    // onSuccess / onError callbacks
    // ──────────────────────────────────────────────

    @Test
    fun `onSuccess executes block for success`() {
        var captured: String? = null
        Result.Success("hello").onSuccess { captured = it }
        assertEquals("hello", captured)
    }

    @Test
    fun `onSuccess does not execute block for error`() {
        var executed = false
        Result.Error(testError).onSuccess { executed = true }
        assertFalse(executed)
    }

    @Test
    fun `onError executes block for error`() {
        var captured: RepositoryError? = null
        Result.Error(testError).onError { captured = it }
        assertEquals(testError, captured)
    }

    @Test
    fun `onError does not execute block for success`() {
        var executed = false
        Result.Success("hello").onError { executed = true }
        assertFalse(executed)
    }

    @Test
    fun `onSuccess returns same result for chaining`() {
        val result = Result.Success("hello")
        assertSame(result, result.onSuccess { })
    }

    @Test
    fun `onError returns same result for chaining`() {
        val result: Result<String> = Result.Error(testError)
        assertSame(result, result.onError { })
    }
}
