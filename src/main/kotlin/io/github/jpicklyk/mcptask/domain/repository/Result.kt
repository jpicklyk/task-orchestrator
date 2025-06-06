package io.github.jpicklyk.mcptask.domain.repository

import io.github.jpicklyk.mcptask.domain.model.EntityType
import java.util.UUID

/**
 * Generic result type for repository operations with explicit error handling.
 */
sealed class Result<out T> {
    /**
     * Represents a successful operation with data.
     */
    data class Success<T>(val data: T) : Result<T>()

    /**
     * Represents a failed operation with an error.
     */
    data class Error(val error: RepositoryError) : Result<Nothing>()

    /**
     * Maps the success data to a new type using the provided transform function.
     * Errors are passed through unchanged.
     */
    fun <R> map(transform: (T) -> R): Result<R> = when (this) {
        is Success -> Success(transform(data))
        is Error -> this
    }

    /**
     * Returns the data or null if this is an error.
     */
    fun getOrNull(): T? = when (this) {
        is Success -> data
        is Error -> null
    }

    /**
     * Returns true if this is a success result.
     */
    fun isSuccess(): Boolean = this is Success

    /**
     * Returns true if this is an error result.
     */
    fun isError(): Boolean = this is Error

    /**
     * Executes the given block if this is a success result.
     */
    inline fun onSuccess(block: (T) -> Unit): Result<T> {
        if (this is Success) {
            block(data)
        }
        return this
    }

    /**
     * Executes the given block if this is an error result.
     */
    inline fun onError(block: (RepositoryError) -> Unit): Result<T> {
        if (this is Error) {
            block(error)
        }
        return this
    }
}

/**
 * Generic repository error type for unified error handling.
 */
sealed class RepositoryError {
    /**
     * Common error message that all repository errors must provide.
     */
    abstract val message: String

    /**
     * Entity not found error.
     */
    data class NotFound(val id: UUID, val entityType: EntityType, override val message: String) : RepositoryError()

    /**
     * Validation error with message.
     */
    data class ValidationError(override val message: String) : RepositoryError()

    /**
     * Database error with message and optional cause.
     */
    data class DatabaseError(override val message: String, val cause: Throwable? = null) : RepositoryError()

    /**
     * Conflict error (e.g., duplicate key).
     */
    data class ConflictError(override val message: String) : RepositoryError()

    /**
     * Unknown error with message and optional cause.
     */
    data class UnknownError(override val message: String, val cause: Throwable? = null) : RepositoryError()
}
