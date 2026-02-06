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

class RequestTransitionToolTest {
    private lateinit var tool: RequestTransitionTool
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

    private lateinit var pendingTask: Task
    private lateinit var inProgressTask: Task

    @BeforeEach
    fun setup() {
        mockStatusProgressionService = mockk()
        mockTaskRepository = mockk()
        mockFeatureRepository = mockk()
        mockProjectRepository = mockk()
        mockSectionRepository = mockk()
        mockDependencyRepository = mockk()
        mockTemplateRepository = mockk()
        mockRepositoryProvider = mockk()

        every { mockRepositoryProvider.taskRepository() } returns mockTaskRepository
        every { mockRepositoryProvider.featureRepository() } returns mockFeatureRepository
        every { mockRepositoryProvider.projectRepository() } returns mockProjectRepository
        every { mockRepositoryProvider.sectionRepository() } returns mockSectionRepository
        every { mockRepositoryProvider.dependencyRepository() } returns mockDependencyRepository
        every { mockRepositoryProvider.templateRepository() } returns mockTemplateRepository

        pendingTask = Task(
            id = taskId,
            title = "Test Task",
            description = "Test description",
            summary = "Test summary",
            status = TaskStatus.PENDING,
            priority = Priority.HIGH,
            complexity = 5,
            tags = listOf("backend"),
            featureId = featureId,
            projectId = projectId,
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )

        inProgressTask = pendingTask.copy(status = TaskStatus.IN_PROGRESS)

        context = ToolExecutionContext(mockRepositoryProvider)
        tool = RequestTransitionTool(mockStatusProgressionService)
    }

    @Nested
    inner class ValidationTests {
        @Test
        fun `should require containerId parameter`() {
            val params = buildJsonObject {
                put("containerType", "task")
                put("trigger", "start")
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
                put("trigger", "start")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("containerType"))
        }

        @Test
        fun `should require trigger parameter`() {
            val params = buildJsonObject {
                put("containerId", taskId.toString())
                put("containerType", "task")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("trigger"))
        }

        @Test
        fun `should reject invalid containerType`() {
            val params = buildJsonObject {
                put("containerId", taskId.toString())
                put("containerType", "invalid")
                put("trigger", "start")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("containerType"))
        }

        @Test
        fun `should reject invalid UUID format`() {
            val params = buildJsonObject {
                put("containerId", "not-a-uuid")
                put("containerType", "task")
                put("trigger", "start")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("UUID"))
        }

        @Test
        fun `should accept valid parameters`() {
            val params = buildJsonObject {
                put("containerId", taskId.toString())
                put("containerType", "task")
                put("trigger", "start")
            }

            assertDoesNotThrow { tool.validateParams(params) }
        }

        @Test
        fun `should accept parameters with optional summary`() {
            val params = buildJsonObject {
                put("containerId", taskId.toString())
                put("containerType", "task")
                put("trigger", "start")
                put("summary", "Starting work on this task")
            }

            assertDoesNotThrow { tool.validateParams(params) }
        }
    }

    @Nested
    inner class ExecutionTests {
        @Test
        fun `should return not found for nonexistent entity`() = runBlocking {
            coEvery { mockTaskRepository.getById(taskId) } returns Result.Error(
                RepositoryError.NotFound(taskId, EntityType.TASK, "Task not found")
            )

            val params = buildJsonObject {
                put("containerId", taskId.toString())
                put("containerType", "task")
                put("trigger", "start")
            }

            val result = tool.execute(params, context) as JsonObject
            assertFalse(result["success"]!!.jsonPrimitive.boolean)
            assertTrue(result["message"]!!.jsonPrimitive.content.contains("not found"))
        }

        @Test
        fun `should return error for unknown trigger`() = runBlocking {
            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(pendingTask)

            val params = buildJsonObject {
                put("containerId", taskId.toString())
                put("containerType", "task")
                put("trigger", "unknown_trigger")
            }

            val result = tool.execute(params, context) as JsonObject
            assertFalse(result["success"]!!.jsonPrimitive.boolean)
            assertTrue(result["message"]!!.jsonPrimitive.content.contains("Unknown trigger"))
        }

        @Test
        fun `should handle start trigger with successful transition`() = runBlocking {
            // Setup: pending task should progress to in-progress
            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(pendingTask)
            coEvery { mockStatusProgressionService.getNextStatus(
                currentStatus = any(),
                containerType = any(),
                tags = any(),
                containerId = any()
            ) } returns NextStatusRecommendation.Ready(
                recommendedStatus = "in-progress",
                activeFlow = "default_flow",
                flowSequence = listOf("backlog", "pending", "in-progress", "testing", "completed"),
                currentPosition = 1,
                matchedTags = emptyList(),
                reason = "Next status in default_flow workflow"
            )

            // Mock the update
            val updatedTask = pendingTask.copy(status = TaskStatus.IN_PROGRESS)
            coEvery { mockTaskRepository.update(any()) } returns Result.Success(updatedTask)

            // Mock dependency repository for prerequisite validation
            every { mockDependencyRepository.findByTaskId(any()) } returns emptyList()
            every { mockDependencyRepository.findByToTaskId(any()) } returns emptyList()

            val params = buildJsonObject {
                put("containerId", taskId.toString())
                put("containerType", "task")
                put("trigger", "start")
            }

            val result = tool.execute(params, context) as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean)

            val data = result["data"]!!.jsonObject
            assertEquals("pending", data["previousStatus"]!!.jsonPrimitive.content)
            assertEquals("in-progress", data["newStatus"]!!.jsonPrimitive.content)
            assertEquals("start", data["trigger"]!!.jsonPrimitive.content)
            assertTrue(data["applied"]!!.jsonPrimitive.boolean)
        }

        @Test
        fun `should handle cancel trigger as emergency transition`() = runBlocking {
            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(inProgressTask)

            val cancelledTask = inProgressTask.copy(status = TaskStatus.CANCELLED)
            coEvery { mockTaskRepository.update(any()) } returns Result.Success(cancelledTask)
            every { mockDependencyRepository.findByTaskId(any()) } returns emptyList()
            every { mockDependencyRepository.findByToTaskId(any()) } returns emptyList()

            val params = buildJsonObject {
                put("containerId", taskId.toString())
                put("containerType", "task")
                put("trigger", "cancel")
                put("summary", "Requirements changed")
            }

            val result = tool.execute(params, context) as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean)

            val data = result["data"]!!.jsonObject
            assertEquals("cancelled", data["newStatus"]!!.jsonPrimitive.content)
            assertEquals("cancel", data["trigger"]!!.jsonPrimitive.content)
            assertEquals("Requirements changed", data["summary"]!!.jsonPrimitive.content)
        }

        @Test
        fun `should handle block trigger as emergency transition`() = runBlocking {
            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(inProgressTask)

            val blockedTask = inProgressTask.copy(status = TaskStatus.BLOCKED)
            coEvery { mockTaskRepository.update(any()) } returns Result.Success(blockedTask)
            every { mockDependencyRepository.findByTaskId(any()) } returns emptyList()
            every { mockDependencyRepository.findByToTaskId(any()) } returns emptyList()

            val params = buildJsonObject {
                put("containerId", taskId.toString())
                put("containerType", "task")
                put("trigger", "block")
            }

            val result = tool.execute(params, context) as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean)

            val data = result["data"]!!.jsonObject
            assertEquals("blocked", data["newStatus"]!!.jsonPrimitive.content)
        }

        @Test
        fun `should return no transition needed when already at target status`() = runBlocking {
            val completedTask = pendingTask.copy(status = TaskStatus.COMPLETED)
            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(completedTask)

            val params = buildJsonObject {
                put("containerId", taskId.toString())
                put("containerType", "task")
                put("trigger", "complete")
            }

            val result = tool.execute(params, context) as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            assertTrue(result["message"]!!.jsonPrimitive.content.contains("No transition needed"))

            val data = result["data"]!!.jsonObject
            assertFalse(data["applied"]!!.jsonPrimitive.boolean)
        }
    }
}
