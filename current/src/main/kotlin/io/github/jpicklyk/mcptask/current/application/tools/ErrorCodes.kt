package io.github.jpicklyk.mcptask.current.application.tools

/**
 * Standard error code constants used in error response envelopes.
 * These codes map to categories of failures that tools can produce,
 * enabling consistent error handling across all MCP tool implementations.
 */
object ErrorCodes {
    /** Input parameter validation failed (e.g., missing required field, invalid UUID format) */
    const val VALIDATION_ERROR = "VALIDATION_ERROR"

    /** Requested entity was not found in the database */
    const val RESOURCE_NOT_FOUND = "RESOURCE_NOT_FOUND"

    /** Database operation failed (e.g., connection error, constraint violation) */
    const val DATABASE_ERROR = "DATABASE_ERROR"

    /** Operation conflicts with existing state (e.g., duplicate key, cyclic dependency) */
    const val CONFLICT_ERROR = "CONFLICT_ERROR"

    /** Unexpected internal error not covered by other codes */
    const val INTERNAL_ERROR = "INTERNAL_ERROR"

    /** Named operation could not be completed (e.g., transition blocked, prerequisite unmet) */
    const val OPERATION_FAILED = "OPERATION_FAILED"
}
