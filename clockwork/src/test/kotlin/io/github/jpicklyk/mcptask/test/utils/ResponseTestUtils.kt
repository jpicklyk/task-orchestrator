package io.github.jpicklyk.mcptask.test.utils

import kotlinx.serialization.json.*

/**
 * Utility class for response assertions in tests.
 */
object ResponseTestUtils {

    /**
     * Checks if a JSON response is a success response.
     *
     * @param response The JSON response to check
     * @return true if it's a success response, false otherwise
     */
    fun isSuccessResponse(response: JsonElement): Boolean {
        val jsonObj = response.jsonObject
        return jsonObj["success"]?.jsonPrimitive?.boolean == true
    }

    /**
     * Checks if a JSON response is an error response.
     *
     * @param response The JSON response to check
     * @return true if it's an error response, false otherwise
     */
    fun isErrorResponse(response: JsonElement): Boolean {
        val jsonObj = response.jsonObject
        return jsonObj["success"]?.jsonPrimitive?.boolean == false
    }

    /**
     * Extracts the data object from a success response.
     *
     * @param response The JSON response to extract data from
     * @return The data object or null if not found
     */
    fun getResponseData(response: JsonElement): JsonObject? {
        val jsonObj = response.jsonObject
        return if (isSuccessResponse(response)) {
            jsonObj["data"]?.jsonObject
        } else {
            null
        }
    }

    /**
     * Extracts the error object from an error response.
     *
     * @param response The JSON response to extract error from
     * @return The error object or null if not found
     */
    fun getResponseError(response: JsonElement): JsonObject? {
        val jsonObj = response.jsonObject
        return if (isErrorResponse(response)) {
            jsonObj["error"]?.jsonObject
        } else {
            null
        }
    }

    /**
     * Gets the message from a response.
     *
     * @param response The JSON response to extract message from
     * @return The message or null if not found
     */
    fun getResponseMessage(response: JsonElement): String? {
        val jsonObj = response.jsonObject
        return jsonObj["message"]?.jsonPrimitive?.content
    }

    /**
     * Gets the error code from an error response.
     *
     * @param response The JSON response to extract error code from
     * @return The error code or null if not found
     */
    fun getErrorCode(response: JsonElement): String? {
        val errorObj = getResponseError(response)
        return errorObj?.get("code")?.jsonPrimitive?.content
    }

    /**
     * Gets the error details from an error response.
     *
     * @param response The JSON response to extract error details from
     * @return The error details or null if not found
     */
    fun getErrorDetails(response: JsonElement): String? {
        val errorObj = getResponseError(response)
        return errorObj?.get("details")?.jsonPrimitive?.content
    }

    /**
     * Gets the additional data from an error response.
     *
     * @param response The JSON response to extract additional data from
     * @return The additional data or null if not found
     */
    fun getAdditionalData(response: JsonElement): JsonObject? {
        val errorObj = getResponseError(response)
        return errorObj?.get("additionalData")?.jsonObject
    }

    /**
     * Verifies that an error response has the expected error code.
     *
     * @param response The JSON response to check
     * @param expectedCode The expected error code
     * @throws AssertionError if the error code doesn't match
     */
    fun assertErrorCode(response: JsonElement, expectedCode: String) {
        val errorCode = getErrorCode(response)
        assert(errorCode == expectedCode) {
            "Expected error code $expectedCode but got $errorCode"
        }
    }
}