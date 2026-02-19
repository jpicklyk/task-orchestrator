package io.github.jpicklyk.mcptask.interfaces.api.response

import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.*

class ResponseUtilsTest {

    @Test
    fun `success creates correct response`() {
        // Given
        val testData = TestData("test-id", "Test Data")

        // When
        val response = ResponseUtils.success(
            data = testData,
            message = "Success message"
        )

        // Then
        assertTrue(response is ApiResponse.Success)
        assertTrue(response.success)
        assertEquals("Success message", response.message)
        assertEquals(testData, (response as ApiResponse.Success).data)
        assertNotNull(response.metadata)
    }

    @Test
    fun `paginatedSuccess creates correct response`() {
        // Given
        val items = listOf(
            TestData("item-1", "Item 1"),
            TestData("item-2", "Item 2")
        )

        // When
        val response = ResponseUtils.paginatedSuccess(
            items = items,
            page = 1,
            pageSize = 10,
            totalItems = 20,
            message = "Page 1 of results"
        )

        // Then
        assertTrue(response is ApiResponse.Success)
        assertTrue(response.success)
        assertEquals("Page 1 of results", response.message)

        val data = (response as ApiResponse.Success).data
        assertEquals(items, data.items)
        assertEquals(1, data.pagination.page)
        assertEquals(10, data.pagination.pageSize)
        assertEquals(20, data.pagination.totalItems)
        assertEquals(2, data.pagination.totalPages)
        assertTrue(data.pagination.hasNext)
        assertFalse(data.pagination.hasPrevious)
    }

    @Test
    fun `bulkSuccess creates correct response with partial success`() {
        // Given
        val successItems = listOf(
            TestData("success-1", "Success 1"),
            TestData("success-2", "Success 2")
        )

        val failures = listOf(
            BulkOperationFailure(
                index = 2,
                error = ErrorDetails(
                    code = ErrorCode.VALIDATION_ERROR,
                    details = "Title cannot be empty"
                )
            )
        )

        // When
        val response = ResponseUtils.bulkSuccess(
            items = successItems,
            failedItems = failures
        )

        // Then
        assertTrue(response is ApiResponse.Success)
        assertTrue(response.success)
        assertEquals("2 items processed successfully, 1 failed", response.message)

        val data = (response as ApiResponse.Success).data
        assertEquals(successItems, data.items)
        assertEquals(2, data.count)
        assertEquals(1, data.failed)
        assertEquals(failures, data.failures)
    }

    @Test
    fun `error creates correct response`() {
        // When
        val response = ResponseUtils.error<TestData>(
            code = ErrorCode.VALIDATION_ERROR,
            message = "Validation failed",
            details = "Field 'name' cannot be empty"
        )

        // Then
        assertTrue(response is ApiResponse.Error)
        assertFalse(response.success)
        assertEquals("Validation failed", response.message)

        val error = (response as ApiResponse.Error).error
        assertEquals(ErrorCode.VALIDATION_ERROR, error.code)
        assertEquals("Field 'name' cannot be empty", error.details)
    }

    @Test
    fun `notFound creates correct response`() {
        // When
        val response = ResponseUtils.notFound<TestData>(
            resourceType = "TestData",
            id = "test-id"
        )

        // Then
        assertTrue(response is ApiResponse.Error)
        assertFalse(response.success)
        assertEquals("TestData not found", response.message)

        val error = (response as ApiResponse.Error).error
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, error.code)
        assertEquals("No TestData exists with ID test-id", error.details)
    }

    @Test
    fun `fromResult handles success correctly`() {
        // Given
        val testData = TestData("test-id", "Test Data")
        val result = Result.Success(testData)

        // When
        val response = ResponseUtils.fromResult(
            result = result,
            successMessage = "Item retrieved successfully"
        )

        // Then
        assertTrue(response is ApiResponse.Success)
        assertTrue(response.success)
        assertEquals("Item retrieved successfully", response.message)
        assertEquals(testData, (response as ApiResponse.Success).data)
    }

    @Test
    fun `fromResult handles not found error correctly`() {
        // Given
        val id = UUID.randomUUID()
        val result = Result.Error(
            RepositoryError.NotFound(id, EntityType.TASK, "TestData not found")
        )

        // When
        val response = ResponseUtils.fromResult(result)

        // Then
        assertTrue(response is ApiResponse.Error)
        assertFalse(response.success)
        assertEquals("TASK not found", response.message)

        val error = (response as ApiResponse.Error).error
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, error.code)
        assertEquals("No TASK exists with ID $id", error.details)
    }

    @Test
    fun `fromResult handles validation error correctly`() {
        // Given
        val result = Result.Error(
            RepositoryError.ValidationError("Name cannot be empty")
        )

        // When
        val response = ResponseUtils.fromResult(result)

        // Then
        assertTrue(response is ApiResponse.Error)
        assertFalse(response.success)
        assertEquals("Validation error", response.message)

        val error = (response as ApiResponse.Error).error
        assertEquals(ErrorCode.VALIDATION_ERROR, error.code)
        assertEquals("Name cannot be empty", error.details)
    }

    @Test
    fun `fromResult handles database error correctly`() {
        // Given
        val result = Result.Error(
            RepositoryError.DatabaseError("Database connection failed")
        )

        // When
        val response = ResponseUtils.fromResult(result)

        // Then
        assertTrue(response is ApiResponse.Error)
        assertFalse(response.success)
        assertEquals("Database operation failed", response.message)

        val error = (response as ApiResponse.Error).error
        assertEquals(ErrorCode.DATABASE_ERROR, error.code)
        assertEquals("Database connection failed", error.details)
    }

    @Test
    fun `fromResult with transformation handles success correctly`() {
        // Given
        val testData = TestData("test-id", "Test Data")
        val result = Result.Success(testData)

        // When
        val response = ResponseUtils.fromResult(
            result = result,
            successMessage = "Item retrieved successfully"
        ) { data ->
            TransformedData(data.id, data.name.uppercase())
        }

        // Then
        assertTrue(response is ApiResponse.Success)
        assertTrue(response.success)
        assertEquals("Item retrieved successfully", response.message)

        val transformedData = (response as ApiResponse.Success).data
        assertEquals("test-id", transformedData.id)
        assertEquals("TEST DATA", transformedData.transformedName)
    }

    // Test data classes
    data class TestData(val id: String, val name: String)
    data class TransformedData(val id: String, val transformedName: String)
}
