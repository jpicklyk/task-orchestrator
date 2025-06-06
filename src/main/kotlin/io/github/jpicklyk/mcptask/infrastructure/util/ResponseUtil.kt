package io.github.jpicklyk.mcptask.infrastructure.util

import kotlinx.serialization.json.*
import java.time.Instant

/**
 * Utility class for creating standardized JSON responses across all tools.
 * This ensures a consistent response format for all MCP tool operations.
 */
object ResponseUtil {
    /**
     * Creates a standardized success response with the given data and message.
     *
     * @param message Optional success message describing the result
     * @param data Optional data payload containing operation results
     * @return A standardized JSON object success response
     */
    fun createSuccessResponse(message: String? = null, data: JsonElement? = null): JsonObject {
        return buildJsonObject {
            put("success", true)
            put("message", message ?: "Operation completed successfully")
            put("data", data ?: JsonNull)
            put("error", JsonNull)
            put("metadata", createMetadata())
        }
    }

    /**
     * Creates a standardized error response with the given message and error details.
     *
     * @param message Error message describing what went wrong
     * @param code Error code for categorizing the error (e.g., VALIDATION_ERROR)
     * @param details Optional additional details about the error
     * @param additionalData Optional additional data to include in the error response
     * @return A standardized JSON object error response
     */
    fun createErrorResponse(
        message: String,
        code: String = ErrorCodes.VALIDATION_ERROR,
        details: String? = null,
        additionalData: JsonElement? = null
    ): JsonObject {
        return buildJsonObject {
            put("success", false)
            put("message", message)
            put("data", JsonNull)
            val errorObj = buildJsonObject {
                put("code", code)
                put("details", details ?: message)
                if (additionalData != null) {
                    put("additionalData", additionalData)
                }
            }
            put("error", errorObj)
            put("metadata", createMetadata())
        }
    }

    /**
     * Creates a standardized metadata object with timestamp and version information.
     *
     * @return A JSON object containing metadata
     */
    fun createMetadata(): JsonObject = buildJsonObject {
        put("timestamp", Instant.now().toString())
        put("version", "1.0.0")
    }
}