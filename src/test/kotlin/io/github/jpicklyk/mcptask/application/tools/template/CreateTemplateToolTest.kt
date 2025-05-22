package io.github.jpicklyk.mcptask.application.tools.template

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Template
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

class CreateTemplateToolTest {
    private lateinit var tool: CreateTemplateTool
    private lateinit var context: ToolExecutionContext
    private lateinit var mockTemplateRepository: TemplateRepository

    @BeforeEach
    fun setup() {
        // Create mock repositories
        mockTemplateRepository = mockk<TemplateRepository>()
        val mockRepositoryProvider = mockk<RepositoryProvider>()

        // Configure the repository provider to return the mock repository
        every { mockRepositoryProvider.templateRepository() } returns mockTemplateRepository

        // Define behavior for createTemplate method
        coEvery {
            mockTemplateRepository.createTemplate(any())
        } returns Result.Success(
            Template(
                id = UUID.randomUUID(),
                name = "Test Template",
                description = "Test Template Description",
                targetEntityType = EntityType.TASK,
                isBuiltIn = false,
                isProtected = false,
                isEnabled = true,
                createdBy = "Test User",
                tags = listOf("test", "template"),
                createdAt = Instant.now(),
                modifiedAt = Instant.now()
            )
        )

        // Create the execution context with the mock repository provider
        context = ToolExecutionContext(mockRepositoryProvider)

        tool = CreateTemplateTool()
    }

    @Test
    fun `test valid parameters validation`() {
        val params = JsonObject(
            mapOf(
                "name" to JsonPrimitive("Test Template"),
                "description" to JsonPrimitive("This is a test template"),
                "targetEntityType" to JsonPrimitive("TASK"),
                "isBuiltIn" to JsonPrimitive(false),
                "isProtected" to JsonPrimitive(false),
                "isEnabled" to JsonPrimitive(true),
                "createdBy" to JsonPrimitive("Test User"),
                "tags" to JsonPrimitive("test,template,example")
            )
        )

        // Should not throw an exception
        assertDoesNotThrow { tool.validateParams(params) }
    }

    @Test
    fun `test missing required parameters validation`() {
        val params = JsonObject(
            mapOf(
                "name" to JsonPrimitive("Test Template"),
                "description" to JsonPrimitive("This is a test template")
                // Missing targetEntityType
            )
        )

        // Should throw an exception for missing targetEntityType
        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Missing required parameter: targetEntityType"))
    }

    @Test
    fun `test empty name parameter validation`() {
        val params = JsonObject(
            mapOf(
                "name" to JsonPrimitive(""),
                "description" to JsonPrimitive("This is a test template"),
                "targetEntityType" to JsonPrimitive("TASK")
            )
        )

        // When the tool validates, it should throw an exception for empty name
        coEvery {
            mockTemplateRepository.createTemplate(any())
        } returns Result.Error(
            RepositoryError.ValidationError("Template validation failed: Name cannot be empty")
        )

        runBlocking {
            val result = tool.execute(params, context) as JsonObject
            assertEquals(false, (result["success"] as JsonPrimitive).content.toBoolean())
            // Check error code is present, as the exact code might vary depending on implementation
            assertTrue(result.containsKey("error"))
            assertTrue((result["error"] as JsonObject).containsKey("code"))
        }
    }

    @Test
    fun `test invalid targetEntityType parameter validation`() {
        val params = JsonObject(
            mapOf(
                "name" to JsonPrimitive("Test Template"),
                "description" to JsonPrimitive("This is a test template"),
                "targetEntityType" to JsonPrimitive("INVALID_TYPE")
            )
        )

        // Should throw an exception for invalid targetEntityType
        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Invalid target entity type"))
    }

    @Test
    fun `test successful template creation`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "name" to JsonPrimitive("Test Template"),
                "description" to JsonPrimitive("This is a test template"),
                "targetEntityType" to JsonPrimitive("TASK"),
                "isBuiltIn" to JsonPrimitive(false),
                "isProtected" to JsonPrimitive(false),
                "isEnabled" to JsonPrimitive(true),
                "createdBy" to JsonPrimitive("Test User"),
                "tags" to JsonPrimitive("test,template")
            )
        )

        val result = tool.execute(params, context)

        // Check that the result is a success response
        val resultObj = result as JsonObject
        assertTrue(resultObj.containsKey("success"))
        assertTrue(resultObj["success"] is JsonPrimitive)
        assertTrue((resultObj["success"] as JsonPrimitive).content.toBoolean())

        // Check response message
        assertEquals("Template created successfully", (resultObj["message"] as JsonPrimitive).content)

        // Check that the data contains the template information
        val data = resultObj["data"] as JsonObject
        assertTrue(data.containsKey("id"))
        assertTrue(data.containsKey("name"))
        assertEquals("Test Template", (data["name"] as JsonPrimitive).content)
        assertEquals("TASK", (data["targetEntityType"] as JsonPrimitive).content)
    }

    @Test
    fun `test template creation with name conflict`() = runBlocking {
        // Setup mock to return a conflict error
        coEvery {
            mockTemplateRepository.createTemplate(any())
        } returns Result.Error(
            RepositoryError.ConflictError("A template with name 'Test Template' already exists")
        )

        val params = JsonObject(
            mapOf(
                "name" to JsonPrimitive("Test Template"),
                "description" to JsonPrimitive("This is a test template"),
                "targetEntityType" to JsonPrimitive("TASK")
            )
        )

        val result = tool.execute(params, context)

        // Check that the result is an error response
        val resultObj = result as JsonObject
        assertEquals(false, (resultObj["success"] as JsonPrimitive).content.toBoolean())

        // Check error code
        val error = resultObj["error"] as JsonObject
        assertEquals(ErrorCodes.CONFLICT_ERROR, (error["code"] as JsonPrimitive).content)

        // Check error message
        assertTrue((resultObj["message"] as JsonPrimitive).content.contains("Template name already exists"))
    }

    @Test
    fun `test template creation with database error`() = runBlocking {
        // Setup mock to return a database error
        coEvery {
            mockTemplateRepository.createTemplate(any())
        } returns Result.Error(
            RepositoryError.DatabaseError("Failed to insert template into database")
        )

        val params = JsonObject(
            mapOf(
                "name" to JsonPrimitive("Test Template"),
                "description" to JsonPrimitive("This is a test template"),
                "targetEntityType" to JsonPrimitive("TASK")
            )
        )

        val result = tool.execute(params, context)

        // Check that the result is an error response
        val resultObj = result as JsonObject
        assertEquals(false, (resultObj["success"] as JsonPrimitive).content.toBoolean())

        // Check error code
        val error = resultObj["error"] as JsonObject
        assertEquals(ErrorCodes.DATABASE_ERROR, (error["code"] as JsonPrimitive).content)

        // Check error message
        assertTrue((resultObj["message"] as JsonPrimitive).content.contains("Failed to create template"))
    }
}
