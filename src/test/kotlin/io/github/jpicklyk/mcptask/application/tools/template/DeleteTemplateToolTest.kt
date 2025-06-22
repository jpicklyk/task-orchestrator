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
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*

class DeleteTemplateToolTest {

    private lateinit var deleteTemplateTool: DeleteTemplateTool
    private lateinit var mockTemplateRepository: TemplateRepository
    private lateinit var mockRepositoryProvider: RepositoryProvider
    private lateinit var context: ToolExecutionContext

    private val regularTemplateId = UUID.randomUUID()
    private val builtInTemplateId = UUID.randomUUID()
    private val protectedTemplateId = UUID.randomUUID()
    private val nonExistentTemplateId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        // Create tool instance
        deleteTemplateTool = DeleteTemplateTool()

        // Set up mock repositories
        mockTemplateRepository = mockk<TemplateRepository>()
        mockRepositoryProvider = mockk<RepositoryProvider>()
        every { mockRepositoryProvider.templateRepository() } returns mockTemplateRepository

        // Set up context
        context = ToolExecutionContext(mockRepositoryProvider)

        // Set up template repository data
        val regularTemplate = Template(
            id = regularTemplateId,
            name = "Regular Template",
            description = "A regular user-created template",
            targetEntityType = EntityType.TASK,
            isBuiltIn = false,
            isProtected = false
        )

        val builtInTemplate = Template(
            id = builtInTemplateId,
            name = "Built-in Template",
            description = "A built-in template that cannot be deleted",
            targetEntityType = EntityType.TASK,
            isBuiltIn = true,
            isProtected = true
        )

        val protectedTemplate = Template(
            id = protectedTemplateId,
            name = "Protected Template",
            description = "A user-created template that is protected",
            targetEntityType = EntityType.TASK,
            isBuiltIn = false,
            isProtected = true
        )

        // Configure mock repository behavior for getTemplate
        coEvery { mockTemplateRepository.getTemplate(regularTemplateId) } returns Result.Success(regularTemplate)
        coEvery { mockTemplateRepository.getTemplate(builtInTemplateId) } returns Result.Success(builtInTemplate)
        coEvery { mockTemplateRepository.getTemplate(protectedTemplateId) } returns Result.Success(protectedTemplate)
        coEvery { mockTemplateRepository.getTemplate(nonExistentTemplateId) } returns
                Result.Error(RepositoryError.NotFound(nonExistentTemplateId, EntityType.TEMPLATE, "Template not found"))

        // Configure mock repository behavior for deleteTemplate
        coEvery { mockTemplateRepository.deleteTemplate(regularTemplateId, any()) } returns Result.Success(true)

        coEvery { mockTemplateRepository.deleteTemplate(builtInTemplateId, false) } returns
                Result.Error(RepositoryError.ValidationError("Cannot delete a built-in template without force=true"))
        coEvery { mockTemplateRepository.deleteTemplate(builtInTemplateId, true) } returns Result.Success(true)

        coEvery { mockTemplateRepository.deleteTemplate(protectedTemplateId, false) } returns
                Result.Error(RepositoryError.ValidationError("Cannot delete a protected template without force=true"))
        coEvery { mockTemplateRepository.deleteTemplate(protectedTemplateId, true) } returns Result.Success(true)

        coEvery { mockTemplateRepository.deleteTemplate(nonExistentTemplateId, any()) } returns
                Result.Error(RepositoryError.NotFound(nonExistentTemplateId, EntityType.TEMPLATE, "Template not found"))
    }

    @Test
    fun `should validate parameters correctly`() {
        // Valid parameters
        val validParams = buildJsonObject {
            put("id", JsonPrimitive(regularTemplateId.toString()))
        }
        assertDoesNotThrow { deleteTemplateTool.validateParams(validParams) }

        // Invalid UUID format
        val invalidIdParams = buildJsonObject {
            put("id", JsonPrimitive("not-a-uuid"))
        }
        assertThrows(ToolValidationException::class.java) {
            deleteTemplateTool.validateParams(invalidIdParams)
        }

        // Missing ID parameter
        val missingIdParams = buildJsonObject {}
        assertThrows(ToolValidationException::class.java) {
            deleteTemplateTool.validateParams(missingIdParams)
        }
    }

    @Test
    fun `should delete a regular template successfully`() = runBlocking {
        // Arrange
        val params = buildJsonObject {
            put("id", JsonPrimitive(regularTemplateId.toString()))
        }

        // Act
        val response = deleteTemplateTool.execute(params, context) as JsonObject

        // Assert
        assertTrue(response["success"]?.let { it as? JsonPrimitive }?.content?.toBoolean() ?: false)
        assertEquals("Template deleted successfully", response["message"]?.let { it as? JsonPrimitive }?.content)

        // Check the data
        val data = response["data"] as? JsonObject
        assertNotNull(data)
        assertEquals(regularTemplateId.toString(), data?.get("id")?.let { it as? JsonPrimitive }?.content)
        assertEquals("Regular Template", data?.get("name")?.let { it as? JsonPrimitive }?.content)
    }

    @Test
    fun `should fail to delete a built-in template without force`() = runBlocking {
        // Arrange
        val params = buildJsonObject {
            put("id", JsonPrimitive(builtInTemplateId.toString()))
        }

        // Act
        val response = deleteTemplateTool.execute(params, context) as JsonObject

        // Assert
        assertFalse(response["success"]?.let { it as? JsonPrimitive }?.content?.toBoolean() ?: true)
        assertTrue(
            response["message"]?.let { it as? JsonPrimitive }?.content?.contains("Built-in templates cannot be deleted")
                ?: false
        )

        // Check the error code
        val error = response["error"] as? JsonObject
        assertNotNull(error)
        assertEquals(ErrorCodes.VALIDATION_ERROR, error?.get("code")?.let { it as? JsonPrimitive }?.content)
    }

    @Test
    fun `should delete a built-in template with force parameter`() = runBlocking {
        // Arrange
        val params = buildJsonObject {
            put("id", JsonPrimitive(builtInTemplateId.toString()))
            put("force", JsonPrimitive(true))
        }

        // Act
        val response = deleteTemplateTool.execute(params, context) as JsonObject

        // Assert
        assertTrue(response["success"]?.let { it as? JsonPrimitive }?.content?.toBoolean() ?: false)
        assertEquals("Template deleted successfully", response["message"]?.let { it as? JsonPrimitive }?.content)
    }

    @Test
    fun `should fail to delete a protected template without force`() = runBlocking {
        // Arrange
        val params = buildJsonObject {
            put("id", JsonPrimitive(protectedTemplateId.toString()))
        }

        // Act
        val response = deleteTemplateTool.execute(params, context) as JsonObject

        // Assert
        assertFalse(response["success"]?.let { it as? JsonPrimitive }?.content?.toBoolean() ?: true)
        assertTrue(
            response["message"]?.let { it as? JsonPrimitive }?.content?.contains("Protected templates cannot be deleted")
                ?: false
        )

        // Check the error code
        val error = response["error"] as? JsonObject
        assertNotNull(error)
        assertEquals(ErrorCodes.VALIDATION_ERROR, error?.get("code")?.let { it as? JsonPrimitive }?.content)
    }

    @Test
    fun `should delete a protected template with force parameter`() = runBlocking {
        // Arrange
        val params = buildJsonObject {
            put("id", JsonPrimitive(protectedTemplateId.toString()))
            put("force", JsonPrimitive(true))
        }

        // Act
        val response = deleteTemplateTool.execute(params, context) as JsonObject

        // Assert
        assertTrue(response["success"]?.let { it as? JsonPrimitive }?.content?.toBoolean() ?: false)
        assertEquals("Template deleted successfully", response["message"]?.let { it as? JsonPrimitive }?.content)
    }

    @Test
    fun `should return error for non-existent template`() = runBlocking {
        // Arrange
        val params = buildJsonObject {
            put("id", JsonPrimitive(nonExistentTemplateId.toString()))
        }

        // Act
        val response = deleteTemplateTool.execute(params, context) as JsonObject

        // Assert
        assertFalse(response["success"]?.let { it as? JsonPrimitive }?.content?.toBoolean() ?: true)
        assertEquals("Template not found", response["message"]?.let { it as? JsonPrimitive }?.content)

        // Check the error code
        val error = response["error"] as? JsonObject
        assertNotNull(error)
        assertEquals(ErrorCodes.RESOURCE_NOT_FOUND, error?.get("code")?.let { it as? JsonPrimitive }?.content)
    }
}