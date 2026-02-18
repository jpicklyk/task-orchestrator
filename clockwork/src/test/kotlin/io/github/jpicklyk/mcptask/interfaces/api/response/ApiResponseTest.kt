package io.github.jpicklyk.mcptask.interfaces.api.response

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ApiResponseTest {

    @Test
    fun `Success response has correct structure`() {
        // Given
        val testData = TestData("test-id", "Test Data")

        // When
        val response = ApiResponse.Success(
            message = "Success message",
            data = testData,
            metadata = ResponseMetadata(
                timestamp = "2025-05-09T14:30:22Z",
                requestId = "test-request-id",
                version = "1.0.0"
            )
        )

        // Then
        assertTrue(response.success)
        assertEquals("Success message", response.message)
        assertEquals(testData, response.data)
        assertEquals("2025-05-09T14:30:22Z", response.metadata.timestamp)
        assertEquals("test-request-id", response.metadata.requestId)
        assertEquals("1.0.0", response.metadata.version)
        assertNull(response.error)
    }

    @Test
    fun `Error response has correct structure`() {
        // Given
        val errorDetails = ErrorDetails(
            code = ErrorCode.RESOURCE_NOT_FOUND,
            details = "Resource with ID test-id not found"
        )

        // When
        val response = ApiResponse.Error<TestData>(
            message = "Error message",
            error = errorDetails,
            metadata = ResponseMetadata(
                timestamp = "2025-05-09T14:30:22Z",
                requestId = "test-request-id",
                version = "1.0.0"
            )
        )

        // Then
        assertFalse(response.success)
        assertEquals("Error message", response.message)
        assertEquals(errorDetails, response.error)
        assertEquals("2025-05-09T14:30:22Z", response.metadata.timestamp)
        assertEquals("test-request-id", response.metadata.requestId)
        assertEquals("1.0.0", response.metadata.version)
        assertNull(response.data)
    }

    @Test
    fun `PaginationInfo calculates correctly`() {
        // When
        val pagination = PaginationInfo.create(
            totalItems = 45,
            page = 2,
            pageSize = 20
        )

        // Then
        assertEquals(2, pagination.page)
        assertEquals(20, pagination.pageSize)
        assertEquals(45, pagination.totalItems)
        assertEquals(3, pagination.totalPages)
        assertTrue(pagination.hasNext)
        assertTrue(pagination.hasPrevious)
    }

    @Test
    fun `PaginationInfo handles edge cases correctly`() {
        // Case 1: Empty result set
        val emptyPagination = PaginationInfo.create(
            totalItems = 0,
            page = 1,
            pageSize = 20
        )

        assertEquals(1, emptyPagination.totalPages)
        assertFalse(emptyPagination.hasNext)
        assertFalse(emptyPagination.hasPrevious)

        // Case 2: Last page
        val lastPagePagination = PaginationInfo.create(
            totalItems = 45,
            page = 3,
            pageSize = 20
        )

        assertEquals(3, lastPagePagination.totalPages)
        assertFalse(lastPagePagination.hasNext)
        assertTrue(lastPagePagination.hasPrevious)

        // Case 3: Exactly one full page
        val exactPagePagination = PaginationInfo.create(
            totalItems = 20,
            page = 1,
            pageSize = 20
        )

        assertEquals(1, exactPagePagination.totalPages)
        assertFalse(exactPagePagination.hasNext)
        assertFalse(exactPagePagination.hasPrevious)
    }

    @Test
    fun `BulkOperationResult handles partial success correctly`() {
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
        val result = BulkOperationResult(
            items = successItems,
            count = successItems.size,
            failed = failures.size,
            failures = failures
        )

        // Then
        assertEquals(2, result.count)
        assertEquals(1, result.failed)
        assertEquals(successItems, result.items)
        assertEquals(failures, result.failures)
    }

    // Test data class
    data class TestData(val id: String, val name: String)
}
