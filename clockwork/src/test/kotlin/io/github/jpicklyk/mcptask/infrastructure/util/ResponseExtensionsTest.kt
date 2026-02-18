package io.github.jpicklyk.mcptask.infrastructure.util

import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.interfaces.api.ErrorCodes
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResponseExtensionsTest {

    private data class TestEntity(
        val id: UUID = UUID.randomUUID(),
        val name: String
    )

    private fun serializeTestEntity(entity: TestEntity): JsonElement = buildJsonObject {
        put("id", entity.id.toString())
        put("name", entity.name)
    }

    @Test
    fun `toJsonResponse converts success result correctly`() {
        val entity = TestEntity(name = "Test Entity")
        val result = Result.Success(entity)

        val jsonResponse = result.toJsonResponse(
            successMessage = "Operation successful",
            errorMessage = null,
            dataSerializer = ::serializeTestEntity
        )

        assertTrue(jsonResponse.containsKey("success"))
        assertTrue(jsonResponse["success"]!!.jsonPrimitive.boolean)

        assertEquals("Operation successful", jsonResponse["message"]?.jsonPrimitive?.content)

        val data = jsonResponse["data"]!!.jsonObject
        assertEquals(entity.id.toString(), data["id"]?.jsonPrimitive?.content)
        assertEquals("Test Entity", data["name"]?.jsonPrimitive?.content)

        assertTrue(jsonResponse["error"] is JsonNull)

        val metadata = jsonResponse["metadata"]!!.jsonObject
        assertTrue(metadata.containsKey("timestamp"))
        assertEquals("1.0.0", metadata["version"]?.jsonPrimitive?.content)
    }

    @Test
    fun `toJsonResponse converts error result correctly`() {
        val entityId = UUID.randomUUID()
        val result: Result<TestEntity> = Result.Error(
            RepositoryError.NotFound(entityId, EntityType.TASK, "TestEntity not found")
        )

        val jsonResponse = result.toJsonResponse(
            successMessage = "Operation successful",
            errorMessage = "Custom error message",
            dataSerializer = ::serializeTestEntity
        )

        assertTrue(jsonResponse.containsKey("success"))
        assertFalse(jsonResponse["success"]!!.jsonPrimitive.boolean)

        assertEquals("Custom error message", jsonResponse["message"]?.jsonPrimitive?.content)

        assertTrue(jsonResponse["data"] is JsonNull)

        val error = jsonResponse["error"]!!.jsonObject
        assertEquals(ErrorCodes.RESOURCE_NOT_FOUND, error["code"]?.jsonPrimitive?.content)
        assertTrue(error["details"]?.jsonPrimitive?.content?.contains(entityId.toString()) ?: false)

        val metadata = jsonResponse["metadata"]!!.jsonObject
        assertTrue(metadata.containsKey("timestamp"))
        assertEquals("1.0.0", metadata["version"]?.jsonPrimitive?.content)
    }

    @Test
    fun `toJsonResponse uses default error message when custom message is null`() {
        val entityId = UUID.randomUUID()
        val result: Result<TestEntity> = Result.Error(
            RepositoryError.NotFound(entityId, EntityType.TASK, "TestEntity not found")
        )

        val jsonResponse = result.toJsonResponse(
            successMessage = "Operation successful",
            errorMessage = null,
            dataSerializer = ::serializeTestEntity
        )

        assertEquals("Resource not found", jsonResponse["message"]?.jsonPrimitive?.content)
    }

    @Test
    fun `createPaginatedResponse creates correct pagination info`() {
        val items = listOf(
            TestEntity(name = "Entity 1"),
            TestEntity(name = "Entity 2")
        )

        val response = createPaginatedResponse(
            items = items,
            page = 2,
            pageSize = 10,
            totalItems = 25
        )

        assertEquals(2, response.items.size)
        assertEquals(2, response.pagination.page)
        assertEquals(10, response.pagination.pageSize)
        assertEquals(25, response.pagination.totalItems)
        assertEquals(3, response.pagination.totalPages)
        assertTrue(response.pagination.hasNext)
        assertTrue(response.pagination.hasPrevious)
    }

    @Test
    fun `createPaginatedResponse handles empty results correctly`() {
        val items = emptyList<TestEntity>()

        val response = createPaginatedResponse(
            items = items,
            page = 1,
            pageSize = 10,
            totalItems = 0
        )

        assertEquals(0, response.items.size)
        assertEquals(1, response.pagination.page)
        assertEquals(10, response.pagination.pageSize)
        assertEquals(0, response.pagination.totalItems)
        assertEquals(0, response.pagination.totalPages)
        assertFalse(response.pagination.hasNext)
        assertFalse(response.pagination.hasPrevious)
    }

    @Test
    fun `jsonObjectOf creates JsonObject from pairs`() {
        val obj = jsonObjectOf(
            "name" to JsonPrimitive("Test"),
            "value" to JsonPrimitive(42)
        )

        assertEquals("Test", obj["name"]?.jsonPrimitive?.content)
        assertEquals(42, obj["value"]?.jsonPrimitive?.int)
    }
}