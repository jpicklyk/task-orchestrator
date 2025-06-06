package io.github.jpicklyk.mcptask.interfaces.api.response

import io.github.jpicklyk.mcptask.domain.repository.Result

/**
 * Utility functions for creating standardized API responses
 */
object ResponseUtils {
    /**
     * Creates a successful response with data
     */
    fun <T> success(
        data: T,
        message: String? = null,
        metadata: ResponseMetadata = ResponseMetadata()
    ): ApiResponse<T> {
        return ApiResponse.Success(
            message = message,
            data = data,
            metadata = metadata
        )
    }

    /**
     * Creates a successful paginated response
     */
    fun <T> paginatedSuccess(
        items: List<T>,
        page: Int,
        pageSize: Int,
        totalItems: Int,
        message: String? = null,
        metadata: ResponseMetadata = ResponseMetadata()
    ): ApiResponse<PaginatedResponse<T>> {
        val pagination = PaginationInfo.create(
            totalItems = totalItems,
            page = page,
            pageSize = pageSize
        )

        return success(
            data = PaginatedResponse(items, pagination),
            message = message,
            metadata = metadata
        )
    }

    /**
     * Creates a successful bulk operation response
     */
    fun <T> bulkSuccess(
        items: List<T>,
        failedItems: List<BulkOperationFailure> = emptyList(),
        message: String? = null,
        metadata: ResponseMetadata = ResponseMetadata()
    ): ApiResponse<BulkOperationResult<T>> {
        val result = BulkOperationResult(
            items = items,
            count = items.size,
            failed = failedItems.size,
            failures = failedItems.ifEmpty { null }
        )

        return success(
            data = result,
            message = message ?: if (failedItems.isEmpty())
                "${items.size} items processed successfully"
            else
                "${items.size} items processed successfully, ${failedItems.size} failed",
            metadata = metadata
        )
    }

    /**
     * Creates an error response
     */
    fun <T> error(
        code: ErrorCode,
        message: String,
        details: String? = null,
        metadata: ResponseMetadata = ResponseMetadata()
    ): ApiResponse<T> {
        return ApiResponse.Error(
            message = message,
            error = ErrorDetails(code, details),
            metadata = metadata
        )
    }

    /**
     * Creates a not found error response
     */
    fun <T> notFound(
        resourceType: String,
        id: String,
        metadata: ResponseMetadata = ResponseMetadata()
    ): ApiResponse<T> {
        return error(
            code = ErrorCode.RESOURCE_NOT_FOUND,
            message = "$resourceType not found",
            details = "No $resourceType exists with ID $id",
            metadata = metadata
        )
    }

    /**
     * Creates a validation error response
     */
    fun <T> validationError(
        message: String,
        details: String? = null,
        metadata: ResponseMetadata = ResponseMetadata()
    ): ApiResponse<T> {
        return error(
            code = ErrorCode.VALIDATION_ERROR,
            message = message,
            details = details,
            metadata = metadata
        )
    }

    /**
     * Creates a database error response
     */
    fun <T> databaseError(
        message: String,
        details: String? = null,
        metadata: ResponseMetadata = ResponseMetadata()
    ): ApiResponse<T> {
        return error(
            code = ErrorCode.DATABASE_ERROR,
            message = message,
            details = details,
            metadata = metadata
        )
    }

    /**
     * Creates a dependency error response
     */
    fun <T> dependencyError(
        message: String,
        details: String? = null,
        metadata: ResponseMetadata = ResponseMetadata()
    ): ApiResponse<T> {
        return error(
            code = ErrorCode.DEPENDENCY_ERROR,
            message = message,
            details = details,
            metadata = metadata
        )
    }

    /**
     * Creates an internal error response
     */
    fun <T> internalError(
        message: String = "An internal server error occurred",
        details: String? = null,
        metadata: ResponseMetadata = ResponseMetadata()
    ): ApiResponse<T> {
        return error(
            code = ErrorCode.INTERNAL_ERROR,
            message = message,
            details = details,
            metadata = metadata
        )
    }

    /**
     * Converts a domain Result to an ApiResponse
     */
    inline fun <T, R> fromResult(
        result: Result<T>,
        successMessage: String? = null,
        crossinline transform: (T) -> R
    ): ApiResponse<R> {
        return when (result) {
            is Result.Success -> success(
                data = transform(result.data),
                message = successMessage
            )

            is Result.Error -> when (result.error) {
                is io.github.jpicklyk.mcptask.domain.repository.RepositoryError.NotFound ->
                    notFound(
                        resourceType = result.error.entityType.name,
                        id = result.error.id.toString()
                    )

                is io.github.jpicklyk.mcptask.domain.repository.RepositoryError.ValidationError ->
                    validationError(
                        message = "Validation error",
                        details = result.error.message
                    )

                is io.github.jpicklyk.mcptask.domain.repository.RepositoryError.DatabaseError ->
                    databaseError(
                        message = "Database operation failed",
                        details = result.error.message
                    )

                is io.github.jpicklyk.mcptask.domain.repository.RepositoryError.ConflictError ->
                    error(
                        code = ErrorCode.DUPLICATE_RESOURCE,
                        message = "Resource conflict",
                        details = result.error.message
                    )

                is io.github.jpicklyk.mcptask.domain.repository.RepositoryError.UnknownError ->
                    internalError(
                        message = "An unexpected error occurred",
                        details = result.error.message
                    )
            }
        }
    }

    /**
     * Converts a domain Result to an ApiResponse without transformation
     */
    fun <T> fromResult(
        result: Result<T>,
        successMessage: String? = null
    ): ApiResponse<T> {
        return fromResult(result, successMessage) { it }
    }
}
