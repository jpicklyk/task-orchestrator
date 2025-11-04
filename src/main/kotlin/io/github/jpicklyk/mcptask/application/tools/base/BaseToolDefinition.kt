package io.github.jpicklyk.mcptask.application.tools.base

import io.github.jpicklyk.mcptask.application.tools.ToolDefinition
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.github.jpicklyk.mcptask.infrastructure.util.ResponseUtil
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Base abstract class for all tool definitions.
 * Provides common functionality and utility methods for parameter validation and response formatting.
 */
abstract class BaseToolDefinition : ToolDefinition {
    protected val logger = LoggerFactory.getLogger(this.javaClass)

    /**
     * Standard success response with data payload.
     *
     * @param data The data payload
     * @param message An optional success message
     * @return A standardized success response
     */
    protected fun successResponse(
        data: JsonElement,
        message: String? = null
    ): JsonObject {
        return ResponseUtil.createSuccessResponse(message, data)
    }

    /**
     * Standard success response with only a message.
     *
     * @param message The success message
     * @return A standardized success response
     */
    protected fun successResponse(message: String): JsonObject {
        return ResponseUtil.createSuccessResponse(message, null)
    }

    /**
     * Standard error response.
     *
     * @param message The error message
     * @param code Error code for categorizing the error
     * @param details Optional additional details about the error
     * @param additionalData Optional additional data to include in the error
     * @return A standardized error response
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
     * Extract a required string parameter from the input parameters.
     *
     * @param params The input parameters
     * @param name The name of the parameter to extract
     * @return The extracted string value
     * @throws ToolValidationException If the parameter is missing or not a string
     */
    protected fun requireString(params: JsonElement, name: String): String {
        val paramsObj = params as? JsonObject
            ?: throw ToolValidationException("Parameters must be a JSON object")

        val value = paramsObj[name] as? JsonPrimitive
            ?: throw ToolValidationException("Missing required parameter: $name")

        if (!value.isString) {
            throw ToolValidationException("Parameter $name must be a string")
        }

        // Check that the string is not blank for required parameters
        val content = value.content
        if (content.isBlank()) {
            throw ToolValidationException("Required parameter $name cannot be empty")
        }

        return content
    }

    /**
     * Extract an optional string parameter from the input parameters.
     *
     * @param params The input parameters
     * @param name The name of the parameter to extract
     * @return The extracted string value, or null if the parameter is missing
     * @throws ToolValidationException If the parameter is present but not a string
     */
    protected fun optionalString(params: JsonElement, name: String): String? {
        val paramsObj = params as? JsonObject
            ?: throw ToolValidationException("Parameters must be a JSON object")

        val value = paramsObj[name] as? JsonPrimitive ?: return null

        if (!value.isString) {
            throw ToolValidationException("Parameter $name must be a string")
        }

        // Special handling for empty strings that should be treated as null
        if (value.content.isBlank()) {
            return null
        }

        return value.content
    }

    /**
     * Extract an optional string parameter from the input parameters.
     *
     * @param params The input parameters
     * @param name The name of the parameter to extract
     * @param defaultValue An optional default value to return if the parameter is missing
     * @return The extracted string value, or the defaultValue if the parameter is missing
     * @throws ToolValidationException If the parameter is present but not a string
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

    /**
     * Extract a required boolean parameter from the input parameters.
     *
     * @param params The input parameters
     * @param name The name of the parameter to extract
     * @return The extracted boolean value
     * @throws ToolValidationException If the parameter is missing or not a boolean
     */
    protected fun requireBoolean(params: JsonElement, name: String): Boolean {
        val paramsObj = params as? JsonObject
            ?: throw ToolValidationException("Parameters must be a JSON object")

        val value = paramsObj[name] as? JsonPrimitive
            ?: throw ToolValidationException("Missing required parameter: $name")

        logger.debug("Processing required boolean parameter '$name' with value: $value (isString=${value.isString}, content='${value.content}')")

        return when {
            // If it's a boolean literal in JSON
            value.isString && value.content.equals("true", ignoreCase = true) -> {
                logger.debug("Parameter '$name' parsed as boolean true (string format)")
                true
            }

            value.isString && value.content.equals("false", ignoreCase = true) -> {
                logger.debug("Parameter '$name' parsed as boolean false (string format)")
                false
            }

            // If it's a numeric representation (1 for true, 0 for false)
            value.isString && value.content == "1" -> {
                logger.debug("Parameter '$name' parsed as boolean true (numeric string '1')")
                true
            }

            value.isString && value.content == "0" -> {
                logger.debug("Parameter '$name' parsed as boolean false (numeric string '0')")
                false
            }

            // Check for non-string boolean format
            !value.isString && try {
                value.content.toBoolean()
                true // If this succeeds, we can parse as boolean
            } catch (e: Exception) {
                try {
                    value.content.toInt() == 1
                    true // If this succeeds, we can parse as int
                } catch (e: Exception) {
                    false // If both fail, we need to check below
                }
            } -> {
                // Now do the actual conversion
                try {
                    val result = value.content.toBoolean()
                    logger.debug("Parameter '$name' parsed as boolean $result (non-string boolean)")
                    result
                } catch (e: Exception) {
                    try {
                        val result = value.content.toInt() == 1
                        logger.debug("Parameter '$name' parsed as boolean $result (non-string numeric)")
                        result
                    } catch (e: Exception) {
                        logger.debug("Parameter '$name' failed all boolean parsing attempts")
                        false
                    }
                }
            }

            // If we can't parse it as a boolean, throw an exception
            else -> {
                logger.warn("Invalid boolean parameter '$name': ${value.content}")
                throw ToolValidationException("Parameter $name must be a boolean. Received: ${value.content} (${if (value.isString) "string" else "non-string"})")
            }
        }
    }

    /**
     * Extract an optional boolean parameter from the input parameters.
     *
     * @param params The input parameters
     * @param name The name of the parameter to extract
     * @param defaultValue The default value to return if the parameter is missing
     * @return The extracted boolean value, or the default value if the parameter is missing
     * @throws ToolValidationException If the parameter is present but not a boolean
     */
    protected fun optionalBoolean(params: JsonElement, name: String, defaultValue: Boolean = false): Boolean {
        val paramsObj = params as? JsonObject
            ?: throw ToolValidationException("Parameters must be a JSON object")

        val value = paramsObj[name] as? JsonPrimitive ?: return defaultValue

        logger.debug("Processing boolean parameter '$name' with value: $value (isString=${value.isString}, content='${value.content}')")

        return when {
            // If it's a boolean literal in JSON
            value.isString && value.content.equals("true", ignoreCase = true) -> {
                logger.debug("Parameter '$name' parsed as boolean true (string format)")
                true
            }

            value.isString && value.content.equals("false", ignoreCase = true) -> {
                logger.debug("Parameter '$name' parsed as boolean false (string format)")
                false
            }

            // If it's a numeric representation (1 for true, 0 for false)
            value.isString && value.content == "1" -> {
                logger.debug("Parameter '$name' parsed as boolean true (numeric string '1')")
                true
            }

            value.isString && value.content == "0" -> {
                logger.debug("Parameter '$name' parsed as boolean false (numeric string '0')")
                false
            }

            // Check for non-string boolean format
            !value.isString && try {
                value.content.toBoolean()
                true // If this succeeds, we can parse as boolean
            } catch (e: Exception) {
                try {
                    value.content.toInt() == 1
                    true // If this succeeds, we can parse as int
                } catch (e: Exception) {
                    false // If both fail, we need to check below
                }
            } -> {
                // Now do the actual conversion
                try {
                    val result = value.content.toBoolean()
                    logger.debug("Parameter '$name' parsed as boolean $result (non-string boolean)")
                    result
                } catch (e: Exception) {
                    try {
                        val result = value.content.toInt() == 1
                        logger.debug("Parameter '$name' parsed as boolean $result (non-string numeric)")
                        result
                    } catch (e: Exception) {
                        logger.debug("Parameter '$name' failed all boolean parsing attempts")
                        false
                    }
                }
            }

            // If we can't parse it as a boolean, throw an exception
            else -> {
                logger.warn("Invalid boolean parameter '$name': ${value.content}")
                throw ToolValidationException("Parameter $name must be a boolean. Received: ${value.content} (${if (value.isString) "string" else "non-string"})")
            }
        }
    }

    /**
     * Extract a required integer parameter from the input parameters.
     *
     * @param params The input parameters
     * @param name The name of the parameter to extract
     * @return The extracted integer value
     * @throws ToolValidationException If the parameter is missing or not an integer
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
     * Extract an optional integer parameter from the input parameters.
     *
     * @param params The input parameters
     * @param name The name of the parameter to extract
     * @param defaultValue The default value to return if the parameter is missing
     * @return The extracted integer value, or the default value if the parameter is missing
     * @throws ToolValidationException If the parameter is present but not an integer
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

    /**
     * Extract a required string list parameter from the input parameters.
     *
     * @param params The input parameters
     * @param name The name of the parameter to extract
     * @return The extracted string list
     * @throws ToolValidationException If the parameter is missing or not a string list
     */
    protected fun requireStringList(params: JsonElement, name: String): List<String> {
        val paramsObj = params as? JsonObject
            ?: throw ToolValidationException("Parameters must be a JSON object")

        val arrayElement = paramsObj[name]
            ?: throw ToolValidationException("Missing required parameter: $name")

        // Handle comma-separated string format
        if (arrayElement is JsonPrimitive && arrayElement.isString) {
            return arrayElement.content.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }

        // TODO: Handle proper JSON array format when we have the appropriate JSON library functions

        throw ToolValidationException("Parameter $name must be a string list (comma-separated string)")
    }

    /**
     * Extract an optional string list parameter from the input parameters.
     *
     * @param params The input parameters
     * @param name The name of the parameter to extract
     * @return The extracted string list, or an empty list if the parameter is missing
     * @throws ToolValidationException If the parameter is present but not a string list
     */
    protected fun optionalStringList(params: JsonElement, name: String): List<String> {
        val paramsObj = params as? JsonObject
            ?: throw ToolValidationException("Parameters must be a JSON object")

        val arrayElement = paramsObj[name] ?: return emptyList()

        // Handle comma-separated string format
        if (arrayElement is JsonPrimitive && arrayElement.isString) {
            return arrayElement.content.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }

        // TODO: Handle proper JSON array format when we have the appropriate JSON library functions

        throw ToolValidationException("Parameter $name must be a string list (comma-separated string)")
    }

    /**
     * Extract an optional JSON array parameter from the input parameters.
     *
     * @param params The input parameters
     * @param name The name of the parameter to extract
     * @return The extracted JsonArray, or null if the parameter is missing
     * @throws ToolValidationException If the parameter is present but not a JSON array
     */
    protected fun optionalJsonArray(params: JsonElement, name: String): JsonArray? {
        val paramsObj = params as? JsonObject
            ?: throw ToolValidationException("Parameters must be a JSON object")

        val arrayElement = paramsObj[name] ?: return null

        if (arrayElement is JsonArray) {
            return arrayElement
        }

        throw ToolValidationException("Parameter $name must be a JSON array")
    }
}