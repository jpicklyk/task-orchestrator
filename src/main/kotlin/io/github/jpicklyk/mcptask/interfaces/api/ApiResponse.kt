package io.github.jpicklyk.mcptask.interfaces.api

import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

/**
 * Standardized API response wrapper for all API endpoints.
 * Provides a consistent format for both success and error responses.
 */
@Serializable
sealed class ApiResponse<T> {
    abstract val success: Boolean
    abstract val message: String?
    abstract val metadata: ResponseMetadata
}

/**
 * Success response containing the requested data.
 * @param message Optional success message
 * @param data The operation result
 * @param metadata Additional information about the response
 */
@Serializable
data class SuccessResponse<T>(
    override val success: Boolean = true,
    override val message: String? = null,
    val data: T,
    override val metadata: ResponseMetadata = ResponseMetadata(),
    val error: ErrorDetails? = null
) : ApiResponse<T>()

/**
 * Error response indicating operation failure.
 * @param message Human-readable error message
 * @param error Detailed error information
 * @param metadata Additional information about the response
 */
@Serializable
data class ErrorResponse<T>(
    override val success: Boolean = false,
    override val message: String,
    val error: ErrorDetails,
    override val metadata: ResponseMetadata = ResponseMetadata(),
    val data: T? = null
) : ApiResponse<T>()

/**
 * Additional metadata about the API response.
 */
@Serializable
data class ResponseMetadata(
    val timestamp: String = Instant.now().toString(),
    val requestId: String = UUID.randomUUID().toString(),
    val version: String = "1.0.0"
)

/**
 * Detailed information about an error.
 */
@Serializable
data class ErrorDetails(
    val code: String,
    val details: String? = null
)

/**
 * Predefined error codes for consistent error handling.
 */
object ErrorCodes {
    const val VALIDATION_ERROR = "VALIDATION_ERROR"
    const val RESOURCE_NOT_FOUND = "RESOURCE_NOT_FOUND"
    const val DUPLICATE_RESOURCE = "DUPLICATE_RESOURCE"
    const val DATABASE_ERROR = "DATABASE_ERROR"
    const val PERMISSION_DENIED = "PERMISSION_DENIED"
    const val DEPENDENCY_ERROR = "DEPENDENCY_ERROR"
    const val INTERNAL_ERROR = "INTERNAL_ERROR"
}

/**
 * Pagination information for endpoints that return collections.
 */
@Serializable
data class PaginationInfo(
    val page: Int,
    val pageSize: Int,
    val totalItems: Int,
    val totalPages: Int,
    val hasNext: Boolean,
    val hasPrevious: Boolean
)

/**
 * Standard wrapper for paginated collections.
 */
@Serializable
data class PaginatedResponse<T>(
    val items: List<T>,
    val pagination: PaginationInfo
)

/**
 * Result of a bulk operation.
 */
@Serializable
data class BulkOperationResult<T>(
    val items: List<T>,
    val count: Int,
    val failed: Int = 0,
    val failures: List<BulkOperationFailure>? = null
)

/**
 * Information about a failed item in a bulk operation.
 */
@Serializable
data class BulkOperationFailure(
    val index: Int,
    val error: ErrorDetails
)
