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
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.*

class ManageTemplateToolTest {
    private lateinit var tool: ManageTemplateTool
    private lateinit var context: ToolExecutionContext
    private lateinit var mockTemplateRepository: TemplateRepository
    private val testTemplateId = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        mockTemplateRepository = mockk<TemplateRepository>()
        val mockRepositoryProvider = mockk<RepositoryProvider>()

        every { mockRepositoryProvider.templateRepository() } returns mockTemplateRepository

        context = ToolExecutionContext(mockRepositoryProvider)
        tool = ManageTemplateTool(null, null)
    }

    // ========== VALIDATION TESTS ==========

    @Test
    fun `test missing operation parameter`() {
        val params = JsonObject(mapOf())

        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Missing required parameter: operation"))
    }

    @Test
    fun `test invalid operation`() {
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("invalid_op")
            )
        )

        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Invalid operation"))
    }

    @Test
    fun `test create operation missing required parameters`() {
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
                "name" to JsonPrimitive("Test Template")
                // Missing description and targetEntityType
            )
        )

        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Missing required parameter"))
    }

    @Test
    fun `test create operation with invalid targetEntityType`() {
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
                "name" to JsonPrimitive("Test Template"),
                "description" to JsonPrimitive("Test Description"),
                "targetEntityType" to JsonPrimitive("INVALID")
            )
        )

        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Invalid target entity type"))
    }

    @Test
    fun `test update operation missing id`() {
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("update"),
                "name" to JsonPrimitive("Updated Name")
            )
        )

        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Missing required parameter: id"))
    }

    @Test
    fun `test update operation with no update fields`() {
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("update"),
                "id" to JsonPrimitive(testTemplateId.toString())
            )
        )

        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("at least one field to update"))
    }

    @Test
    fun `test addSection operation missing required parameters`() {
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("addSection"),
                "id" to JsonPrimitive(testTemplateId.toString()),
                "title" to JsonPrimitive("Section Title")
                // Missing usageDescription, contentSample, ordinal
            )
        )

        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Missing required parameter"))
    }

    @Test
    fun `test addSection operation with negative ordinal`() {
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("addSection"),
                "id" to JsonPrimitive(testTemplateId.toString()),
                "title" to JsonPrimitive("Section Title"),
                "usageDescription" to JsonPrimitive("Usage"),
                "contentSample" to JsonPrimitive("Content"),
                "ordinal" to JsonPrimitive(-1)
            )
        )

        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Ordinal must be a non-negative integer"))
    }

    // ========== CREATE OPERATION TESTS ==========

    @Test
    fun `test create operation success`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
                "name" to JsonPrimitive("Test Template"),
                "description" to JsonPrimitive("Test Description"),
                "targetEntityType" to JsonPrimitive("TASK"),
                "tags" to JsonPrimitive("test,template")
            )
        )

        val createdTemplate = Template(
            id = testTemplateId,
            name = "Test Template",
            description = "Test Description",
            targetEntityType = EntityType.TASK,
            isBuiltIn = false,
            isProtected = false,
            isEnabled = true,
            createdBy = null,
            tags = listOf("test", "template"),
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )

        coEvery { mockTemplateRepository.createTemplate(any()) } returns Result.Success(createdTemplate)

        val result = tool.execute(params, context)

        assertTrue(result is JsonObject)
        val resultObj = result as JsonObject
        assertEquals(JsonPrimitive(true), resultObj["success"])
        assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("created successfully") == true)

        coVerify { mockTemplateRepository.createTemplate(any()) }
    }

    @Test
    fun `test create operation with all optional parameters`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
                "name" to JsonPrimitive("Test Template"),
                "description" to JsonPrimitive("Test Description"),
                "targetEntityType" to JsonPrimitive("FEATURE"),
                "isBuiltIn" to JsonPrimitive(true),
                "isProtected" to JsonPrimitive(true),
                "isEnabled" to JsonPrimitive(false),
                "createdBy" to JsonPrimitive("Test User"),
                "tags" to JsonPrimitive("tag1,tag2,tag3")
            )
        )

        val createdTemplate = Template(
            id = testTemplateId,
            name = "Test Template",
            description = "Test Description",
            targetEntityType = EntityType.FEATURE,
            isBuiltIn = true,
            isProtected = true,
            isEnabled = false,
            createdBy = "Test User",
            tags = listOf("tag1", "tag2", "tag3"),
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )

        coEvery { mockTemplateRepository.createTemplate(any()) } returns Result.Success(createdTemplate)

        val result = tool.execute(params, context)

        assertTrue(result is JsonObject)
        val resultObj = result as JsonObject
        assertEquals(JsonPrimitive(true), resultObj["success"])

        coVerify { mockTemplateRepository.createTemplate(any()) }
    }

    // ========== UPDATE OPERATION TESTS ==========

    @Test
    fun `test update operation success`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("update"),
                "id" to JsonPrimitive(testTemplateId.toString()),
                "name" to JsonPrimitive("Updated Template"),
                "description" to JsonPrimitive("Updated Description")
            )
        )

        val existingTemplate = Template(
            id = testTemplateId,
            name = "Original Template",
            description = "Original Description",
            targetEntityType = EntityType.TASK,
            isBuiltIn = false,
            isProtected = false,
            isEnabled = true,
            createdBy = null,
            tags = emptyList(),
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )

        val updatedTemplate = existingTemplate.copy(
            name = "Updated Template",
            description = "Updated Description"
        ).withUpdatedModificationTime()

        coEvery { mockTemplateRepository.getTemplate(testTemplateId) } returns Result.Success(existingTemplate)
        coEvery { mockTemplateRepository.updateTemplate(any()) } returns Result.Success(updatedTemplate)

        val result = tool.execute(params, context)

        assertTrue(result is JsonObject)
        val resultObj = result as JsonObject
        assertEquals(JsonPrimitive(true), resultObj["success"])

        coVerify { mockTemplateRepository.getTemplate(testTemplateId) }
        coVerify { mockTemplateRepository.updateTemplate(any()) }
    }

    @Test
    fun `test update operation on protected template fails`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("update"),
                "id" to JsonPrimitive(testTemplateId.toString()),
                "name" to JsonPrimitive("Updated Name")
            )
        )

        val protectedTemplate = Template(
            id = testTemplateId,
            name = "Protected Template",
            description = "Description",
            targetEntityType = EntityType.TASK,
            isBuiltIn = true,
            isProtected = true,
            isEnabled = true,
            createdBy = null,
            tags = emptyList(),
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )

        coEvery { mockTemplateRepository.getTemplate(testTemplateId) } returns Result.Success(protectedTemplate)

        val result = tool.execute(params, context)

        assertTrue(result is JsonObject)
        val resultObj = result as JsonObject
        assertEquals(JsonPrimitive(false), resultObj["success"])
        assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("protected") == true)
    }

    // ========== DELETE OPERATION TESTS ==========

    @Test
    fun `test delete operation success`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("delete"),
                "id" to JsonPrimitive(testTemplateId.toString())
            )
        )

        val template = Template(
            id = testTemplateId,
            name = "Template to Delete",
            description = "Description",
            targetEntityType = EntityType.TASK,
            isBuiltIn = false,
            isProtected = false,
            isEnabled = true,
            createdBy = null,
            tags = emptyList(),
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )

        coEvery { mockTemplateRepository.getTemplate(testTemplateId) } returns Result.Success(template)
        coEvery { mockTemplateRepository.deleteTemplate(testTemplateId, false) } returns Result.Success(true)

        val result = tool.execute(params, context)

        assertTrue(result is JsonObject)
        val resultObj = result as JsonObject
        assertEquals(JsonPrimitive(true), resultObj["success"])

        coVerify { mockTemplateRepository.deleteTemplate(testTemplateId, false) }
    }

    @Test
    fun `test delete built-in template without force fails`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("delete"),
                "id" to JsonPrimitive(testTemplateId.toString()),
                "force" to JsonPrimitive(false)
            )
        )

        val builtInTemplate = Template(
            id = testTemplateId,
            name = "Built-in Template",
            description = "Description",
            targetEntityType = EntityType.TASK,
            isBuiltIn = true,
            isProtected = false,
            isEnabled = true,
            createdBy = null,
            tags = emptyList(),
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )

        coEvery { mockTemplateRepository.getTemplate(testTemplateId) } returns Result.Success(builtInTemplate)

        val result = tool.execute(params, context)

        assertTrue(result is JsonObject)
        val resultObj = result as JsonObject
        assertEquals(JsonPrimitive(false), resultObj["success"])
        assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("Built-in templates cannot be deleted") == true)
    }

    // ========== ENABLE/DISABLE OPERATION TESTS ==========

    @Test
    fun `test enable operation success`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("enable"),
                "id" to JsonPrimitive(testTemplateId.toString())
            )
        )

        val enabledTemplate = Template(
            id = testTemplateId,
            name = "Template",
            description = "Description",
            targetEntityType = EntityType.TASK,
            isBuiltIn = false,
            isProtected = false,
            isEnabled = true,
            createdBy = null,
            tags = emptyList(),
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )

        coEvery { mockTemplateRepository.enableTemplate(testTemplateId) } returns Result.Success(enabledTemplate)

        val result = tool.execute(params, context)

        assertTrue(result is JsonObject)
        val resultObj = result as JsonObject
        assertEquals(JsonPrimitive(true), resultObj["success"])

        coVerify { mockTemplateRepository.enableTemplate(testTemplateId) }
    }

    @Test
    fun `test disable operation success`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("disable"),
                "id" to JsonPrimitive(testTemplateId.toString())
            )
        )

        val disabledTemplate = Template(
            id = testTemplateId,
            name = "Template",
            description = "Description",
            targetEntityType = EntityType.TASK,
            isBuiltIn = false,
            isProtected = false,
            isEnabled = false,
            createdBy = null,
            tags = emptyList(),
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )

        coEvery { mockTemplateRepository.disableTemplate(testTemplateId) } returns Result.Success(disabledTemplate)

        val result = tool.execute(params, context)

        assertTrue(result is JsonObject)
        val resultObj = result as JsonObject
        assertEquals(JsonPrimitive(true), resultObj["success"])

        coVerify { mockTemplateRepository.disableTemplate(testTemplateId) }
    }

    // ========== ADD SECTION OPERATION TESTS ==========

    @Test
    fun `test addSection operation success`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("addSection"),
                "id" to JsonPrimitive(testTemplateId.toString()),
                "title" to JsonPrimitive("Test Section"),
                "usageDescription" to JsonPrimitive("Usage description"),
                "contentSample" to JsonPrimitive("Sample content"),
                "ordinal" to JsonPrimitive(0),
                "contentFormat" to JsonPrimitive("MARKDOWN"),
                "isRequired" to JsonPrimitive(true),
                "tags" to JsonPrimitive("section,test")
            )
        )

        val template = Template(
            id = testTemplateId,
            name = "Template",
            description = "Description",
            targetEntityType = EntityType.TASK,
            isBuiltIn = false,
            isProtected = false,
            isEnabled = true,
            createdBy = null,
            tags = emptyList(),
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )

        val templateSection = TemplateSection(
            id = UUID.randomUUID(),
            templateId = testTemplateId,
            title = "Test Section",
            usageDescription = "Usage description",
            contentSample = "Sample content",
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 0,
            isRequired = true,
            tags = listOf("section", "test")
        )

        coEvery { mockTemplateRepository.getTemplate(testTemplateId) } returns Result.Success(template)
        coEvery { mockTemplateRepository.addTemplateSection(testTemplateId, any()) } returns Result.Success(templateSection)

        val result = tool.execute(params, context)

        assertTrue(result is JsonObject)
        val resultObj = result as JsonObject
        assertEquals(JsonPrimitive(true), resultObj["success"])

        coVerify { mockTemplateRepository.getTemplate(testTemplateId) }
        coVerify { mockTemplateRepository.addTemplateSection(testTemplateId, any()) }
    }

    @Test
    fun `test addSection operation to non-existent template fails`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("addSection"),
                "id" to JsonPrimitive(testTemplateId.toString()),
                "title" to JsonPrimitive("Test Section"),
                "usageDescription" to JsonPrimitive("Usage description"),
                "contentSample" to JsonPrimitive("Sample content"),
                "ordinal" to JsonPrimitive(0)
            )
        )

        coEvery { mockTemplateRepository.getTemplate(testTemplateId) } returns Result.Error(
            RepositoryError.NotFound(
                id = testTemplateId,
                entityType = EntityType.TEMPLATE,
                message = "Template not found"
            )
        )

        val result = tool.execute(params, context)

        assertTrue(result is JsonObject)
        val resultObj = result as JsonObject
        assertEquals(JsonPrimitive(false), resultObj["success"])
        assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("not found") == true)
    }

    // ========== ERROR HANDLING TESTS ==========

    @Test
    fun `test repository error handling`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("create"),
                "name" to JsonPrimitive("Test Template"),
                "description" to JsonPrimitive("Test Description"),
                "targetEntityType" to JsonPrimitive("TASK")
            )
        )

        coEvery { mockTemplateRepository.createTemplate(any()) } returns Result.Error(
            RepositoryError.DatabaseError("Database connection failed")
        )

        val result = tool.execute(params, context)

        assertTrue(result is JsonObject)
        val resultObj = result as JsonObject
        assertEquals(JsonPrimitive(false), resultObj["success"])
    }

    @Test
    fun `test template not found error`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "operation" to JsonPrimitive("update"),
                "id" to JsonPrimitive(testTemplateId.toString()),
                "name" to JsonPrimitive("Updated Name")
            )
        )

        coEvery { mockTemplateRepository.getTemplate(testTemplateId) } returns Result.Error(
            RepositoryError.NotFound(
                id = testTemplateId,
                entityType = EntityType.TEMPLATE,
                message = "Template not found"
            )
        )

        val result = tool.execute(params, context)

        assertTrue(result is JsonObject)
        val resultObj = result as JsonObject
        assertEquals(JsonPrimitive(false), resultObj["success"])
        assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("not found") == true)
    }
}
