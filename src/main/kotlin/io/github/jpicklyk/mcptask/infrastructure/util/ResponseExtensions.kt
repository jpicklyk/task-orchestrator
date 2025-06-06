package io.github.jpicklyk.mcptask.infrastructure.util

import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.interfaces.api.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Extension functions to convert domain results to standardized API responses.
 */

/**
 * Converts a domain Result to an ApiResponse.
 */
fun <T> Result<T>.toApiResponse(
    successMessage: String? = null,
    errorMessage: String? = null
): ApiResponse<T> = when (this) {
    is Result.Success -> SuccessResponse(
        message = successMessage,
        data = data
    )
    is Result.Error -> ErrorResponse(
        message = errorMessage ?: error.toErrorMessage(),
        error = error.toErrorDetails()
    )
}

/**
 * Converts a domain Result to a JSON object for MCP tool responses.
 */
@OptIn(ExperimentalSerializationApi::class)
fun <T> Result<T>.toJsonResponse(
    successMessage: String? = null,
    errorMessage: String? = null,
    dataSerializer: (T) -> JsonElement
): JsonObject = when (this) {
    is Result.Success -> ResponseUtil.createSuccessResponse(
        message = successMessage ?: "Operation completed successfully",
        data = dataSerializer(data)
    )

    is Result.Error -> ResponseUtil.createErrorResponse(
        message = errorMessage ?: error.toErrorMessage(),
        code = error.toErrorCode(),
        details = error.toErrorDetailString() ?: ""
    )
}

/**
 * Creates a metadata object for API responses.
 * @deprecated Use ResponseUtil.createMetadata() instead
 */
@Deprecated(
    "Use ResponseUtil.createMetadata() instead",
    replaceWith = ReplaceWith("ResponseUtil.createMetadata()")
)
fun createMetadata(): JsonObject = ResponseUtil.createMetadata()

/**
 * Creates an error details object from a repository error.
 */
fun createErrorDetails(error: RepositoryError): JsonObject = buildJsonObject {
    put("code", error.toErrorCode())
    put("details", error.toErrorDetailString() ?: "")
}

/**
 * Converts a repository error to a human-readable error message.
 */
private fun RepositoryError.toErrorMessage(): String = when (this) {
    is RepositoryError.NotFound -> "Resource not found"
    is RepositoryError.ValidationError -> "Validation error: $message"
    is RepositoryError.DatabaseError -> "Database error occurred"
    is RepositoryError.ConflictError -> "Conflict error: $message"
    is RepositoryError.UnknownError -> "An unknown error occurred"
}

/**
 * Converts a repository error to a standardized error code.
 */
private fun RepositoryError.toErrorCode(): String = when (this) {
    is RepositoryError.NotFound -> ErrorCodes.RESOURCE_NOT_FOUND
    is RepositoryError.ValidationError -> ErrorCodes.VALIDATION_ERROR
    is RepositoryError.DatabaseError -> ErrorCodes.DATABASE_ERROR
    is RepositoryError.ConflictError -> ErrorCodes.DUPLICATE_RESOURCE
    is RepositoryError.UnknownError -> ErrorCodes.INTERNAL_ERROR
}

/**
 * Converts a repository error to a detailed error message.
 */
private fun RepositoryError.toErrorDetailString(): String? = when (this) {
    is RepositoryError.NotFound -> "No resource found with ID $id (type: $entityType)"
    is RepositoryError.ValidationError -> message
    is RepositoryError.DatabaseError -> message
    is RepositoryError.ConflictError -> message
    is RepositoryError.UnknownError -> message
}

/**
 * Converts a repository error to error details.
 */
private fun RepositoryError.toErrorDetails(): ErrorDetails = when (this) {
    is RepositoryError.NotFound -> ErrorDetails(
        code = ErrorCodes.RESOURCE_NOT_FOUND,
        details = "No resource found with ID $id (type: $entityType)"
    )
    is RepositoryError.ValidationError -> ErrorDetails(
        code = ErrorCodes.VALIDATION_ERROR,
        details = message
    )
    is RepositoryError.DatabaseError -> ErrorDetails(
        code = ErrorCodes.DATABASE_ERROR,
        details = message
    )
    is RepositoryError.ConflictError -> ErrorDetails(
        code = ErrorCodes.DUPLICATE_RESOURCE,
        details = message
    )
    is RepositoryError.UnknownError -> ErrorDetails(
        code = ErrorCodes.INTERNAL_ERROR,
        details = message
    )
}

/**
 * Creates a success response with the given data.
 */
fun <T> success(
    data: T, 
    message: String? = null
): ApiResponse<T> = SuccessResponse(
    message = message,
    data = data
)

/**
 * Creates an error response with the given details.
 */
fun <T> error(
    message: String,
    code: String = ErrorCodes.INTERNAL_ERROR,
    details: String? = null
): ApiResponse<T> = ErrorResponse(
    message = message,
    error = ErrorDetails(code = code, details = details)
)

/**
 * Creates a paginated response.
 */
fun <T> createPaginatedResponse(
    items: List<T>,
    page: Int,
    pageSize: Int,
    totalItems: Int
): PaginatedResponse<T> {
    val totalPages = if (pageSize > 0) (totalItems + pageSize - 1) / pageSize else 0

    return PaginatedResponse(
        items = items,
        pagination = PaginationInfo(
            page = page,
            pageSize = pageSize,
            totalItems = totalItems,
            totalPages = totalPages,
            hasNext = page < totalPages,
            hasPrevious = page > 1
        )
    )
}

/**
 * Helper function to create a JsonObject from pairs.
 */
fun jsonObjectOf(vararg pairs: Pair<String, JsonElement>) = buildJsonObject {
    pairs.forEach { (key, value) ->
        put(key, value)
    }
}