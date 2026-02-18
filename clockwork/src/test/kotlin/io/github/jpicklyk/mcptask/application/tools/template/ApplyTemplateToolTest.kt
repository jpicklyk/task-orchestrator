package io.github.jpicklyk.mcptask.application.tools.template

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.repository.*
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

class ApplyTemplateToolTest {
    private lateinit var tool: ApplyTemplateTool
    private lateinit var context: ToolExecutionContext
    private lateinit var mockTemplateRepository: TemplateRepository
    private lateinit var mockTaskRepository: TaskRepository
    private lateinit var mockFeatureRepository: FeatureRepository

    private val testTemplateId1 = UUID.randomUUID()
    private val testTemplateId2 = UUID.randomUUID()
    private val disabledTemplateId = UUID.randomUUID()
    private val testTaskId = UUID.randomUUID()
    private val testFeatureId = UUID.randomUUID()

    private val testSectionId1 = UUID.randomUUID()
    private val testSectionId2 = UUID.randomUUID()
    private val testSectionId3 = UUID.randomUUID()
    private val testSectionId4 = UUID.randomUUID()
    private val testSectionId5 = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        // Create mock repositories
        mockTemplateRepository = mockk<TemplateRepository>()
        mockTaskRepository = mockk<TaskRepository>()
        mockFeatureRepository = mockk<FeatureRepository>()
        val mockRepositoryProvider = mockk<RepositoryProvider>()

        // Configure the repository provider to return the mock repositories
        every { mockRepositoryProvider.templateRepository() } returns mockTemplateRepository
        every { mockRepositoryProvider.taskRepository() } returns mockTaskRepository
        every { mockRepositoryProvider.featureRepository() } returns mockFeatureRepository

        // Create test templates
        val testTemplate1 = Template(
            id = testTemplateId1,
            name = "Test Template 1",
            description = "This is test template 1",
            targetEntityType = EntityType.TASK, // Can be applied to tasks
            isBuiltIn = false,
            isProtected = false,
            isEnabled = true,
            createdBy = "Test User",
            tags = listOf("test", "template1"),
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )
        
        val testTemplate2 = Template(
            id = testTemplateId2,
            name = "Test Template 2",
            description = "This is test template 2",
            targetEntityType = EntityType.TASK, // Can be applied to tasks
            isBuiltIn = false,
            isProtected = false,
            isEnabled = true,
            createdBy = "Test User",
            tags = listOf("test", "template2"),
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )
        
        // Create a disabled test template
        val disabledTemplate = Template(
            id = disabledTemplateId,
            name = "Disabled Template",
            description = "This is a disabled template",
            targetEntityType = EntityType.TASK, // Can be applied to tasks
            isBuiltIn = false,
            isProtected = false,
            isEnabled = false, // Template is disabled
            createdBy = "Test User",
            tags = listOf("test", "template", "disabled"),
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )

        // Create a test task and feature
        val testTask = Task(
            id = testTaskId,
            title = "Test Task",
            summary = "This is a test task",
            status = TaskStatus.PENDING
        )

        val testFeature = Feature(
            id = testFeatureId,
            name = "Test Feature",
            summary = "This is a test feature",
            status = FeatureStatus.PLANNING
        )

        // Define behavior for template repository getTemplate method
        coEvery {
            mockTemplateRepository.getTemplate(testTemplateId1)
        } returns Result.Success(testTemplate1)
        
        coEvery {
            mockTemplateRepository.getTemplate(testTemplateId2)
        } returns Result.Success(testTemplate2)
        
        // Define behavior for disabled template
        coEvery {
            mockTemplateRepository.getTemplate(disabledTemplateId)
        } returns Result.Success(disabledTemplate)

        // Define behavior for non-existent template
        coEvery {
            mockTemplateRepository.getTemplate(match { 
                it != testTemplateId1 && it != testTemplateId2 && it != disabledTemplateId 
            })
        } returns Result.Error(RepositoryError.NotFound(UUID.randomUUID(), EntityType.TEMPLATE, "Template not found"))

        // Define behavior for task repository getById method
        coEvery {
            mockTaskRepository.getById(testTaskId)
        } returns Result.Success(testTask)

        // Define behavior for feature repository getById method
        coEvery {
            mockFeatureRepository.getById(testFeatureId)
        } returns Result.Success(testFeature)

        // Define behavior for non-existent task and feature
        coEvery {
            mockTaskRepository.getById(neq(testTaskId))
        } returns Result.Error(RepositoryError.NotFound(UUID.randomUUID(), EntityType.TASK, "Task not found"))

        coEvery {
            mockFeatureRepository.getById(neq(testFeatureId))
        } returns Result.Error(RepositoryError.NotFound(UUID.randomUUID(), EntityType.FEATURE, "Feature not found"))

        // Define template sections for template 1
        val createdSections1 = listOf(
            TemplateSection(
                id = testSectionId1,
                templateId = testTemplateId1,
                title = "Requirements",
                usageDescription = "Key requirements for this task",
                contentSample = "List all requirements here...",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0,
                isRequired = true,
                tags = listOf("requirements")
            ),
            TemplateSection(
                id = testSectionId2,
                templateId = testTemplateId1,
                title = "Implementation Notes",
                usageDescription = "Technical details for implementation",
                contentSample = "Describe implementation approach here...",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 1,
                isRequired = false,
                tags = listOf("implementation")
            ),
            TemplateSection(
                id = testSectionId3,
                templateId = testTemplateId1,
                title = "Testing Strategy",
                usageDescription = "How this task should be tested",
                contentSample = "Describe testing approach here...",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 2,
                isRequired = false,
                tags = listOf("testing")
            )
        )
        
        // Define template sections for template 2
        val createdSections2 = listOf(
            TemplateSection(
                id = testSectionId4,
                templateId = testTemplateId2,
                title = "Design Documentation",
                usageDescription = "Design details for this task",
                contentSample = "Describe design approach here...",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0,
                isRequired = true,
                tags = listOf("design")
            ),
            TemplateSection(
                id = testSectionId5,
                templateId = testTemplateId2,
                title = "Related Tasks",
                usageDescription = "List of related tasks",
                contentSample = "List related tasks here...",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 1,
                isRequired = false,
                tags = listOf("related")
            )
        )

        // Define behavior for single template application
        coEvery {
            mockTemplateRepository.applyTemplate(testTemplateId1, EntityType.TASK, testTaskId)
        } returns Result.Success(createdSections1)

        coEvery {
            mockTemplateRepository.applyTemplate(testTemplateId1, EntityType.FEATURE, testFeatureId)
        } returns Result.Success(createdSections1)
        
        coEvery {
            mockTemplateRepository.applyTemplate(testTemplateId2, EntityType.TASK, testTaskId)
        } returns Result.Success(createdSections2)
        
        // Define behavior for attempting to apply a disabled template
        coEvery {
            mockTemplateRepository.applyTemplate(disabledTemplateId, any(), any())
        } returns Result.Error(
            RepositoryError.ValidationError(
                "Template 'Disabled Template' (ID: $disabledTemplateId) is disabled and cannot be applied. " +
                "Use enableTemplate() to enable it first."
            )
        )
        
        // Define behavior for multiple template application
        coEvery {
            mockTemplateRepository.applyMultipleTemplates(
                listOf(testTemplateId1, testTemplateId2),
                EntityType.TASK,
                testTaskId
            )
        } returns Result.Success(
            mapOf(
                testTemplateId1 to createdSections1,
                testTemplateId2 to createdSections2
            )
        )
        
        coEvery {
            mockTemplateRepository.applyMultipleTemplates(
                listOf(testTemplateId1, disabledTemplateId),
                EntityType.TASK,
                testTaskId
            )
        } returns Result.Success(
            mapOf(
                testTemplateId1 to createdSections1
            )
        )
        
        coEvery {
            mockTemplateRepository.applyMultipleTemplates(
                listOf(disabledTemplateId),
                EntityType.TASK,
                testTaskId
            )
        } returns Result.Error(
            RepositoryError.ValidationError(
                "Failed to apply template $disabledTemplateId: Template 'Disabled Template' is disabled and cannot be applied. " +
                "Use enableTemplate() to enable it first."
            )
        )

        // Create the execution context with the mock repository provider
        context = ToolExecutionContext(mockRepositoryProvider)

        tool = ApplyTemplateTool()
    }

    @Test
    fun `test valid parameters validation - single template`() {
        val params = JsonObject(
            mapOf(
                "templateIds" to JsonArray(listOf(JsonPrimitive(testTemplateId1.toString()))),
                "entityType" to JsonPrimitive("TASK"),
                "entityId" to JsonPrimitive(testTaskId.toString())
            )
        )

        // Should not throw an exception
        assertDoesNotThrow { tool.validateParams(params) }
    }
    
    @Test
    fun `test valid parameters validation - multiple templates`() {
        val params = JsonObject(
            mapOf(
                "templateIds" to JsonArray(listOf(
                    JsonPrimitive(testTemplateId1.toString()),
                    JsonPrimitive(testTemplateId2.toString())
                )),
                "entityType" to JsonPrimitive("TASK"),
                "entityId" to JsonPrimitive(testTaskId.toString())
            )
        )

        // Should not throw an exception
        assertDoesNotThrow { tool.validateParams(params) }
    }
    
    @Test
    fun `test valid parameters validation - both single and multiple templates`() {
        val params = JsonObject(
            mapOf(
                "templateId" to JsonPrimitive(testTemplateId1.toString()),
                "templateIds" to JsonArray(listOf(
                    JsonPrimitive(testTemplateId2.toString())
                )),
                "entityType" to JsonPrimitive("TASK"),
                "entityId" to JsonPrimitive(testTaskId.toString())
            )
        )

        // Should not throw an exception
        assertDoesNotThrow { tool.validateParams(params) }
    }

    @Test
    fun `test missing template IDs validation`() {
        val params = JsonObject(
            mapOf(
                "entityType" to JsonPrimitive("TASK"),
                "entityId" to JsonPrimitive(testTaskId.toString())
            )
        )

        // Should throw an exception for missing template identifier
        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Parameter 'templateIds' must be an array of strings (UUIDs)"))
    }
    
    @Test
    fun `test empty templateIds array validation`() {
        val params = JsonObject(
            mapOf(
                "templateIds" to JsonArray(emptyList()),
                "entityType" to JsonPrimitive("TASK"),
                "entityId" to JsonPrimitive(testTaskId.toString())
            )
        )

        // Should throw an exception for empty templateIds array
        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Parameter 'templateIds' cannot be an empty array"))
    }

    @Test
    fun `test invalid templateId format validation`() {
        val params = JsonObject(
            mapOf(
                "templateIds" to JsonArray(listOf(JsonPrimitive("not-a-uuid"))),
                "entityType" to JsonPrimitive("TASK"),
                "entityId" to JsonPrimitive(testTaskId.toString())
            )
        )

        // Should throw an exception for invalid UUID
        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("is not a valid UUID format"))
    }
    
    @Test
    fun `test invalid templateIds format validation`() {
        val params = JsonObject(
            mapOf(
                "templateIds" to JsonArray(listOf(
                    JsonPrimitive(testTemplateId1.toString()),
                    JsonPrimitive("not-a-uuid")
                )),
                "entityType" to JsonPrimitive("TASK"),
                "entityId" to JsonPrimitive(testTaskId.toString())
            )
        )

        // Should throw an exception for invalid UUID
        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("templateIds[1] is not a valid UUID format"))
    }

    @Test
    fun `test invalid entityId format validation`() {
        val params = JsonObject(
            mapOf(
                "templateIds" to JsonArray(listOf(JsonPrimitive(testTemplateId1.toString()))),
                "entityType" to JsonPrimitive("TASK"),
                "entityId" to JsonPrimitive("not-a-uuid")
            )
        )

        // Should throw an exception for invalid UUID
        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Invalid entity ID format"))
    }

    @Test
    fun `test invalid entityType validation`() {
        val params = JsonObject(
            mapOf(
                "templateIds" to JsonArray(listOf(JsonPrimitive(testTemplateId1.toString()))),
                "entityType" to JsonPrimitive("INVALID_TYPE"),
                "entityId" to JsonPrimitive(testTaskId.toString())
            )
        )

        // Should throw an exception for invalid entity type
        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Invalid entity type"))
    }

    @Test
    fun `test applying single template to a task successfully`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "templateIds" to JsonArray(listOf(JsonPrimitive(testTemplateId1.toString()))),
                "entityType" to JsonPrimitive("TASK"),
                "entityId" to JsonPrimitive(testTaskId.toString())
            )
        )

        val result = tool.execute(params, context)

        // Check that the result is a success response
        val resultObj = result as JsonObject
        assertEquals(true, (resultObj["success"] as JsonPrimitive).content.toBoolean())
        assertEquals(
            "Template applied successfully, created 3 sections",
            (resultObj["message"] as JsonPrimitive).content
        )

        // Check the returned data
        val data = resultObj["data"] as JsonObject
        assertEquals(testTemplateId1.toString(), (data["templateId"] as JsonPrimitive).content)
        assertEquals("TASK", (data["entityType"] as JsonPrimitive).content)
        assertEquals(testTaskId.toString(), (data["entityId"] as JsonPrimitive).content)
        assertEquals(3, (data["sectionsCreated"] as JsonPrimitive).content.toInt())

        // Check sections array
        assertTrue(data.containsKey("sections"))
        val sections = data["sections"] as JsonArray
        assertEquals(3, sections.size)

        // Verify first section
        val section1 = sections[0] as JsonObject
        assertEquals(testSectionId1.toString(), (section1["id"] as JsonPrimitive).content)
        assertEquals("Requirements", (section1["title"] as JsonPrimitive).content)
        assertEquals(0, (section1["ordinal"] as JsonPrimitive).content.toInt())
    }

    @Test
    fun `test applying multiple templates to a task successfully`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "templateIds" to JsonArray(listOf(
                    JsonPrimitive(testTemplateId1.toString()),
                    JsonPrimitive(testTemplateId2.toString())
                )),
                "entityType" to JsonPrimitive("TASK"),
                "entityId" to JsonPrimitive(testTaskId.toString())
            )
        )

        val result = tool.execute(params, context)

        // Check that the result is a success response
        val resultObj = result as JsonObject
        assertEquals(true, (resultObj["success"] as JsonPrimitive).content.toBoolean())
        assertEquals(
            "Applied 2 templates successfully, created 5 sections",
            (resultObj["message"] as JsonPrimitive).content
        )

        // Check the returned data
        val data = resultObj["data"] as JsonObject
        assertEquals("TASK", (data["entityType"] as JsonPrimitive).content)
        assertEquals(testTaskId.toString(), (data["entityId"] as JsonPrimitive).content)
        assertEquals(5, (data["totalSectionsCreated"] as JsonPrimitive).content.toInt())

        // Check appliedTemplates array
        assertTrue(data.containsKey("appliedTemplates"))
        val appliedTemplates = data["appliedTemplates"] as JsonArray
        assertEquals(2, appliedTemplates.size)
        
        // Verify the first template data
        val template1Data = appliedTemplates[0] as JsonObject
        assertEquals(testTemplateId1.toString(), (template1Data["templateId"] as JsonPrimitive).content)
        assertEquals(3, (template1Data["sectionsCreated"] as JsonPrimitive).content.toInt())
        
        val template1Sections = template1Data["sections"] as JsonArray
        assertEquals(3, template1Sections.size)
        
        // Verify second template data
        val template2Data = appliedTemplates[1] as JsonObject
        assertEquals(testTemplateId2.toString(), (template2Data["templateId"] as JsonPrimitive).content)
        assertEquals(2, (template2Data["sectionsCreated"] as JsonPrimitive).content.toInt())
        
        val template2Sections = template2Data["sections"] as JsonArray
        assertEquals(2, template2Sections.size)
    }
    
    @Test
    fun `test applying both single and multiple templates`() = runBlocking {
        // Note: Since we removed support for templateId in favor of templateIds, this test is now the same as 
        // testing multiple templates application. We'll keep it for test coverage but adjust the expectations.
        val params = JsonObject(
            mapOf(
                "templateIds" to JsonArray(listOf(
                    JsonPrimitive(testTemplateId1.toString()),
                    JsonPrimitive(testTemplateId2.toString())
                )),
                "entityType" to JsonPrimitive("TASK"),
                "entityId" to JsonPrimitive(testTaskId.toString())
            )
        )

        val result = tool.execute(params, context)

        // Should be treated as multiple templates
        val resultObj = result as JsonObject
        assertEquals(true, (resultObj["success"] as JsonPrimitive).content.toBoolean())
        
        // Check that all templates were applied
        val data = resultObj["data"] as JsonObject
        assertTrue(data.containsKey("appliedTemplates"))
        val appliedTemplates = data["appliedTemplates"] as JsonArray
        assertEquals(2, appliedTemplates.size)
    }

    @Test
    fun `test applying template to non-existent entity`() = runBlocking {
        val nonExistentId = UUID.randomUUID()
        val params = JsonObject(
            mapOf(
                "templateIds" to JsonArray(listOf(JsonPrimitive(testTemplateId1.toString()))),
                "entityType" to JsonPrimitive("TASK"),
                "entityId" to JsonPrimitive(nonExistentId.toString())
            )
        )

        val result = tool.execute(params, context)

        // Check that the result is an error response
        val resultObj = result as JsonObject
        assertEquals(false, (resultObj["success"] as JsonPrimitive).content.toBoolean())
        assertTrue((resultObj["message"] as JsonPrimitive).content.contains("Entity not found"))

        // Error should contain the error code
        val error = resultObj["error"] as JsonObject
        assertEquals(ErrorCodes.RESOURCE_NOT_FOUND, (error["code"] as JsonPrimitive).content)
    }

    @Test
    fun `test applying non-existent template`() = runBlocking {
        val nonExistentId = UUID.randomUUID()
        val params = JsonObject(
            mapOf(
                "templateIds" to JsonArray(listOf(JsonPrimitive(nonExistentId.toString()))),
                "entityType" to JsonPrimitive("TASK"),
                "entityId" to JsonPrimitive(testTaskId.toString())
            )
        )

        val result = tool.execute(params, context)

        // Check that the result is an error response
        val resultObj = result as JsonObject
        assertEquals(false, (resultObj["success"] as JsonPrimitive).content.toBoolean())
        assertTrue(
            (resultObj["message"] as JsonPrimitive).content.contains("Template not found") ||
                    (resultObj["message"] as JsonPrimitive).content.contains("One or more templates or the entity not found")
        )

        // Error should contain the error code
        val error = resultObj["error"] as JsonObject
        assertEquals(ErrorCodes.RESOURCE_NOT_FOUND, (error["code"] as JsonPrimitive).content)
    }

    @Test
    fun `test applying disabled template`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "templateIds" to JsonArray(listOf(JsonPrimitive(disabledTemplateId.toString()))),
                "entityType" to JsonPrimitive("TASK"),
                "entityId" to JsonPrimitive(testTaskId.toString())
            )
        )

        val result = tool.execute(params, context)

        // Check that the result is an error response
        val resultObj = result as JsonObject
        assertEquals(false, (resultObj["success"] as JsonPrimitive).content.toBoolean())
        
        // Error should contain the error code
        val error = resultObj["error"] as JsonObject
        assertEquals(ErrorCodes.VALIDATION_ERROR, (error["code"] as JsonPrimitive).content)
        
        // Error details should mention that the template is disabled
        val details = (error["details"] as? JsonPrimitive)?.content ?: ""
        assertTrue(details.contains("is disabled and cannot be applied") || 
                  resultObj["message"].toString().contains("disabled"))
    }

    @Test
    fun `test template application with error`() = runBlocking {
        // Setup mock to return an error for applyTemplate (since single template in templateIds goes to applySingleTemplate method)
        coEvery {
            mockTemplateRepository.applyTemplate(testTemplateId1, EntityType.TASK, testTaskId)
        } returns Result.Error(RepositoryError.DatabaseError("Failed to apply template"))

        val params = JsonObject(
            mapOf(
                "templateIds" to JsonArray(listOf(JsonPrimitive(testTemplateId1.toString()))),
                "entityType" to JsonPrimitive("TASK"),
                "entityId" to JsonPrimitive(testTaskId.toString())
            )
        )

        val result = tool.execute(params, context)

        // Check that the result is an error response
        val resultObj = result as JsonObject
        assertEquals(false, (resultObj["success"] as JsonPrimitive).content.toBoolean())
        assertTrue((resultObj["message"] as JsonPrimitive).content.contains("Failed to apply template"))

        // Error should contain the error code
        val error = resultObj["error"] as JsonObject
        assertEquals(ErrorCodes.DATABASE_ERROR, (error["code"] as JsonPrimitive).content)
    }

    @Test
    fun `test template application with ordinal conflict resolution`() = runBlocking {
        // This test verifies that when applying templates to entities with existing sections,
        // the response shows the correct ordinal values where sections were actually placed

        // Create template sections that will be placed after existing sections (starting at ordinal 7)
        val sectionsWithCorrectOrdinals = listOf(
            TemplateSection(
                id = testSectionId1,
                templateId = testTemplateId1,
                title = "Requirements",
                usageDescription = "Key requirements for this task",
                contentSample = "List all requirements here...",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 7, // Should be placed after existing sections (0-6)
                isRequired = true,
                tags = listOf("requirements")
            ),
            TemplateSection(
                id = testSectionId2,
                templateId = testTemplateId1,
                title = "Implementation Notes",
                usageDescription = "Technical details for implementation",
                contentSample = "Describe implementation approach here...",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 8, // Next available ordinal
                isRequired = false,
                tags = listOf("implementation")
            ),
            TemplateSection(
                id = testSectionId3,
                templateId = testTemplateId1,
                title = "Testing Strategy",
                usageDescription = "How this task should be tested",
                contentSample = "Describe testing approach here...",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 9, // Next available ordinal
                isRequired = false,
                tags = listOf("testing")
            )
        )

        // Setup mock to return sections with adjusted ordinals (simulating ordinal conflict resolution)
        coEvery {
            mockTemplateRepository.applyTemplate(testTemplateId1, EntityType.TASK, testTaskId)
        } returns Result.Success(sectionsWithCorrectOrdinals)

        val params = JsonObject(
            mapOf(
                "templateIds" to JsonArray(listOf(JsonPrimitive(testTemplateId1.toString()))),
                "entityType" to JsonPrimitive("TASK"),
                "entityId" to JsonPrimitive(testTaskId.toString())
            )
        )

        val result = tool.execute(params, context)

        // Check that the result is a success response
        val resultObj = result as JsonObject
        assertEquals(true, (resultObj["success"] as JsonPrimitive).content.toBoolean())
        assertEquals(
            "Template applied successfully, created 3 sections",
            (resultObj["message"] as JsonPrimitive).content
        )

        // Check the returned data
        val data = resultObj["data"] as JsonObject
        val sections = data["sections"] as JsonArray
        assertEquals(3, sections.size)

        // Verify that the response shows the correct ordinal values (7, 8, 9)
        // This tests the fix for the bug where responses showed incorrect ordinals (0, 1, 2)
        val section1 = sections[0] as JsonObject
        assertEquals(testSectionId1.toString(), (section1["id"] as JsonPrimitive).content)
        assertEquals("Requirements", (section1["title"] as JsonPrimitive).content)
        assertEquals(
            7,
            (section1["ordinal"] as JsonPrimitive).content.toInt()
        ) // Should show actual placement, not template ordinal

        val section2 = sections[1] as JsonObject
        assertEquals(testSectionId2.toString(), (section2["id"] as JsonPrimitive).content)
        assertEquals("Implementation Notes", (section2["title"] as JsonPrimitive).content)
        assertEquals(
            8,
            (section2["ordinal"] as JsonPrimitive).content.toInt()
        ) // Should show actual placement, not template ordinal

        val section3 = sections[2] as JsonObject
        assertEquals(testSectionId3.toString(), (section3["id"] as JsonPrimitive).content)
        assertEquals("Testing Strategy", (section3["title"] as JsonPrimitive).content)
        assertEquals(
            9,
            (section3["ordinal"] as JsonPrimitive).content.toInt()
        ) // Should show actual placement, not template ordinal
    }

    @Test
    fun `test multiple template application with ordinal conflict resolution`() = runBlocking {
        // This test verifies ordinal conflict resolution works correctly for multiple templates

        // First template sections starting at ordinal 5
        val template1SectionsWithCorrectOrdinals = listOf(
            TemplateSection(
                id = testSectionId1,
                templateId = testTemplateId1,
                title = "Requirements",
                usageDescription = "Key requirements for this task",
                contentSample = "List all requirements here...",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 5, // Placed after existing sections
                isRequired = true,
                tags = listOf("requirements")
            ),
            TemplateSection(
                id = testSectionId2,
                templateId = testTemplateId1,
                title = "Implementation Notes",
                usageDescription = "Technical details for implementation",
                contentSample = "Describe implementation approach here...",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 6, // Next ordinal
                isRequired = false,
                tags = listOf("implementation")
            )
        )

        // Second template sections starting at ordinal 7 (after first template)
        val template2SectionsWithCorrectOrdinals = listOf(
            TemplateSection(
                id = testSectionId4,
                templateId = testTemplateId2,
                title = "Design Documentation",
                usageDescription = "Design details for this task",
                contentSample = "Describe design approach here...",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 7, // Placed after first template sections
                isRequired = true,
                tags = listOf("design")
            ),
            TemplateSection(
                id = testSectionId5,
                templateId = testTemplateId2,
                title = "Related Tasks",
                usageDescription = "List of related tasks",
                contentSample = "List related tasks here...",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 8, // Next ordinal
                isRequired = false,
                tags = listOf("related")
            )
        )

        // Setup mock to return sections with correct ordinals for multiple template application
        coEvery {
            mockTemplateRepository.applyMultipleTemplates(
                listOf(testTemplateId1, testTemplateId2),
                EntityType.TASK,
                testTaskId
            )
        } returns Result.Success(
            mapOf(
                testTemplateId1 to template1SectionsWithCorrectOrdinals,
                testTemplateId2 to template2SectionsWithCorrectOrdinals
            )
        )

        val params = JsonObject(
            mapOf(
                "templateIds" to JsonArray(
                    listOf(
                        JsonPrimitive(testTemplateId1.toString()),
                        JsonPrimitive(testTemplateId2.toString())
                    )
                ),
                "entityType" to JsonPrimitive("TASK"),
                "entityId" to JsonPrimitive(testTaskId.toString())
            )
        )

        val result = tool.execute(params, context)

        // Check that the result is a success response
        val resultObj = result as JsonObject
        assertEquals(true, (resultObj["success"] as JsonPrimitive).content.toBoolean())

        // Check the returned data
        val data = resultObj["data"] as JsonObject
        val appliedTemplates = data["appliedTemplates"] as JsonArray
        assertEquals(2, appliedTemplates.size)

        // Verify first template ordinals (5, 6)
        val template1Data = appliedTemplates[0] as JsonObject
        val template1Sections = template1Data["sections"] as JsonArray

        val template1Section1 = template1Sections[0] as JsonObject
        assertEquals(5, (template1Section1["ordinal"] as JsonPrimitive).content.toInt())

        val template1Section2 = template1Sections[1] as JsonObject
        assertEquals(6, (template1Section2["ordinal"] as JsonPrimitive).content.toInt())

        // Verify second template ordinals (7, 8)
        val template2Data = appliedTemplates[1] as JsonObject
        val template2Sections = template2Data["sections"] as JsonArray

        val template2Section1 = template2Sections[0] as JsonObject
        assertEquals(7, (template2Section1["ordinal"] as JsonPrimitive).content.toInt())

        val template2Section2 = template2Sections[1] as JsonObject
        assertEquals(8, (template2Section2["ordinal"] as JsonPrimitive).content.toInt())
    }
}