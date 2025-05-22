package io.github.jpicklyk.mcptask.application.tools.template

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Template
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

class ListTemplatesToolTest {
    private lateinit var tool: ListTemplatesTool
    private lateinit var context: ToolExecutionContext
    private lateinit var mockTemplateRepository: TemplateRepository
    private val testTemplateId1 = UUID.randomUUID()
    private val testTemplateId2 = UUID.randomUUID()
    private val testTemplateId3 = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        // Create mock repositories
        mockTemplateRepository = mockk<TemplateRepository>()
        val mockRepositoryProvider = mockk<RepositoryProvider>()

        // Configure the repository provider to return the mock repository
        every { mockRepositoryProvider.templateRepository() } returns mockTemplateRepository

        // Create a list of templates
        val templates = listOf(
            Template(
                id = testTemplateId1,
                name = "Authentication Template",
                description = "User authentication implementation",
                targetEntityType = EntityType.FEATURE,
                isBuiltIn = true,
                isProtected = true,
                isEnabled = true,
                createdBy = "System",
                tags = listOf("auth", "security", "user"),
                createdAt = Instant.now().minusSeconds(86400 * 10), // 10 days ago
                modifiedAt = Instant.now().minusSeconds(86400 * 5)  // 5 days ago
            ),
            Template(
                id = testTemplateId2,
                name = "API Endpoint",
                description = "REST API implementation",
                targetEntityType = EntityType.TASK,
                isBuiltIn = true,
                isProtected = false,
                isEnabled = true,
                createdBy = "System",
                tags = listOf("api", "rest", "backend"),
                createdAt = Instant.now().minusSeconds(86400 * 8),  // 8 days ago
                modifiedAt = Instant.now().minusSeconds(86400 * 3)  // 3 days ago
            ),
            Template(
                id = testTemplateId3,
                name = "Custom Template",
                description = "User-defined custom template",
                targetEntityType = EntityType.TASK,
                isBuiltIn = false,
                isProtected = false,
                isEnabled = true,
                createdBy = "Test User",
                tags = listOf("custom", "example"),
                createdAt = Instant.now().minusSeconds(86400 * 2),  // 2 days ago
                modifiedAt = Instant.now().minusSeconds(86400)      // 1 day ago
            )
        )

        // Define behavior for getAllTemplates method
        coEvery {
            mockTemplateRepository.getAllTemplates(any(), any(), any(), any())
        } answers {
            // Extract parameters
            val targetEntityType = arg<EntityType?>(0)
            val isBuiltIn = arg<Boolean?>(1)
            val isEnabled = arg<Boolean?>(2)
            val tags = arg<List<String>?>(3)

            // Filter templates based on parameters
            var filtered = templates

            // Filter by targetEntityType if provided
            if (targetEntityType != null) {
                filtered = filtered.filter { it.targetEntityType == targetEntityType }
            }

            // Filter by isBuiltIn if provided
            if (isBuiltIn != null) {
                filtered = filtered.filter { it.isBuiltIn == isBuiltIn }
            }

            // Filter by isEnabled if provided
            if (isEnabled != null) {
                filtered = filtered.filter { it.isEnabled == isEnabled }
            }

            // Filter by tags if provided
            if (tags != null && tags.isNotEmpty()) {
                filtered = filtered.filter { template ->
                    tags.any { tag -> template.tags.contains(tag) }
                }
            }

            Result.Success(filtered)
        }

        // Create the execution context with the mock repository provider
        context = ToolExecutionContext(mockRepositoryProvider)

        tool = ListTemplatesTool()
    }

    @Test
    fun `test valid parameters validation`() {
        val params = JsonObject(
            mapOf(
                "targetEntityType" to JsonPrimitive("TASK"),
                "isBuiltIn" to JsonPrimitive(true),
                "isEnabled" to JsonPrimitive(true),
                "tags" to JsonPrimitive("api,rest")
            )
        )

        // Should not throw an exception
        assertDoesNotThrow { tool.validateParams(params) }
    }

    @Test
    fun `test invalid targetEntityType parameter validation`() {
        val params = JsonObject(
            mapOf(
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
    fun `execute with no filters should return all templates`() = runBlocking {
        // Create empty parameters (no filters)
        val params = JsonObject(mapOf())

        // Execute the tool
        val result = tool.execute(params, context)

        // Check that the result is a success response
        val resultObj = result as JsonObject
        assertEquals(true, (resultObj["success"] as JsonPrimitive).content.toBoolean())

        // Check the templates list and count
        val data = resultObj["data"] as JsonObject
        val templates = data["templates"] as JsonArray
        assertEquals(3, templates.size)
        assertEquals(3, (data["count"] as JsonPrimitive).content.toInt())

        // Check filters in response
        val filters = data["filters"] as JsonObject
        assertEquals("Any", (filters["targetEntityType"] as JsonPrimitive).content)
        assertEquals("Any", (filters["isBuiltIn"] as JsonPrimitive).content)
        assertEquals("Any", (filters["isEnabled"] as JsonPrimitive).content)
    }

    @Test
    fun `execute with targetEntityType filter should return filtered templates`() = runBlocking {
        // Create parameters with targetEntityType filter
        val params = JsonObject(
            mapOf(
                "targetEntityType" to JsonPrimitive("TASK")
            )
        )

        // Execute the tool
        val result = tool.execute(params, context)

        // Check that the result is a success response
        val resultObj = result as JsonObject
        assertEquals(true, (resultObj["success"] as JsonPrimitive).content.toBoolean())

        // Check the templates list and count (should be 2 TASK templates)
        val data = resultObj["data"] as JsonObject
        val templates = data["templates"] as JsonArray
        assertEquals(2, templates.size)
        assertEquals(2, (data["count"] as JsonPrimitive).content.toInt())

        // Check filters in response
        val filters = data["filters"] as JsonObject
        assertEquals("TASK", (filters["targetEntityType"] as JsonPrimitive).content)
    }

    @Test
    fun `execute with isBuiltIn filter should return filtered templates`() = runBlocking {
        // Create parameters with isBuiltIn filter
        val params = JsonObject(
            mapOf(
                "isBuiltIn" to JsonPrimitive(true)
            )
        )

        // Execute the tool
        val result = tool.execute(params, context)

        // Check that the result is a success response
        val resultObj = result as JsonObject
        assertEquals(true, (resultObj["success"] as JsonPrimitive).content.toBoolean())

        // Check the templates list and count (should be 2 built-in templates)
        val data = resultObj["data"] as JsonObject
        val templates = data["templates"] as JsonArray
        assertEquals(2, templates.size)
        assertEquals(2, (data["count"] as JsonPrimitive).content.toInt())

        // Check filters in response
        val filters = data["filters"] as JsonObject
        assertEquals("true", (filters["isBuiltIn"] as JsonPrimitive).content)
    }

    @Test
    fun `execute with tags filter should return filtered templates`() = runBlocking {
        // Create parameters with tags filter
        val params = JsonObject(
            mapOf(
                "tags" to JsonPrimitive("api")
            )
        )

        // Execute the tool
        val result = tool.execute(params, context)

        // Check that the result is a success response
        val resultObj = result as JsonObject
        assertEquals(true, (resultObj["success"] as JsonPrimitive).content.toBoolean())

        // Check the templates list and count (should be 1 template with 'api' tag)
        val data = resultObj["data"] as JsonObject
        val templates = data["templates"] as JsonArray
        assertEquals(1, templates.size)
        assertEquals(1, (data["count"] as JsonPrimitive).content.toInt())

        // Check the template is the API Endpoint one
        val template = templates[0] as JsonObject
        assertEquals("API Endpoint", (template["name"] as JsonPrimitive).content)

        // Check filters in response
        val filters = data["filters"] as JsonObject
        assertEquals("api", (filters["tags"] as JsonPrimitive).content)
    }

    @Test
    fun `execute with multiple filters should return correctly filtered templates`() = runBlocking {
        // Create parameters with multiple filters
        val params = JsonObject(
            mapOf(
                "targetEntityType" to JsonPrimitive("TASK"),
                "isBuiltIn" to JsonPrimitive(false)
            )
        )

        // Execute the tool
        val result = tool.execute(params, context)

        // Check that the result is a success response
        val resultObj = result as JsonObject
        assertEquals(true, (resultObj["success"] as JsonPrimitive).content.toBoolean())

        // Check the templates list and count (should be 1 template - the custom one)
        val data = resultObj["data"] as JsonObject
        val templates = data["templates"] as JsonArray
        assertEquals(1, templates.size)
        assertEquals(1, (data["count"] as JsonPrimitive).content.toInt())

        // Check the template is the Custom Template one
        val template = templates[0] as JsonObject
        assertEquals("Custom Template", (template["name"] as JsonPrimitive).content)
    }

    @Test
    fun `execute with filters that match no templates should return empty list`() = runBlocking {
        // Create parameters with filters that won't match any templates
        val params = JsonObject(
            mapOf(
                "targetEntityType" to JsonPrimitive("FEATURE"),
                "isBuiltIn" to JsonPrimitive(false)
            )
        )

        // Execute the tool
        val result = tool.execute(params, context)

        // Check that the result is a success response
        val resultObj = result as JsonObject
        assertEquals(true, (resultObj["success"] as JsonPrimitive).content.toBoolean())

        // Check message indicates no templates found
        assertEquals("No templates found matching criteria", (resultObj["message"] as JsonPrimitive).content)

        // Check the templates list is empty
        val data = resultObj["data"] as JsonObject
        val templates = data["templates"] as JsonArray
        assertEquals(0, templates.size)
        assertEquals(0, (data["count"] as JsonPrimitive).content.toInt())
    }

    @Test
    fun `test database error handling`() = runBlocking {
        // Setup mock to return a database error
        coEvery {
            mockTemplateRepository.getAllTemplates(any(), any(), any(), any())
        } returns Result.Error(io.github.jpicklyk.mcptask.domain.repository.RepositoryError.DatabaseError("Database connection failed"))

        // Create parameters
        val params = JsonObject(mapOf())

        // Execute the tool
        val result = tool.execute(params, context)

        // Check that the result is an error response
        val resultObj = result as JsonObject
        assertEquals(false, (resultObj["success"] as JsonPrimitive).content.toBoolean())

        // Check error message
        assertTrue((resultObj["message"] as JsonPrimitive).content.contains("Failed to retrieve templates"))
    }
}
