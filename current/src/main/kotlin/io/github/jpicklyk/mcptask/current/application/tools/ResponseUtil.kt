package io.github.jpicklyk.mcptask.current.application.tools

import kotlinx.serialization.json.*
import java.time.Instant

/**
 * Utility object for creating standardized JSON response envelopes.
 *
 * All MCP tool responses use a consistent envelope format:
 * - Success: `{ "success": true, "message": "...", "data": {...}, "metadata": {...} }`
 * - Error: `{ "success": false, "error": { "message": "...", "code": "...", "details": "..." }, "metadata": {...} }`
 *
 * This ensures AI agents can reliably parse tool responses regardless of which tool produced them.
 */
object ResponseUtil {

    private const val CURRENT_VERSION = "0.1.0"

    /**
     * Creates a success response envelope.
     *
     * @param data Optional JSON payload with the operation result
     * @param message Optional human-readable success message
     * @return A JsonObject with the standard success envelope format
     */
    fun createSuccessResponse(data: JsonElement? = null, message: String? = null): JsonObject {
        return buildJsonObject {
            put("success", JsonPrimitive(true))
            if (message != null) {
                put("message", JsonPrimitive(message))
            }
            if (data != null) {
                put("data", data)
            }
            put("metadata", createMetadata())
        }
    }

    /**
     * Creates an error response envelope.
     *
     * @param message Human-readable error description
     * @param code Error code constant from [ErrorCodes] (defaults to VALIDATION_ERROR)
     * @param details Optional additional details about the error (e.g., stack trace, field-level errors)
     * @param additionalData Optional JSON payload with extra context about the error
     * @return A JsonObject with the standard error envelope format
     */
    fun createErrorResponse(
        message: String,
        code: String = ErrorCodes.VALIDATION_ERROR,
        details: String? = null,
        additionalData: JsonElement? = null
    ): JsonObject {
        return buildJsonObject {
            put("success", JsonPrimitive(false))
            put("error", buildJsonObject {
                put("message", JsonPrimitive(message))
                put("code", JsonPrimitive(code))
                if (details != null) {
                    put("details", JsonPrimitive(details))
                }
            })
            if (additionalData != null) {
                put("data", additionalData)
            }
            put("metadata", createMetadata())
        }
    }

    /**
     * Checks whether a JSON response is an error envelope.
     *
     * @param response The JSON element to inspect
     * @return true if the response has `"success": false`
     */
    fun isErrorResponse(response: JsonElement): Boolean {
        val obj = response as? JsonObject ?: return false
        val success = obj["success"]
        return success is JsonPrimitive && !success.boolean
    }

    /**
     * Extracts the "data" payload from a response envelope.
     *
     * @param response The JSON response envelope
     * @return The data payload, or null if not present or if response is not a JsonObject
     */
    fun extractDataPayload(response: JsonElement): JsonElement? {
        val obj = response as? JsonObject ?: return null
        return obj["data"]
    }

    /**
     * Creates the metadata block included in every response envelope.
     *
     * @return A JsonObject with timestamp (ISO 8601) and version fields
     */
    fun createMetadata(): JsonObject {
        return buildJsonObject {
            put("timestamp", JsonPrimitive(Instant.now().toString()))
            put("version", JsonPrimitive(CURRENT_VERSION))
        }
    }
}
