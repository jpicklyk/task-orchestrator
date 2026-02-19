package io.github.jpicklyk.mcptask.current.application.tools

import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Abstract base class for all MCP tool implementations in Current (v3).
 *
 * Provides parameter extraction helpers for common JSON types, UUID/Instant parsing,
 * and standardized response envelope construction via [ResponseUtil].
 *
 * Tools extend this class directly (no lock-aware variant in v3) and implement
 * [ToolDefinition.execute] and optionally override [ToolDefinition.validateParams].
 */
abstract class BaseToolDefinition : ToolDefinition {

    protected val logger = LoggerFactory.getLogger(this.javaClass)

    // ──────────────────────────────────────────────
    // Response helpers — delegate to ResponseUtil
    // ──────────────────────────────────────────────

    /**
     * Creates a standardized success response with a data payload.
     *
     * @param data The JSON data payload
     * @param message Optional human-readable message
     * @return A success response envelope
     */
    protected fun successResponse(data: JsonElement, message: String? = null): JsonObject {
        return ResponseUtil.createSuccessResponse(data = data, message = message)
    }

    /**
     * Creates a standardized success response with only a message.
     *
     * @param message The human-readable success message
     * @return A success response envelope
     */
    protected fun successResponse(message: String): JsonObject {
        return ResponseUtil.createSuccessResponse(message = message)
    }

    /**
     * Creates a standardized error response and logs a warning.
     *
     * @param message Human-readable error description
     * @param code Error code from [ErrorCodes] (defaults to VALIDATION_ERROR)
     * @param details Optional additional details about the error
     * @param additionalData Optional JSON payload with extra error context
     * @return An error response envelope
     */
    protected fun errorResponse(
        message: String,
        code: String = ErrorCodes.VALIDATION_ERROR,
        details: String? = null,
        additionalData: JsonElement? = null
    ): JsonObject {
        logger.warn("Tool error: $message")
        return ResponseUtil.createErrorResponse(message, code, details, additionalData)
    }

    // ──────────────────────────────────────────────
    // String extraction
    // ──────────────────────────────────────────────

    /**
     * Extracts a required string parameter. Throws if missing, not a string, or blank.
     *
     * @param params The input parameters (must be a JsonObject)
     * @param name The parameter name
     * @return The non-blank string value
     * @throws ToolValidationException if missing, wrong type, or blank
     */
    protected fun requireString(params: JsonElement, name: String): String {
        val paramsObj = params as? JsonObject
            ?: throw ToolValidationException("Parameters must be a JSON object")

        val value = paramsObj[name] as? JsonPrimitive
            ?: throw ToolValidationException("Missing required parameter: $name")

        if (!value.isString) {
            throw ToolValidationException("Parameter $name must be a string")
        }

        val content = value.content
        if (content.isBlank()) {
            throw ToolValidationException("Required parameter $name cannot be empty")
        }

        return content
    }

    /**
     * Extracts an optional string parameter. Returns null if missing or blank.
     *
     * @param params The input parameters
     * @param name The parameter name
     * @return The string value, or null if absent/blank
     * @throws ToolValidationException if present but not a string
     */
    protected fun optionalString(params: JsonElement, name: String): String? {
        val paramsObj = params as? JsonObject
            ?: throw ToolValidationException("Parameters must be a JSON object")

        val value = paramsObj[name] as? JsonPrimitive ?: return null

        if (!value.isString) {
            throw ToolValidationException("Parameter $name must be a string")
        }

        if (value.content.isBlank()) {
            return null
        }

        return value.content
    }

    /**
     * Extracts an optional string parameter with a default value.
     *
     * @param params The input parameters
     * @param name The parameter name
     * @param defaultValue The value to return if the parameter is missing
     * @return The string value, or [defaultValue] if absent
     * @throws ToolValidationException if present but not a string
     */
    protected fun optionalString(params: JsonElement, name: String, defaultValue: String): String {
        val paramsObj = params as? JsonObject
            ?: throw ToolValidationException("Parameters must be a JSON object")

        val value = paramsObj[name] as? JsonPrimitive ?: return defaultValue

        if (!value.isString) {
            throw ToolValidationException("Parameter $name must be a string")
        }

        return value.content
    }

    // ──────────────────────────────────────────────
    // Boolean extraction
    // ──────────────────────────────────────────────

    /**
     * Extracts an optional boolean parameter.
     * Handles JSON booleans, string "true"/"false" (case-insensitive), and numeric 1/0.
     *
     * @param params The input parameters
     * @param name The parameter name
     * @param defaultValue The value to return if the parameter is missing (defaults to false)
     * @return The boolean value, or [defaultValue] if absent
     * @throws ToolValidationException if present but not parseable as a boolean
     */
    protected fun optionalBoolean(params: JsonElement, name: String, defaultValue: Boolean = false): Boolean {
        val paramsObj = params as? JsonObject
            ?: throw ToolValidationException("Parameters must be a JSON object")

        val value = paramsObj[name] as? JsonPrimitive ?: return defaultValue

        return when {
            // JSON boolean literal (non-string)
            !value.isString -> try {
                value.boolean
            } catch (_: Exception) {
                throw ToolValidationException("Parameter $name must be a boolean")
            }
            // String representations
            value.content.equals("true", ignoreCase = true) -> true
            value.content.equals("false", ignoreCase = true) -> false
            value.content == "1" -> true
            value.content == "0" -> false
            else -> throw ToolValidationException("Parameter $name must be a boolean")
        }
    }

    // ──────────────────────────────────────────────
    // Integer extraction
    // ──────────────────────────────────────────────

    /**
     * Extracts a required integer parameter.
     *
     * @param params The input parameters
     * @param name The parameter name
     * @return The integer value
     * @throws ToolValidationException if missing or not parseable as an integer
     */
    protected fun requireInt(params: JsonElement, name: String): Int {
        val paramsObj = params as? JsonObject
            ?: throw ToolValidationException("Parameters must be a JSON object")

        val value = paramsObj[name] as? JsonPrimitive
            ?: throw ToolValidationException("Missing required parameter: $name")

        return try {
            value.content.toInt()
        } catch (_: NumberFormatException) {
            throw ToolValidationException("Parameter $name must be an integer")
        }
    }

    /**
     * Extracts an optional integer parameter.
     *
     * @param params The input parameters
     * @param name The parameter name
     * @param defaultValue The value to return if the parameter is missing (defaults to null)
     * @return The integer value, or [defaultValue] if absent
     * @throws ToolValidationException if present but not parseable as an integer
     */
    protected fun optionalInt(params: JsonElement, name: String, defaultValue: Int? = null): Int? {
        val paramsObj = params as? JsonObject
            ?: throw ToolValidationException("Parameters must be a JSON object")

        val value = paramsObj[name] as? JsonPrimitive ?: return defaultValue

        return try {
            value.content.toInt()
        } catch (_: NumberFormatException) {
            throw ToolValidationException("Parameter $name must be an integer")
        }
    }

    // ──────────────────────────────────────────────
    // JSON array extraction
    // ──────────────────────────────────────────────

    /**
     * Extracts an optional JSON array parameter.
     *
     * @param params The input parameters
     * @param name The parameter name
     * @return The JsonArray, or null if the parameter is absent
     * @throws ToolValidationException if present but not a JSON array
     */
    protected fun optionalJsonArray(params: JsonElement, name: String): JsonArray? {
        val paramsObj = params as? JsonObject
            ?: throw ToolValidationException("Parameters must be a JSON object")

        val element = paramsObj[name] ?: return null

        if (element is JsonArray) {
            return element
        }

        throw ToolValidationException("Parameter $name must be a JSON array")
    }

    /**
     * Extracts a required JSON array parameter.
     *
     * @param params The input parameters
     * @param name The parameter name
     * @return The JsonArray
     * @throws ToolValidationException if missing or not a JSON array
     */
    protected fun requireJsonArray(params: JsonElement, name: String): JsonArray {
        val paramsObj = params as? JsonObject
            ?: throw ToolValidationException("Parameters must be a JSON object")

        val element = paramsObj[name]
            ?: throw ToolValidationException("Missing required parameter: $name")

        if (element is JsonArray) {
            return element
        }

        throw ToolValidationException("Parameter $name must be a JSON array")
    }

    // ──────────────────────────────────────────────
    // UUID extraction (v3-specific)
    // ──────────────────────────────────────────────

    /**
     * Extracts and parses a UUID parameter from a string value.
     *
     * @param params The input parameters
     * @param name The parameter name
     * @param required If true, throws when the parameter is missing; if false, returns null
     * @return The parsed UUID, or null if not required and absent
     * @throws ToolValidationException if required and missing, or if the value is not a valid UUID
     */
    protected fun extractUUID(params: JsonElement, name: String, required: Boolean = true): UUID? {
        val paramsObj = params as? JsonObject
            ?: throw ToolValidationException("Parameters must be a JSON object")

        val value = paramsObj[name] as? JsonPrimitive

        if (value == null) {
            if (required) {
                throw ToolValidationException("Missing required parameter: $name")
            }
            return null
        }

        if (!value.isString) {
            throw ToolValidationException("Parameter $name must be a string (UUID format)")
        }

        val content = value.content
        if (content.isBlank()) {
            if (required) {
                throw ToolValidationException("Required parameter $name cannot be empty")
            }
            return null
        }

        return try {
            UUID.fromString(content)
        } catch (_: IllegalArgumentException) {
            throw ToolValidationException("Parameter $name must be a valid UUID, got: $content")
        }
    }

    // ──────────────────────────────────────────────
    // Instant parsing (v3-specific)
    // ──────────────────────────────────────────────

    /**
     * Parses an optional ISO 8601 timestamp string into a [java.time.Instant].
     *
     * @param params The input parameters
     * @param name The parameter name
     * @return The parsed Instant, or null if the parameter is absent
     * @throws ToolValidationException if present but not a valid ISO 8601 timestamp
     */
    protected fun parseInstant(params: JsonElement, name: String): Instant? {
        val paramsObj = params as? JsonObject
            ?: throw ToolValidationException("Parameters must be a JSON object")

        val value = paramsObj[name] as? JsonPrimitive ?: return null

        if (!value.isString) {
            throw ToolValidationException("Parameter $name must be a string (ISO 8601 timestamp)")
        }

        val content = value.content
        if (content.isBlank()) {
            return null
        }

        return try {
            Instant.parse(content)
        } catch (_: DateTimeParseException) {
            throw ToolValidationException("Parameter $name must be a valid ISO 8601 timestamp, got: $content")
        }
    }

    // ──────────────────────────────────────────────
    // Utility
    // ──────────────────────────────────────────────

    /**
     * Truncates a UUID string to its first 8 characters for compact display in summaries.
     *
     * @param uuid The full UUID string
     * @return The first 8 characters
     */
    protected fun shortId(uuid: String): String = uuid.take(8)
}
