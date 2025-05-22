package io.github.jpicklyk.mcptask.application.tools.feature

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.repository.FeatureRepository
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.domain.repository.TemplateRepository
import io.github.jpicklyk.mcptask.infrastructure.repository.RepositoryProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.*

/**
 * Tests for template execution-related functionality in the CreateFeatureTool
 */
class CreateFeatureToolTemplateExecutionTest {
    
    private lateinit var tool: CreateFeatureTool
    private lateinit var context: ToolExecutionContext
    private lateinit var mockFeatureRepository: FeatureRepository
    private lateinit var mockTemplateRepository: TemplateRepository
    private lateinit var mockRepositoryProvider: RepositoryProvider
    private lateinit var mockFeature: Feature
    private val featureId = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        // Create mock repositories
        mockFeatureRepository = mockk<FeatureRepository>()
        mockTemplateRepository = mockk<TemplateRepository>()
        mockRepositoryProvider = mockk<RepositoryProvider>()

        // Configure the repository provider to return the mock repositories
        every { mockRepositoryProvider.featureRepository() } returns mockFeatureRepository
        every { mockRepositoryProvider.templateRepository() } returns mockTemplateRepository

        // Create a sample feature for the mock response
        mockFeature = Feature(
            id = featureId,
            name = "Test Feature",
            summary = "Test Feature Summary",
            status = FeatureStatus.PLANNING,
            priority = Priority.MEDIUM,
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )

        // Define behavior for create method
        coEvery {
            mockFeatureRepository.create(any())
        } returns Result.Success(mockFeature)
        
        // Default behavior for template repository (no templates applied)
        coEvery {
            mockTemplateRepository.applyMultipleTemplates(any(), any(), any())
        } returns Result.Success(emptyMap<UUID, List<TemplateSection>>())

        // Create the execution context with the mock repository provider
        context = ToolExecutionContext(mockRepositoryProvider)

        tool = CreateFeatureTool()
    }
    
    @Test
    fun `test feature creation with single template application`() = runBlocking {
        val templateId = UUID.randomUUID()
        
        // Mock template application result
        val templateSections = mapOf(templateId to listOf<TemplateSection>())
        
        coEvery {
            mockTemplateRepository.applyMultipleTemplates(listOf(templateId), EntityType.FEATURE, featureId)
        } returns Result.Success(templateSections)
        
        val params = JsonObject(
            mapOf(
                "name" to JsonPrimitive("Test Feature"),
                "summary" to JsonPrimitive("This is a test feature"),
                "templateIds" to JsonArray(listOf(JsonPrimitive(templateId.toString())))
            )
        )

        val result = tool.execute(params, context)

        // Check that the result is a success response
        val resultObj = result as JsonObject
        assertEquals(true, (resultObj["success"] as JsonPrimitive).content.toBoolean())
        assertTrue((resultObj["message"] as JsonPrimitive).content.contains("template(s) applied"))
        
        // Check that the data contains appliedTemplates
        val data = resultObj["data"] as JsonObject
        assertNotNull(data["appliedTemplates"])
        
        val appliedTemplates = data["appliedTemplates"] as JsonArray
        assertEquals(1, appliedTemplates.size)
        
        val appliedTemplate = appliedTemplates[0] as JsonObject
        assertEquals(templateId.toString(), (appliedTemplate["templateId"] as JsonPrimitive).content)
        assertEquals(0, (appliedTemplate["sectionsCreated"] as JsonPrimitive).content.toInt())
    }
}
