package io.github.jpicklyk.mcptask.test.util

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.function.Executable

/**
 * Utility class for testing standardized JSON responses.
 * Provides assertions for verifying response format and structure.
 */
object ResponseAssertions {
    /**
     * Asserts that the given JSON element is a valid success response.
     *
     * @param response The JSON response to validate
     * @param message Optional expected success message
     * @return The data element from the response for further assertions
     */
    fun assertSuccessResponse(response: JsonElement, message: String? = null): JsonElement? {
        assertTrue(response is JsonObject, "Response should be a JSON object")
        val responseObj = response as JsonObject

        // Check success field
        assertTrue(responseObj.containsKey("success"), "Response should contain 'success' field")
        val success = responseObj["success"]?.jsonPrimitive?.booleanOrNull
        assertNotNull(success, "Success field should be a boolean")
        assertTrue(success!!, "Success field should be true")

        // Check message field if provided
        if (message != null) {
            assertTrue(responseObj.containsKey("message"), "Response should contain 'message' field")
            val actualMessage = responseObj["message"]?.jsonPrimitive?.content
            assertEquals(message, actualMessage, "Response message should match expected")
        }

        // Check for data field
        assertTrue(responseObj.containsKey("data"), "Response should contain 'data' field")
        val data = responseObj["data"]

        // Check for error field (should be null for success)
        assertTrue(responseObj.containsKey("error"), "Response should contain 'error' field")
        val error = responseObj["error"]
        // Check if error is null (could be null or a JsonNull element)
        assertTrue(error == null || !error.toString().contains("\""), "Error field should be null for success response")

        // Check metadata
        assertTrue(responseObj.containsKey("metadata"), "Response should contain 'metadata' field")
        val metadata = responseObj["metadata"]
        assertTrue(metadata is JsonObject, "Metadata should be a JSON object")

        // Return data for further assertions
        return data
    }

    /**
     * Asserts that the given JSON element is a valid error response.
     *
     * @param response The JSON response to validate
     * @param message Optional expected error message
     * @param errorCode Optional expected error code
     * @return The error element from the response for further assertions
     */
    fun assertErrorResponse(
        response: JsonElement,
        message: String? = null,
        errorCode: String? = null
    ): JsonElement? {
        assertTrue(response is JsonObject, "Response should be a JSON object")
        val responseObj = response as JsonObject

        // Check success field
        assertTrue(responseObj.containsKey("success"), "Response should contain 'success' field")
        val success = responseObj["success"]?.jsonPrimitive?.booleanOrNull
        assertNotNull(success, "Success field should be a boolean")
        assertFalse(success!!, "Success field should be false")

        // Check message field if provided
        if (message != null) {
            assertTrue(responseObj.containsKey("message"), "Response should contain 'message' field")
            val actualMessage = responseObj["message"]?.jsonPrimitive?.content
            assertEquals(message, actualMessage, "Response message should match expected")
        }

        // Check for data field (should be null for error)
        assertTrue(responseObj.containsKey("data"), "Response should contain 'data' field")
        val data = responseObj["data"]
        // Check if data is null (could be null or a JsonNull element)
        assertTrue(data == null || !data.toString().contains("\""), "Data field should be null for error response")

        // Check for error field
        assertTrue(responseObj.containsKey("error"), "Response should contain 'error' field")
        val error = responseObj["error"]
        assertTrue(error is JsonObject, "Error field should be a JSON object")

        // Check error code if provided
        if (errorCode != null && error is JsonObject) {
            assertTrue(error.containsKey("code"), "Error should contain 'code' field")
            val code = error["code"]?.jsonPrimitive?.content
            assertEquals(errorCode, code, "Error code should match expected")
        }

        // Check metadata
        assertTrue(responseObj.containsKey("metadata"), "Response should contain 'metadata' field")
        val metadata = responseObj["metadata"]
        assertTrue(metadata is JsonObject, "Metadata should be a JSON object")

        // Return error for further assertions
        return error
    }

    /**
     * Asserts that the data in a response contains the expected fields.
     *
     * @param data The data element from a response
     * @param expectedFields Array of field names expected to be present
     */
    fun assertDataContainsFields(data: JsonElement?, vararg expectedFields: String) {
        assertNotNull(data, "Data should not be null")
        assertTrue(data is JsonObject, "Data should be a JSON object")

        val dataObj = data as JsonObject
        for (field in expectedFields) {
            assertTrue(dataObj.containsKey(field), "Data should contain field: $field")
        }
    }

    /**
     * Asserts that the data in a response contains a nested field with the expected value.
     *
     * @param data The data element from a response
     * @param path Array of field names forming a path to the nested field
     * @param expectedValue The expected value of the nested field
     */
    fun assertNestedFieldValue(data: JsonElement?, expectedValue: Any, vararg path: String) {
        assertNotNull(data, "Data should not be null")

        var current: JsonElement? = data
        for (field in path) {
            assertTrue(current is JsonObject, "Expected object at path: ${path.joinToString(".")}")
            current = (current as JsonObject)[field]
            assertNotNull(current, "Missing field at path: ${path.joinToString(".")}")
        }

        when (expectedValue) {
            is String -> assertEquals(expectedValue, current?.jsonPrimitive?.content)
            is Number -> assertEquals(expectedValue.toString(), current?.jsonPrimitive?.content)
            is Boolean -> assertEquals(expectedValue, current?.jsonPrimitive?.booleanOrNull)
            else -> throw IllegalArgumentException("Unsupported expected value type: ${expectedValue::class.java}")
        }
    }

    /**
     * Asserts that the data in a response contains a collection with the expected size.
     *
     * @param data The data element from a response
     * @param collectionPath Array of field names forming a path to the collection
     * @param expectedSize The expected size of the collection
     * @return The collection for further assertions
     */
    fun assertCollectionSize(data: JsonElement?, expectedSize: Int, vararg collectionPath: String): JsonArray? {
        assertNotNull(data, "Data should not be null")

        var current: JsonElement? = data
        for (field in collectionPath) {
            assertTrue(current is JsonObject, "Expected object at path: ${collectionPath.joinToString(".")}")
            current = (current as JsonObject)[field]
            assertNotNull(current, "Missing field at path: ${collectionPath.joinToString(".")}")
        }

        assertTrue(current is JsonArray, "Expected array at path: ${collectionPath.joinToString(".")}")
        val collection = current as JsonArray
        assertEquals(expectedSize, collection.size, "Collection size should match expected")

        return collection
    }

    /**
     * Asserts that all provided assertions pass for the given response.
     * This is useful for grouping multiple assertions into a single failure message.
     *
     * @param response The response to validate
     * @param assertions The assertions to execute
     */
    fun assertAll(response: JsonElement, vararg assertions: (JsonElement) -> Unit) {
        val executableAssertions = assertions.map { assertion ->
            Executable { assertion(response) }
        }.toTypedArray()

        org.junit.jupiter.api.Assertions.assertAll(*executableAssertions)
    }
}