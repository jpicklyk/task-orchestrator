package io.github.jpicklyk.mcptask.domain.repository

import io.github.jpicklyk.mcptask.domain.model.EntityType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.*

class ResultTest {

    @Test
    fun `Success should wrap data correctly`() {
        // Arrange
        val data = "test data"

        // Act
        val result: Result<String> = Result.Success(data)

        // Assert
        assertEquals(data, (result as Result.Success).data)
        assertTrue(result.isSuccess())
        assertEquals(data, result.getOrNull())
    }

    @Test
    fun `map should transform success data`() {
        // Arrange
        val data = 5
        val result: Result<Int> = Result.Success(data)

        // Act
        val mappedResult = result.map { it * 2 }

        // Assert
        assertTrue(mappedResult is Result.Success)
        assertEquals(10, (mappedResult as Result.Success).data)
    }

    @Test
    fun `map should pass through error`() {
        // Arrange
        val error = RepositoryError.DatabaseError("Database error")
        val result: Result<Int> = Result.Error(error)

        // Act
        val mappedResult = result.map { it * 2 }

        // Assert
        assertTrue(mappedResult is Result.Error)
        assertEquals(error, (mappedResult as Result.Error).error)
    }

    @Test
    fun `onSuccess should execute block for success`() = runBlocking {
        // Arrange
        val data = "test data"
        val result: Result<String> = Result.Success(data)
        var blockExecuted = false
        var receivedData: String? = null

        // Act
        result.onSuccess {
            blockExecuted = true
            receivedData = it
        }

        // Assert
        assertTrue(blockExecuted)
        assertEquals(data, receivedData)
    }

    @Test
    fun `onSuccess should not execute block for error`() = runBlocking {
        // Arrange
        val error = RepositoryError.ValidationError("Validation error")
        val result: Result<String> = Result.Error(error)
        var blockExecuted = false

        // Act
        result.onSuccess {
            blockExecuted = true
        }

        // Assert
        assertTrue(!blockExecuted)
    }

    @Test
    fun `onError should execute block for error`() = runBlocking {
        // Arrange
        val error = RepositoryError.ValidationError("Validation error")
        val result: Result<String> = Result.Error(error)
        var blockExecuted = false
        var receivedError: RepositoryError? = null

        // Act
        result.onError {
            blockExecuted = true
            receivedError = it
        }

        // Assert
        assertTrue(blockExecuted)
        assertEquals(error, receivedError)
    }

    @Test
    fun `onError should not execute block for success`() = runBlocking {
        // Arrange
        val data = "test data"
        val result: Result<String> = Result.Success(data)
        var blockExecuted = false

        // Act
        result.onError {
            blockExecuted = true
        }

        // Assert
        assertTrue(!blockExecuted)
    }

    @Test
    fun `RepositoryError subtypes should be distinguishable`() {
        // Arrange
        val uuid = UUID.randomUUID()
        val notFound = RepositoryError.NotFound(uuid, EntityType.TASK, "Entity not found")
        val validation = RepositoryError.ValidationError("Validation error")
        val database = RepositoryError.DatabaseError("Database error")
        val conflict = RepositoryError.ConflictError("Conflict error")
        val unknown = RepositoryError.UnknownError("Unknown error")

        // Assert
        assertEquals(uuid, notFound.id)
        assertEquals(EntityType.TASK, notFound.entityType)
        assertEquals("Entity not found", notFound.message)

        assertEquals("Validation error", validation.message)

        assertEquals("Database error", database.message)

        assertEquals("Conflict error", conflict.message)

        assertEquals("Unknown error", unknown.message)
    }
}