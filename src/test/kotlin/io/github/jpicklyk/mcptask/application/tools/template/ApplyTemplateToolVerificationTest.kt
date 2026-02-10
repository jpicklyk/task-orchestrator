package io.github.jpicklyk.mcptask.application.tools.template

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.repository.*
import io.github.jpicklyk.mcptask.infrastructure.repository.RepositoryProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.*

/**
 * Tests for auto-setting requiresVerification when applying templates with Verification sections.
 */
class ApplyTemplateToolVerificationTest {
    private lateinit var tool: ApplyTemplateTool
    private lateinit var context: ToolExecutionContext
    private lateinit var mockTemplateRepository: TemplateRepository
    private lateinit var mockTaskRepository: TaskRepository
    private lateinit var mockFeatureRepository: FeatureRepository
    private lateinit var mockSectionRepository: SectionRepository
    private lateinit var mockRepositoryProvider: RepositoryProvider

    private val templateId = UUID.randomUUID()
    private val taskId = UUID.randomUUID()
    private val featureId = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        mockTemplateRepository = mockk()
        mockTaskRepository = mockk()
        mockFeatureRepository = mockk()
        mockSectionRepository = mockk()
        mockRepositoryProvider = mockk()

        every { mockRepositoryProvider.templateRepository() } returns mockTemplateRepository
        every { mockRepositoryProvider.taskRepository() } returns mockTaskRepository
        every { mockRepositoryProvider.featureRepository() } returns mockFeatureRepository
        every { mockRepositoryProvider.sectionRepository() } returns mockSectionRepository

        context = ToolExecutionContext(mockRepositoryProvider)
        tool = ApplyTemplateTool()
    }

    @Test
    fun `apply template with Verification section auto-sets requiresVerification to true on task`() = runBlocking {
        // Setup template
        val template = Template(
            id = templateId,
            name = "Implementation Template",
            description = "Template with verification",
            targetEntityType = EntityType.TASK,
            isBuiltIn = false,
            isProtected = false,
            isEnabled = true,
            createdBy = "test",
            tags = emptyList(),
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )

        // Setup test task that does NOT yet require verification
        val testTask = Task(
            id = taskId,
            title = "Test Task",
            summary = "Test",
            status = TaskStatus.PENDING,
            requiresVerification = false
        )

        // Template sections include a "Verification" section
        val templateSections = listOf(
            TemplateSection(
                id = UUID.randomUUID(),
                templateId = templateId,
                title = "Implementation Notes",
                usageDescription = "Implementation details",
                contentSample = "Details here",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0
            ),
            TemplateSection(
                id = UUID.randomUUID(),
                templateId = templateId,
                title = "Verification",
                usageDescription = "Acceptance criteria",
                contentSample = """[{"criteria": "Tests pass", "pass": false}]""",
                contentFormat = ContentFormat.JSON,
                ordinal = 1
            )
        )

        // Mock repository calls
        coEvery { mockTemplateRepository.getTemplate(templateId) } returns Result.Success(template)
        coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(testTask)
        coEvery { mockTemplateRepository.applyTemplate(templateId, EntityType.TASK, taskId) } returns Result.Success(templateSections)

        // After template application, getSectionsForEntity is called for the auto-set check.
        // Return sections that include a "Verification" titled section (the one just created)
        val createdSections = listOf(
            Section(
                id = UUID.randomUUID(),
                entityType = EntityType.TASK,
                entityId = taskId,
                title = "Implementation Notes",
                usageDescription = "Implementation details",
                content = "Details here",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0
            ),
            Section(
                id = UUID.randomUUID(),
                entityType = EntityType.TASK,
                entityId = taskId,
                title = "Verification",
                usageDescription = "Acceptance criteria",
                content = """[{"criteria": "Tests pass", "pass": false}]""",
                contentFormat = ContentFormat.JSON,
                ordinal = 1
            )
        )
        coEvery {
            mockSectionRepository.getSectionsForEntity(EntityType.TASK, taskId)
        } returns Result.Success(createdSections)

        // Mock the update to capture the task being saved
        val taskSlot = slot<Task>()
        coEvery { mockTaskRepository.update(capture(taskSlot)) } answers {
            Result.Success(taskSlot.captured)
        }

        // Execute
        val params = JsonObject(
            mapOf(
                "templateIds" to JsonArray(listOf(JsonPrimitive(templateId.toString()))),
                "entityType" to JsonPrimitive("TASK"),
                "entityId" to JsonPrimitive(taskId.toString())
            )
        )

        val result = tool.execute(params, context) as JsonObject
        assertEquals(true, (result["success"] as JsonPrimitive).content.toBoolean())

        // Verify that the task was updated with requiresVerification=true
        coVerify { mockTaskRepository.update(match { it.requiresVerification }) }
        assertTrue(taskSlot.captured.requiresVerification,
            "Task should have requiresVerification auto-set to true after template with Verification section")
    }

    @Test
    fun `apply template without Verification section does not change requiresVerification`() = runBlocking {
        // Setup template
        val template = Template(
            id = templateId,
            name = "Basic Template",
            description = "Template without verification",
            targetEntityType = EntityType.TASK,
            isBuiltIn = false,
            isProtected = false,
            isEnabled = true,
            createdBy = "test",
            tags = emptyList(),
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )

        // Setup test task that does NOT require verification
        val testTask = Task(
            id = taskId,
            title = "Test Task",
            summary = "Test",
            status = TaskStatus.PENDING,
            requiresVerification = false
        )

        // Template sections with NO "Verification" section
        val templateSections = listOf(
            TemplateSection(
                id = UUID.randomUUID(),
                templateId = templateId,
                title = "Implementation Notes",
                usageDescription = "Implementation details",
                contentSample = "Details here",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0
            ),
            TemplateSection(
                id = UUID.randomUUID(),
                templateId = templateId,
                title = "Testing Strategy",
                usageDescription = "How to test",
                contentSample = "Test plan here",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 1
            )
        )

        // Mock repository calls
        coEvery { mockTemplateRepository.getTemplate(templateId) } returns Result.Success(template)
        coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(testTask)
        coEvery { mockTemplateRepository.applyTemplate(templateId, EntityType.TASK, taskId) } returns Result.Success(templateSections)

        // After template application, getSectionsForEntity returns sections without Verification
        val createdSections = listOf(
            Section(
                id = UUID.randomUUID(),
                entityType = EntityType.TASK,
                entityId = taskId,
                title = "Implementation Notes",
                usageDescription = "Implementation details",
                content = "Details here",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0
            ),
            Section(
                id = UUID.randomUUID(),
                entityType = EntityType.TASK,
                entityId = taskId,
                title = "Testing Strategy",
                usageDescription = "How to test",
                content = "Test plan here",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 1
            )
        )
        coEvery {
            mockSectionRepository.getSectionsForEntity(EntityType.TASK, taskId)
        } returns Result.Success(createdSections)

        // Execute
        val params = JsonObject(
            mapOf(
                "templateIds" to JsonArray(listOf(JsonPrimitive(templateId.toString()))),
                "entityType" to JsonPrimitive("TASK"),
                "entityId" to JsonPrimitive(taskId.toString())
            )
        )

        val result = tool.execute(params, context) as JsonObject
        assertEquals(true, (result["success"] as JsonPrimitive).content.toBoolean())

        // Verify that the task was NOT updated (no call to update since no Verification section)
        coVerify(exactly = 0) { mockTaskRepository.update(any()) }
    }

    @Test
    fun `apply template with Verification section auto-sets requiresVerification on feature`() = runBlocking {
        // Setup template
        val template = Template(
            id = templateId,
            name = "Feature Template",
            description = "Template with verification",
            targetEntityType = EntityType.FEATURE,
            isBuiltIn = false,
            isProtected = false,
            isEnabled = true,
            createdBy = "test",
            tags = emptyList(),
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )

        // Setup test feature that does NOT yet require verification
        val testFeature = Feature(
            id = featureId,
            name = "Test Feature",
            summary = "Test",
            status = FeatureStatus.PLANNING,
            requiresVerification = false
        )

        // Template sections include a "Verification" section
        val templateSections = listOf(
            TemplateSection(
                id = UUID.randomUUID(),
                templateId = templateId,
                title = "Verification",
                usageDescription = "Acceptance criteria",
                contentSample = """[{"criteria": "Feature tests pass", "pass": false}]""",
                contentFormat = ContentFormat.JSON,
                ordinal = 0
            )
        )

        // Mock repository calls
        coEvery { mockTemplateRepository.getTemplate(templateId) } returns Result.Success(template)
        coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(testFeature)
        coEvery { mockTemplateRepository.applyTemplate(templateId, EntityType.FEATURE, featureId) } returns Result.Success(templateSections)

        // Sections after template application include Verification
        val createdSections = listOf(
            Section(
                id = UUID.randomUUID(),
                entityType = EntityType.FEATURE,
                entityId = featureId,
                title = "Verification",
                usageDescription = "Acceptance criteria",
                content = """[{"criteria": "Feature tests pass", "pass": false}]""",
                contentFormat = ContentFormat.JSON,
                ordinal = 0
            )
        )
        coEvery {
            mockSectionRepository.getSectionsForEntity(EntityType.FEATURE, featureId)
        } returns Result.Success(createdSections)

        // Mock the feature update to capture it
        val featureSlot = slot<Feature>()
        coEvery { mockFeatureRepository.update(capture(featureSlot)) } answers {
            Result.Success(featureSlot.captured)
        }

        // Execute
        val params = JsonObject(
            mapOf(
                "templateIds" to JsonArray(listOf(JsonPrimitive(templateId.toString()))),
                "entityType" to JsonPrimitive("FEATURE"),
                "entityId" to JsonPrimitive(featureId.toString())
            )
        )

        val result = tool.execute(params, context) as JsonObject
        assertEquals(true, (result["success"] as JsonPrimitive).content.toBoolean())

        // Verify that the feature was updated with requiresVerification=true
        coVerify { mockFeatureRepository.update(match { it.requiresVerification }) }
        assertTrue(featureSlot.captured.requiresVerification,
            "Feature should have requiresVerification auto-set to true after template with Verification section")
    }

    @Test
    fun `apply template to task that already has requiresVerification true does not re-update`() = runBlocking {
        // Setup template
        val template = Template(
            id = templateId,
            name = "Implementation Template",
            description = "Template with verification",
            targetEntityType = EntityType.TASK,
            isBuiltIn = false,
            isProtected = false,
            isEnabled = true,
            createdBy = "test",
            tags = emptyList(),
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )

        // Task ALREADY requires verification
        val testTask = Task(
            id = taskId,
            title = "Test Task",
            summary = "Test",
            status = TaskStatus.PENDING,
            requiresVerification = true
        )

        val templateSections = listOf(
            TemplateSection(
                id = UUID.randomUUID(),
                templateId = templateId,
                title = "Verification",
                usageDescription = "Acceptance criteria",
                contentSample = """[{"criteria": "Tests pass", "pass": false}]""",
                contentFormat = ContentFormat.JSON,
                ordinal = 0
            )
        )

        coEvery { mockTemplateRepository.getTemplate(templateId) } returns Result.Success(template)
        coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(testTask)
        coEvery { mockTemplateRepository.applyTemplate(templateId, EntityType.TASK, taskId) } returns Result.Success(templateSections)

        val createdSections = listOf(
            Section(
                id = UUID.randomUUID(),
                entityType = EntityType.TASK,
                entityId = taskId,
                title = "Verification",
                usageDescription = "Acceptance criteria",
                content = """[{"criteria": "Tests pass", "pass": false}]""",
                contentFormat = ContentFormat.JSON,
                ordinal = 0
            )
        )
        coEvery {
            mockSectionRepository.getSectionsForEntity(EntityType.TASK, taskId)
        } returns Result.Success(createdSections)

        // Execute
        val params = JsonObject(
            mapOf(
                "templateIds" to JsonArray(listOf(JsonPrimitive(templateId.toString()))),
                "entityType" to JsonPrimitive("TASK"),
                "entityId" to JsonPrimitive(taskId.toString())
            )
        )

        val result = tool.execute(params, context) as JsonObject
        assertEquals(true, (result["success"] as JsonPrimitive).content.toBoolean())

        // Task already has requiresVerification=true, so update should NOT be called
        coVerify(exactly = 0) { mockTaskRepository.update(any()) }
    }
}
