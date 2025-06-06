package io.github.jpicklyk.mcptask.application.tools.feature

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
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
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.*

class CreateFeatureToolTest {
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
    fun `test valid parameters validation`() {
        val params = JsonObject(
            mapOf(
                "name" to JsonPrimitive("Test Feature"),
                "summary" to JsonPrimitive("This is a test feature"),
                "status" to JsonPrimitive("planning"),
                "priority" to JsonPrimitive("medium"),
                "tags" to JsonPrimitive("test,kotlin,mcp")
            )
        )

        // Should not throw an exception
        assertDoesNotThrow { tool.validateParams(params) }
    }

    @Test
    fun `test missing required parameters validation`() {
        val params = JsonObject(
            mapOf(
                "summary" to JsonPrimitive("This is a test feature")
            )
        )

        // Should throw an exception for missing name
        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Missing required parameter: name"))
    }

    @Test
    fun `test empty name parameter validation`() {
        val params = JsonObject(
            mapOf(
                "name" to JsonPrimitive(""),
                "summary" to JsonPrimitive("This is a test feature")
            )
        )

        // Should throw an exception for empty name
        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Required parameter name cannot be empty"))
    }

    @Test
    fun `test missing summary parameter validation`() {
        val params = JsonObject(
            mapOf(
                "name" to JsonPrimitive("Test Feature")
            )
        )

        // Should throw an exception for missing summary
        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Missing required parameter: summary"))
    }

    @Test
    fun `test empty summary parameter validation`() {
        val params = JsonObject(
            mapOf(
                "name" to JsonPrimitive("Test Feature"),
                "summary" to JsonPrimitive("")
            )
        )

        // Should throw an exception for empty summary
        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Required parameter summary cannot be empty"))
    }

    @Test
    fun `test invalid status parameter validation`() {
        val params = JsonObject(
            mapOf(
                "name" to JsonPrimitive("Test Feature"),
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
                "name" to JsonPrimitive("Test Feature"),
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
    fun `test successful feature creation`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "name" to JsonPrimitive("Test Feature"),
                "summary" to JsonPrimitive("This is a test feature"),
                "status" to JsonPrimitive("planning"),
                "priority" to JsonPrimitive("medium"),
                "tags" to JsonPrimitive("test,kotlin,mcp")
            )
        )

        val result = tool.execute(params, context)

        // Check that the result is a success response
        val resultObj = result as JsonObject
        assertTrue(resultObj.containsKey("success"))
        assertTrue(resultObj["success"] is JsonPrimitive)
        assertTrue((resultObj["success"] as JsonPrimitive).content.toBoolean())
    }
    
    // Template-specific validation tests
    
    @Test
    fun `test valid templateId as array parameter validation`() {
        val templateId = UUID.randomUUID()
        val params = JsonObject(
            mapOf(
                "name" to JsonPrimitive("Test Feature"),
                "summary" to JsonPrimitive("This is a test feature"),
                "templateIds" to JsonArray(listOf(JsonPrimitive(templateId.toString())))
            )
        )

        // Should not throw an exception
        assertDoesNotThrow { tool.validateParams(params) }
    }
    
    @Test
    fun `test valid templateIds array validation`() {
        val templateId1 = UUID.randomUUID()
        val templateId2 = UUID.randomUUID()
        val params = JsonObject(
            mapOf(
                "name" to JsonPrimitive("Test Feature"),
                "summary" to JsonPrimitive("This is a test feature"),
                "templateIds" to JsonArray(listOf(
                    JsonPrimitive(templateId1.toString()),
                    JsonPrimitive(templateId2.toString())
                ))
            )
        )

        // Should not throw an exception
        assertDoesNotThrow { tool.validateParams(params) }
    }
    
    @Test
    fun `test invalid templateId format in array validation`() {
        val params = JsonObject(
            mapOf(
                "name" to JsonPrimitive("Test Feature"),
                "summary" to JsonPrimitive("This is a test feature"),
                "templateIds" to JsonArray(listOf(JsonPrimitive("not-a-uuid")))
            )
        )

        // Should throw an exception for invalid template ID
        val exception = assertThrows<ToolValidationException> { tool.validateParams(params) }
        assertTrue(exception.message!!.contains("is not a valid UUID format"))
    }
    
    @Test
    fun `test invalid templateIds array validation`() {
        val params = JsonObject(
            mapOf(
                "name" to JsonPrimitive("Test Feature"),
                "summary" to JsonPrimitive("This is a test feature"),
                "templateIds" to JsonPrimitive("not-an-array")
            )
        )

        // Should throw an exception for invalid templateIds
        val exception = assertThrows<ToolValidationException> { tool.validateParams(params) }
        assertTrue(exception.message!!.contains("'templateIds' must be an array"))
    }
    
    @Test
    fun `test invalid templateIds array item validation`() {
        val params = JsonObject(
            mapOf(
                "name" to JsonPrimitive("Test Feature"),
                "summary" to JsonPrimitive("This is a test feature"),
                "templateIds" to JsonArray(listOf(JsonPrimitive("not-a-uuid")))
            )
        )

        // Should throw an exception for invalid templateIds array item
        val exception = assertThrows<ToolValidationException> { tool.validateParams(params) }
        assertTrue(exception.message!!.contains("is not a valid UUID format"))
    }
    
    // Template application execution tests
    
    @Test
    fun `test feature creation with single template application`() = runBlocking {
        val templateId = UUID.randomUUID()
        
        // Mock sections returned from template application
        val section = TemplateSection(
            id = UUID.randomUUID(),
            templateId = templateId,
            title = "Test Section",
            usageDescription = "Test usage description",
            contentSample = "Test content",
            ordinal = 0
        )
        
        coEvery {
            mockTemplateRepository.applyMultipleTemplates(listOf(templateId), EntityType.FEATURE, featureId)
        } returns Result.Success(mapOf<UUID, List<TemplateSection>>(templateId to listOf(section)))
        
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
        assertEquals(1, (appliedTemplate["sectionsCreated"] as JsonPrimitive).content.toInt())
    }
    
    @Test
    fun `test feature creation with multiple template application`() = runBlocking {
        val templateId1 = UUID.randomUUID()
        val templateId2 = UUID.randomUUID()
        
        // Mock sections returned from template application
        val section1 = TemplateSection(
            id = UUID.randomUUID(),
            templateId = templateId1,
            title = "Test Section 1",
            usageDescription = "Test usage description",
            contentSample = "Test content",
            ordinal = 0
        )

        val section2 = TemplateSection(
            id = UUID.randomUUID(),
            templateId = templateId2,
            title = "Test Section 2",
            usageDescription = "Test usage description",
            contentSample = "Test content",
            ordinal = 1
        )
        
        coEvery {
            mockTemplateRepository.applyMultipleTemplates(listOf(templateId1, templateId2), EntityType.FEATURE, featureId)
        } returns Result.Success(
            mapOf<UUID, List<TemplateSection>>(
            templateId1 to listOf(section1),
            templateId2 to listOf(section2)
        ))
        
        val params = JsonObject(
            mapOf(
                "name" to JsonPrimitive("Test Feature"),
                "summary" to JsonPrimitive("This is a test feature"),
                "templateIds" to JsonArray(listOf(
                    JsonPrimitive(templateId1.toString()),
                    JsonPrimitive(templateId2.toString())
                ))
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
        assertEquals(2, appliedTemplates.size)
        
        // Verify that both templates are reported in the response
        var foundTemplate1 = false
        var foundTemplate2 = false
        
        for (i in 0 until appliedTemplates.size) {
            val template = appliedTemplates[i] as JsonObject
            val id = (template["templateId"] as JsonPrimitive).content
            val sectionsCreated = (template["sectionsCreated"] as JsonPrimitive).content.toInt()
            
            if (id == templateId1.toString()) {
                foundTemplate1 = true
                assertEquals(1, sectionsCreated)
            } else if (id == templateId2.toString()) {
                foundTemplate2 = true
                assertEquals(1, sectionsCreated)
            }
        }
        
        assertTrue(foundTemplate1)
        assertTrue(foundTemplate2)
    }
}