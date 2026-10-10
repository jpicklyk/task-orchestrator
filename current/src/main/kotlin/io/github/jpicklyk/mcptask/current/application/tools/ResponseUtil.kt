package io.github.jpicklyk.mcptask.current.application.tools

import io.github.jpicklyk.mcptask.current.application.BuildInfo
import io.github.jpicklyk.mcptask.current.domain.model.ToolError
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
    /**
     * Creates a success response envelope.
     *
     * @param data Optional JSON payload with the operation result
     * @param message Optional human-readable success message
     * @return A JsonObject with the standard success envelope format
     */
    fun createSuccessResponse(
        data: JsonElement? = null,
        message: String? = null
    ): JsonObject =
        buildJsonObject {
            put("success", JsonPrimitive(true))
            if (message != null) {
                put("message", JsonPrimitive(message))
            }
            if (data != null) {
                put("data", data)
            }
            put("metadata", createMetadata())
        }

    /**
     * Creates a structured error response envelope from a [ToolError].
     *
     * The `error` object is `{code, message, kind, details?, retryAfterMs?, contendedItemId?}`: the legacy
     * fields plus the structured ones for agent-parseable retry semantics; optional fields are omitted when null.
     * Callers build the [ToolError] through `LegacyMcpErrorMapper`, the only classifier of MCP errors.
     *
     * @param toolError The structured error descriptor.
     * @param additionalData Optional JSON payload with extra context about the error
     *        (e.g., gate-failure `missingNotes` details); emitted as the envelope's `data` field.
     * @return A JsonObject with the standard error envelope format including structured fields.
     */
    fun createErrorResponse(
        toolError: ToolError,
        additionalData: JsonElement? = null
    ): JsonObject =
        buildJsonObject {
            put("success", JsonPrimitive(false))
            put(
                "error",
                buildJsonObject {
                    put("code", JsonPrimitive(toolError.code))
                    put("message", JsonPrimitive(toolError.message))
                    put("kind", JsonPrimitive(toolError.kind.toJsonString()))
                    toolError.details?.let { put("details", JsonPrimitive(it)) }
                    toolError.retryAfterMs?.let { put("retryAfterMs", JsonPrimitive(it)) }
                    toolError.contendedItemId?.let { put("contendedItemId", JsonPrimitive(it.toString())) }
                }
            )
            if (additionalData != null) {
                put("data", additionalData)
            }
            put("metadata", createMetadata())
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
     * Extracts the structured error payload from an error response envelope.
     *
     * Returns a JsonObject containing the envelope's `error` object
     * (`{code, message, kind?, retryAfterMs?, contendedItemId?, details?}`) plus the
     * envelope's `data` payload when present (e.g., gate-failure `missingNotes` details).
     * Used by the MCP adapter to surface structured error fields through
     * `structuredContent` so clients can make retry decisions without string-parsing
     * the text summary.
     *
     * @param response The JSON response envelope
     * @return `{error: {...}, data?: {...}}`, or null if the envelope has no error object
     */
    fun extractErrorPayload(response: JsonElement): JsonObject? {
        val obj = response as? JsonObject ?: return null
        val error = obj["error"] as? JsonObject ?: return null
        return buildJsonObject {
            put("error", error)
            obj["data"]?.let { put("data", it) }
        }
    }

    /**
     * Creates the metadata block included in every response envelope.
     *
     * @return A JsonObject with timestamp (ISO 8601) and version fields
     */
    fun createMetadata(): JsonObject =
        buildJsonObject {
            put("timestamp", JsonPrimitive(Instant.now().toString()))
            put("version", JsonPrimitive(BuildInfo.version))
        }
}
