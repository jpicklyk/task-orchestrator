package io.github.jpicklyk.mcptask.application.tools.status

import io.github.jpicklyk.mcptask.application.service.progression.StatusProgressionService
import io.github.jpicklyk.mcptask.application.service.progression.NextStatusRecommendation
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.repository.*
import io.github.jpicklyk.mcptask.infrastructure.repository.RepositoryProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.*

class GetNextStatusToolTest {
    private lateinit var tool: GetNextStatusTool
    private lateinit var context: ToolExecutionContext
    private lateinit var mockStatusProgressionService: StatusProgressionService
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

    private lateinit var mockTask: Task
    private lateinit var mockFeature: Feature
    private lateinit var mockProject: Project

    @BeforeEach
    fun setup() {
        // Create mock service
        mockStatusProgressionService = mockk()

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
            status = TaskStatus.IN_PROGRESS,
            priority = Priority.HIGH,
            complexity = 7,
            tags = listOf("backend", "api"),
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
            projectId = projectId,
            tags = listOf("api"),
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )

        mockProject = Project(
            id = projectId,
            name = "Test Project",
            summary = "Test project summary",
            status = ProjectStatus.IN_DEVELOPMENT,
            tags = listOf("kotlin"),
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )

        // Create execution context
        context = ToolExecutionContext(mockRepositoryProvider)

        // Create tool
        tool = GetNextStatusTool(mockStatusProgressionService)
    }

    @Nested
    inner class ValidationTests {
        @Test
        fun `should require containerId parameter`() {
            val params = buildJsonObject {
                put("containerType", "task")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("containerId"))
        }

        @Test
        fun `should require containerType parameter`() {
            val params = buildJsonObject {
                put("containerId", taskId.toString())
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("containerType"))
        }

        @Test
        fun `should reject invalid containerId format`() {
            val params = buildJsonObject {
                put("containerId", "not-a-uuid")
                put("containerType", "task")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("Invalid containerId"))
        }

        @Test
        fun `should reject invalid containerType`() {
            val params = buildJsonObject {
                put("containerId", taskId.toString())
                put("containerType", "invalid")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("Invalid containerType"))
        }

        @Test
        fun `should accept valid task params`() {
            val params = buildJsonObject {
                put("containerId", taskId.toString())
                put("containerType", "task")
            }

            assertDoesNotThrow {
                tool.validateParams(params)
            }
        }

        @Test
        fun `should accept valid feature params`() {
            val params = buildJsonObject {
                put("containerId", featureId.toString())
                put("containerType", "feature")
            }

            assertDoesNotThrow {
                tool.validateParams(params)
            }
        }

        @Test
        fun `should accept valid project params`() {
            val params = buildJsonObject {
                put("containerId", projectId.toString())
                put("containerType", "project")
            }

            assertDoesNotThrow {
                tool.validateParams(params)
            }
        }

        @Test
        fun `should accept optional currentStatus parameter`() {
            val params = buildJsonObject {
                put("containerId", taskId.toString())
                put("containerType", "task")
                put("currentStatus", "in-progress")
            }

            assertDoesNotThrow {
                tool.validateParams(params)
            }
        }

        @Test
        fun `should accept optional tags parameter`() {
            val params = buildJsonObject {
                put("containerId", taskId.toString())
                put("containerType", "task")
                put("tags", JsonArray(listOf(JsonPrimitive("bug"), JsonPrimitive("backend"))))
            }

            assertDoesNotThrow {
                tool.validateParams(params)
            }
        }

        @Test
        fun `should reject non-array tags parameter`() {
            val params = buildJsonObject {
                put("containerId", taskId.toString())
                put("containerType", "task")
                put("tags", "not-an-array")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("tags") && exception.message!!.contains("array"))
        }

        @Test
        fun `should reject non-string tags elements`() {
            val params = buildJsonObject {
                put("containerId", taskId.toString())
                put("containerType", "task")
                put("tags", JsonArray(listOf(JsonPrimitive(123), JsonPrimitive(true))))
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("tags") && exception.message!!.contains("strings"))
        }
    }

    @Nested
    inner class ExecutionTests {
        @Test
        fun `should return Ready recommendation for task`() = runBlocking {
            val params = buildJsonObject {
                put("containerId", taskId.toString())
                put("containerType", "task")
            }

            // Mock task repository
            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)

            // Mock service response
            val recommendation = NextStatusRecommendation.Ready(
                recommendedStatus = "testing",
                activeFlow = "default_flow",
                flowSequence = listOf("pending", "in-progress", "testing", "completed"),
                currentPosition = 1,
                matchedTags = emptyList(),
                reason = "Next status in default_flow workflow"
            )
            coEvery {
                mockStatusProgressionService.getNextStatus(
                    currentStatus = "in-progress",
                    containerType = "task",
                    tags = listOf("backend", "api"),
                    containerId = taskId
                )
            } returns recommendation

            // Execute tool
            val result = tool.execute(params, context) as JsonObject

            // Verify response
            assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
            val data = result["data"] as JsonObject
            assertEquals("Ready", data["recommendation"]?.jsonPrimitive?.content)
            assertEquals("testing", data["recommendedStatus"]?.jsonPrimitive?.content)
            assertEquals("in-progress", data["currentStatus"]?.jsonPrimitive?.content)
            assertEquals("default_flow", data["activeFlow"]?.jsonPrimitive?.content)
            assertEquals(1, data["currentPosition"]?.jsonPrimitive?.int)
        }

        @Test
        fun `should return Blocked recommendation for task`() = runBlocking {
            val params = buildJsonObject {
                put("containerId", taskId.toString())
                put("containerType", "task")
            }

            // Mock task repository
            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)

            // Mock service response
            val recommendation = NextStatusRecommendation.Blocked(
                currentStatus = "in-progress",
                blockers = listOf("Summary required", "3 subtasks incomplete"),
                activeFlow = "default_flow",
                flowSequence = listOf("pending", "in-progress", "testing", "completed"),
                currentPosition = 1
            )
            coEvery {
                mockStatusProgressionService.getNextStatus(
                    currentStatus = "in-progress",
                    containerType = "task",
                    tags = listOf("backend", "api"),
                    containerId = taskId
                )
            } returns recommendation

            // Execute tool
            val result = tool.execute(params, context) as JsonObject

            // Verify response
            assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
            val data = result["data"] as JsonObject
            assertEquals("Blocked", data["recommendation"]?.jsonPrimitive?.content)
            assertEquals("in-progress", data["currentStatus"]?.jsonPrimitive?.content)

            val blockers = data["blockers"] as JsonArray
            assertEquals(2, blockers.size)
            assertEquals("Summary required", blockers[0].jsonPrimitive.content)
        }

        @Test
        fun `should return Terminal recommendation for task`() = runBlocking {
            val params = buildJsonObject {
                put("containerId", taskId.toString())
                put("containerType", "task")
            }

            // Mock task with completed status
            val completedTask = mockTask.copy(status = TaskStatus.COMPLETED)
            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(completedTask)

            // Mock service response
            val recommendation = NextStatusRecommendation.Terminal(
                terminalStatus = "completed",
                activeFlow = "default_flow",
                reason = "Status 'completed' is terminal. No further progression available."
            )
            coEvery {
                mockStatusProgressionService.getNextStatus(
                    currentStatus = "completed",
                    containerType = "task",
                    tags = listOf("backend", "api"),
                    containerId = taskId
                )
            } returns recommendation

            // Execute tool
            val result = tool.execute(params, context) as JsonObject

            // Verify response
            assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
            val data = result["data"] as JsonObject
            assertEquals("Terminal", data["recommendation"]?.jsonPrimitive?.content)
            assertEquals("completed", data["currentStatus"]?.jsonPrimitive?.content)
            assertEquals("default_flow", data["activeFlow"]?.jsonPrimitive?.content)
        }

        @Test
        fun `should work with feature container`() = runBlocking {
            val params = buildJsonObject {
                put("containerId", featureId.toString())
                put("containerType", "feature")
            }

            // Mock feature repository
            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(mockFeature)

            // Mock service response
            val recommendation = NextStatusRecommendation.Ready(
                recommendedStatus = "testing",
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "completed"),
                currentPosition = 1,
                matchedTags = emptyList(),
                reason = "Next status in workflow"
            )
            coEvery {
                mockStatusProgressionService.getNextStatus(
                    currentStatus = "in-development",
                    containerType = "feature",
                    tags = listOf("api"),
                    containerId = featureId
                )
            } returns recommendation

            // Execute tool
            val result = tool.execute(params, context) as JsonObject

            // Verify response
            assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
            val data = result["data"] as JsonObject
            assertEquals("Ready", data["recommendation"]?.jsonPrimitive?.content)
            assertEquals("testing", data["recommendedStatus"]?.jsonPrimitive?.content)
        }

        @Test
        fun `should work with project container`() = runBlocking {
            val params = buildJsonObject {
                put("containerId", projectId.toString())
                put("containerType", "project")
            }

            // Mock project repository
            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(mockProject)

            // Mock service response
            val recommendation = NextStatusRecommendation.Ready(
                recommendedStatus = "active",
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "active", "completed"),
                currentPosition = 1,
                matchedTags = emptyList(),
                reason = "Next status in workflow"
            )
            coEvery {
                mockStatusProgressionService.getNextStatus(
                    currentStatus = "in-development",
                    containerType = "project",
                    tags = listOf("kotlin"),
                    containerId = projectId
                )
            } returns recommendation

            // Execute tool
            val result = tool.execute(params, context) as JsonObject

            // Verify response
            assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
            val data = result["data"] as JsonObject
            assertEquals("Ready", data["recommendation"]?.jsonPrimitive?.content)
        }

        @Test
        fun `should handle entity not found`() = runBlocking {
            val params = buildJsonObject {
                put("containerId", taskId.toString())
                put("containerType", "task")
            }

            // Mock task repository to return error
            coEvery { mockTaskRepository.getById(taskId) } returns Result.Error(
                RepositoryError.NotFound(taskId, EntityType.TASK, "Task not found")
            )

            // Execute tool
            val result = tool.execute(params, context) as JsonObject

            // Verify error response
            assertFalse(result["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(result["message"]?.jsonPrimitive?.content?.contains("not found") == true)
        }

        @Test
        fun `should override entity status when currentStatus provided`() = runBlocking {
            val params = buildJsonObject {
                put("containerId", taskId.toString())
                put("containerType", "task")
                put("currentStatus", "testing")
            }

            // Mock task repository
            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)

            // Mock service response - should be called with overridden status
            val recommendation = NextStatusRecommendation.Ready(
                recommendedStatus = "completed",
                activeFlow = "default_flow",
                flowSequence = listOf("pending", "in-progress", "testing", "completed"),
                currentPosition = 2,
                matchedTags = emptyList(),
                reason = "Next status in workflow"
            )
            coEvery {
                mockStatusProgressionService.getNextStatus(
                    currentStatus = "testing", // Overridden status
                    containerType = "task",
                    tags = listOf("backend", "api"),
                    containerId = taskId
                )
            } returns recommendation

            // Execute tool
            val result = tool.execute(params, context) as JsonObject

            // Verify response uses overridden status
            assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
            val data = result["data"] as JsonObject
            assertEquals("testing", data["currentStatus"]?.jsonPrimitive?.content)
        }

        @Test
        fun `should override entity tags when tags provided`() = runBlocking {
            val params = buildJsonObject {
                put("containerId", taskId.toString())
                put("containerType", "task")
                put("tags", JsonArray(listOf(JsonPrimitive("bug"), JsonPrimitive("hotfix"))))
            }

            // Mock task repository
            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)

            // Mock service response - should be called with overridden tags
            val recommendation = NextStatusRecommendation.Ready(
                recommendedStatus = "in-progress",
                activeFlow = "bug_fix_flow",
                flowSequence = listOf("reported", "triaged", "in-progress", "verified", "closed"),
                currentPosition = 1,
                matchedTags = listOf("bug"),
                reason = "Next status in bug_fix_flow"
            )
            coEvery {
                mockStatusProgressionService.getNextStatus(
                    currentStatus = "in-progress",
                    containerType = "task",
                    tags = listOf("bug", "hotfix"), // Overridden tags
                    containerId = taskId
                )
            } returns recommendation

            // Execute tool
            val result = tool.execute(params, context) as JsonObject

            // Verify response
            assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
            val data = result["data"] as JsonObject
            assertEquals("bug_fix_flow", data["activeFlow"]?.jsonPrimitive?.content)
        }
    }
}
