package io.github.jpicklyk.mcptask.application.tools

import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.repository.*
import io.github.jpicklyk.mcptask.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
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
        val defaultConfigResource = this::class.java.classLoader.getResourceAsStream("configuration/default-config.yaml")
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
            assertTrue(exception.message!!.contains("create, update, delete"))
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
        fun `should require containers array for create operation`() {
            val params = buildJsonObject {
                put("operation", "create")
                put("containerType", "task")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("containers"))
        }

        @Test
        fun `should require containers array for update operation`() {
            val params = buildJsonObject {
                put("operation", "update")
                put("containerType", "task")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("containers"))
        }

        @Test
        fun `should require ids array for delete operation`() {
            val params = buildJsonObject {
                put("operation", "delete")
                put("containerType", "task")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("ids"))
        }

        @Test
        fun `should require title for task create operation`() {
            val params = buildJsonObject {
                put("operation", "create")
                put("containerType", "task")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        // Missing title
                    })
                })
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("Title") || exception.message!!.contains("title"))
        }

        @Test
        fun `should require name for project create operation`() {
            val params = buildJsonObject {
                put("operation", "create")
                put("containerType", "project")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        // Missing name
                    })
                })
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("Name") || exception.message!!.contains("name"))
        }

        @Test
        fun `should accept valid create params for task`() {
            val params = buildJsonObject {
                put("operation", "create")
                put("containerType", "task")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("title", "New Task")
                    })
                })
            }

            assertDoesNotThrow {
                tool.validateParams(params)
            }
        }

        @Test
        fun `should require id in containers array for update operation`() {
            val params = buildJsonObject {
                put("operation", "update")
                put("containerType", "task")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("title", "Updated Task")
                        // Missing id
                    })
                })
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("id"))
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
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("title", "Test Task")
                        put("status", "invalid-status")
                    })
                })
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
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("name", "Test Feature")
                        put("status", "active")
                    })
                })
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
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("name", "Test Project")
                        put("status", "running")
                    })
                })
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
        fun `should include allowed statuses in batch create error - v2_0 mode`() {
            val params = buildJsonObject {
                put("operation", "create")
                put("containerType", "task")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("title", "Task 1")
                    })
                    add(buildJsonObject {
                        put("title", "Task 2")
                        put("status", "invalid-status")
                    })
                })
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }

            // Error should mention it's at index 1
            assertTrue(exception.message!!.contains("index 1") || exception.message!!.contains("At index 1"),
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
                    put("containers", buildJsonArray {
                        add(buildJsonObject {
                            put("title", "Test Task")
                            put("status", status)
                        })
                    })
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
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("id", taskId.toString())
                        put("status", "invalid-update-status")
                    })
                })
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
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("title", "Test Task")
                        put("status", "invalid-status")
                    })
                })
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
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("name", "Test Feature")
                        put("status", "active")
                    })
                })
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
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("name", "Test Project")
                        put("status", "running")
                    })
                })
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
                    put("containers", buildJsonArray {
                        add(buildJsonObject {
                            put("title", "Test Task")
                            put("status", status)
                        })
                    })
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
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("title", "New Task")
                        put("description", "Task description")
                        put("summary", "Task summary")
                        put("priority", "high")
                        put("complexity", 5)
                    })
                })
            }

            // Mock repository create
            coEvery { mockTaskRepository.create(any()) } returns Result.Success(mockTask)

            val result = tool.execute(params, context)

            val jsonResponse = result.jsonObject
            assertTrue(jsonResponse["success"]?.jsonPrimitive?.boolean == true)
            val data = jsonResponse["data"]!!.jsonObject

            assertEquals(1, data["created"]?.jsonPrimitive?.int)
            assertEquals(0, data["failed"]?.jsonPrimitive?.int)

            val items = data["items"]!!.jsonArray
            assertEquals(1, items.size)
            assertEquals(taskId.toString(), items[0].jsonObject["id"]?.jsonPrimitive?.content)
            assertEquals("Test Task", items[0].jsonObject["title"]?.jsonPrimitive?.content)
        }

        @Test
        fun `should create feature successfully`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "create")
                put("containerType", "feature")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("name", "New Feature")
                        put("summary", "Feature summary")
                        put("priority", "high")
                        put("projectId", projectId.toString())
                    })
                })
            }

            // Mock parent project lookup
            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(mockProject)
            coEvery { mockFeatureRepository.create(any()) } returns Result.Success(mockFeature)

            val result = tool.execute(params, context)

            val jsonResponse = result.jsonObject
            assertTrue(jsonResponse["success"]?.jsonPrimitive?.boolean == true)
            val data = jsonResponse["data"]!!.jsonObject

            assertEquals(1, data["created"]?.jsonPrimitive?.int)
            assertEquals(0, data["failed"]?.jsonPrimitive?.int)

            val items = data["items"]!!.jsonArray
            assertEquals(1, items.size)
            assertEquals(featureId.toString(), items[0].jsonObject["id"]?.jsonPrimitive?.content)
            assertEquals("Test Feature", items[0].jsonObject["name"]?.jsonPrimitive?.content)
        }

        @Test
        fun `should create project successfully`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "create")
                put("containerType", "project")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("name", "New Project")
                        put("summary", "Project summary")
                    })
                })
            }

            coEvery { mockProjectRepository.create(any()) } returns Result.Success(mockProject)

            val result = tool.execute(params, context)

            val jsonResponse = result.jsonObject
            assertTrue(jsonResponse["success"]?.jsonPrimitive?.boolean == true)
            val data = jsonResponse["data"]!!.jsonObject

            assertEquals(1, data["created"]?.jsonPrimitive?.int)
            assertEquals(0, data["failed"]?.jsonPrimitive?.int)

            val items = data["items"]!!.jsonArray
            assertEquals(1, items.size)
            assertEquals(projectId.toString(), items[0].jsonObject["id"]?.jsonPrimitive?.content)
            assertEquals("Test Project", items[0].jsonObject["name"]?.jsonPrimitive?.content)
        }

        @Test
        fun `should create task with shared parent IDs from top level`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "create")
                put("containerType", "task")
                put("projectId", projectId.toString())
                put("featureId", featureId.toString())
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("title", "Task 1")
                    })
                    add(buildJsonObject {
                        put("title", "Task 2")
                    })
                })
            }

            // Mock parent lookups
            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(mockProject)
            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(mockFeature)

            val task1 = mockTask.copy(id = UUID.randomUUID(), title = "Task 1")
            val task2 = mockTask.copy(id = UUID.randomUUID(), title = "Task 2")

            coEvery { mockTaskRepository.create(match { it.title == "Task 1" }) } returns Result.Success(task1)
            coEvery { mockTaskRepository.create(match { it.title == "Task 2" }) } returns Result.Success(task2)

            val result = tool.execute(params, context)

            val jsonResponse = result.jsonObject
            assertTrue(jsonResponse["success"]?.jsonPrimitive?.boolean == true)
            val data = jsonResponse["data"]!!.jsonObject

            assertEquals(2, data["created"]?.jsonPrimitive?.int)
            assertEquals(0, data["failed"]?.jsonPrimitive?.int)

            val items = data["items"]!!.jsonArray
            assertEquals(2, items.size)
        }

        @Test
        fun `should apply templates during create`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "create")
                put("containerType", "task")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("title", "New Task")
                        put("templateIds", buildJsonArray {
                            add(templateId.toString())
                        })
                    })
                })
            }

            coEvery { mockTaskRepository.create(any()) } returns Result.Success(mockTask)
            coEvery { mockTemplateRepository.applyMultipleTemplates(any(), any(), any()) } returns Result.Success(emptyMap())

            val result = tool.execute(params, context)

            val jsonResponse = result.jsonObject
            assertTrue(jsonResponse["success"]?.jsonPrimitive?.boolean == true)
            val data = jsonResponse["data"]!!.jsonObject

            assertEquals(1, data["created"]?.jsonPrimitive?.int)
        }

        @Test
        fun `should handle partial batch creation failure`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "create")
                put("containerType", "task")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("title", "Task 1")
                    })
                    add(buildJsonObject {
                        put("title", "Task 2")
                    })
                })
            }

            val task1 = mockTask.copy(id = UUID.randomUUID(), title = "Task 1")

            coEvery { mockTaskRepository.create(match { it.title == "Task 1" }) } returns Result.Success(task1)
            coEvery { mockTaskRepository.create(match { it.title == "Task 2" }) } returns
                Result.Error(RepositoryError.DatabaseError("Database error"))

            val result = tool.execute(params, context)

            val jsonResponse = result.jsonObject
            assertTrue(jsonResponse["success"]?.jsonPrimitive?.boolean == true) // Partial success
            val data = jsonResponse["data"]!!.jsonObject

            assertEquals(1, data["created"]?.jsonPrimitive?.int)
            assertEquals(1, data["failed"]?.jsonPrimitive?.int)

            val items = data["items"]!!.jsonArray
            assertEquals(1, items.size)

            val failures = data["failures"]?.jsonArray
            assertEquals(1, failures?.size)
        }

        @Test
        fun `should fail if shared parent project not found`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "create")
                put("containerType", "task")
                put("projectId", projectId.toString())
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("title", "Task 1")
                    })
                })
            }

            coEvery { mockProjectRepository.getById(projectId) } returns
                Result.Error(RepositoryError.NotFound(projectId, EntityType.PROJECT, "Not found"))

            val result = tool.execute(params, context)

            val jsonResponse = result.jsonObject
            assertFalse(jsonResponse["success"]?.jsonPrimitive?.boolean == true)
            val errorObj = jsonResponse["error"]?.jsonObject
            assertEquals(ErrorCodes.RESOURCE_NOT_FOUND, errorObj?.get("code")?.jsonPrimitive?.content)
        }

        @Test
        fun `should override shared parent with per-item parent`() = runBlocking {
            val otherProjectId = UUID.randomUUID()
            val otherProject = mockProject.copy(id = otherProjectId, name = "Other Project")

            val params = buildJsonObject {
                put("operation", "create")
                put("containerType", "task")
                put("projectId", projectId.toString()) // Shared default
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("title", "Task 1")
                        // Uses shared projectId
                    })
                    add(buildJsonObject {
                        put("title", "Task 2")
                        put("projectId", otherProjectId.toString()) // Override
                    })
                })
            }

            // Mock both project lookups
            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(mockProject)
            coEvery { mockProjectRepository.getById(otherProjectId) } returns Result.Success(otherProject)

            val task1 = mockTask.copy(id = UUID.randomUUID(), title = "Task 1", projectId = projectId)
            val task2 = mockTask.copy(id = UUID.randomUUID(), title = "Task 2", projectId = otherProjectId)

            coEvery { mockTaskRepository.create(match { it.projectId == projectId }) } returns Result.Success(task1)
            coEvery { mockTaskRepository.create(match { it.projectId == otherProjectId }) } returns Result.Success(task2)

            val result = tool.execute(params, context)

            val jsonResponse = result.jsonObject
            assertTrue(jsonResponse["success"]?.jsonPrimitive?.boolean == true)
            val data = jsonResponse["data"]!!.jsonObject

            assertEquals(2, data["created"]?.jsonPrimitive?.int)
        }
    }

    @Nested
    inner class UpdateOperationTests {
        @Test
        fun `should update task successfully`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "update")
                put("containerType", "task")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("id", taskId.toString())
                        put("title", "Updated Task")
                        put("summary", "Updated summary")
                    })
                })
            }

            val updatedTask = mockTask.copy(title = "Updated Task", summary = "Updated summary")

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)
            coEvery { mockTaskRepository.update(any()) } returns Result.Success(updatedTask)
            // Mock dependency check for status validation
            every { mockDependencyRepository.findByToTaskId(taskId) } returns emptyList()

            val result = tool.execute(params, context)

            val jsonResponse = result.jsonObject
            assertTrue(jsonResponse["success"]?.jsonPrimitive?.boolean == true)
            val data = jsonResponse["data"]!!.jsonObject

            assertEquals(1, data["updated"]?.jsonPrimitive?.int)
            assertEquals(0, data["failed"]?.jsonPrimitive?.int)

            val items = data["items"]!!.jsonArray
            assertEquals(1, items.size)
            assertEquals(taskId.toString(), items[0].jsonObject["id"]?.jsonPrimitive?.content)
        }

        @Test
        fun `should update feature successfully`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "update")
                put("containerType", "feature")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("id", featureId.toString())
                        put("name", "Updated Feature")
                        put("summary", "Updated summary")
                    })
                })
            }

            val updatedFeature = mockFeature.copy(name = "Updated Feature", summary = "Updated summary")

            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(mockFeature)
            coEvery { mockFeatureRepository.update(any()) } returns Result.Success(updatedFeature)

            val result = tool.execute(params, context)

            val jsonResponse = result.jsonObject
            assertTrue(jsonResponse["success"]?.jsonPrimitive?.boolean == true)
            val data = jsonResponse["data"]!!.jsonObject

            assertEquals(1, data["updated"]?.jsonPrimitive?.int)
            assertEquals(0, data["failed"]?.jsonPrimitive?.int)

            val items = data["items"]!!.jsonArray
            assertEquals(1, items.size)
            assertEquals(featureId.toString(), items[0].jsonObject["id"]?.jsonPrimitive?.content)
        }

        @Test
        fun `should update project successfully`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "update")
                put("containerType", "project")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("id", projectId.toString())
                        put("name", "Updated Project")
                        put("summary", "Updated summary")
                    })
                })
            }

            val updatedProject = mockProject.copy(name = "Updated Project", summary = "Updated summary")

            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(mockProject)
            coEvery { mockProjectRepository.update(any()) } returns Result.Success(updatedProject)

            val result = tool.execute(params, context)

            val jsonResponse = result.jsonObject
            assertTrue(jsonResponse["success"]?.jsonPrimitive?.boolean == true)
            val data = jsonResponse["data"]!!.jsonObject

            assertEquals(1, data["updated"]?.jsonPrimitive?.int)
            assertEquals(0, data["failed"]?.jsonPrimitive?.int)

            val items = data["items"]!!.jsonArray
            assertEquals(1, items.size)
            assertEquals(projectId.toString(), items[0].jsonObject["id"]?.jsonPrimitive?.content)
        }

        @Test
        fun `should validate status transitions during update`() = runBlocking {
            val completedTask = mockTask.copy(status = TaskStatus.COMPLETED)

            val params = buildJsonObject {
                put("operation", "update")
                put("containerType", "task")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("id", taskId.toString())
                        put("status", "pending") // Invalid: can't go from completed back to pending
                    })
                })
            }

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(completedTask)
            every { mockDependencyRepository.findByToTaskId(taskId) } returns emptyList()

            val result = tool.execute(params, context)

            // Batch API returns success=true with validation failure in failures array
            val jsonResponse = result.jsonObject
            assertTrue(jsonResponse["success"]?.jsonPrimitive?.boolean == true)
            val data = jsonResponse["data"]!!.jsonObject
            assertEquals(0, data["updated"]?.jsonPrimitive?.int)
            assertEquals(1, data["failed"]?.jsonPrimitive?.int)
            val failures = data["failures"]!!.jsonArray
            assertEquals(1, failures.size)
            val failureError = failures[0].jsonObject["error"]?.jsonObject
            assertEquals(ErrorCodes.VALIDATION_ERROR, failureError?.get("code")?.jsonPrimitive?.content)
        }

        @Test
        fun `should handle batch update with partial failures`() = runBlocking {
            val task2Id = UUID.randomUUID()
            val task2 = mockTask.copy(id = task2Id, title = "Task 2")

            val params = buildJsonObject {
                put("operation", "update")
                put("containerType", "task")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("id", taskId.toString())
                        put("title", "Updated Task 1")
                    })
                    add(buildJsonObject {
                        put("id", task2Id.toString())
                        put("title", "Updated Task 2")
                    })
                })
            }

            val updatedTask1 = mockTask.copy(title = "Updated Task 1")

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)
            coEvery { mockTaskRepository.getById(task2Id) } returns
                Result.Error(RepositoryError.NotFound(task2Id, EntityType.TASK, "Not found"))
            coEvery { mockTaskRepository.update(any()) } returns Result.Success(updatedTask1)
            every { mockDependencyRepository.findByToTaskId(any()) } returns emptyList()

            val result = tool.execute(params, context)

            val jsonResponse = result.jsonObject
            assertTrue(jsonResponse["success"]?.jsonPrimitive?.boolean == true) // Partial success
            val data = jsonResponse["data"]!!.jsonObject

            assertEquals(1, data["updated"]?.jsonPrimitive?.int)
            assertEquals(1, data["failed"]?.jsonPrimitive?.int)

            val failures = data["failures"]?.jsonArray
            assertEquals(1, failures?.size)
        }

        @Test
        fun `should update task status with validation`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "update")
                put("containerType", "task")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("id", taskId.toString())
                        put("status", "in-progress")
                    })
                })
            }

            val updatedTask = mockTask.copy(status = TaskStatus.IN_PROGRESS)

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)
            coEvery { mockTaskRepository.update(any()) } returns Result.Success(updatedTask)
            every { mockDependencyRepository.findByToTaskId(taskId) } returns emptyList()

            val result = tool.execute(params, context)

            val jsonResponse = result.jsonObject
            assertTrue(jsonResponse["success"]?.jsonPrimitive?.boolean == true)
            val data = jsonResponse["data"]!!.jsonObject

            assertEquals(1, data["updated"]?.jsonPrimitive?.int)
        }

        @Test
        fun `should update multiple fields at once`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "update")
                put("containerType", "task")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("id", taskId.toString())
                        put("title", "Updated Title")
                        put("summary", "Updated Summary")
                        put("priority", "low")
                        put("complexity", 3)
                        put("status", "in-progress")
                    })
                })
            }

            val updatedTask = mockTask.copy(
                title = "Updated Title",
                summary = "Updated Summary",
                priority = Priority.LOW,
                complexity = 3,
                status = TaskStatus.IN_PROGRESS
            )

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)
            coEvery { mockTaskRepository.update(any()) } returns Result.Success(updatedTask)
            every { mockDependencyRepository.findByToTaskId(taskId) } returns emptyList()

            val result = tool.execute(params, context)

            val jsonResponse = result.jsonObject
            assertTrue(jsonResponse["success"]?.jsonPrimitive?.boolean == true)
        }

        @Test
        fun `should fail update if entity not found`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "update")
                put("containerType", "task")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("id", taskId.toString())
                        put("title", "Updated Task")
                    })
                })
            }

            coEvery { mockTaskRepository.getById(taskId) } returns
                Result.Error(RepositoryError.NotFound(taskId, EntityType.TASK, "Not found"))

            val result = tool.execute(params, context)

            val jsonResponse = result.jsonObject
            assertTrue(jsonResponse["success"]?.jsonPrimitive?.boolean == true) // Batch API returns success with failures array
            val data = jsonResponse["data"]!!.jsonObject

            assertEquals(0, data["updated"]?.jsonPrimitive?.int)
            assertEquals(1, data["failed"]?.jsonPrimitive?.int)
        }

        @Test
        fun `should preserve unchanged fields during update`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "update")
                put("containerType", "task")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("id", taskId.toString())
                        put("title", "Updated Title")
                        // Other fields not specified should remain unchanged
                    })
                })
            }

            val updatedTask = mockTask.copy(title = "Updated Title")

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)
            coEvery { mockTaskRepository.update(match {
                it.summary == mockTask.summary &&
                it.priority == mockTask.priority &&
                it.complexity == mockTask.complexity
            }) } returns Result.Success(updatedTask)
            every { mockDependencyRepository.findByToTaskId(taskId) } returns emptyList()

            val result = tool.execute(params, context)

            val jsonResponse = result.jsonObject
            assertTrue(jsonResponse["success"]?.jsonPrimitive?.boolean == true)
        }
    }

    @Nested
    inner class DeleteOperationTests {
        @Test
        fun `should delete task successfully`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "delete")
                put("containerType", "task")
                put("ids", buildJsonArray {
                    add(taskId.toString())
                })
            }

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)
            every { mockDependencyRepository.findByTaskId(taskId) } returns emptyList()
            coEvery { mockSectionRepository.getSectionsForEntity(any(), any()) } returns Result.Success(emptyList())
            coEvery { mockTaskRepository.delete(taskId) } returns Result.Success(true)

            val result = tool.execute(params, context)

            val jsonResponse = result.jsonObject
            assertTrue(jsonResponse["success"]?.jsonPrimitive?.boolean == true)
            val data = jsonResponse["data"]!!.jsonObject

            assertEquals(1, data["deleted"]?.jsonPrimitive?.int)
            assertEquals(0, data["failed"]?.jsonPrimitive?.int)

            val ids = data["ids"]!!.jsonArray
            assertEquals(1, ids.size)
            assertEquals(taskId.toString(), ids[0].jsonPrimitive.content)
        }

        @Test
        fun `should delete feature successfully`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "delete")
                put("containerType", "feature")
                put("ids", buildJsonArray {
                    add(featureId.toString())
                })
            }

            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(mockFeature)
            coEvery { mockTaskRepository.findByFeature(featureId) } returns Result.Success(emptyList())
            coEvery { mockSectionRepository.getSectionsForEntity(any(), any()) } returns Result.Success(emptyList())
            coEvery { mockFeatureRepository.delete(featureId) } returns Result.Success(true)

            val result = tool.execute(params, context)

            val jsonResponse = result.jsonObject
            assertTrue(jsonResponse["success"]?.jsonPrimitive?.boolean == true)
            val data = jsonResponse["data"]!!.jsonObject

            assertEquals(1, data["deleted"]?.jsonPrimitive?.int)

            val ids = data["ids"]!!.jsonArray
            assertEquals(featureId.toString(), ids[0].jsonPrimitive.content)
        }

        @Test
        fun `should delete project successfully`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "delete")
                put("containerType", "project")
                put("ids", buildJsonArray {
                    add(projectId.toString())
                })
            }

            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(mockProject)
            coEvery { mockFeatureRepository.findByProject(projectId) } returns Result.Success(emptyList())
            coEvery { mockTaskRepository.findByProject(projectId, any()) } returns Result.Success(emptyList())
            coEvery { mockSectionRepository.getSectionsForEntity(any(), any()) } returns Result.Success(emptyList())
            coEvery { mockProjectRepository.delete(projectId) } returns Result.Success(true)

            val result = tool.execute(params, context)

            val jsonResponse = result.jsonObject
            assertTrue(jsonResponse["success"]?.jsonPrimitive?.boolean == true)
            val data = jsonResponse["data"]!!.jsonObject

            assertEquals(1, data["deleted"]?.jsonPrimitive?.int)

            val ids = data["ids"]!!.jsonArray
            assertEquals(projectId.toString(), ids[0].jsonPrimitive.content)
        }

        @Test
        fun `should delete multiple entities`() = runBlocking {
            val task2Id = UUID.randomUUID()
            val task2 = mockTask.copy(id = task2Id)

            val params = buildJsonObject {
                put("operation", "delete")
                put("containerType", "task")
                put("ids", buildJsonArray {
                    add(taskId.toString())
                    add(task2Id.toString())
                })
            }

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)
            coEvery { mockTaskRepository.getById(task2Id) } returns Result.Success(task2)
            every { mockDependencyRepository.findByTaskId(any()) } returns emptyList()
            coEvery { mockSectionRepository.getSectionsForEntity(any(), any()) } returns Result.Success(emptyList())
            coEvery { mockTaskRepository.delete(taskId) } returns Result.Success(true)
            coEvery { mockTaskRepository.delete(task2Id) } returns Result.Success(true)

            val result = tool.execute(params, context)

            val jsonResponse = result.jsonObject
            assertTrue(jsonResponse["success"]?.jsonPrimitive?.boolean == true)
            val data = jsonResponse["data"]!!.jsonObject

            assertEquals(2, data["deleted"]?.jsonPrimitive?.int)
            assertEquals(0, data["failed"]?.jsonPrimitive?.int)

            val ids = data["ids"]!!.jsonArray
            assertEquals(2, ids.size)
        }

        @Test
        fun `should handle partial batch delete failures`() = runBlocking {
            val task2Id = UUID.randomUUID()

            val params = buildJsonObject {
                put("operation", "delete")
                put("containerType", "task")
                put("ids", buildJsonArray {
                    add(taskId.toString())
                    add(task2Id.toString())
                })
            }

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)
            coEvery { mockTaskRepository.getById(task2Id) } returns
                Result.Error(RepositoryError.NotFound(task2Id, EntityType.TASK, "Not found"))
            every { mockDependencyRepository.findByTaskId(taskId) } returns emptyList()
            coEvery { mockSectionRepository.getSectionsForEntity(any(), any()) } returns Result.Success(emptyList())
            coEvery { mockTaskRepository.delete(taskId) } returns Result.Success(true)

            val result = tool.execute(params, context)

            val jsonResponse = result.jsonObject
            assertTrue(jsonResponse["success"]?.jsonPrimitive?.boolean == true) // Partial success
            val data = jsonResponse["data"]!!.jsonObject

            assertEquals(1, data["deleted"]?.jsonPrimitive?.int)
            assertEquals(1, data["failed"]?.jsonPrimitive?.int)

            val failures = data["failures"]?.jsonArray
            assertEquals(1, failures?.size)
        }

        @Test
        fun `should support force delete`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "delete")
                put("containerType", "task")
                put("force", true)
                put("ids", buildJsonArray {
                    add(taskId.toString())
                })
            }

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)
            every { mockDependencyRepository.findByTaskId(taskId) } returns emptyList()
            coEvery { mockSectionRepository.getSectionsForEntity(any(), any()) } returns Result.Success(emptyList())
            coEvery { mockTaskRepository.delete(taskId) } returns Result.Success(true)

            val result = tool.execute(params, context)

            val jsonResponse = result.jsonObject
            assertTrue(jsonResponse["success"]?.jsonPrimitive?.boolean == true)
            val data = jsonResponse["data"]!!.jsonObject

            assertEquals(1, data["deleted"]?.jsonPrimitive?.int)
        }

        @Test
        fun `should fail delete if entity not found`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "delete")
                put("containerType", "task")
                put("ids", buildJsonArray {
                    add(taskId.toString())
                })
            }

            coEvery { mockTaskRepository.getById(taskId) } returns
                Result.Error(RepositoryError.NotFound(taskId, EntityType.TASK, "Not found"))
            every { mockDependencyRepository.findByTaskId(any()) } returns emptyList()
            coEvery { mockSectionRepository.getSectionsForEntity(any(), any()) } returns Result.Success(emptyList())

            val result = tool.execute(params, context)

            val jsonResponse = result.jsonObject
            assertTrue(jsonResponse["success"]?.jsonPrimitive?.boolean == true) // Batch returns success with failures
            val data = jsonResponse["data"]!!.jsonObject

            assertEquals(0, data["deleted"]?.jsonPrimitive?.int)
            assertEquals(1, data["failed"]?.jsonPrimitive?.int)
        }
    }

    @Nested
    inner class StatusParsingTests {
        @Test
        fun `should parse task status correctly`() {
            val validStatuses = mapOf(
                "pending" to TaskStatus.PENDING,
                "in-progress" to TaskStatus.IN_PROGRESS,
                "completed" to TaskStatus.COMPLETED,
                "cancelled" to TaskStatus.CANCELLED,
                "backlog" to TaskStatus.BACKLOG
            )

            validStatuses.forEach { (statusString, expectedEnum) ->
                val params = buildJsonObject {
                    put("operation", "create")
                    put("containerType", "task")
                    put("containers", buildJsonArray {
                        add(buildJsonObject {
                            put("title", "Test Task")
                            put("status", statusString)
                        })
                    })
                }

                assertDoesNotThrow({
                    tool.validateParams(params)
                }, "Status '$statusString' should parse correctly")
            }
        }

        @Test
        fun `should parse feature status correctly`() {
            val validStatuses = listOf(
                "draft", "planning", "in-development", "testing", "validating", "completed", "archived", "pending-review", "blocked", "on-hold"
            )

            validStatuses.forEach { statusString ->
                val params = buildJsonObject {
                    put("operation", "create")
                    put("containerType", "feature")
                    put("containers", buildJsonArray {
                        add(buildJsonObject {
                            put("name", "Test Feature")
                            put("status", statusString)
                        })
                    })
                }

                assertDoesNotThrow({
                    tool.validateParams(params)
                }, "Status '$statusString' should parse correctly")
            }
        }

        @Test
        fun `should parse project status correctly`() {
            val validStatuses = listOf(
                "planning", "in-development", "on-hold", "deployed", "cancelled", "completed", "archived"
            )

            validStatuses.forEach { statusString ->
                val params = buildJsonObject {
                    put("operation", "create")
                    put("containerType", "project")
                    put("containers", buildJsonArray {
                        add(buildJsonObject {
                            put("name", "Test Project")
                            put("status", statusString)
                        })
                    })
                }

                assertDoesNotThrow({
                    tool.validateParams(params)
                }, "Status '$statusString' should parse correctly")
            }
        }

        @Test
        fun `should handle case-insensitive status parsing`() {
            val statusVariants = listOf("pending", "PENDING", "Pending", "PeNdInG")

            statusVariants.forEach { statusVariant ->
                val params = buildJsonObject {
                    put("operation", "create")
                    put("containerType", "task")
                    put("containers", buildJsonArray {
                        add(buildJsonObject {
                            put("title", "Test Task")
                            put("status", statusVariant)
                        })
                    })
                }

                assertDoesNotThrow({
                    tool.validateParams(params)
                }, "Status '$statusVariant' should be case-insensitive")
            }
        }
    }

    @Nested
    inner class BatchUpdateOperationTests {
        @Test
        fun `should update multiple tasks in batch`() = runBlocking {
            val task2Id = UUID.randomUUID()
            val task2 = mockTask.copy(id = task2Id, title = "Task 2")

            val params = buildJsonObject {
                put("operation", "update")
                put("containerType", "task")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("id", taskId.toString())
                        put("title", "Updated Task 1")
                        put("priority", "low")
                    })
                    add(buildJsonObject {
                        put("id", task2Id.toString())
                        put("title", "Updated Task 2")
                        put("complexity", 8)
                    })
                })
            }

            val updatedTask1 = mockTask.copy(title = "Updated Task 1", priority = Priority.LOW)
            val updatedTask2 = task2.copy(title = "Updated Task 2", complexity = 8)

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)
            coEvery { mockTaskRepository.getById(task2Id) } returns Result.Success(task2)
            coEvery { mockTaskRepository.update(match { it.id == taskId }) } returns Result.Success(updatedTask1)
            coEvery { mockTaskRepository.update(match { it.id == task2Id }) } returns Result.Success(updatedTask2)
            every { mockDependencyRepository.findByToTaskId(any()) } returns emptyList()

            val result = tool.execute(params, context)

            val jsonResponse = result.jsonObject
            assertTrue(jsonResponse["success"]?.jsonPrimitive?.boolean == true)
            val data = jsonResponse["data"]!!.jsonObject

            assertEquals(2, data["updated"]?.jsonPrimitive?.int)
            assertEquals(0, data["failed"]?.jsonPrimitive?.int)

            val items = data["items"]!!.jsonArray
            assertEquals(2, items.size)
        }

        @Test
        fun `should handle batch update with mixed entity types validation error`() {
            // This should be caught at validation level - all items must be same containerType
            val params = buildJsonObject {
                put("operation", "update")
                put("containerType", "task")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("id", taskId.toString())
                        put("title", "Updated Task")
                    })
                    add(buildJsonObject {
                        put("id", featureId.toString())
                        put("name", "Updated Feature") // Wrong field for task
                    })
                })
            }

            // Should not throw validation error - just updates what it can
            assertDoesNotThrow {
                tool.validateParams(params)
            }
        }

        @Test
        fun `should batch update with shared status change`() = runBlocking {
            val task2Id = UUID.randomUUID()
            val task2 = mockTask.copy(id = task2Id, title = "Task 2")

            val params = buildJsonObject {
                put("operation", "update")
                put("containerType", "task")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("id", taskId.toString())
                        put("status", "in-progress")
                    })
                    add(buildJsonObject {
                        put("id", task2Id.toString())
                        put("status", "in-progress")
                    })
                })
            }

            val updatedTask1 = mockTask.copy(status = TaskStatus.IN_PROGRESS)
            val updatedTask2 = task2.copy(status = TaskStatus.IN_PROGRESS)

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)
            coEvery { mockTaskRepository.getById(task2Id) } returns Result.Success(task2)
            coEvery { mockTaskRepository.update(match { it.id == taskId }) } returns Result.Success(updatedTask1)
            coEvery { mockTaskRepository.update(match { it.id == task2Id }) } returns Result.Success(updatedTask2)
            every { mockDependencyRepository.findByToTaskId(any()) } returns emptyList()

            val result = tool.execute(params, context)

            val jsonResponse = result.jsonObject
            assertTrue(jsonResponse["success"]?.jsonPrimitive?.boolean == true)
            val data = jsonResponse["data"]!!.jsonObject

            assertEquals(2, data["updated"]?.jsonPrimitive?.int)
        }

        @Test
        fun `should handle empty containers array for update`() {
            val params = buildJsonObject {
                put("operation", "update")
                put("containerType", "task")
                put("containers", buildJsonArray {
                    // Empty array
                })
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("container") || exception.message!!.contains("empty"))
        }

        @Test
        fun `should batch update features with different priorities`() = runBlocking {
            val feature2Id = UUID.randomUUID()
            val feature2 = mockFeature.copy(id = feature2Id, name = "Feature 2")

            val params = buildJsonObject {
                put("operation", "update")
                put("containerType", "feature")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("id", featureId.toString())
                        put("priority", "high")
                    })
                    add(buildJsonObject {
                        put("id", feature2Id.toString())
                        put("priority", "low")
                    })
                })
            }

            val updatedFeature1 = mockFeature.copy(priority = Priority.HIGH)
            val updatedFeature2 = feature2.copy(priority = Priority.LOW)

            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(mockFeature)
            coEvery { mockFeatureRepository.getById(feature2Id) } returns Result.Success(feature2)
            coEvery { mockFeatureRepository.update(match { it.id == featureId }) } returns Result.Success(updatedFeature1)
            coEvery { mockFeatureRepository.update(match { it.id == feature2Id }) } returns Result.Success(updatedFeature2)

            val result = tool.execute(params, context)

            val jsonResponse = result.jsonObject
            assertTrue(jsonResponse["success"]?.jsonPrimitive?.boolean == true)
            val data = jsonResponse["data"]!!.jsonObject

            assertEquals(2, data["updated"]?.jsonPrimitive?.int)
        }

        @Test
        fun `should handle validation failure in batch update`() = runBlocking {
            val completedTask = mockTask.copy(status = TaskStatus.COMPLETED)

            val params = buildJsonObject {
                put("operation", "update")
                put("containerType", "task")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("id", taskId.toString())
                        put("status", "pending") // Invalid transition from completed
                    })
                })
            }

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(completedTask)
            every { mockDependencyRepository.findByToTaskId(taskId) } returns emptyList()

            val result = tool.execute(params, context)

            // Batch API returns success=true with validation failure in failures array
            val jsonResponse = result.jsonObject
            assertTrue(jsonResponse["success"]?.jsonPrimitive?.boolean == true)
            val data = jsonResponse["data"]!!.jsonObject
            assertEquals(0, data["updated"]?.jsonPrimitive?.int)
            assertEquals(1, data["failed"]?.jsonPrimitive?.int)
        }
    }

    @Nested
    inner class UserSummaryTests {
        @Test
        fun `should provide clear summary for create operation`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "create")
                put("containerType", "task")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("title", "New Task")
                    })
                })
            }

            coEvery { mockTaskRepository.create(any()) } returns Result.Success(mockTask)

            val result = tool.execute(params, context)

            val jsonResponse = result.jsonObject
            assertTrue(jsonResponse["success"]?.jsonPrimitive?.boolean == true)

            val summary = tool.userSummary(params, result, false)
            // Single-item create shows name: "Created task 'New Task' (xxxxxxxx)"
            assertTrue(summary.contains("task"), "Summary should contain container type: $summary")
            assertTrue(summary.contains("Created"), "Summary should contain 'Created': $summary")
        }

        @Test
        fun `should provide clear summary for batch create`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "create")
                put("containerType", "task")
                put("containers", buildJsonArray {
                    add(buildJsonObject { put("title", "Task 1") })
                    add(buildJsonObject { put("title", "Task 2") })
                    add(buildJsonObject { put("title", "Task 3") })
                })
            }

            val task1 = mockTask.copy(id = UUID.randomUUID(), title = "Task 1")
            val task2 = mockTask.copy(id = UUID.randomUUID(), title = "Task 2")
            val task3 = mockTask.copy(id = UUID.randomUUID(), title = "Task 3")

            coEvery { mockTaskRepository.create(match { it.title == "Task 1" }) } returns Result.Success(task1)
            coEvery { mockTaskRepository.create(match { it.title == "Task 2" }) } returns Result.Success(task2)
            coEvery { mockTaskRepository.create(match { it.title == "Task 3" }) } returns Result.Success(task3)

            val result = tool.execute(params, context)

            val jsonResponse = result.jsonObject
            assertTrue(jsonResponse["success"]?.jsonPrimitive?.boolean == true)

            val summary = tool.userSummary(params, result, false)
            assertTrue(summary.contains("3"))
        }

        @Test
        fun `should provide clear summary for update operation`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "update")
                put("containerType", "task")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("id", taskId.toString())
                        put("title", "Updated Task")
                    })
                })
            }

            val updatedTask = mockTask.copy(title = "Updated Task")
            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)
            coEvery { mockTaskRepository.update(any()) } returns Result.Success(updatedTask)
            every { mockDependencyRepository.findByToTaskId(taskId) } returns emptyList()

            val result = tool.execute(params, context)

            val jsonResponse = result.jsonObject
            assertTrue(jsonResponse["success"]?.jsonPrimitive?.boolean == true)

            val summary = tool.userSummary(params, result, false)
            // Single-item update shows: "Updated task (xxxxxxxx)"
            assertTrue(summary.contains("Updated"), "Summary should contain 'Updated': $summary")
            assertTrue(summary.contains("task"), "Summary should contain container type: $summary")
        }

        @Test
        fun `should provide clear summary for delete operation`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "delete")
                put("containerType", "task")
                put("ids", buildJsonArray {
                    add(taskId.toString())
                })
            }

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)
            every { mockDependencyRepository.findByTaskId(taskId) } returns emptyList()
            coEvery { mockSectionRepository.getSectionsForEntity(EntityType.TASK, taskId) } returns Result.Success(emptyList())
            coEvery { mockTaskRepository.delete(taskId) } returns Result.Success(true)

            val result = tool.execute(params, context)

            val jsonResponse = result.jsonObject
            assertTrue(jsonResponse["success"]?.jsonPrimitive?.boolean == true)

            val summary = tool.userSummary(params, result, false)
            // Single-item delete shows: "Deleted task (xxxxxxxx)"
            assertTrue(summary.contains("Deleted"), "Summary should contain 'Deleted': $summary")
            assertTrue(summary.contains("task"), "Summary should contain container type: $summary")
        }

        @Test
        fun `should provide summary with failure counts`() = runBlocking {
            val task2Id = UUID.randomUUID()

            val params = buildJsonObject {
                put("operation", "create")
                put("containerType", "task")
                put("containers", buildJsonArray {
                    add(buildJsonObject { put("title", "Task 1") })
                    add(buildJsonObject { put("title", "Task 2") })
                })
            }

            val task1 = mockTask.copy(id = UUID.randomUUID(), title = "Task 1")

            coEvery { mockTaskRepository.create(match { it.title == "Task 1" }) } returns Result.Success(task1)
            coEvery { mockTaskRepository.create(match { it.title == "Task 2" }) } returns
                Result.Error(RepositoryError.DatabaseError("Error"))

            val result = tool.execute(params, context)

            val jsonResponse = result.jsonObject
            assertTrue(jsonResponse["success"]?.jsonPrimitive?.boolean == true)

            val summary = tool.userSummary(params, result, false)
            assertTrue(summary.contains("1") && (summary.contains("failed") || summary.contains("error")))
        }
    }

    @Nested
    inner class ForceDeleteCascadeTests {
        @Test
        fun `should force delete with cascade`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "delete")
                put("containerType", "project")
                put("force", true)
                put("ids", buildJsonArray {
                    add(projectId.toString())
                })
            }

            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(mockProject)
            coEvery { mockFeatureRepository.findByProject(projectId) } returns Result.Success(emptyList())
            coEvery { mockTaskRepository.findByProject(projectId, any()) } returns Result.Success(emptyList())
            coEvery { mockSectionRepository.getSectionsForEntity(any(), any()) } returns Result.Success(emptyList())
            coEvery { mockProjectRepository.delete(projectId) } returns Result.Success(true)

            val result = tool.execute(params, context)

            val jsonResponse = result.jsonObject
            assertTrue(jsonResponse["success"]?.jsonPrimitive?.boolean == true)
            val data = jsonResponse["data"]!!.jsonObject

            assertEquals(1, data["deleted"]?.jsonPrimitive?.int)
        }

        @Test
        fun `should fail non-force delete if entity has children`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "delete")
                put("containerType", "project")
                put("force", false)
                put("ids", buildJsonArray {
                    add(projectId.toString())
                })
            }

            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(mockProject)
            coEvery { mockFeatureRepository.findByProject(projectId) } returns Result.Success(emptyList())
            coEvery { mockTaskRepository.findByProject(projectId, any()) } returns Result.Success(emptyList())
            coEvery { mockSectionRepository.getSectionsForEntity(any(), any()) } returns Result.Success(emptyList())
            coEvery { mockProjectRepository.delete(projectId) } returns
                Result.Error(RepositoryError.ValidationError("Has child entities"))

            val result = tool.execute(params, context)

            val jsonResponse = result.jsonObject
            assertTrue(jsonResponse["success"]?.jsonPrimitive?.boolean == true) // Batch returns success with failures
            val data = jsonResponse["data"]!!.jsonObject

            assertEquals(0, data["deleted"]?.jsonPrimitive?.int)
            assertEquals(1, data["failed"]?.jsonPrimitive?.int)
        }

        @Test
        fun `should delete feature with force flag`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "delete")
                put("containerType", "feature")
                put("force", true)
                put("ids", buildJsonArray {
                    add(featureId.toString())
                })
            }

            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(mockFeature)
            coEvery { mockTaskRepository.findByFeature(featureId) } returns Result.Success(emptyList())
            coEvery { mockSectionRepository.getSectionsForEntity(any(), any()) } returns Result.Success(emptyList())
            coEvery { mockFeatureRepository.delete(featureId) } returns Result.Success(true)

            val result = tool.execute(params, context)

            val jsonResponse = result.jsonObject
            assertTrue(jsonResponse["success"]?.jsonPrimitive?.boolean == true)
            val data = jsonResponse["data"]!!.jsonObject

            assertEquals(1, data["deleted"]?.jsonPrimitive?.int)
        }

        @Test
        fun `should batch delete with force flag`() = runBlocking {
            val project2Id = UUID.randomUUID()
            val project2 = mockProject.copy(id = project2Id)

            val params = buildJsonObject {
                put("operation", "delete")
                put("containerType", "project")
                put("force", true)
                put("ids", buildJsonArray {
                    add(projectId.toString())
                    add(project2Id.toString())
                })
            }

            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(mockProject)
            coEvery { mockProjectRepository.getById(project2Id) } returns Result.Success(project2)
            coEvery { mockFeatureRepository.findByProject(any()) } returns Result.Success(emptyList())
            coEvery { mockTaskRepository.findByProject(any(), any()) } returns Result.Success(emptyList())
            coEvery { mockSectionRepository.getSectionsForEntity(any(), any()) } returns Result.Success(emptyList())
            coEvery { mockProjectRepository.delete(projectId) } returns Result.Success(true)
            coEvery { mockProjectRepository.delete(project2Id) } returns Result.Success(true)

            val result = tool.execute(params, context)

            val jsonResponse = result.jsonObject
            assertTrue(jsonResponse["success"]?.jsonPrimitive?.boolean == true)
            val data = jsonResponse["data"]!!.jsonObject

            assertEquals(2, data["deleted"]?.jsonPrimitive?.int)
        }
    }
}
