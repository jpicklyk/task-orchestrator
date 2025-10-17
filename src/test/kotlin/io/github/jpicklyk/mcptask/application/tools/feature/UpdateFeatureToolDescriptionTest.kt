package io.github.jpicklyk.mcptask.application.tools.feature

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.domain.model.Feature
import io.github.jpicklyk.mcptask.domain.repository.FeatureRepository
import io.github.jpicklyk.mcptask.domain.repository.Result
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
 * Tests for UpdateFeatureTool focusing on description and summary field updates.
 *
 * This test class verifies:
 * - Description can be added to features without description
 * - Description can be updated independently
 * - Description can be removed (set to null)
 * - Summary can be updated independently
 * - Both fields can be updated together
 * - Validation rules are enforced
 */
class UpdateFeatureToolDescriptionTest {
    private lateinit var tool: UpdateFeatureTool
    private lateinit var context: ToolExecutionContext
    private lateinit var mockFeatureRepository: FeatureRepository
    private lateinit var mockRepositoryProvider: RepositoryProvider
    private val featureId = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        mockFeatureRepository = mockk<FeatureRepository>()
        mockRepositoryProvider = mockk<RepositoryProvider>()

        every { mockRepositoryProvider.featureRepository() } returns mockFeatureRepository

        context = ToolExecutionContext(mockRepositoryProvider)
        tool = UpdateFeatureTool()
    }

    @Test
    fun `should add description to feature without description`() = runBlocking {
        // Given - Feature with no description
        val originalFeature = Feature(
            id = featureId,
            name = "Original Feature",
            summary = "Original summary",
            description = null
        )

        val newDescription = "Added description after creation"

        val featureSlot = slot<Feature>()
        coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(originalFeature)
        coEvery { mockFeatureRepository.update(capture(featureSlot)) } answers {
            Result.Success(featureSlot.captured)
        }

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(featureId.toString()),
                "description" to JsonPrimitive(newDescription)
            )
        )

        // When
        val result = tool.execute(params, context)

        // Then
        val jsonResult = result.jsonObject
        assertTrue(jsonResult["success"]?.jsonPrimitive?.boolean ?: false)

        // Verify feature was updated with description
        assertEquals(newDescription, featureSlot.captured.description)
        assertEquals("Original summary", featureSlot.captured.summary) // Summary unchanged
    }

    @Test
    fun `should update existing description`() = runBlocking {
        // Given - Feature with existing description
        val originalFeature = Feature(
            id = featureId,
            name = "Original Feature",
            summary = "Original summary",
            description = "Original description"
        )

        val updatedDescription = "Updated description with new details"

        val featureSlot = slot<Feature>()
        coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(originalFeature)
        coEvery { mockFeatureRepository.update(capture(featureSlot)) } answers {
            Result.Success(featureSlot.captured)
        }

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(featureId.toString()),
                "description" to JsonPrimitive(updatedDescription)
            )
        )

        // When
        val result = tool.execute(params, context)

        // Then
        val jsonResult = result.jsonObject
        assertTrue(jsonResult["success"]?.jsonPrimitive?.boolean ?: false)

        // Verify description was updated
        assertEquals(updatedDescription, featureSlot.captured.description)
        assertEquals("Original summary", featureSlot.captured.summary) // Summary unchanged
    }

    @Test
    fun `should reject JsonNull for description parameter`() = runBlocking {
        // Given - Feature with description
        val originalFeature = Feature(
            id = featureId,
            name = "Original Feature",
            summary = "Original summary",
            description = "Original description"
        )

        coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(originalFeature)

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(featureId.toString()),
                "description" to JsonNull
            )
        )

        // When
        val result = tool.execute(params, context)

        // Then - Should fail validation (JsonNull not supported for description)
        val jsonResult = result.jsonObject
        assertFalse(jsonResult["success"]?.jsonPrimitive?.boolean ?: true)

        val message = jsonResult["message"]?.jsonPrimitive?.content
        assertTrue(message?.contains("string") ?: false, "Error message should mention string type requirement")
    }

    @Test
    fun `should update summary independently of description`() = runBlocking {
        // Given - Feature with description
        val originalFeature = Feature(
            id = featureId,
            name = "Original Feature",
            summary = "Original summary",
            description = "Original description"
        )

        val updatedSummary = "Updated summary - what was accomplished"

        val featureSlot = slot<Feature>()
        coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(originalFeature)
        coEvery { mockFeatureRepository.update(capture(featureSlot)) } answers {
            Result.Success(featureSlot.captured)
        }

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(featureId.toString()),
                "summary" to JsonPrimitive(updatedSummary)
            )
        )

        // When
        val result = tool.execute(params, context)

        // Then
        val jsonResult = result.jsonObject
        assertTrue(jsonResult["success"]?.jsonPrimitive?.boolean ?: false)

        // Verify summary was updated but description unchanged
        assertEquals(updatedSummary, featureSlot.captured.summary)
        assertEquals("Original description", featureSlot.captured.description) // Description unchanged
    }

    @Test
    fun `should update both description and summary together`() = runBlocking {
        // Given - Feature with both fields
        val originalFeature = Feature(
            id = featureId,
            name = "Original Feature",
            summary = "Original summary",
            description = "Original description"
        )

        val updatedDescription = "Updated description - new requirements"
        val updatedSummary = "Updated summary - implementation complete"

        val featureSlot = slot<Feature>()
        coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(originalFeature)
        coEvery { mockFeatureRepository.update(capture(featureSlot)) } answers {
            Result.Success(featureSlot.captured)
        }

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(featureId.toString()),
                "description" to JsonPrimitive(updatedDescription),
                "summary" to JsonPrimitive(updatedSummary)
            )
        )

        // When
        val result = tool.execute(params, context)

        // Then
        val jsonResult = result.jsonObject
        assertTrue(jsonResult["success"]?.jsonPrimitive?.boolean ?: false)

        // Verify both fields were updated
        assertEquals(updatedDescription, featureSlot.captured.description)
        assertEquals(updatedSummary, featureSlot.captured.summary)
    }

    @Test
    fun `should treat empty string summary as null and preserve existing value`() = runBlocking {
        // Given - Feature with summary
        // Note: Empty/blank strings are treated as null by optionalString() helper
        val originalFeature = Feature(
            id = featureId,
            name = "Original Feature",
            summary = "Original summary",
            description = "Original description"
        )

        val featureSlot = slot<Feature>()
        coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(originalFeature)
        coEvery { mockFeatureRepository.update(capture(featureSlot)) } answers {
            Result.Success(featureSlot.captured)
        }

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(featureId.toString()),
                "summary" to JsonPrimitive("") // Empty string treated as null = keep existing
            )
        )

        // When
        val result = tool.execute(params, context)

        // Then
        val jsonResult = result.jsonObject
        assertTrue(jsonResult["success"]?.jsonPrimitive?.boolean ?: false)

        // Verify summary was preserved (empty string treated as "no change")
        assertEquals("Original summary", featureSlot.captured.summary)
        assertEquals("Original description", featureSlot.captured.description) // Description unchanged
    }

    @Test
    fun `should enforce summary max length of 500 characters`() = runBlocking {
        // Given - Feature to update with long summary
        val originalFeature = Feature(
            id = featureId,
            name = "Original Feature",
            summary = "Original summary"
        )

        val tooLongSummary = "x".repeat(501) // 501 characters - exceeds limit

        coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(originalFeature)
        // Note: Don't need to mock update() because domain validation will fail before update is called

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(featureId.toString()),
                "summary" to JsonPrimitive(tooLongSummary)
            )
        )

        // When
        val result = tool.execute(params, context)

        // Then - Should fail validation (domain model enforces max 500 chars)
        val jsonResult = result.jsonObject
        assertFalse(jsonResult["success"]?.jsonPrimitive?.boolean ?: true)

        val message = jsonResult["message"]?.jsonPrimitive?.content
        assertTrue(
            message?.contains("concise") == true || message?.contains("500") == true || message?.contains("Failed to update") == true,
            "Error message should mention validation failure. Actual: $message"
        )
    }

    @Test
    fun `should allow summary at max length of 500 characters`() = runBlocking {
        // Given - Feature to update with summary at max length
        val originalFeature = Feature(
            id = featureId,
            name = "Original Feature",
            summary = "Original summary"
        )

        val maxLengthSummary = "x".repeat(500) // Exactly 500 characters

        val featureSlot = slot<Feature>()
        coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(originalFeature)
        coEvery { mockFeatureRepository.update(capture(featureSlot)) } answers {
            Result.Success(featureSlot.captured)
        }

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(featureId.toString()),
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
    fun `should allow very long description without limit`() = runBlocking {
        // Given - Feature to update with very long description
        val originalFeature = Feature(
            id = featureId,
            name = "Original Feature",
            summary = "Original summary",
            description = "Short description"
        )

        val veryLongDescription = "x".repeat(10000) // 10,000 characters - should be allowed

        val featureSlot = slot<Feature>()
        coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(originalFeature)
        coEvery { mockFeatureRepository.update(capture(featureSlot)) } answers {
            Result.Success(featureSlot.captured)
        }

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(featureId.toString()),
                "description" to JsonPrimitive(veryLongDescription)
            )
        )

        // When
        val result = tool.execute(params, context)

        // Then
        val jsonResult = result.jsonObject
        assertTrue(jsonResult["success"]?.jsonPrimitive?.boolean ?: false)

        // Verify long description was accepted
        assertEquals(10000, featureSlot.captured.description?.length)
    }
}
