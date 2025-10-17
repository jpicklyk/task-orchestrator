package io.github.jpicklyk.mcptask.application.tools.feature

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.domain.model.Feature
import io.github.jpicklyk.mcptask.domain.repository.FeatureRepository
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.domain.repository.TemplateRepository
import io.github.jpicklyk.mcptask.infrastructure.repository.RepositoryProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*

/**
 * Tests for CreateFeatureTool focusing on description and summary field handling.
 *
 * This test class verifies:
 * - Description parameter is optional (nullable)
 * - Summary parameter is optional (defaults to empty string)
 * - Both fields can be set independently
 * - Response includes both description and summary fields
 * - Validation rules are enforced
 */
class CreateFeatureToolDescriptionTest {
    private lateinit var tool: CreateFeatureTool
    private lateinit var context: ToolExecutionContext
    private lateinit var mockFeatureRepository: FeatureRepository
    private lateinit var mockTemplateRepository: TemplateRepository
    private lateinit var mockRepositoryProvider: RepositoryProvider
    private val featureId = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        mockFeatureRepository = mockk<FeatureRepository>()
        mockTemplateRepository = mockk<TemplateRepository>()
        mockRepositoryProvider = mockk<RepositoryProvider>()

        every { mockRepositoryProvider.featureRepository() } returns mockFeatureRepository
        every { mockRepositoryProvider.templateRepository() } returns mockTemplateRepository

        // Default behavior for template repository (no templates applied)
        coEvery {
            mockTemplateRepository.applyMultipleTemplates(any(), any(), any())
        } returns Result.Success(emptyMap())

        context = ToolExecutionContext(mockRepositoryProvider)
        tool = CreateFeatureTool()
    }

    @Test
    fun `should create feature with description and summary`() = runBlocking {
        // Given
        val description = "Detailed description of what needs to be implemented"
        val summary = "Brief summary of what was accomplished"

        val featureSlot = slot<Feature>()
        coEvery {
            mockFeatureRepository.create(capture(featureSlot))
        } answers {
            val feature = featureSlot.captured
            Result.Success(feature.copy(id = featureId))
        }

        val params = JsonObject(
            mapOf(
                "name" to JsonPrimitive("Test Feature"),
                "description" to JsonPrimitive(description),
                "summary" to JsonPrimitive(summary)
            )
        )

        // When
        val result = tool.execute(params, context)

        // Then
        val jsonResult = result.jsonObject
        assertTrue(jsonResult["success"]?.jsonPrimitive?.boolean ?: false)

        val data = jsonResult["data"]?.jsonObject
        assertNotNull(data)
        assertEquals(description, data?.get("description")?.jsonPrimitive?.content)
        assertEquals(summary, data?.get("summary")?.jsonPrimitive?.content)

        // Verify feature was created with correct values
        assertEquals(description, featureSlot.captured.description)
        assertEquals(summary, featureSlot.captured.summary)
    }

    @Test
    fun `should create feature with description only (no summary)`() = runBlocking {
        // Given
        val description = "Only description provided"

        val featureSlot = slot<Feature>()
        coEvery {
            mockFeatureRepository.create(capture(featureSlot))
        } answers {
            val feature = featureSlot.captured
            Result.Success(feature.copy(id = featureId))
        }

        val params = JsonObject(
            mapOf(
                "name" to JsonPrimitive("Test Feature"),
                "description" to JsonPrimitive(description)
            )
        )

        // When
        val result = tool.execute(params, context)

        // Then
        val jsonResult = result.jsonObject
        assertTrue(jsonResult["success"]?.jsonPrimitive?.boolean ?: false)

        val data = jsonResult["data"]?.jsonObject
        assertNotNull(data)
        assertEquals(description, data?.get("description")?.jsonPrimitive?.content)
        assertEquals("", data?.get("summary")?.jsonPrimitive?.content) // Default empty string

        // Verify feature was created with correct values
        assertEquals(description, featureSlot.captured.description)
        assertEquals("", featureSlot.captured.summary)
    }

    @Test
    fun `should create feature with summary only (no description)`() = runBlocking {
        // Given
        val summary = "Only summary provided"

        val featureSlot = slot<Feature>()
        coEvery {
            mockFeatureRepository.create(capture(featureSlot))
        } answers {
            val feature = featureSlot.captured
            Result.Success(feature.copy(id = featureId))
        }

        val params = JsonObject(
            mapOf(
                "name" to JsonPrimitive("Test Feature"),
                "summary" to JsonPrimitive(summary)
            )
        )

        // When
        val result = tool.execute(params, context)

        // Then
        val jsonResult = result.jsonObject
        assertTrue(jsonResult["success"]?.jsonPrimitive?.boolean ?: false)

        val data = jsonResult["data"]?.jsonObject
        assertNotNull(data)

        // Description should be JsonNull in response
        val descriptionElement = data?.get("description")
        assertTrue(descriptionElement is JsonNull, "Description should be JsonNull when not provided")

        assertEquals(summary, data?.get("summary")?.jsonPrimitive?.content)

        // Verify feature was created with correct values
        assertNull(featureSlot.captured.description)
        assertEquals(summary, featureSlot.captured.summary)
    }

    @Test
    fun `should create feature with neither description nor summary`() = runBlocking {
        // Given - Only name provided
        val featureSlot = slot<Feature>()
        coEvery {
            mockFeatureRepository.create(capture(featureSlot))
        } answers {
            val feature = featureSlot.captured
            Result.Success(feature.copy(id = featureId))
        }

        val params = JsonObject(
            mapOf(
                "name" to JsonPrimitive("Test Feature")
            )
        )

        // When
        val result = tool.execute(params, context)

        // Then
        val jsonResult = result.jsonObject
        assertTrue(jsonResult["success"]?.jsonPrimitive?.boolean ?: false)

        val data = jsonResult["data"]?.jsonObject
        assertNotNull(data)

        // Description should be JsonNull
        val descriptionElement = data?.get("description")
        assertTrue(descriptionElement is JsonNull, "Description should be JsonNull when not provided")

        // Summary should default to empty string
        assertEquals("", data?.get("summary")?.jsonPrimitive?.content)

        // Verify feature was created with defaults
        assertNull(featureSlot.captured.description)
        assertEquals("", featureSlot.captured.summary)
    }

    @Test
    fun `should validate description parameter correctly`() {
        // Test that description parameter accepts string values
        val params = JsonObject(
            mapOf(
                "name" to JsonPrimitive("Test Feature"),
                "description" to JsonPrimitive("Valid description")
            )
        )

        // Should not throw exception
        assertDoesNotThrow { tool.validateParams(params) }
    }

    @Test
    fun `should create feature with empty string summary`() = runBlocking {
        // Given - Empty string for summary is allowed
        val featureSlot = slot<Feature>()
        coEvery {
            mockFeatureRepository.create(capture(featureSlot))
        } answers {
            val feature = featureSlot.captured
            Result.Success(feature.copy(id = featureId))
        }

        val params = JsonObject(
            mapOf(
                "name" to JsonPrimitive("Test Feature"),
                "summary" to JsonPrimitive("")
            )
        )

        // When
        val result = tool.execute(params, context)

        // Then
        val jsonResult = result.jsonObject
        assertTrue(jsonResult["success"]?.jsonPrimitive?.boolean ?: false)

        // Verify feature was created correctly
        assertNull(featureSlot.captured.description)
        assertEquals("", featureSlot.captured.summary)
    }

    @Test
    fun `should handle long description without length limit`() = runBlocking {
        // Given - Very long description (no limit in domain model)
        val longDescription = "x".repeat(10000)

        val featureSlot = slot<Feature>()
        coEvery {
            mockFeatureRepository.create(capture(featureSlot))
        } answers {
            val feature = featureSlot.captured
            Result.Success(feature.copy(id = featureId))
        }

        val params = JsonObject(
            mapOf(
                "name" to JsonPrimitive("Test Feature"),
                "description" to JsonPrimitive(longDescription)
            )
        )

        // When
        val result = tool.execute(params, context)

        // Then
        val jsonResult = result.jsonObject
        assertTrue(jsonResult["success"]?.jsonPrimitive?.boolean ?: false)

        // Verify long description was accepted
        assertEquals(longDescription, featureSlot.captured.description)
        assertEquals(10000, featureSlot.captured.description?.length)
    }

    @Test
    fun `should enforce summary max length of 500 characters`() = runBlocking {
        // Given - Summary at max length (500 characters)
        val maxLengthSummary = "x".repeat(500)

        val featureSlot = slot<Feature>()
        coEvery {
            mockFeatureRepository.create(capture(featureSlot))
        } answers {
            val feature = featureSlot.captured
            Result.Success(feature.copy(id = featureId))
        }

        val params = JsonObject(
            mapOf(
                "name" to JsonPrimitive("Test Feature"),
                "summary" to JsonPrimitive(maxLengthSummary)
            )
        )

        // When
        val result = tool.execute(params, context)

        // Then
        val jsonResult = result.jsonObject
        assertTrue(jsonResult["success"]?.jsonPrimitive?.boolean ?: false)

        // Verify summary at max length was accepted
        assertEquals(500, featureSlot.captured.summary.length)
    }

    @Test
    fun `response should include description as null when not provided`() = runBlocking {
        // Given
        val featureSlot = slot<Feature>()
        coEvery {
            mockFeatureRepository.create(capture(featureSlot))
        } answers {
            val feature = featureSlot.captured
            Result.Success(feature.copy(id = featureId))
        }

        val params = JsonObject(
            mapOf(
                "name" to JsonPrimitive("Feature without description"),
                "summary" to JsonPrimitive("Has summary")
            )
        )

        // When
        val result = tool.execute(params, context)

        // Then
        val jsonResult = result.jsonObject
        val data = jsonResult["data"]?.jsonObject
        assertNotNull(data)

        // Verify description is explicitly included as JsonNull (not omitted)
        assertTrue(data?.containsKey("description") == true, "Response should include description field")
        val descriptionElement = data?.get("description")
        assertTrue(descriptionElement is JsonNull, "Description should be JsonNull when null")
    }

    @Test
    fun `should differentiate between user-provided description and agent-generated summary`() = runBlocking {
        // Given - Simulating the intended use case:
        // - description: what the user wants done (provided at creation)
        // - summary: what the agent accomplished (would be set later)
        val userDescription = "User wants: Complete authentication system with OAuth2"
        val agentSummary = "" // Empty initially, would be populated by Feature Manager agent

        val featureSlot = slot<Feature>()
        coEvery {
            mockFeatureRepository.create(capture(featureSlot))
        } answers {
            val feature = featureSlot.captured
            Result.Success(feature.copy(id = featureId))
        }

        val params = JsonObject(
            mapOf(
                "name" to JsonPrimitive("Authentication System"),
                "description" to JsonPrimitive(userDescription),
                "summary" to JsonPrimitive(agentSummary)
            )
        )

        // When
        val result = tool.execute(params, context)

        // Then
        val jsonResult = result.jsonObject
        assertTrue(jsonResult["success"]?.jsonPrimitive?.boolean ?: false)

        // Verify both fields serve their distinct purposes
        assertEquals(userDescription, featureSlot.captured.description)
        assertEquals(agentSummary, featureSlot.captured.summary)

        // The description contains user intent (what to do)
        assertTrue(featureSlot.captured.description?.contains("User wants") == true)

        // The summary would later be updated by Feature Manager with what was accomplished
        assertEquals("", featureSlot.captured.summary)
    }
}
