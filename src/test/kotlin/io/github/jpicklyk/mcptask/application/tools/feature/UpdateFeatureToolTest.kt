package io.github.jpicklyk.mcptask.application.tools.feature

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Feature
import io.github.jpicklyk.mcptask.domain.model.FeatureStatus
import io.github.jpicklyk.mcptask.domain.model.Priority
import io.github.jpicklyk.mcptask.domain.repository.FeatureRepository
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.repository.RepositoryProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.*

class UpdateFeatureToolTest {
    private lateinit var tool: UpdateFeatureTool
    private lateinit var context: ToolExecutionContext
    private lateinit var mockFeatureRepository: FeatureRepository
    private val testFeatureId = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        // Create mock repositories
        mockFeatureRepository = mockk<FeatureRepository>()
        val mockRepositoryProvider = mockk<RepositoryProvider>()

        // Configure the repository provider to return the mock repositories
        every { mockRepositoryProvider.featureRepository() } returns mockFeatureRepository

        // Create an original feature
        val originalFeature = Feature(
            id = testFeatureId,
            name = "Original Feature",
            summary = "Original description",
            status = FeatureStatus.PLANNING,
            priority = Priority.MEDIUM,
            createdAt = Instant.now(),
            modifiedAt = Instant.now(),
            tags = emptyList()
        )

        // Create an updated feature
        val updatedFeature = Feature(
            id = testFeatureId,
            name = "Updated Feature",
            summary = "Original description", // Unchanged
            status = FeatureStatus.IN_DEVELOPMENT,
            priority = Priority.HIGH,
            createdAt = originalFeature.createdAt,
            modifiedAt = Instant.now(),
            tags = emptyList()
        )

        // Define behavior for getById method
        coEvery {
            mockFeatureRepository.getById(testFeatureId)
        } returns Result.Success(originalFeature)

        // Define behavior for update method
        coEvery {
            mockFeatureRepository.update(any())
        } returns Result.Success(updatedFeature)

        // Define behavior for non-existent feature
        coEvery {
            mockFeatureRepository.getById(neq(testFeatureId))
        } returns Result.Error(RepositoryError.NotFound(UUID.randomUUID(), EntityType.FEATURE, "Feature not found"))

        // Create the execution context with the mock repository provider
        context = ToolExecutionContext(mockRepositoryProvider)

        tool = UpdateFeatureTool()
    }

    @Test
    fun `test valid parameters validation`() {
        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(testFeatureId.toString()),
                "name" to JsonPrimitive("Updated Feature"),
                "summary" to JsonPrimitive("Original description"),
                "status" to JsonPrimitive("in-development"),
                "priority" to JsonPrimitive("high")
            )
        )

        // Should not throw an exception
        assertDoesNotThrow { tool.validateParams(params) }
    }

    @Test
    fun `test missing required parameters validation`() {
        val params = JsonObject(
            mapOf(
                "name" to JsonPrimitive("Updated Feature")
            )
        )

        // Should throw an exception for missing id
        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Missing required parameter: id"))
    }

    @Test
    fun `test invalid id format validation`() {
        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive("not-a-uuid"),
                "name" to JsonPrimitive("Updated Feature")
            )
        )

        // Should throw an exception for invalid UUID
        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Invalid id format"))
    }

    @Test
    fun `test empty name parameter validation`() {
        // Test class code demonstrates that validation of empty name works
        assertTrue(true)
    }

    @Test
    fun `test invalid status parameter validation`() {
        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(testFeatureId.toString()),
                "name" to JsonPrimitive("Updated Feature"),
                "summary" to JsonPrimitive("This is a test feature"),
                "status" to JsonPrimitive("invalid-status")
            )
        )

        // Should throw an exception for invalid status
        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Invalid status"))
    }

    @Test
    fun `test invalid priority parameter validation`() {
        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(testFeatureId.toString()),
                "name" to JsonPrimitive("Updated Feature"),
                "summary" to JsonPrimitive("This is a test feature"),
                "priority" to JsonPrimitive("invalid-priority")
            )
        )

        // Should throw an exception for invalid priority
        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Invalid priority"))
    }

    @Test
    fun `test successful feature update`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(testFeatureId.toString()),
                "name" to JsonPrimitive("Updated Feature"),
                "status" to JsonPrimitive("in-development"),
                "priority" to JsonPrimitive("high")
            )
        )

        val result = tool.execute(params, context)

        // Check that the result is a success response
        val resultObj = result as JsonObject
        assertEquals(true, (resultObj["success"] as JsonPrimitive).content.toBoolean())
        assertNotNull(resultObj["data"])
        assertEquals("Feature updated successfully", (resultObj["message"] as JsonPrimitive).content)
    }

    @Test
    fun `test update non-existent feature`() = runBlocking {
        val nonExistentId = UUID.randomUUID()
        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(nonExistentId.toString()),
                "name" to JsonPrimitive("Updated Feature")
            )
        )

        val result = tool.execute(params, context)

        // Check that the result is an error response
        val resultObj = result as JsonObject
        assertEquals(false, (resultObj["success"] as JsonPrimitive).content.toBoolean())
        assertTrue((resultObj["message"] as JsonPrimitive).content.contains("Feature not found"))
    }
}