package io.github.jpicklyk.mcptask.application.tools

import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.repository.*
import io.github.jpicklyk.mcptask.infrastructure.repository.RepositoryProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import java.util.*

class ManageContainerToolTest {
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
    private val templateId = UUID.randomUUID()

    private lateinit var mockTask: Task
    private lateinit var mockFeature: Feature
    private lateinit var mockProject: Project

    @BeforeEach
    fun setup() {
        // Set up config file for v2.0 validation mode
        val projectRoot = java.nio.file.Paths.get(System.getProperty("user.dir"))
        val configDir = projectRoot.resolve(".taskorchestrator")
        java.nio.file.Files.createDirectories(configDir)
        val configFile = configDir.resolve("config.yaml")

        // Copy default config from resources
        val defaultConfigResource = this::class.java.classLoader.getResourceAsStream("claude/configuration/default-config.yaml")
        if (defaultConfigResource != null) {
            java.nio.file.Files.copy(defaultConfigResource, configFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
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

        // Create test entities
        mockTask = Task(
            id = taskId,
            title = "Test Task",
            description = "Test description",
            summary = "Test summary",
            status = TaskStatus.PENDING,
            priority = Priority.HIGH,
            complexity = 7,
            tags = listOf("test", "kotlin"),
            featureId = featureId,
            projectId = projectId,
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )

        mockFeature = Feature(
            id = featureId,
            name = "Test Feature",
            summary = "Test feature summary",
            status = FeatureStatus.IN_DEVELOPMENT,
            priority = Priority.HIGH,
            projectId = projectId
        )

        mockProject = Project(
            id = projectId,
            name = "Test Project",
            summary = "Test project summary",
            status = ProjectStatus.IN_DEVELOPMENT
        )

        // Create execution context
        context = ToolExecutionContext(mockRepositoryProvider)

        // Create tool with null locking service to bypass locking in unit tests
        tool = ManageContainerTool(null, null)
    }

    @AfterEach
    fun tearDown() {
        // Clean up test config file
        try {
            val configFile = Paths.get(System.getProperty("user.dir"), ".taskorchestrator", "config.yaml")
            Files.deleteIfExists(configFile)
            val configDir = Paths.get(System.getProperty("user.dir"), ".taskorchestrator")
            Files.deleteIfExists(configDir)
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
    }

    @Nested
    inner class ValidationTests {
        @Test
        fun `should require operation parameter`() {
            val params = buildJsonObject {
                put("containerType", "task")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("operation"))
        }

        @Test
        fun `should require containerType parameter`() {
            val params = buildJsonObject {
                put("operation", "create")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("containerType"))
        }

        @Test
        fun `should reject invalid operation`() {
            val params = buildJsonObject {
                put("operation", "invalid")
                put("containerType", "task")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("Invalid operation"))
        }

        @Test
        fun `should reject invalid containerType`() {
            val params = buildJsonObject {
                put("operation", "create")
                put("containerType", "invalid")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("Invalid containerType"))
        }

        @Test
        fun `should require id for update operation`() {
            val params = buildJsonObject {
                put("operation", "update")
                put("containerType", "task")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("id"))
        }

        @Test
        fun `should require title for task create operation`() {
            val params = buildJsonObject {
                put("operation", "create")
                put("containerType", "task")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("Title"))
        }

        @Test
        fun `should require name for project create operation`() {
            val params = buildJsonObject {
                put("operation", "create")
                put("containerType", "project")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("Name"))
        }

        @Test
        fun `should accept valid create params for task`() {
            val params = buildJsonObject {
                put("operation", "create")
                put("containerType", "task")
                put("title", "New Task")
            }

            assertDoesNotThrow {
                tool.validateParams(params)
            }
        }
    }

    @Nested
    inner class StatusValidationErrorMessageTests {
        /**
         * Tests for v2.0 mode (with config.yaml present).
         * The @BeforeEach in the main class sets up config.yaml, so these tests run in v2.0 mode.
         */

        @Test
        fun `should include allowed statuses in error for invalid task status - v2_0 mode`() {
            val params = buildJsonObject {
                put("operation", "create")
                put("containerType", "task")
                put("title", "Test Task")
                put("status", "invalid-status")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }

            // Error should mention the invalid status
            assertTrue(exception.message!!.contains("invalid-status"),
                "Error should mention the invalid status")

            // Error should include "Allowed statuses:" or similar
            assertTrue(exception.message!!.contains("llowed status") || exception.message!!.contains("Valid status"),
                "Error should mention allowed/valid statuses")

            // Error should list at least some valid statuses
            assertTrue(exception.message!!.contains("pending") ||
                       exception.message!!.contains("in-progress") ||
                       exception.message!!.contains("completed"),
                "Error should list valid task statuses")
        }

        @Test
        fun `should include allowed statuses in error for invalid feature status - v2_0 mode`() {
            val params = buildJsonObject {
                put("operation", "create")
                put("containerType", "feature")
                put("name", "Test Feature")
                put("status", "active")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }

            assertTrue(exception.message!!.contains("active"),
                "Error should mention the invalid status")
            assertTrue(exception.message!!.contains("llowed status") || exception.message!!.contains("Valid status"),
                "Error should mention allowed/valid statuses")
            assertTrue(exception.message!!.contains("planning") ||
                       exception.message!!.contains("in-development") ||
                       exception.message!!.contains("completed"),
                "Error should list valid feature statuses")
        }

        @Test
        fun `should include allowed statuses in error for invalid project status - v2_0 mode`() {
            val params = buildJsonObject {
                put("operation", "create")
                put("containerType", "project")
                put("name", "Test Project")
                put("status", "running")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }

            assertTrue(exception.message!!.contains("running"),
                "Error should mention the invalid status")
            assertTrue(exception.message!!.contains("llowed status") || exception.message!!.contains("Valid status"),
                "Error should mention allowed/valid statuses")
            assertTrue(exception.message!!.contains("planning") ||
                       exception.message!!.contains("in-development") ||
                       exception.message!!.contains("completed"),
                "Error should list valid project statuses")
        }

        @Test
        fun `should include allowed statuses in bulk update error - v2_0 mode`() {
            val params = buildJsonObject {
                put("operation", "bulkUpdate")
                put("containerType", "task")
                put("containers", JsonArray(listOf(
                    buildJsonObject {
                        put("id", taskId.toString())
                        put("status", "invalid-status")
                    }
                )))
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }

            // Error should mention it's at index 0
            assertTrue(exception.message!!.contains("index 0") || exception.message!!.contains("At index 0"),
                "Error should mention the index")

            // Error should mention the invalid status
            assertTrue(exception.message!!.contains("invalid-status"),
                "Error should mention the invalid status")

            // Error should include allowed statuses
            assertTrue(exception.message!!.contains("llowed status") || exception.message!!.contains("Valid status"),
                "Error should mention allowed/valid statuses")
        }

        @Test
        fun `should accept valid statuses in v2_0 mode`() {
            // Test that valid statuses don't throw errors
            val validTaskStatuses = listOf("pending", "in-progress", "completed", "backlog", "blocked")

            validTaskStatuses.forEach { status ->
                val params = buildJsonObject {
                    put("operation", "create")
                    put("containerType", "task")
                    put("title", "Test Task")
                    put("status", status)
                }

                assertDoesNotThrow({
                    tool.validateParams(params)
                }, "Status '$status' should be valid")
            }
        }

        @Test
        fun `should include allowed statuses in error for invalid status in update operation - v2_0 mode`() {
            val params = buildJsonObject {
                put("operation", "update")
                put("containerType", "task")
                put("id", taskId.toString())
                put("status", "invalid-update-status")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }

            // Error should mention the invalid status
            assertTrue(exception.message!!.contains("invalid-update-status"),
                "Error should mention the invalid status")

            // Error should include allowed statuses
            assertTrue(exception.message!!.contains("llowed status") || exception.message!!.contains("Valid status"),
                "Error should mention allowed/valid statuses")

            // Should list valid statuses
            assertTrue(exception.message!!.contains("pending") ||
                       exception.message!!.contains("in-progress") ||
                       exception.message!!.contains("completed"),
                "Error should list valid task statuses")
        }
    }

    @Nested
    inner class StatusValidationV1ModeTests {
        /**
         * Tests for v1.0 mode (without config.yaml).
         * We need to delete the config file to test v1.0 fallback behavior.
         */

        @BeforeEach
        fun removeConfigForV1Mode() {
            // Remove config file to force v1.0 mode
            try {
                val configFile = Paths.get(System.getProperty("user.dir"), ".taskorchestrator", "config.yaml")
                Files.deleteIfExists(configFile)
            } catch (e: Exception) {
                // Ignore errors
            }
        }

        @Test
        fun `should include allowed statuses in error for invalid task status - v1_0 mode`() {
            val params = buildJsonObject {
                put("operation", "create")
                put("containerType", "task")
                put("title", "Test Task")
                put("status", "invalid-status")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }

            // In v1.0 mode, error should still include allowed statuses (from enum)
            assertTrue(exception.message!!.contains("invalid-status"),
                "Error should mention the invalid status")
            assertTrue(exception.message!!.contains("llowed status") || exception.message!!.contains("Valid status"),
                "Error should mention allowed/valid statuses")

            // Should list enum-based statuses
            assertTrue(exception.message!!.contains("pending") ||
                       exception.message!!.contains("in-progress"),
                "Error should list valid task statuses from enum")
        }

        @Test
        fun `should include allowed statuses in error for invalid feature status - v1_0 mode`() {
            val params = buildJsonObject {
                put("operation", "create")
                put("containerType", "feature")
                put("name", "Test Feature")
                put("status", "active")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }

            assertTrue(exception.message!!.contains("active"),
                "Error should mention the invalid status")
            assertTrue(exception.message!!.contains("llowed status") || exception.message!!.contains("Valid status"),
                "Error should mention allowed/valid statuses")
        }

        @Test
        fun `should include allowed statuses in error for invalid project status - v1_0 mode`() {
            val params = buildJsonObject {
                put("operation", "create")
                put("containerType", "project")
                put("name", "Test Project")
                put("status", "running")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }

            assertTrue(exception.message!!.contains("running"),
                "Error should mention the invalid status")
            assertTrue(exception.message!!.contains("llowed status") || exception.message!!.contains("Valid status"),
                "Error should mention allowed/valid statuses")
            assertTrue(exception.message!!.contains("planning") ||
                       exception.message!!.contains("in-development"),
                "Error should list valid project statuses from enum")
        }

        @Test
        fun `should accept all enum statuses in v1_0 mode`() {
            // Test that all enum-based statuses are valid in v1.0 mode
            val validTaskStatuses = listOf("pending", "in-progress", "completed", "cancelled", "deferred",
                                           "backlog", "in-review", "changes-requested", "on-hold", "testing")

            validTaskStatuses.forEach { status ->
                val params = buildJsonObject {
                    put("operation", "create")
                    put("containerType", "task")
                    put("title", "Test Task")
                    put("status", status)
                }

                assertDoesNotThrow({
                    tool.validateParams(params)
                }, "Status '$status' should be valid in v1.0 mode")
            }
        }
    }

    @Nested
    inner class CreateOperationTests {
        @Test
        fun `should create task successfully`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "create")
                put("containerType", "task")
                put("title", "New Task")
                put("summary", "Task summary")
                put("status", "pending")
                put("priority", "high")
                put("complexity", 5)
            }

            coEvery { mockTaskRepository.create(any()) } returns Result.Success(mockTask)
            coEvery { mockTemplateRepository.applyMultipleTemplates(any(), any(), any()) } returns Result.Success(emptyMap())

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("created") == true)
            assertEquals(taskId.toString(), resultObj["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content)
        }

        @Test
        fun `should create feature successfully`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "create")
                put("containerType", "feature")
                put("name", "New Feature")
                put("summary", "Feature summary")
                put("status", "planning")
                put("priority", "medium")
                put("projectId", projectId.toString())
            }

            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(mockProject)
            coEvery { mockFeatureRepository.create(any()) } returns Result.Success(mockFeature)
            coEvery { mockTemplateRepository.applyMultipleTemplates(any(), any(), any()) } returns Result.Success(emptyMap())

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("created") == true)
        }

        @Test
        fun `should create project successfully`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "create")
                put("containerType", "project")
                put("name", "New Project")
                put("summary", "Project summary")
                put("status", "planning")
            }

            coEvery { mockProjectRepository.create(any()) } returns Result.Success(mockProject)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("created") == true)
        }

        @Test
        fun `should apply templates on create`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "create")
                put("containerType", "task")
                put("title", "New Task")
                put("templateIds", buildJsonArray {
                    add(templateId.toString())
                })
            }

            val templateSection = TemplateSection(
                id = UUID.randomUUID(),
                templateId = templateId,
                title = "Test Section",
                usageDescription = "Test usage",
                contentSample = "Test content",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0
            )

            coEvery { mockTaskRepository.create(any()) } returns Result.Success(mockTask)
            coEvery { mockTemplateRepository.applyMultipleTemplates(any(), any(), any()) } returns
                Result.Success(mapOf(templateId to listOf(templateSection)))

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("template") == true)
            assertNotNull(resultObj["data"]?.jsonObject?.get("appliedTemplates"))
        }
    }

    @Nested
    inner class UpdateOperationTests {
        @Test
        fun `should update task successfully`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "update")
                put("containerType", "task")
                put("id", taskId.toString())
                put("title", "Updated Task")
                put("status", "in-progress")
            }

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)
            coEvery { mockTaskRepository.update(any()) } returns Result.Success(mockTask.copy(title = "Updated Task"))

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("updated") == true)
        }

        @Test
        fun `should update feature successfully`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "update")
                put("containerType", "feature")
                put("id", featureId.toString())
                put("name", "Updated Feature")
                put("status", "completed")
            }

            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(mockFeature)
            coEvery { mockFeatureRepository.update(any()) } returns Result.Success(mockFeature.copy(name = "Updated Feature"))

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("updated") == true)
        }

        @Test
        fun `should update project successfully`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "update")
                put("containerType", "project")
                put("id", projectId.toString())
                put("name", "Updated Project")
                put("status", "completed")
            }

            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(mockProject)
            coEvery { mockProjectRepository.update(any()) } returns Result.Success(mockProject.copy(name = "Updated Project"))

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("updated") == true)
        }

        @Test
        fun `should return error when task not found`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "update")
                put("containerType", "task")
                put("id", taskId.toString())
                put("title", "Updated Task")
            }

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Error(RepositoryError.NotFound(taskId, EntityType.TASK, "Task not found"))

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertFalse(resultObj["success"]?.jsonPrimitive?.boolean == true)
        }
    }

    @Nested
    inner class DeleteOperationTests {
        @Test
        fun `should delete task successfully`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "delete")
                put("containerType", "task")
                put("id", taskId.toString())
            }

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)
            coEvery { mockDependencyRepository.findByTaskId(taskId) } returns emptyList()
            coEvery { mockSectionRepository.getSectionsForEntity(EntityType.TASK, taskId) } returns Result.Success(emptyList())
            coEvery { mockTaskRepository.delete(taskId) } returns Result.Success(true)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("deleted") == true)
        }

        @Test
        fun `should delete feature successfully`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "delete")
                put("containerType", "feature")
                put("id", featureId.toString())
            }

            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(mockFeature)
            coEvery { mockTaskRepository.findByFeature(featureId) } returns Result.Success(emptyList())
            coEvery { mockSectionRepository.getSectionsForEntity(EntityType.FEATURE, featureId) } returns Result.Success(emptyList())
            coEvery { mockFeatureRepository.delete(featureId) } returns Result.Success(true)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("deleted") == true)
        }

        @Test
        fun `should delete project successfully`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "delete")
                put("containerType", "project")
                put("id", projectId.toString())
            }

            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(mockProject)
            coEvery { mockFeatureRepository.findByProject(projectId) } returns Result.Success(emptyList())
            coEvery { mockTaskRepository.findByProject(projectId, any()) } returns Result.Success(emptyList())
            coEvery { mockSectionRepository.getSectionsForEntity(EntityType.PROJECT, projectId) } returns Result.Success(emptyList())
            coEvery { mockProjectRepository.delete(projectId) } returns Result.Success(true)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("deleted") == true)
        }

        @Test
        fun `should fail to delete task with dependencies without force`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "delete")
                put("containerType", "task")
                put("id", taskId.toString())
                put("force", false)
            }

            val dependency = Dependency(
                id = UUID.randomUUID(),
                fromTaskId = UUID.randomUUID(),
                toTaskId = taskId,
                type = DependencyType.BLOCKS,
                createdAt = Instant.now()
            )

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)
            coEvery { mockDependencyRepository.findByTaskId(taskId) } returns listOf(dependency)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertFalse(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("dependencies") == true)
        }

        @Test
        fun `should delete task with dependencies when force is true`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "delete")
                put("containerType", "task")
                put("id", taskId.toString())
                put("force", true)
            }

            val dependency = Dependency(
                id = UUID.randomUUID(),
                fromTaskId = UUID.randomUUID(),
                toTaskId = taskId,
                type = DependencyType.BLOCKS,
                createdAt = Instant.now()
            )

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)
            coEvery { mockDependencyRepository.findByTaskId(taskId) } returns listOf(dependency)
            coEvery { mockDependencyRepository.deleteByTaskId(taskId) } returns 1
            coEvery { mockSectionRepository.getSectionsForEntity(EntityType.TASK, taskId) } returns Result.Success(emptyList())
            coEvery { mockTaskRepository.delete(taskId) } returns Result.Success(true)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("dependencies") == true)
        }
    }

    @Nested
    inner class SetStatusOperationTests {
        @Test
        fun `should set task status successfully with valid transition`() = runBlocking {
            // Valid transition: PENDING -> IN_PROGRESS
            val params = buildJsonObject {
                put("operation", "setStatus")
                put("containerType", "task")
                put("id", taskId.toString())
                put("status", "in-progress")
            }

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)
            coEvery { mockTaskRepository.update(any()) } returns Result.Success(mockTask.copy(status = TaskStatus.IN_PROGRESS))
            // Mock dependency check for IN_PROGRESS prerequisite validation
            coEvery { mockDependencyRepository.findByToTaskId(taskId) } returns emptyList()
            // Mock cascade detection queries
            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(mockFeature)
            every { mockTaskRepository.findByFeatureId(featureId) } returns listOf(mockTask)
            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(mockProject)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("in-progress") == true)
        }

        @Test
        fun `should reject task status with invalid transition`() = runBlocking {
            // Invalid transition: PENDING -> COMPLETED (skips in-progress and testing)
            val params = buildJsonObject {
                put("operation", "setStatus")
                put("containerType", "task")
                put("id", taskId.toString())
                put("status", "completed")
            }

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertFalse(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("Cannot skip statuses") == true)
        }

        @Test
        fun `should set feature status successfully with valid transition`() = runBlocking {
            // Valid transition: IN_DEVELOPMENT -> TESTING
            val params = buildJsonObject {
                put("operation", "setStatus")
                put("containerType", "feature")
                put("id", featureId.toString())
                put("status", "testing")
            }

            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(mockFeature)
            coEvery { mockFeatureRepository.update(any()) } returns Result.Success(mockFeature.copy(status = FeatureStatus.TESTING))
            // Mock task check for TESTING prerequisite validation (requires all tasks completed)
            coEvery { mockTaskRepository.findByFeature(featureId, null, null, 1000) } returns Result.Success(
                listOf(mockTask.copy(status = TaskStatus.COMPLETED))
            )
            // Mock cascade detection queries
            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(mockProject)
            every { mockProjectRepository.getFeatureCountsByProjectId(projectId) } returns FeatureCounts(total = 1, completed = 0)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("testing") == true)
        }

        @Test
        fun `should reject feature status with invalid transition`() = runBlocking {
            // Invalid transition: IN_DEVELOPMENT -> COMPLETED (skips testing and validating)
            val params = buildJsonObject {
                put("operation", "setStatus")
                put("containerType", "feature")
                put("id", featureId.toString())
                put("status", "completed")
            }

            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(mockFeature)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertFalse(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("Cannot skip statuses") == true)
        }

        @Test
        fun `should set project status successfully`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "setStatus")
                put("containerType", "project")
                put("id", projectId.toString())
                put("status", "completed")
            }

            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(mockProject)
            coEvery { mockProjectRepository.update(any()) } returns Result.Success(mockProject.copy(status = ProjectStatus.COMPLETED))
            // Mock feature check for COMPLETED prerequisite validation (requires all features completed)
            coEvery { mockFeatureRepository.findByProject(projectId, 1000) } returns Result.Success(
                listOf(mockFeature.copy(status = FeatureStatus.COMPLETED))
            )

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("completed") == true)
        }
    }

    @Nested
    inner class StatusParsingTests {
        @Test
        fun `should parse new TaskStatus BACKLOG`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "create")
                put("containerType", "task")
                put("title", "Backlog Task")
                put("status", "backlog")
            }

            coEvery { mockTaskRepository.create(any()) } returns Result.Success(mockTask.copy(status = TaskStatus.BACKLOG))
            coEvery { mockTemplateRepository.applyMultipleTemplates(any(), any(), any()) } returns Result.Success(emptyMap())

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
        }

        @Test
        fun `should parse new TaskStatus IN_REVIEW with different formats`() = runBlocking {
            // Test all format variations
            val formats = listOf("in-review", "in_review", "inreview")

            formats.forEach { format ->
                val params = buildJsonObject {
                    put("operation", "create")
                    put("containerType", "task")
                    put("title", "Review Task")
                    put("status", format)
                }

                coEvery { mockTaskRepository.create(any()) } returns Result.Success(mockTask.copy(status = TaskStatus.IN_REVIEW))
                coEvery { mockTemplateRepository.applyMultipleTemplates(any(), any(), any()) } returns Result.Success(emptyMap())

                val result = tool.execute(params, context)

                val resultObj = result.jsonObject
                assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true, "Failed for format: $format")
            }
        }

        @Test
        fun `should parse new TaskStatus CHANGES_REQUESTED with different formats`() = runBlocking {
            val formats = listOf("changes-requested", "changes_requested", "changesrequested")

            formats.forEach { format ->
                val params = buildJsonObject {
                    put("operation", "create")
                    put("containerType", "task")
                    put("title", "Changes Task")
                    put("status", format)
                }

                coEvery { mockTaskRepository.create(any()) } returns Result.Success(mockTask.copy(status = TaskStatus.CHANGES_REQUESTED))
                coEvery { mockTemplateRepository.applyMultipleTemplates(any(), any(), any()) } returns Result.Success(emptyMap())

                val result = tool.execute(params, context)

                val resultObj = result.jsonObject
                assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true, "Failed for format: $format")
            }
        }

        @Test
        fun `should parse new TaskStatus ON_HOLD with different formats`() = runBlocking {
            val formats = listOf("on-hold", "on_hold", "onhold")

            formats.forEach { format ->
                val params = buildJsonObject {
                    put("operation", "create")
                    put("containerType", "task")
                    put("title", "On Hold Task")
                    put("status", format)
                }

                coEvery { mockTaskRepository.create(any()) } returns Result.Success(mockTask.copy(status = TaskStatus.ON_HOLD))
                coEvery { mockTemplateRepository.applyMultipleTemplates(any(), any(), any()) } returns Result.Success(emptyMap())

                val result = tool.execute(params, context)

                val resultObj = result.jsonObject
                assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true, "Failed for format: $format")
            }
        }

        @Test
        fun `should parse new FeatureStatus DRAFT`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "create")
                put("containerType", "feature")
                put("name", "Draft Feature")
                put("status", "draft")
            }

            coEvery { mockFeatureRepository.create(any()) } returns Result.Success(mockFeature.copy(status = FeatureStatus.DRAFT))
            coEvery { mockTemplateRepository.applyMultipleTemplates(any(), any(), any()) } returns Result.Success(emptyMap())

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
        }

        @Test
        fun `should parse new FeatureStatus ON_HOLD with different formats`() = runBlocking {
            val formats = listOf("on-hold", "on_hold", "onhold")

            formats.forEach { format ->
                val params = buildJsonObject {
                    put("operation", "create")
                    put("containerType", "feature")
                    put("name", "On Hold Feature")
                    put("status", format)
                }

                coEvery { mockFeatureRepository.create(any()) } returns Result.Success(mockFeature.copy(status = FeatureStatus.ON_HOLD))
                coEvery { mockTemplateRepository.applyMultipleTemplates(any(), any(), any()) } returns Result.Success(emptyMap())

                val result = tool.execute(params, context)

                val resultObj = result.jsonObject
                assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true, "Failed for format: $format")
            }
        }

        @Test
        fun `should parse existing ProjectStatus ON_HOLD with different formats`() = runBlocking {
            val formats = listOf("on-hold", "on_hold", "onhold")

            formats.forEach { format ->
                val params = buildJsonObject {
                    put("operation", "create")
                    put("containerType", "project")
                    put("name", "On Hold Project")
                    put("status", format)
                }

                coEvery { mockProjectRepository.create(any()) } returns Result.Success(mockProject.copy(status = ProjectStatus.ON_HOLD))

                val result = tool.execute(params, context)

                val resultObj = result.jsonObject
                assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true, "Failed for format: $format")
            }
        }

        @Test
        fun `should parse existing ProjectStatus CANCELLED with different formats`() = runBlocking {
            val formats = listOf("cancelled", "canceled")

            formats.forEach { format ->
                val params = buildJsonObject {
                    put("operation", "create")
                    put("containerType", "project")
                    put("name", "Cancelled Project")
                    put("status", format)
                }

                coEvery { mockProjectRepository.create(any()) } returns Result.Success(mockProject.copy(status = ProjectStatus.CANCELLED))

                val result = tool.execute(params, context)

                val resultObj = result.jsonObject
                assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true, "Failed for format: $format")
            }
        }

        @Test
        fun `should reject invalid task status`() {
            val params = buildJsonObject {
                put("operation", "create")
                put("containerType", "task")
                put("title", "Invalid Task")
                put("status", "invalid-status")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("Invalid status"))
        }

        @Test
        fun `should reject invalid feature status`() {
            val params = buildJsonObject {
                put("operation", "create")
                put("containerType", "feature")
                put("name", "Invalid Feature")
                put("status", "invalid-status")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("Invalid status"))
        }

        @Test
        fun `should reject invalid project status`() {
            val params = buildJsonObject {
                put("operation", "create")
                put("containerType", "project")
                put("name", "Invalid Project")
                put("status", "invalid-status")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("Invalid status"))
        }
    }

    @Nested
    inner class BulkUpdateOperationTests {
        @Test
        fun `should bulk update tasks successfully`() = runBlocking {
            val task2Id = UUID.randomUUID()
            val task2 = mockTask.copy(id = task2Id, title = "Task 2")

            val params = buildJsonObject {
                put("operation", "bulkUpdate")
                put("containerType", "task")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("id", taskId.toString())
                        put("status", "completed")
                    })
                    add(buildJsonObject {
                        put("id", task2Id.toString())
                        put("status", "in-progress")
                    })
                })
            }

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)
            coEvery { mockTaskRepository.getById(task2Id) } returns Result.Success(task2)
            coEvery { mockTaskRepository.update(any()) } returns Result.Success(mockTask)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("updated") == true)
            assertEquals(2, resultObj["data"]?.jsonObject?.get("updated")?.jsonPrimitive?.int)
            assertEquals(0, resultObj["data"]?.jsonObject?.get("failed")?.jsonPrimitive?.int)
        }

        @Test
        fun `should handle partial bulk update failures`() = runBlocking {
            val task2Id = UUID.randomUUID()

            val params = buildJsonObject {
                put("operation", "bulkUpdate")
                put("containerType", "task")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("id", taskId.toString())
                        put("status", "completed")
                    })
                    add(buildJsonObject {
                        put("id", task2Id.toString())
                        put("status", "in-progress")
                    })
                })
            }

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)
            coEvery { mockTaskRepository.getById(task2Id) } returns Result.Error(RepositoryError.NotFound(task2Id, EntityType.TASK, "Task not found"))
            coEvery { mockTaskRepository.update(any()) } returns Result.Success(mockTask)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("updated") == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("failed") == true)
            assertEquals(1, resultObj["data"]?.jsonObject?.get("updated")?.jsonPrimitive?.int)
            assertEquals(1, resultObj["data"]?.jsonObject?.get("failed")?.jsonPrimitive?.int)
        }

        @Test
        fun `should validate bulk update array size`() {
            val containersArray = buildJsonArray {
                repeat(101) { index ->
                    add(buildJsonObject {
                        put("id", UUID.randomUUID().toString())
                        put("status", "completed")
                    })
                }
            }

            val params = buildJsonObject {
                put("operation", "bulkUpdate")
                put("containerType", "task")
                put("containers", containersArray)
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("Maximum 100"))
        }
    }
}
