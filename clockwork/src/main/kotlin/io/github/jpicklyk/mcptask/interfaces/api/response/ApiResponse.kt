package io.github.jpicklyk.mcptask.interfaces.api.response

import java.time.Instant
import java.util.*

/**
 * Sealed class representing a standardized API response structure.
 * All API responses follow this pattern for consistency and type safety.
 */
sealed class ApiResponse<T> {
    abstract val success: Boolean
    abstract val message: String?
    abstract val metadata: ResponseMetadata

    /**
     * Represents a successful API response with data
     */
    data class Success<T>(
        override val message: String? = null,
        val data: T,
        override val metadata: ResponseMetadata = ResponseMetadata()
    ) : ApiResponse<T>() {
        override val success: Boolean = true
        val error: ErrorDetails? = null // Explicitly null in success responses for JSON serialization
    }

    /**
     * Represents an error API response with error details
     */
    data class Error<T>(
        override val message: String,
        val error: ErrorDetails,
        override val metadata: ResponseMetadata = ResponseMetadata()
    ) : ApiResponse<T>() {
        override val success: Boolean = false
        val data: T? = null // Explicitly null in error responses for JSON serialization
    }
}

/**
 * Contains standardized error details
 */
data class ErrorDetails(
    val code: ErrorCode,
    val details: String? = null
)

/**
 * Standardized error codes for consistent error handling
 */
enum class ErrorCode {
    VALIDATION_ERROR,
    RESOURCE_NOT_FOUND,
    DUPLICATE_RESOURCE,
    DATABASE_ERROR,
    PERMISSION_DENIED,
    DEPENDENCY_ERROR,
    INTERNAL_ERROR;

    override fun toString(): String = name
}

/**
 * Metadata providing additional context for API responses
 */
data class ResponseMetadata(
    val timestamp: String = Instant.now().toString(),
    val requestId: String = UUID.randomUUID().toString(),
    val version: String = "1.0.0"
)

/**
 * Pagination information for paginated responses
 */
data class PaginationInfo(
    val page: Int,
    val pageSize: Int,
    val totalItems: Int,
    val totalPages: Int,
    val hasNext: Boolean,
    val hasPrevious: Boolean
) {
    companion object {
        /**
         * Creates pagination info from total count and request parameters
         */
        fun create(totalItems: Int, page: Int, pageSize: Int): PaginationInfo {
            val totalPages = if (totalItems == 0) 1 else (totalItems + pageSize - 1) / pageSize
            return PaginationInfo(
                page = page,
                pageSize = pageSize,
                totalItems = totalItems,
                totalPages = totalPages,
                hasNext = page < totalPages,
                hasPrevious = page > 1
            )
        }
    }
}

/**
 * Container for paginated data
 */
data class PaginatedResponse<T>(
    val items: List<T>,
    val pagination: PaginationInfo
)

/**
 * Result of a bulk operation, including success and failure information
 */
data class BulkOperationResult<T>(
    val items: List<T>,
    val count: Int,
    val failed: Int = 0,
    val failures: List<BulkOperationFailure>? = null
)

/**
 * Details about a failure in a bulk operation
 */
data class BulkOperationFailure(
    val index: Int,
    val error: ErrorDetails
)
