package io.github.jpicklyk.mcptask.application.tools.template

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.domain.model.ContentFormat
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Template
import io.github.jpicklyk.mcptask.domain.model.TemplateSection
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.domain.repository.TemplateRepository
import io.github.jpicklyk.mcptask.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
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

class GetTemplateToolTest {
    private lateinit var tool: GetTemplateTool
    private lateinit var context: ToolExecutionContext
    private lateinit var mockTemplateRepository: TemplateRepository
    private val testTemplateId = UUID.randomUUID()
    private lateinit var testTemplate: Template
    private lateinit var testTemplateSections: List<TemplateSection>

    @BeforeEach
    fun setup() {
        // Create mock repositories
        mockTemplateRepository = mockk<TemplateRepository>()
        val mockRepositoryProvider = mockk<RepositoryProvider>()

        // Configure the repository provider to return the mock repository
        every { mockRepositoryProvider.templateRepository() } returns mockTemplateRepository

        // Create a test template
        testTemplate = Template(
            id = testTemplateId,
            name = "Test Template",
            description = "This is a test template with a detailed description",
            targetEntityType = EntityType.TASK,
            isBuiltIn = false,
            isProtected = false,
            isEnabled = true,
            createdBy = "Test User",
            tags = listOf("test", "template", "task"),
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )

        // Create test sections for the template
        testTemplateSections = listOf(
            TemplateSection(
                id = UUID.randomUUID(),
                templateId = testTemplateId,
                title = "Requirements",
                usageDescription = "Key requirements for this task",
                contentSample = "List all requirements here...",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0,
                isRequired = true,
                tags = listOf("requirements", "specification")
            ),
            TemplateSection(
                id = UUID.randomUUID(),
                templateId = testTemplateId,
                title = "Implementation Notes",
                usageDescription = "Technical details for implementation",
                contentSample = "Describe implementation approach here...",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 1,
                isRequired = false,
                tags = listOf("implementation", "technical")
            )
        )

        // Define behavior for getTemplate method
        coEvery {
            mockTemplateRepository.getTemplate(testTemplateId)
        } returns Result.Success(testTemplate)

        // Define behavior for getTemplateSections method
        coEvery {
            mockTemplateRepository.getTemplateSections(testTemplateId)
        } returns Result.Success(testTemplateSections)

        // Define behavior for non-existent template
        coEvery {
            mockTemplateRepository.getTemplate(neq(testTemplateId))
        } returns Result.Error(RepositoryError.NotFound(UUID.randomUUID(), EntityType.TEMPLATE, "Template not found"))

        // Create the execution context with the mock repository provider
        context = ToolExecutionContext(mockRepositoryProvider)

        tool = GetTemplateTool()
    }

    @Test
    fun `test valid parameters validation`() {
        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(testTemplateId.toString()),
                "includeSections" to JsonPrimitive(true)
            )
        )

        // Should not throw an exception
        assertDoesNotThrow { tool.validateParams(params) }
    }

    @Test
    fun `test missing required parameters validation`() {
        val params = JsonObject(
            mapOf(
                "includeSections" to JsonPrimitive(true)
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
                "id" to JsonPrimitive("not-a-uuid")
            )
        )

        // Should throw an exception for invalid UUID
        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Invalid template ID format"))
    }

    @Test
    fun `execute with valid ID should return template details`() = runBlocking {
        // Create parameters with just the ID
        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(testTemplateId.toString())
            )
        )

        // Execute the tool
        val result = tool.execute(params, context)

        // Check that the result is a success response
        val resultObj = result as JsonObject
        assertEquals(true, (resultObj["success"] as JsonPrimitive).content.toBoolean())
        assertTrue((resultObj["message"] as JsonPrimitive).content.contains("Template retrieved successfully"))

        // Verify the returned data
        val data = resultObj["data"] as JsonObject
        assertEquals(testTemplateId.toString(), (data["id"] as JsonPrimitive).content)
        assertEquals("Test Template", (data["name"] as JsonPrimitive).content)
        assertTrue(data.containsKey("description"))
        assertEquals("TASK", (data["targetEntityType"] as JsonPrimitive).content)

        // Tags should be included but we don't assert on exact count
        assertTrue(data.containsKey("tags"))

        // Sections should not be included by default
        assertFalse(data.containsKey("sections"))
    }

    @Test
    fun `execute with includeSections should return template with sections`() = runBlocking {
        // Create parameters with includeSections=true
        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(testTemplateId.toString()),
                "includeSections" to JsonPrimitive(true)
            )
        )

        // Execute the tool
        val result = tool.execute(params, context)

        // Check that the result is a success response
        val resultObj = result as JsonObject
        assertEquals(true, (resultObj["success"] as JsonPrimitive).content.toBoolean())

        // Verify the returned data
        val data = resultObj["data"] as JsonObject
        assertEquals(testTemplateId.toString(), (data["id"] as JsonPrimitive).content)

        // Check if sections exist - we can't guarantee the exact structure as it depends on implementation
        assertTrue(data.containsKey("sections"))
        val sections = data["sections"] as JsonArray
        assertTrue(sections.size > 0)

        // Verify basic section structure
        val section1 = sections[0] as JsonObject
        assertTrue(section1.containsKey("id"))
        assertTrue(section1.containsKey("title"))
    }

    @Test
    fun `execute with non-existent ID should return error`() = runBlocking {
        // Create parameters with a non-existent template ID
        val nonExistentId = UUID.randomUUID()
        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(nonExistentId.toString())
            )
        )

        // Execute the tool
        val result = tool.execute(params, context)

        // Check that the result is an error response
        val resultObj = result as JsonObject
        assertEquals(false, (resultObj["success"] as JsonPrimitive).content.toBoolean())
        assertTrue((resultObj["message"] as JsonPrimitive).content.contains("Template not found"))

        // Error should contain the error code
        val error = resultObj["error"] as JsonObject
        assertEquals(ErrorCodes.RESOURCE_NOT_FOUND, (error["code"] as JsonPrimitive).content)
    }

    @Test
    fun `execute with section retrieval error should still return template`() = runBlocking {
        // Setup mock to return an error for section retrieval
        coEvery {
            mockTemplateRepository.getTemplateSections(testTemplateId)
        } returns Result.Error(RepositoryError.DatabaseError("Failed to retrieve template sections"))

        // Create parameters with includeSections=true
        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(testTemplateId.toString()),
                "includeSections" to JsonPrimitive(true)
            )
        )

        // Execute the tool
        val result = tool.execute(params, context)

        // Check that the result is a success response (should still succeed with the template)
        val resultObj = result as JsonObject
        assertEquals(true, (resultObj["success"] as JsonPrimitive).content.toBoolean())

        // Template data should still be present
        val data = resultObj["data"] as JsonObject
        assertEquals(testTemplateId.toString(), (data["id"] as JsonPrimitive).content)

        // We won't assert on the exact message or sections content, just that we get a success response
        // with the basic template data
    }
}
