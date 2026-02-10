package io.github.jpicklyk.mcptask.application.tools

import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.repository.*
import io.github.jpicklyk.mcptask.infrastructure.repository.RepositoryProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import java.util.*

/**
 * Tests for verification gate behavior in ManageContainerTool.
 * Covers setStatus, create, and update operations related to requiresVerification.
 */
class ManageContainerToolVerificationTest {
    private lateinit var tool: ManageContainerTool
    private lateinit var context: ToolExecutionContext
    private lateinit var mockTaskRepository: TaskRepository
    private lateinit var mockFeatureRepository: FeatureRepository
    private lateinit var mockProjectRepository: ProjectRepository
    private lateinit var mockSectionRepository: SectionRepository
    private lateinit var mockDependencyRepository: DependencyRepository
    private lateinit var mockTemplateRepository: TemplateRepository
    private lateinit var mockRepositoryProvider: RepositoryProvider

    private val taskId = UUID.randomUUID()
    private val featureId = UUID.randomUUID()
    private val projectId = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        // Set up config file for v2.0 validation mode
        val projectRoot = Paths.get(System.getProperty("user.dir"))
        val configDir = projectRoot.resolve(".taskorchestrator")
        Files.createDirectories(configDir)
        val configFile = configDir.resolve("config.yaml")

        val defaultConfigResource = this::class.java.classLoader.getResourceAsStream("configuration/default-config.yaml")
        if (defaultConfigResource != null) {
            Files.copy(defaultConfigResource, configFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }

        // Create mock repositories
        mockTaskRepository = mockk()
        mockFeatureRepository = mockk()
        mockProjectRepository = mockk()
        mockSectionRepository = mockk()
        mockDependencyRepository = mockk()
        mockTemplateRepository = mockk()
        mockRepositoryProvider = mockk()

        // Configure repository provider
        every { mockRepositoryProvider.taskRepository() } returns mockTaskRepository
        every { mockRepositoryProvider.featureRepository() } returns mockFeatureRepository
        every { mockRepositoryProvider.projectRepository() } returns mockProjectRepository
        every { mockRepositoryProvider.sectionRepository() } returns mockSectionRepository
        every { mockRepositoryProvider.dependencyRepository() } returns mockDependencyRepository
        every { mockRepositoryProvider.templateRepository() } returns mockTemplateRepository

        // Create execution context
        context = ToolExecutionContext(mockRepositoryProvider)

        // Create tool with null locking service to bypass locking in unit tests
        tool = ManageContainerTool(null, null)
    }

    @AfterEach
    fun tearDown() {
        try {
            val configFile = Paths.get(System.getProperty("user.dir"), ".taskorchestrator", "config.yaml")
            Files.deleteIfExists(configFile)
            val configDir = Paths.get(System.getProperty("user.dir"), ".taskorchestrator")
            Files.deleteIfExists(configDir)
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
    }

    // Summary must be 300-500 characters to pass the StatusValidator completion prerequisite
    private val validSummary = "This is a comprehensive test task summary that provides detailed information about " +
        "the work being performed. It covers the implementation approach, testing strategy, and expected outcomes " +
        "for the verification gate feature. The task involves creating unit tests for the verification service and " +
        "integration tests for the manage container tool and request transition tool."

    private fun createTestTask(
        id: UUID = taskId,
        status: TaskStatus = TaskStatus.IN_PROGRESS,
        requiresVerification: Boolean = false
    ): Task {
        return Task(
            id = id,
            title = "Test Task",
            description = "Test description",
            summary = validSummary,
            status = status,
            priority = Priority.HIGH,
            complexity = 5,
            tags = listOf("test"),
            featureId = featureId,
            projectId = projectId,
            requiresVerification = requiresVerification,
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )
    }

    private fun createTestFeature(
        id: UUID = featureId,
        status: FeatureStatus = FeatureStatus.VALIDATING,
        requiresVerification: Boolean = false
    ): Feature {
        return Feature(
            id = id,
            name = "Test Feature",
            summary = "Test feature summary",
            status = status,
            priority = Priority.HIGH,
            projectId = projectId,
            requiresVerification = requiresVerification
        )
    }

    private fun createVerificationSection(
        entityType: EntityType,
        entityId: UUID,
        content: String
    ): Section {
        return Section(
            id = UUID.randomUUID(),
            entityType = entityType,
            entityId = entityId,
            title = "Verification",
            usageDescription = "Acceptance criteria",
            content = content,
            contentFormat = ContentFormat.JSON,
            ordinal = 0,
            tags = emptyList(),
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )
    }

    @Nested
    inner class SetStatusVerificationGateTests {

        @Test
        fun `setStatus to completed with requiresVerification true and no verification section is blocked`() = runBlocking {
            // Task is in testing status (one step before completed in v2 workflow)
            val task = createTestTask(status = TaskStatus.TESTING, requiresVerification = true)

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(task)
            // Mock dependency check for status validation
            coEvery { mockDependencyRepository.findByToTaskId(taskId) } returns emptyList()
            // No verification section
            coEvery {
                mockSectionRepository.getSectionsForEntity(EntityType.TASK, taskId)
            } returns Result.Success(emptyList())

            val params = buildJsonObject {
                put("operation", "setStatus")
                put("containerType", "task")
                put("id", taskId.toString())
                put("status", "completed")
            }

            val result = tool.execute(params, context)
            val resultObj = result.jsonObject

            assertFalse(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("Completion blocked") == true)
        }

        @Test
        fun `setStatus to completed with requiresVerification true and all criteria passing is allowed`() = runBlocking {
            val task = createTestTask(status = TaskStatus.TESTING, requiresVerification = true)

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(task)
            coEvery { mockDependencyRepository.findByToTaskId(taskId) } returns emptyList()

            // Verification section with all criteria passing
            val verificationSection = createVerificationSection(
                EntityType.TASK, taskId,
                """[{"criteria": "Tests pass", "pass": true}, {"criteria": "Reviewed", "pass": true}]"""
            )
            coEvery {
                mockSectionRepository.getSectionsForEntity(EntityType.TASK, taskId)
            } returns Result.Success(listOf(verificationSection))

            // Mock the update and cascade detection
            coEvery { mockTaskRepository.update(any()) } returns Result.Success(task.copy(status = TaskStatus.COMPLETED))
            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(createTestFeature())
            every { mockTaskRepository.findByFeatureId(featureId) } returns listOf(task)
            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(
                Project(id = projectId, name = "Test Project", summary = "Test", status = ProjectStatus.IN_DEVELOPMENT)
            )

            val params = buildJsonObject {
                put("operation", "setStatus")
                put("containerType", "task")
                put("id", taskId.toString())
                put("status", "completed")
            }

            val result = tool.execute(params, context)
            val resultObj = result.jsonObject

            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("completed") == true)
        }

        @Test
        fun `setStatus to cancelled with requiresVerification true is allowed without gate`() = runBlocking {
            val task = createTestTask(status = TaskStatus.IN_PROGRESS, requiresVerification = true)

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(task)
            coEvery { mockDependencyRepository.findByToTaskId(taskId) } returns emptyList()

            // No sections needed - cancellation should bypass verification gate
            coEvery { mockTaskRepository.update(any()) } returns Result.Success(task.copy(status = TaskStatus.CANCELLED))
            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(createTestFeature())
            every { mockTaskRepository.findByFeatureId(featureId) } returns listOf(task)
            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(
                Project(id = projectId, name = "Test Project", summary = "Test", status = ProjectStatus.IN_DEVELOPMENT)
            )

            val params = buildJsonObject {
                put("operation", "setStatus")
                put("containerType", "task")
                put("id", taskId.toString())
                put("status", "cancelled")
            }

            val result = tool.execute(params, context)
            val resultObj = result.jsonObject

            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("cancelled") == true)
        }

        @Test
        fun `setStatus to completed with requiresVerification false is allowed without gate`() = runBlocking {
            // Task does NOT require verification
            val task = createTestTask(status = TaskStatus.TESTING, requiresVerification = false)

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(task)
            coEvery { mockDependencyRepository.findByToTaskId(taskId) } returns emptyList()

            // No sections - but that's fine because requiresVerification is false
            coEvery { mockTaskRepository.update(any()) } returns Result.Success(task.copy(status = TaskStatus.COMPLETED))
            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(createTestFeature())
            every { mockTaskRepository.findByFeatureId(featureId) } returns listOf(task)
            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(
                Project(id = projectId, name = "Test Project", summary = "Test", status = ProjectStatus.IN_DEVELOPMENT)
            )

            val params = buildJsonObject {
                put("operation", "setStatus")
                put("containerType", "task")
                put("id", taskId.toString())
                put("status", "completed")
            }

            val result = tool.execute(params, context)
            val resultObj = result.jsonObject

            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("completed") == true)
        }

        @Test
        fun `setStatus to completed on feature with requiresVerification true and no section is blocked`() = runBlocking {
            val feature = createTestFeature(status = FeatureStatus.VALIDATING, requiresVerification = true)

            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            // Mock task check for completion prerequisite validation
            coEvery { mockTaskRepository.findByFeature(featureId, null, null, 1000) } returns Result.Success(
                listOf(createTestTask(status = TaskStatus.COMPLETED))
            )
            // No verification section
            coEvery {
                mockSectionRepository.getSectionsForEntity(EntityType.FEATURE, featureId)
            } returns Result.Success(emptyList())

            val params = buildJsonObject {
                put("operation", "setStatus")
                put("containerType", "feature")
                put("id", featureId.toString())
                put("status", "completed")
            }

            val result = tool.execute(params, context)
            val resultObj = result.jsonObject

            assertFalse(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("Completion blocked") == true)
        }
    }

    @Nested
    inner class CreateWithVerificationFlagTests {

        @Test
        fun `create task with requiresVerification true persists the flag`() = runBlocking {
            val taskSlot = slot<Task>()
            coEvery { mockTaskRepository.create(capture(taskSlot)) } answers {
                Result.Success(taskSlot.captured)
            }
            coEvery { mockTemplateRepository.applyMultipleTemplates(any(), any(), any()) } returns Result.Success(emptyMap())

            val params = buildJsonObject {
                put("operation", "create")
                put("containerType", "task")
                put("title", "Verified Task")
                put("requiresVerification", true)
            }

            val result = tool.execute(params, context)
            val resultObj = result.jsonObject

            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(taskSlot.captured.requiresVerification, "requiresVerification should be true on the created task")
        }

        @Test
        fun `create task without requiresVerification defaults to false`() = runBlocking {
            val taskSlot = slot<Task>()
            coEvery { mockTaskRepository.create(capture(taskSlot)) } answers {
                Result.Success(taskSlot.captured)
            }
            coEvery { mockTemplateRepository.applyMultipleTemplates(any(), any(), any()) } returns Result.Success(emptyMap())

            val params = buildJsonObject {
                put("operation", "create")
                put("containerType", "task")
                put("title", "Normal Task")
            }

            val result = tool.execute(params, context)
            val resultObj = result.jsonObject

            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertFalse(taskSlot.captured.requiresVerification, "requiresVerification should default to false")
        }
    }

    @Nested
    inner class UpdateVerificationFlagTests {

        @Test
        fun `update task requiresVerification from false to true`() = runBlocking {
            val existingTask = createTestTask(requiresVerification = false)
            val taskSlot = slot<Task>()

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(existingTask)
            coEvery { mockTaskRepository.update(capture(taskSlot)) } answers {
                Result.Success(taskSlot.captured)
            }

            val params = buildJsonObject {
                put("operation", "update")
                put("containerType", "task")
                put("id", taskId.toString())
                put("requiresVerification", true)
            }

            val result = tool.execute(params, context)
            val resultObj = result.jsonObject

            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(taskSlot.captured.requiresVerification, "requiresVerification should be updated to true")
        }

        @Test
        fun `update task requiresVerification from true to false`() = runBlocking {
            val existingTask = createTestTask(requiresVerification = true)
            val taskSlot = slot<Task>()

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(existingTask)
            coEvery { mockTaskRepository.update(capture(taskSlot)) } answers {
                Result.Success(taskSlot.captured)
            }

            val params = buildJsonObject {
                put("operation", "update")
                put("containerType", "task")
                put("id", taskId.toString())
                put("requiresVerification", false)
            }

            val result = tool.execute(params, context)
            val resultObj = result.jsonObject

            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertFalse(taskSlot.captured.requiresVerification, "requiresVerification should be updated to false")
        }
    }
}
