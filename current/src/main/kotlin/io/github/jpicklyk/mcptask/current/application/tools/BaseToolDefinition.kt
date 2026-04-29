package io.github.jpicklyk.mcptask.current.application.tools

import io.github.jpicklyk.mcptask.current.domain.model.ToolError
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
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
    protected fun successResponse(
        data: JsonElement,
        message: String? = null
    ): JsonObject = ResponseUtil.createSuccessResponse(data = data, message = message)

    /**
     * Creates a standardized success response with only a message.
     *
     * @param message The human-readable success message
     * @return A success response envelope
     */
    protected fun successResponse(message: String): JsonObject = ResponseUtil.createSuccessResponse(message = message)

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

    /**
     * Creates a structured error response from a [ToolError] and logs a warning.
     *
     * Emits the full structured envelope with `kind`, `retryAfterMs`, and `contendedItemId`
     * so agents can make retry decisions without string-parsing the message.
     * Use this overload for contention errors, policy rejections, and load-shedding.
     *
     * @param toolError The structured error descriptor.
     * @return An error response envelope with structured fields.
     */
    protected fun errorResponse(toolError: ToolError): JsonObject {
        logger.warn("Tool error [${toolError.kind}]: ${toolError.code} — ${toolError.message}")
        return ResponseUtil.createErrorResponse(toolError)
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
    protected fun requireString(
        params: JsonElement,
        name: String
    ): String {
        val paramsObj =
            params as? JsonObject
                ?: throw ToolValidationException("Parameters must be a JSON object")

        val value =
            paramsObj[name] as? JsonPrimitive
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
    protected fun optionalString(
        params: JsonElement,
        name: String
    ): String? {
        val paramsObj =
            params as? JsonObject
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
    protected fun optionalString(
        params: JsonElement,
        name: String,
        defaultValue: String
    ): String {
        val paramsObj =
            params as? JsonObject
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
    protected fun optionalBoolean(
        params: JsonElement,
        name: String,
        defaultValue: Boolean = false
    ): Boolean {
        val paramsObj =
            params as? JsonObject
                ?: throw ToolValidationException("Parameters must be a JSON object")

        val value = paramsObj[name] as? JsonPrimitive ?: return defaultValue

        return when {
            // JSON boolean literal (non-string)
            !value.isString ->
                try {
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
    protected fun requireInt(
        params: JsonElement,
        name: String
    ): Int {
        val paramsObj =
            params as? JsonObject
                ?: throw ToolValidationException("Parameters must be a JSON object")

        val value =
            paramsObj[name] as? JsonPrimitive
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
    protected fun optionalInt(
        params: JsonElement,
        name: String,
        defaultValue: Int? = null
    ): Int? {
        val paramsObj =
            params as? JsonObject
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
    protected fun optionalJsonArray(
        params: JsonElement,
        name: String
    ): JsonArray? {
        val paramsObj =
            params as? JsonObject
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
    protected fun requireJsonArray(
        params: JsonElement,
        name: String
    ): JsonArray {
        val paramsObj =
            params as? JsonObject
                ?: throw ToolValidationException("Parameters must be a JSON object")

        val element =
            paramsObj[name]
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
     * Extracts and parses a required UUID parameter, returning a non-null UUID.
     *
     * Wraps [extractUUID] with `required=true` so call sites avoid `!!` non-null assertions.
     *
     * @param params The input parameters
     * @param name The parameter name
     * @return The parsed UUID (never null)
     * @throws ToolValidationException if missing or not a valid UUID
     */
    protected fun requireUUID(
        params: JsonElement,
        name: String
    ): UUID =
        extractUUID(params, name, required = true)
            ?: throw ToolValidationException("'$name' is required and must be a valid UUID")

    /**
     * Extracts and parses a UUID parameter from a string value.
     *
     * @param params The input parameters
     * @param name The parameter name
     * @param required If true, throws when the parameter is missing; if false, returns null
     * @return The parsed UUID, or null if not required and absent
     * @throws ToolValidationException if required and missing, or if the value is not a valid UUID
     */
    protected fun extractUUID(
        params: JsonElement,
        name: String,
        required: Boolean = true
    ): UUID? {
        val paramsObj =
            params as? JsonObject
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
    // Short-ID prefix resolution
    // ──────────────────────────────────────────────

    companion object {
        /** Minimum hex prefix length for short-ID resolution. */
        const val MIN_PREFIX_LENGTH = 4

        /** Regex for a valid hex string (UUID prefix). */
        val HEX_PATTERN: Regex = Regex("^[0-9a-fA-F]+$")
    }

    /**
     * Validates that a raw string value is either a full UUID or a valid hex prefix.
     * Use this in [validateParams] for array elements or extracted strings.
     *
     * @param value The string value to validate
     * @param fieldLabel Human-readable field label for error messages (e.g. "transitions[0].itemId")
     * @throws ToolValidationException if the value is neither a valid UUID nor a valid hex prefix
     */
    protected fun validateIdStringOrPrefix(
        value: String,
        fieldLabel: String
    ) {
        if (value.length == 36) {
            try {
                UUID.fromString(value)
            } catch (_: IllegalArgumentException) {
                throw ToolValidationException("$fieldLabel must be a valid UUID or hex prefix, got: $value")
            }
            return
        }
        if (!value.matches(HEX_PATTERN)) {
            throw ToolValidationException(
                "$fieldLabel must be a UUID or hex prefix ($MIN_PREFIX_LENGTH-35 chars), got: $value"
            )
        }
        if (value.length < MIN_PREFIX_LENGTH) {
            throw ToolValidationException(
                "ID prefix too short for $fieldLabel: minimum $MIN_PREFIX_LENGTH hex characters required, got ${value.length}"
            )
        }
    }

    /**
     * Validates that a string parameter is either a full UUID or a valid hex prefix.
     * Use this in [validateParams] for top-level parameters that accept short IDs.
     * Delegates to [validateIdStringOrPrefix] after extracting the value.
     *
     * @param params The input parameters
     * @param name The parameter name
     * @param required If true, throws when the parameter is missing
     * @throws ToolValidationException if the value is neither a valid UUID nor a valid hex prefix
     */
    protected fun validateIdOrPrefix(
        params: JsonElement,
        name: String,
        required: Boolean = true
    ) {
        val paramsObj =
            params as? JsonObject
                ?: throw ToolValidationException("Parameters must be a JSON object")

        val value = paramsObj[name] as? JsonPrimitive
        if (value == null) {
            if (required) throw ToolValidationException("Missing required parameter: $name")
            return
        }
        if (!value.isString) throw ToolValidationException("Parameter $name must be a string")

        val content = value.content
        if (content.isBlank()) {
            if (required) throw ToolValidationException("Required parameter $name cannot be empty")
            return
        }

        validateIdStringOrPrefix(content, name)
    }

    /**
     * Resolves a raw ID string that may be a full UUID or a short hex prefix.
     * Tries full UUID parse first (fast path), then falls back to prefix resolution
     * via [WorkItemRepository.findByIdPrefix]. Use this for array elements or
     * pre-extracted strings.
     *
     * @param idStr The ID string to resolve
     * @param context The tool execution context (for repository access)
     * @return A pair of (UUID?, errorResponse?) — exactly one will be non-null
     */
    protected suspend fun resolveIdString(
        idStr: String,
        context: ToolExecutionContext
    ): Pair<UUID?, JsonElement?> {
        // Fast path: full UUID (36 chars)
        if (idStr.length == 36) {
            return try {
                Pair(UUID.fromString(idStr), null)
            } catch (_: IllegalArgumentException) {
                Pair(null, errorResponse("Invalid UUID format: $idStr", ErrorCodes.VALIDATION_ERROR))
            }
        }

        // Prefix resolution path
        if (!idStr.matches(HEX_PATTERN)) {
            return Pair(
                null,
                errorResponse(
                    "Invalid ID format: must be a UUID or hex prefix ($MIN_PREFIX_LENGTH-35 chars), got: $idStr",
                    ErrorCodes.VALIDATION_ERROR
                )
            )
        }
        if (idStr.length < MIN_PREFIX_LENGTH) {
            return Pair(
                null,
                errorResponse(
                    "ID prefix too short: minimum $MIN_PREFIX_LENGTH hex characters required, got ${idStr.length}",
                    ErrorCodes.VALIDATION_ERROR
                )
            )
        }

        return when (val result = context.workItemRepository().findByIdPrefix(idStr)) {
            is Result.Success -> {
                val matches = result.data
                when {
                    matches.isEmpty() ->
                        Pair(
                            null,
                            errorResponse("No WorkItem found matching prefix: $idStr", ErrorCodes.RESOURCE_NOT_FOUND)
                        )
                    matches.size > 1 ->
                        Pair(
                            null,
                            errorResponse(
                                "Ambiguous prefix: $idStr matches ${matches.size} items",
                                ErrorCodes.VALIDATION_ERROR,
                                additionalData =
                                    buildJsonObject {
                                        put("prefix", JsonPrimitive(idStr))
                                        put(
                                            "matches",
                                            JsonArray(
                                                matches.map { match ->
                                                    buildJsonObject {
                                                        put("id", JsonPrimitive(match.id.toString()))
                                                        put("title", JsonPrimitive(match.title))
                                                    }
                                                }
                                            )
                                        )
                                    }
                            )
                        )
                    else -> Pair(matches.first().id, null)
                }
            }
            is Result.Error ->
                Pair(
                    null,
                    errorResponse("Failed to resolve ID prefix: ${result.error.message}", ErrorCodes.INTERNAL_ERROR)
                )
        }
    }

    /**
     * Resolves an item ID parameter that may be a full UUID or a short hex prefix.
     * Extracts the value from [params] and delegates to [resolveIdString].
     *
     * @param params The input parameters
     * @param name The parameter name
     * @param context The tool execution context (for repository access)
     * @param required If true, returns an error response when the parameter is missing
     * @return A pair of (UUID?, errorResponse?) — exactly one will be non-null on success/failure,
     *         or both null if the parameter is absent and not required
     */
    protected suspend fun resolveItemId(
        params: JsonElement,
        name: String,
        context: ToolExecutionContext,
        required: Boolean = true
    ): Pair<UUID?, JsonElement?> {
        val paramsObj =
            params as? JsonObject
                ?: return Pair(null, errorResponse("Parameters must be a JSON object", ErrorCodes.VALIDATION_ERROR))

        val value = paramsObj[name] as? JsonPrimitive
        if (value == null || !value.isString || value.content.isBlank()) {
            if (required) {
                return Pair(null, errorResponse("Missing required parameter: $name", ErrorCodes.VALIDATION_ERROR))
            }
            return Pair(null, null)
        }

        return resolveIdString(value.content, context)
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
    protected fun parseInstant(
        params: JsonElement,
        name: String
    ): Instant? {
        val paramsObj =
            params as? JsonObject
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

    // ──────────────────────────────────────────────
    // JSON helpers
    // ──────────────────────────────────────────────

    /**
     * Converts an ordered list of ancestor WorkItems (root-first) into a JSON array
     * with id, title, and depth fields. Used by any tool that supports the includeAncestors parameter.
     */
    protected fun buildAncestorsArray(ancestors: List<WorkItem>): JsonArray =
        JsonArray(
            ancestors.map { ancestor ->
                buildJsonObject {
                    put("id", JsonPrimitive(ancestor.id.toString()))
                    put("title", JsonPrimitive(ancestor.title))
                    put("depth", JsonPrimitive(ancestor.depth))
                }
            }
        )
}
