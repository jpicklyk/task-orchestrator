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

class DisableTemplateToolTest {

    private lateinit var disableTemplateTool: DisableTemplateTool
    private lateinit var mockTemplateRepository: TemplateRepository
    private lateinit var mockRepositoryProvider: RepositoryProvider
    private lateinit var context: ToolExecutionContext

    private val enabledTemplateId = UUID.randomUUID()
    private val alreadyDisabledTemplateId = UUID.randomUUID()
    private val nonExistentTemplateId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        // Create tool instance
        disableTemplateTool = DisableTemplateTool()

        // Set up mock repositories
        mockTemplateRepository = mockk<TemplateRepository>()
        mockRepositoryProvider = mockk<RepositoryProvider>()
        every { mockRepositoryProvider.templateRepository() } returns mockTemplateRepository

        // Set up context
        context = ToolExecutionContext(mockRepositoryProvider)

        // Set up template repository data
        val enabledTemplate = Template(
            id = enabledTemplateId,
            name = "Enabled Template",
            description = "A template that is currently enabled",
            targetEntityType = EntityType.TASK,
            isEnabled = true
        )

        val disabledTemplate = Template(
            id = alreadyDisabledTemplateId,
            name = "Disabled Template",
            description = "A template that is already disabled",
            targetEntityType = EntityType.TASK,
            isEnabled = false
        )

        // Configure mock repository behavior for disableTemplate
        coEvery { mockTemplateRepository.disableTemplate(enabledTemplateId) } returns 
            Result.Success(enabledTemplate.copy(isEnabled = false))
        
        coEvery { mockTemplateRepository.disableTemplate(alreadyDisabledTemplateId) } returns 
            Result.Success(disabledTemplate)
            
        coEvery { mockTemplateRepository.disableTemplate(nonExistentTemplateId) } returns
            Result.Error(RepositoryError.NotFound(nonExistentTemplateId, EntityType.TEMPLATE, "Template not found"))
    }

    @Test
    fun `should validate parameters correctly`() {
        // Valid parameters
        val validParams = buildJsonObject {
            put("id", JsonPrimitive(enabledTemplateId.toString()))
        }
        assertDoesNotThrow { disableTemplateTool.validateParams(validParams) }

        // Invalid UUID format
        val invalidIdParams = buildJsonObject {
            put("id", JsonPrimitive("not-a-uuid"))
        }
        assertThrows(ToolValidationException::class.java) {
            disableTemplateTool.validateParams(invalidIdParams)
        }

        // Missing ID parameter
        val missingIdParams = buildJsonObject {}
        assertThrows(ToolValidationException::class.java) {
            disableTemplateTool.validateParams(missingIdParams)
        }
    }

    @Test
    fun `should disable a template successfully`() = runBlocking {
        // Arrange
        val params = buildJsonObject {
            put("id", JsonPrimitive(enabledTemplateId.toString()))
        }

        // Act
        val response = disableTemplateTool.execute(params, context) as JsonObject

        // Assert
        assertTrue(response["success"]?.let { it as? JsonPrimitive }?.content?.toBoolean() ?: false)
        assertEquals("Template disabled successfully", response["message"]?.let { it as? JsonPrimitive }?.content)

        // Check the data
        val data = response["data"] as? JsonObject
        assertNotNull(data)
        assertEquals(enabledTemplateId.toString(), data?.get("id")?.let { it as? JsonPrimitive }?.content)
        assertFalse(data?.get("isEnabled")?.let { it as? JsonPrimitive }?.content?.toBoolean() ?: true)
    }

    @Test
    fun `should return already disabled template without error`() = runBlocking {
        // Arrange
        val params = buildJsonObject {
            put("id", JsonPrimitive(alreadyDisabledTemplateId.toString()))
        }

        // Act
        val response = disableTemplateTool.execute(params, context) as JsonObject

        // Assert
        assertTrue(response["success"]?.let { it as? JsonPrimitive }?.content?.toBoolean() ?: false)
        assertEquals("Template disabled successfully", response["message"]?.let { it as? JsonPrimitive }?.content)

        // Check the data
        val data = response["data"] as? JsonObject
        assertNotNull(data)
        assertEquals(alreadyDisabledTemplateId.toString(), data?.get("id")?.let { it as? JsonPrimitive }?.content)
        assertFalse(data?.get("isEnabled")?.let { it as? JsonPrimitive }?.content?.toBoolean() ?: true)
    }

    @Test
    fun `should return error for non-existent template`() = runBlocking {
        // Arrange
        val params = buildJsonObject {
            put("id", JsonPrimitive(nonExistentTemplateId.toString()))
        }

        // Act
        val response = disableTemplateTool.execute(params, context) as JsonObject

        // Assert
        assertFalse(response["success"]?.let { it as? JsonPrimitive }?.content?.toBoolean() ?: true)
        assertEquals("Template not found", response["message"]?.let { it as? JsonPrimitive }?.content)

        // Check the error code
        val error = response["error"] as? JsonObject
        assertNotNull(error)
        assertEquals(ErrorCodes.RESOURCE_NOT_FOUND, error?.get("code")?.let { it as? JsonPrimitive }?.content)
    }
}