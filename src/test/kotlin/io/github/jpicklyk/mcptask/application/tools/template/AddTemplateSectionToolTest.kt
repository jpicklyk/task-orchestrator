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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.*

class AddTemplateSectionToolTest {
    private lateinit var tool: AddTemplateSectionTool
    private lateinit var context: ToolExecutionContext
    private lateinit var mockTemplateRepository: TemplateRepository
    private val testTemplateId = UUID.randomUUID()
    private val testTemplateSectionId = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        // Create mock repositories
        mockTemplateRepository = mockk<TemplateRepository>()
        val mockRepositoryProvider = mockk<RepositoryProvider>()

        // Configure the repository provider to return the mock repository
        every { mockRepositoryProvider.templateRepository() } returns mockTemplateRepository

        // Create a test template
        val testTemplate = Template(
            id = testTemplateId,
            name = "Test Template",
            description = "This is a test template",
            targetEntityType = EntityType.TASK,
            isBuiltIn = false,
            isProtected = false,
            isEnabled = true,
            createdBy = "Test User",
            tags = listOf("test", "template"),
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )

        // Define behavior for getTemplate method
        coEvery {
            mockTemplateRepository.getTemplate(testTemplateId)
        } returns Result.Success(testTemplate)

        // Define behavior for non-existent template
        coEvery {
            mockTemplateRepository.getTemplate(neq(testTemplateId))
        } returns Result.Error(RepositoryError.NotFound(UUID.randomUUID(), EntityType.TEMPLATE, "Template not found"))

        // Define behavior for addTemplateSection method
        coEvery {
            mockTemplateRepository.addTemplateSection(eq(testTemplateId), any())
        } returns Result.Success(
            TemplateSection(
                id = testTemplateSectionId,
                templateId = testTemplateId,
                title = "Test Section",
                usageDescription = "Test section usage",
                contentSample = "Sample content for test section",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0,
                isRequired = true,
                tags = listOf("test", "section")
            )
        )

        // Create the execution context with the mock repository provider
        context = ToolExecutionContext(mockRepositoryProvider)

        tool = AddTemplateSectionTool()
    }

    @Test
    fun `test valid parameters validation`() {
        val params = JsonObject(
            mapOf(
                "templateId" to JsonPrimitive(testTemplateId.toString()),
                "title" to JsonPrimitive("Test Section"),
                "usageDescription" to JsonPrimitive("This section is for test purposes"),
                "contentSample" to JsonPrimitive("Sample content goes here"),
                "contentFormat" to JsonPrimitive("MARKDOWN"),
                "ordinal" to JsonPrimitive(0),
                "isRequired" to JsonPrimitive(true),
                "tags" to JsonPrimitive("test,section,example")
            )
        )

        // Should not throw an exception
        assertDoesNotThrow { tool.validateParams(params) }
    }

    @Test
    fun `test missing required parameters validation`() {
        val params = JsonObject(
            mapOf(
                "templateId" to JsonPrimitive(testTemplateId.toString()),
                "title" to JsonPrimitive("Test Section")
                // Missing usageDescription, contentSample, and ordinal
            )
        )

        // Should throw an exception for missing usageDescription
        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Missing required parameter: usageDescription"))
    }

    @Test
    fun `test invalid templateId format validation`() {
        val params = JsonObject(
            mapOf(
                "templateId" to JsonPrimitive("not-a-uuid"),
                "title" to JsonPrimitive("Test Section"),
                "usageDescription" to JsonPrimitive("Test description"),
                "contentSample" to JsonPrimitive("Sample content"),
                "ordinal" to JsonPrimitive(0)
            )
        )

        // Should throw an exception for invalid UUID
        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Invalid template ID format"))
    }

    @Test
    fun `test negative ordinal validation`() {
        val params = JsonObject(
            mapOf(
                "templateId" to JsonPrimitive(testTemplateId.toString()),
                "title" to JsonPrimitive("Test Section"),
                "usageDescription" to JsonPrimitive("Test description"),
                "contentSample" to JsonPrimitive("Sample content"),
                "ordinal" to JsonPrimitive(-1)
            )
        )

        // Should throw an exception for negative ordinal
        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Ordinal must be a non-negative integer"))
    }

    @Test
    fun `test invalid contentFormat validation`() {
        val params = JsonObject(
            mapOf(
                "templateId" to JsonPrimitive(testTemplateId.toString()),
                "title" to JsonPrimitive("Test Section"),
                "usageDescription" to JsonPrimitive("Test description"),
                "contentSample" to JsonPrimitive("Sample content"),
                "ordinal" to JsonPrimitive(0),
                "contentFormat" to JsonPrimitive("INVALID_FORMAT")
            )
        )

        // Should throw an exception for invalid content format
        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Invalid content format"))
    }

    @Test
    fun `test successful section creation`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "templateId" to JsonPrimitive(testTemplateId.toString()),
                "title" to JsonPrimitive("Test Section"),
                "usageDescription" to JsonPrimitive("Test section usage"),
                "contentSample" to JsonPrimitive("Sample content for test section"),
                "contentFormat" to JsonPrimitive("MARKDOWN"),
                "ordinal" to JsonPrimitive(0),
                "isRequired" to JsonPrimitive(true),
                "tags" to JsonPrimitive("test,section")
            )
        )

        val result = tool.execute(params, context)

        // Check that the result is a success response
        val resultObj = result as JsonObject
        assertEquals(true, (resultObj["success"] as JsonPrimitive).content.toBoolean())
        assertEquals("Template section added successfully", (resultObj["message"] as JsonPrimitive).content)

        // Check the returned data
        val data = resultObj["data"] as JsonObject
        assertEquals(testTemplateSectionId.toString(), (data["id"] as JsonPrimitive).content)
        assertEquals(testTemplateId.toString(), (data["templateId"] as JsonPrimitive).content)
        assertEquals("Test Section", (data["title"] as JsonPrimitive).content)
        assertEquals("Test section usage", (data["usageDescription"] as JsonPrimitive).content)
        assertEquals("Sample content for test section", (data["contentSample"] as JsonPrimitive).content)
        assertEquals("markdown", (data["contentFormat"] as JsonPrimitive).content)
        assertEquals(0, (data["ordinal"] as JsonPrimitive).content.toInt())
        assertEquals(true, (data["isRequired"] as JsonPrimitive).content.toBoolean())
    }

    @Test
    fun `test creating section for non-existent template`() = runBlocking {
        val nonExistentId = UUID.randomUUID()
        val params = JsonObject(
            mapOf(
                "templateId" to JsonPrimitive(nonExistentId.toString()),
                "title" to JsonPrimitive("Test Section"),
                "usageDescription" to JsonPrimitive("Test section usage"),
                "contentSample" to JsonPrimitive("Sample content for test section"),
                "ordinal" to JsonPrimitive(0)
            )
        )

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
    fun `test section creation with validation error`() = runBlocking {
        // Since our parameter validation now catches empty strings earlier,
        // we'll test with a non-empty title but setup the repository to return a validation error
        coEvery {
            mockTemplateRepository.addTemplateSection(eq(testTemplateId), any())
        } returns Result.Error(RepositoryError.ValidationError("Section validation failed: Title cannot be empty"))

        val params = JsonObject(
            mapOf(
                "templateId" to JsonPrimitive(testTemplateId.toString()),
                "title" to JsonPrimitive("Test Section"),  // Valid title to get past parameter validation
                "usageDescription" to JsonPrimitive("Test section usage"),
                "contentSample" to JsonPrimitive("Sample content for test section"),
                "ordinal" to JsonPrimitive(0)
            )
        )

        val result = tool.execute(params, context)

        // Check that the result is an error response
        val resultObj = result as JsonObject
        assertEquals(false, (resultObj["success"] as JsonPrimitive).content.toBoolean())

        // Error should contain the validation error code
        val error = resultObj["error"] as JsonObject
        assertEquals(ErrorCodes.VALIDATION_ERROR, (error["code"] as JsonPrimitive).content)
    }

    @Test
    fun `test section creation with database error`() = runBlocking {
        // Setup mock to return a database error
        coEvery {
            mockTemplateRepository.addTemplateSection(eq(testTemplateId), any())
        } returns Result.Error(RepositoryError.DatabaseError("Failed to add section to template"))

        val params = JsonObject(
            mapOf(
                "templateId" to JsonPrimitive(testTemplateId.toString()),
                "title" to JsonPrimitive("Test Section"),
                "usageDescription" to JsonPrimitive("Test section usage"),
                "contentSample" to JsonPrimitive("Sample content for test section"),
                "ordinal" to JsonPrimitive(0)
            )
        )

        val result = tool.execute(params, context)

        // Check that the result is an error response
        val resultObj = result as JsonObject
        assertEquals(false, (resultObj["success"] as JsonPrimitive).content.toBoolean())

        // Error should contain the database error code
        val error = resultObj["error"] as JsonObject
        assertEquals(ErrorCodes.DATABASE_ERROR, (error["code"] as JsonPrimitive).content)
    }
}
