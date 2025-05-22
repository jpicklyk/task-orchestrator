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

class EnableTemplateToolTest {

    private lateinit var enableTemplateTool: EnableTemplateTool
    private lateinit var mockTemplateRepository: TemplateRepository
    private lateinit var mockRepositoryProvider: RepositoryProvider
    private lateinit var context: ToolExecutionContext

    private val disabledTemplateId = UUID.randomUUID()
    private val alreadyEnabledTemplateId = UUID.randomUUID()
    private val nonExistentTemplateId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        // Create tool instance
        enableTemplateTool = EnableTemplateTool()

        // Set up mock repositories
        mockTemplateRepository = mockk<TemplateRepository>()
        mockRepositoryProvider = mockk<RepositoryProvider>()
        every { mockRepositoryProvider.templateRepository() } returns mockTemplateRepository

        // Set up context
        context = ToolExecutionContext(mockRepositoryProvider)

        // Set up template repository data
        val disabledTemplate = Template(
            id = disabledTemplateId,
            name = "Disabled Template",
            description = "A template that is currently disabled",
            targetEntityType = EntityType.TASK,
            isEnabled = false
        )

        val enabledTemplate = Template(
            id = alreadyEnabledTemplateId,
            name = "Enabled Template",
            description = "A template that is already enabled",
            targetEntityType = EntityType.TASK,
            isEnabled = true
        )

        // Configure mock repository behavior for enableTemplate
        coEvery { mockTemplateRepository.enableTemplate(disabledTemplateId) } returns 
            Result.Success(disabledTemplate.copy(isEnabled = true))
        
        coEvery { mockTemplateRepository.enableTemplate(alreadyEnabledTemplateId) } returns 
            Result.Success(enabledTemplate)
            
        coEvery { mockTemplateRepository.enableTemplate(nonExistentTemplateId) } returns
            Result.Error(RepositoryError.NotFound(nonExistentTemplateId, EntityType.TEMPLATE, "Template not found"))
    }

    @Test
    fun `should validate parameters correctly`() {
        // Valid parameters
        val validParams = buildJsonObject {
            put("id", JsonPrimitive(disabledTemplateId.toString()))
        }
        assertDoesNotThrow { enableTemplateTool.validateParams(validParams) }

        // Invalid UUID format
        val invalidIdParams = buildJsonObject {
            put("id", JsonPrimitive("not-a-uuid"))
        }
        assertThrows(ToolValidationException::class.java) {
            enableTemplateTool.validateParams(invalidIdParams)
        }

        // Missing ID parameter
        val missingIdParams = buildJsonObject {}
        assertThrows(ToolValidationException::class.java) {
            enableTemplateTool.validateParams(missingIdParams)
        }
    }

    @Test
    fun `should enable a template successfully`() = runBlocking {
        // Arrange
        val params = buildJsonObject {
            put("id", JsonPrimitive(disabledTemplateId.toString()))
        }

        // Act
        val response = enableTemplateTool.execute(params, context) as JsonObject

        // Assert
        assertTrue(response["success"]?.let { it as? JsonPrimitive }?.content?.toBoolean() ?: false)
        assertEquals("Template enabled successfully", response["message"]?.let { it as? JsonPrimitive }?.content)

        // Check the data
        val data = response["data"] as? JsonObject
        assertNotNull(data)
        assertEquals(disabledTemplateId.toString(), data?.get("id")?.let { it as? JsonPrimitive }?.content)
        assertTrue(data?.get("isEnabled")?.let { it as? JsonPrimitive }?.content?.toBoolean() ?: false)
    }

    @Test
    fun `should return already enabled template without error`() = runBlocking {
        // Arrange
        val params = buildJsonObject {
            put("id", JsonPrimitive(alreadyEnabledTemplateId.toString()))
        }

        // Act
        val response = enableTemplateTool.execute(params, context) as JsonObject

        // Assert
        assertTrue(response["success"]?.let { it as? JsonPrimitive }?.content?.toBoolean() ?: false)
        assertEquals("Template enabled successfully", response["message"]?.let { it as? JsonPrimitive }?.content)

        // Check the data
        val data = response["data"] as? JsonObject
        assertNotNull(data)
        assertEquals(alreadyEnabledTemplateId.toString(), data?.get("id")?.let { it as? JsonPrimitive }?.content)
        assertTrue(data?.get("isEnabled")?.let { it as? JsonPrimitive }?.content?.toBoolean() ?: false)
    }

    @Test
    fun `should return error for non-existent template`() = runBlocking {
        // Arrange
        val params = buildJsonObject {
            put("id", JsonPrimitive(nonExistentTemplateId.toString()))
        }

        // Act
        val response = enableTemplateTool.execute(params, context) as JsonObject

        // Assert
        assertFalse(response["success"]?.let { it as? JsonPrimitive }?.content?.toBoolean() ?: true)
        assertEquals("Template not found", response["message"]?.let { it as? JsonPrimitive }?.content)

        // Check the error code
        val error = response["error"] as? JsonObject
        assertNotNull(error)
        assertEquals(ErrorCodes.RESOURCE_NOT_FOUND, error?.get("code")?.let { it as? JsonPrimitive }?.content)
    }
}