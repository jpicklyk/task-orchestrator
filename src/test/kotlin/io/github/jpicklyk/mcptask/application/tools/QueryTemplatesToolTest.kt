package io.github.jpicklyk.mcptask.application.tools

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
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.*

class QueryTemplatesToolTest {
    private lateinit var tool: QueryTemplatesTool
    private lateinit var context: ToolExecutionContext
    private lateinit var mockTemplateRepository: TemplateRepository
    private lateinit var mockRepositoryProvider: RepositoryProvider

    private val templateId = UUID.randomUUID()
    private lateinit var mockTemplate: Template
    private lateinit var mockTemplateSections: List<TemplateSection>

    @BeforeEach
    fun setup() {
        // Create mock repositories
        mockTemplateRepository = mockk()
        mockRepositoryProvider = mockk()

        // Configure repository provider
        every { mockRepositoryProvider.templateRepository() } returns mockTemplateRepository

        // Create test template
        mockTemplate = Template(
            id = templateId,
            name = "Test Template",
            description = "Test template description",
            targetEntityType = EntityType.TASK,
            isBuiltIn = true,
            isProtected = true,
            isEnabled = true,
            createdBy = "system",
            tags = listOf("test", "kotlin"),
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )

        // Create test template sections
        mockTemplateSections = listOf(
            TemplateSection(
                id = UUID.randomUUID(),
                templateId = templateId,
                title = "Requirements",
                usageDescription = "Key requirements section",
                contentSample = "List requirements here...",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0,
                isRequired = true,
                tags = listOf("requirements")
            ),
            TemplateSection(
                id = UUID.randomUUID(),
                templateId = templateId,
                title = "Implementation",
                usageDescription = "Implementation details",
                contentSample = "Implementation approach...",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 1,
                isRequired = false,
                tags = listOf("implementation")
            )
        )

        // Create execution context
        context = ToolExecutionContext(mockRepositoryProvider)

        // Create tool (read-only, no locking needed)
        tool = QueryTemplatesTool(null, null)
    }

    @Nested
    inner class ValidationTests {
        @Test
        fun `should require operation parameter`() {
            val params = buildJsonObject {}

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("operation"))
        }

        @Test
        fun `should reject invalid operation`() {
            val params = buildJsonObject {
                put("operation", "invalid")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("Invalid operation"))
        }

        @Test
        fun `should require id for get operation`() {
            val params = buildJsonObject {
                put("operation", "get")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("id"))
        }

        @Test
        fun `should reject invalid UUID format for get operation`() {
            val params = buildJsonObject {
                put("operation", "get")
                put("id", "not-a-uuid")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("Invalid template ID format"))
        }

        @Test
        fun `should accept valid get parameters`() {
            val params = buildJsonObject {
                put("operation", "get")
                put("id", templateId.toString())
                put("includeSections", true)
            }

            assertDoesNotThrow { tool.validateParams(params) }
        }

        @Test
        fun `should accept valid list parameters`() {
            val params = buildJsonObject {
                put("operation", "list")
                put("targetEntityType", "TASK")
                put("isBuiltIn", true)
                put("isEnabled", true)
                put("tags", "test,kotlin")
            }

            assertDoesNotThrow { tool.validateParams(params) }
        }

        @Test
        fun `should reject invalid targetEntityType for list operation`() {
            val params = buildJsonObject {
                put("operation", "list")
                put("targetEntityType", "INVALID")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("Invalid target entity type"))
        }
    }

    @Nested
    inner class GetOperationTests {
        @Test
        fun `should get template without sections`() = runBlocking {
            // Setup mock
            coEvery { mockTemplateRepository.getTemplate(templateId) } returns Result.Success(mockTemplate)

            // Execute
            val params = buildJsonObject {
                put("operation", "get")
                put("id", templateId.toString())
                put("includeSections", false)
            }

            val result = tool.execute(params, context) as JsonObject

            // Verify
            assertEquals(true, result["success"]?.jsonPrimitive?.boolean)
            assertTrue(result["message"]?.jsonPrimitive?.content?.contains("Template retrieved successfully") == true)

            val data = result["data"]?.jsonObject!!
            assertEquals(templateId.toString(), data["id"]?.jsonPrimitive?.content)
            assertEquals("Test Template", data["name"]?.jsonPrimitive?.content)
            assertEquals("Test template description", data["description"]?.jsonPrimitive?.content)
            assertEquals("TASK", data["targetEntityType"]?.jsonPrimitive?.content)
            assertEquals(true, data["isBuiltIn"]?.jsonPrimitive?.boolean)
            assertEquals(true, data["isProtected"]?.jsonPrimitive?.boolean)
            assertEquals(true, data["isEnabled"]?.jsonPrimitive?.boolean)
            assertEquals("system", data["createdBy"]?.jsonPrimitive?.content)

            // Sections should not be included
            assertFalse(data.containsKey("sections"))
        }

        @Test
        fun `should get template with sections`() = runBlocking {
            // Setup mock
            coEvery { mockTemplateRepository.getTemplate(templateId) } returns Result.Success(mockTemplate)
            coEvery { mockTemplateRepository.getTemplateSections(templateId) } returns Result.Success(mockTemplateSections)

            // Execute
            val params = buildJsonObject {
                put("operation", "get")
                put("id", templateId.toString())
                put("includeSections", true)
            }

            val result = tool.execute(params, context) as JsonObject

            // Verify
            assertEquals(true, result["success"]?.jsonPrimitive?.boolean)

            val data = result["data"]?.jsonObject!!
            assertEquals(templateId.toString(), data["id"]?.jsonPrimitive?.content)

            // Sections should be included
            assertTrue(data.containsKey("sections"))
            val sections = data["sections"]?.jsonArray!!
            assertEquals(2, sections.size)

            // Verify first section
            val section1 = sections[0].jsonObject
            assertEquals("Requirements", section1["title"]?.jsonPrimitive?.content)
            assertEquals("Key requirements section", section1["usageDescription"]?.jsonPrimitive?.content)
            assertEquals("List requirements here...", section1["contentSample"]?.jsonPrimitive?.content)
            assertEquals("markdown", section1["contentFormat"]?.jsonPrimitive?.content)
            assertEquals(0, section1["ordinal"]?.jsonPrimitive?.int)
            assertEquals(true, section1["isRequired"]?.jsonPrimitive?.boolean)
        }

        @Test
        fun `should return error for non-existent template`() = runBlocking {
            val nonExistentId = UUID.randomUUID()
            coEvery { mockTemplateRepository.getTemplate(nonExistentId) } returns
                Result.Error(RepositoryError.NotFound(nonExistentId, EntityType.TEMPLATE, "Template not found"))

            val params = buildJsonObject {
                put("operation", "get")
                put("id", nonExistentId.toString())
            }

            val result = tool.execute(params, context) as JsonObject

            assertEquals(false, result["success"]?.jsonPrimitive?.boolean)
            assertTrue(result["message"]?.jsonPrimitive?.content?.contains("Template not found") == true)

            val error = result["error"]?.jsonObject!!
            assertEquals(ErrorCodes.RESOURCE_NOT_FOUND, error["code"]?.jsonPrimitive?.content)
        }

        @Test
        fun `should handle section retrieval error gracefully`() = runBlocking {
            coEvery { mockTemplateRepository.getTemplate(templateId) } returns Result.Success(mockTemplate)
            coEvery { mockTemplateRepository.getTemplateSections(templateId) } returns
                Result.Error(RepositoryError.DatabaseError("Section retrieval failed"))

            val params = buildJsonObject {
                put("operation", "get")
                put("id", templateId.toString())
                put("includeSections", true)
            }

            val result = tool.execute(params, context) as JsonObject

            // Should still succeed with template data
            assertEquals(true, result["success"]?.jsonPrimitive?.boolean)
            val data = result["data"]?.jsonObject!!
            assertEquals(templateId.toString(), data["id"]?.jsonPrimitive?.content)
        }
    }

    @Nested
    inner class ListOperationTests {
        @Test
        fun `should list all templates without filters`() = runBlocking {
            val templates = listOf(mockTemplate)
            coEvery { mockTemplateRepository.getAllTemplates(null, null, null, null) } returns Result.Success(templates)

            val params = buildJsonObject {
                put("operation", "list")
            }

            val result = tool.execute(params, context) as JsonObject

            assertEquals(true, result["success"]?.jsonPrimitive?.boolean)
            assertTrue(result["message"]?.jsonPrimitive?.content?.contains("Retrieved 1 template") == true)

            val data = result["data"]?.jsonObject!!
            assertEquals(1, data["count"]?.jsonPrimitive?.int)

            val templatesList = data["templates"]?.jsonArray!!
            assertEquals(1, templatesList.size)

            val template = templatesList[0].jsonObject
            assertEquals(templateId.toString(), template["id"]?.jsonPrimitive?.content)
            assertEquals("Test Template", template["name"]?.jsonPrimitive?.content)
        }

        @Test
        fun `should list templates filtered by targetEntityType`() = runBlocking {
            val templates = listOf(mockTemplate)
            coEvery { mockTemplateRepository.getAllTemplates(EntityType.TASK, null, null, null) } returns Result.Success(templates)

            val params = buildJsonObject {
                put("operation", "list")
                put("targetEntityType", "TASK")
            }

            val result = tool.execute(params, context) as JsonObject

            assertEquals(true, result["success"]?.jsonPrimitive?.boolean)

            val data = result["data"]?.jsonObject!!
            assertEquals(1, data["count"]?.jsonPrimitive?.int)

            val filters = data["filters"]?.jsonObject!!
            assertEquals("TASK", filters["targetEntityType"]?.jsonPrimitive?.content)
        }

        @Test
        fun `should list templates filtered by isBuiltIn`() = runBlocking {
            val templates = listOf(mockTemplate)
            coEvery { mockTemplateRepository.getAllTemplates(null, true, null, null) } returns Result.Success(templates)

            val params = buildJsonObject {
                put("operation", "list")
                put("isBuiltIn", true)
            }

            val result = tool.execute(params, context) as JsonObject

            assertEquals(true, result["success"]?.jsonPrimitive?.boolean)

            val data = result["data"]?.jsonObject!!
            val filters = data["filters"]?.jsonObject!!
            assertEquals("true", filters["isBuiltIn"]?.jsonPrimitive?.content)
        }

        @Test
        fun `should list templates filtered by isEnabled`() = runBlocking {
            val templates = listOf(mockTemplate)
            coEvery { mockTemplateRepository.getAllTemplates(null, null, true, null) } returns Result.Success(templates)

            val params = buildJsonObject {
                put("operation", "list")
                put("isEnabled", true)
            }

            val result = tool.execute(params, context) as JsonObject

            assertEquals(true, result["success"]?.jsonPrimitive?.boolean)

            val data = result["data"]?.jsonObject!!
            val filters = data["filters"]?.jsonObject!!
            assertEquals("true", filters["isEnabled"]?.jsonPrimitive?.content)
        }

        @Test
        fun `should list templates filtered by tags`() = runBlocking {
            val templates = listOf(mockTemplate)
            val tags = listOf("test", "kotlin")
            coEvery { mockTemplateRepository.getAllTemplates(null, null, null, tags) } returns Result.Success(templates)

            val params = buildJsonObject {
                put("operation", "list")
                put("tags", "test,kotlin")
            }

            val result = tool.execute(params, context) as JsonObject

            assertEquals(true, result["success"]?.jsonPrimitive?.boolean)

            val data = result["data"]?.jsonObject!!
            val filters = data["filters"]?.jsonObject!!
            assertEquals("test,kotlin", filters["tags"]?.jsonPrimitive?.content)
        }

        @Test
        fun `should list templates with multiple filters`() = runBlocking {
            val templates = listOf(mockTemplate)
            val tags = listOf("test")
            coEvery {
                mockTemplateRepository.getAllTemplates(EntityType.TASK, true, true, tags)
            } returns Result.Success(templates)

            val params = buildJsonObject {
                put("operation", "list")
                put("targetEntityType", "TASK")
                put("isBuiltIn", true)
                put("isEnabled", true)
                put("tags", "test")
            }

            val result = tool.execute(params, context) as JsonObject

            assertEquals(true, result["success"]?.jsonPrimitive?.boolean)

            val data = result["data"]?.jsonObject!!
            assertEquals(1, data["count"]?.jsonPrimitive?.int)

            val filters = data["filters"]?.jsonObject!!
            assertEquals("TASK", filters["targetEntityType"]?.jsonPrimitive?.content)
            assertEquals("true", filters["isBuiltIn"]?.jsonPrimitive?.content)
            assertEquals("true", filters["isEnabled"]?.jsonPrimitive?.content)
            assertEquals("test", filters["tags"]?.jsonPrimitive?.content)
        }

        @Test
        fun `should return empty list when no templates match`() = runBlocking {
            coEvery { mockTemplateRepository.getAllTemplates(null, null, null, null) } returns Result.Success(emptyList())

            val params = buildJsonObject {
                put("operation", "list")
            }

            val result = tool.execute(params, context) as JsonObject

            assertEquals(true, result["success"]?.jsonPrimitive?.boolean)
            assertTrue(result["message"]?.jsonPrimitive?.content?.contains("No templates found") == true)

            val data = result["data"]?.jsonObject!!
            assertEquals(0, data["count"]?.jsonPrimitive?.int)

            val templatesList = data["templates"]?.jsonArray!!
            assertEquals(0, templatesList.size)
        }

        @Test
        fun `should handle repository error in list operation`() = runBlocking {
            coEvery { mockTemplateRepository.getAllTemplates(null, null, null, null) } returns
                Result.Error(RepositoryError.DatabaseError("Database connection failed"))

            val params = buildJsonObject {
                put("operation", "list")
            }

            val result = tool.execute(params, context) as JsonObject

            assertEquals(false, result["success"]?.jsonPrimitive?.boolean)
            assertTrue(result["message"]?.jsonPrimitive?.content?.contains("Failed to retrieve templates") == true)

            val error = result["error"]?.jsonObject!!
            assertEquals(ErrorCodes.DATABASE_ERROR, error["code"]?.jsonPrimitive?.content)
        }
    }
}
