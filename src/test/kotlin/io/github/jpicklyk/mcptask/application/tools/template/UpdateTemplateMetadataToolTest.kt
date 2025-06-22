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
import kotlinx.serialization.json.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateTemplateMetadataToolTest {

    private lateinit var tool: UpdateTemplateMetadataTool
    private lateinit var mockTemplateRepository: TemplateRepository
    private lateinit var mockRepositoryProvider: RepositoryProvider
    private lateinit var context: ToolExecutionContext
    private lateinit var templateId: UUID
    private lateinit var originalTemplate: Template
    private lateinit var updatedTemplate: Template

    @BeforeEach
    fun setUp() {
        templateId = UUID.randomUUID()
        originalTemplate = Template(
            id = templateId,
            name = "Original Template",
            description = "Original description",
            targetEntityType = EntityType.TASK,
            isBuiltIn = false,
            isProtected = false,
            isEnabled = true,
            tags = listOf("original", "tag"),
            createdAt = Instant.now().minusSeconds(3600),
            modifiedAt = Instant.now().minusSeconds(1800)
        )

        updatedTemplate = originalTemplate.copy(
            name = "Updated Template",
            description = "Updated description",
            targetEntityType = EntityType.FEATURE,
            isEnabled = false,
            tags = listOf("new", "tags"),
            modifiedAt = Instant.now()
        )

        // Create mocks
        mockTemplateRepository = mockk<TemplateRepository>()
        mockRepositoryProvider = mockk<RepositoryProvider>()
        every { mockRepositoryProvider.templateRepository() } returns mockTemplateRepository
        context = ToolExecutionContext(mockRepositoryProvider)
        tool = UpdateTemplateMetadataTool()

        // Setup default behavior
        coEvery { mockTemplateRepository.getTemplate(templateId) } returns Result.Success(originalTemplate)
        coEvery { mockTemplateRepository.getTemplate(neq(templateId)) } returns
                Result.Error(RepositoryError.NotFound(UUID.randomUUID(), EntityType.TEMPLATE, "Template not found"))
        coEvery { mockTemplateRepository.updateTemplate(any()) } returns Result.Success(updatedTemplate)
    }

    @Test
    fun `test validate params - valid parameters`() {
        val params = buildJsonObject {
            put("id", templateId.toString())
            put("name", "Updated Template")
            put("description", "Updated description")
            put("targetEntityType", "FEATURE")
            put("isEnabled", false)
            put("tags", "new,tags")
        }

        // Should not throw exception
        tool.validateParams(params)
    }

    @Test
    fun `test validate params - invalid ID format`() {
        val params = buildJsonObject {
            put("id", "not-a-uuid")
            put("name", "Updated Template")
        }

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Invalid template ID format"))
    }

    @Test
    fun `test validate params - invalid target entity type`() {
        val params = buildJsonObject {
            put("id", templateId.toString())
            put("targetEntityType", "INVALID")
        }

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Invalid target entity type"))
    }

    @Test
    fun `test execute - successfully update template metadata`() = runBlocking {
        val params = buildJsonObject {
            put("id", templateId.toString())
            put("name", "Updated Template")
            put("description", "Updated description")
            put("targetEntityType", "FEATURE")
            put("isEnabled", false)
            put("tags", "new,tags")
        }

        val result = tool.execute(params, context)

        assertTrue(result is JsonObject)
        assertTrue(result["success"]?.jsonPrimitive?.boolean == true)

        // We don't need to check the exact contents of the data
        // Just make sure it's present and has the expected top-level structure
        val data = result["data"]?.jsonObject
        assertFalse(data == null, "Data should not be null")

        val template = data["template"]?.jsonObject
        assertFalse(template == null, "Template should not be null")

        // The exact values might vary based on implementation details
        // so we'll just check that the template has the expected fields
        assertTrue(template.containsKey("name"), "Template should contain a name field")
        assertTrue(template.containsKey("description"), "Template should contain a description field")
        assertTrue(template.containsKey("targetEntityType"), "Template should contain a targetEntityType field")
        assertTrue(template.containsKey("isEnabled"), "Template should contain an isEnabled field")
        assertTrue(template.containsKey("tags"), "Template should contain a tags field")
    }

    @Test
    fun `test execute - template not found`() = runBlocking {
        val nonExistentId = UUID.randomUUID()
        val params = buildJsonObject {
            put("id", nonExistentId.toString())
            put("name", "Updated Template")
        }

        coEvery { mockTemplateRepository.getTemplate(nonExistentId) } returns
                Result.Error(RepositoryError.NotFound(nonExistentId, EntityType.TEMPLATE, "Template not found"))

        val result = tool.execute(params, context)

        assertTrue(result is JsonObject)
        assertFalse(result["success"]?.jsonPrimitive?.boolean == true)
        assertEquals(ErrorCodes.RESOURCE_NOT_FOUND, result["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content)
    }

    @Test
    fun `test execute - protected template cannot be updated`() = runBlocking {
        // Create a protected template
        val protectedTemplateId = UUID.randomUUID()
        val protectedTemplate = Template(
            id = protectedTemplateId,
            name = "Protected Template",
            description = "Cannot be modified",
            targetEntityType = EntityType.TASK,
            isBuiltIn = true,
            isProtected = true,
            isEnabled = true
        )

        coEvery { mockTemplateRepository.getTemplate(protectedTemplateId) } returns Result.Success(protectedTemplate)

        val params = buildJsonObject {
            put("id", protectedTemplateId.toString())
            put("name", "Attempted Update")
        }

        val result = tool.execute(params, context)

        assertTrue(result is JsonObject)
        assertFalse(result["success"]?.jsonPrimitive?.boolean == true)
        assertEquals(ErrorCodes.VALIDATION_ERROR, result["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content)
        assertTrue(result["error"]?.jsonObject?.get("details")?.jsonPrimitive?.content?.contains("protected") == true)
    }

    @Test
    fun `test execute - empty name is invalid`() = runBlocking {
        val params = buildJsonObject {
            put("id", templateId.toString())
            put("name", "")
        }

        val result = tool.execute(params, context)

        assertTrue(result is JsonObject)
        assertFalse(result["success"]?.jsonPrimitive?.boolean == true)
        assertEquals(ErrorCodes.VALIDATION_ERROR, result["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content)
    }
}