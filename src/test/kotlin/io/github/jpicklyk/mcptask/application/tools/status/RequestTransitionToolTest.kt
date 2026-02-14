package io.github.jpicklyk.mcptask.application.tools.status

import io.github.jpicklyk.mcptask.application.service.cascade.CascadeService
import io.github.jpicklyk.mcptask.application.service.progression.FlowPath
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
import io.mockk.coVerify
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
    private lateinit var mockCascadeService: CascadeService
    private lateinit var mockTaskRepository: TaskRepository
    private lateinit var mockFeatureRepository: FeatureRepository
    private lateinit var mockProjectRepository: ProjectRepository
    private lateinit var mockSectionRepository: SectionRepository
    private lateinit var mockDependencyRepository: DependencyRepository
    private lateinit var mockTemplateRepository: TemplateRepository
    private lateinit var mockRoleTransitionRepository: RoleTransitionRepository
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
        mockRoleTransitionRepository = mockk(relaxed = true)
        mockRepositoryProvider = mockk()

        every { mockRepositoryProvider.taskRepository() } returns mockTaskRepository
        every { mockRepositoryProvider.featureRepository() } returns mockFeatureRepository
        every { mockRepositoryProvider.projectRepository() } returns mockProjectRepository
        every { mockRepositoryProvider.sectionRepository() } returns mockSectionRepository
        every { mockRepositoryProvider.dependencyRepository() } returns mockDependencyRepository
        every { mockRepositoryProvider.templateRepository() } returns mockTemplateRepository
        every { mockRepositoryProvider.roleTransitionRepository() } returns mockRoleTransitionRepository

        // Mock CascadeService - tests that need cascade behavior will override these defaults
        mockCascadeService = mockk()
        every { mockCascadeService.loadAutoCascadeConfig() } returns io.github.jpicklyk.mcptask.domain.model.workflow.AutoCascadeConfig(enabled = false, maxDepth = 3)
        coEvery { mockCascadeService.applyCascades(any(), any(), any(), any()) } returns emptyList()
        coEvery { mockCascadeService.detectCascadeEvents(any(), any()) } returns emptyList()
        coEvery { mockCascadeService.findNewlyUnblockedTasks(any()) } returns emptyList()

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

        // Default mock for role lookups (returns null unless overridden)
        every { mockStatusProgressionService.getRoleForStatus(any(), any(), any()) } returns null

        context = ToolExecutionContext(
            repositoryProvider = mockRepositoryProvider,
            statusProgressionService = null,
            cascadeService = mockCascadeService
        )
        tool = RequestTransitionTool(mockStatusProgressionService)
    }

    @Nested
    inner class ValidationTests {
        @Test
        fun `should require containerId parameter in legacy mode`() {
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
    inner class BatchValidationTests {
        @Test
        fun `should reject when both transitions and legacy params provided`() {
            val params = buildJsonObject {
                put("containerId", UUID.randomUUID().toString())
                put("containerType", "task")
                put("trigger", "start")
                put("transitions", buildJsonArray {
                    add(buildJsonObject {
                        put("containerId", UUID.randomUUID().toString())
                        put("containerType", "task")
                        put("trigger", "complete")
                    })
                })
            }
            assertThrows<ToolValidationException> { tool.validateParams(params) }
        }

        @Test
        fun `should reject empty transitions array`() {
            val params = buildJsonObject {
                put("transitions", buildJsonArray {})
            }
            assertThrows<ToolValidationException> { tool.validateParams(params) }
        }

        @Test
        fun `should validate each transition item - missing containerId`() {
            val params = buildJsonObject {
                put("transitions", buildJsonArray {
                    add(buildJsonObject {
                        put("containerType", "task")  // missing containerId
                        put("trigger", "complete")
                    })
                })
            }
            assertThrows<ToolValidationException> { tool.validateParams(params) }
        }

        @Test
        fun `should validate each transition item - missing containerType`() {
            val params = buildJsonObject {
                put("transitions", buildJsonArray {
                    add(buildJsonObject {
                        put("containerId", UUID.randomUUID().toString())
                        put("trigger", "complete")  // missing containerType
                    })
                })
            }
            assertThrows<ToolValidationException> { tool.validateParams(params) }
        }

        @Test
        fun `should validate each transition item - missing trigger`() {
            val params = buildJsonObject {
                put("transitions", buildJsonArray {
                    add(buildJsonObject {
                        put("containerId", UUID.randomUUID().toString())
                        put("containerType", "task")  // missing trigger
                    })
                })
            }
            assertThrows<ToolValidationException> { tool.validateParams(params) }
        }

        @Test
        fun `should validate each transition item - invalid UUID`() {
            val params = buildJsonObject {
                put("transitions", buildJsonArray {
                    add(buildJsonObject {
                        put("containerId", "not-a-uuid")
                        put("containerType", "task")
                        put("trigger", "complete")
                    })
                })
            }
            assertThrows<ToolValidationException> { tool.validateParams(params) }
        }

        @Test
        fun `should validate each transition item - invalid containerType`() {
            val params = buildJsonObject {
                put("transitions", buildJsonArray {
                    add(buildJsonObject {
                        put("containerId", UUID.randomUUID().toString())
                        put("containerType", "invalid")
                        put("trigger", "complete")
                    })
                })
            }
            assertThrows<ToolValidationException> { tool.validateParams(params) }
        }

        @Test
        fun `should validate each transition item - blank trigger`() {
            val params = buildJsonObject {
                put("transitions", buildJsonArray {
                    add(buildJsonObject {
                        put("containerId", UUID.randomUUID().toString())
                        put("containerType", "task")
                        put("trigger", "   ")
                    })
                })
            }
            assertThrows<ToolValidationException> { tool.validateParams(params) }
        }

        @Test
        fun `should accept valid transitions array`() {
            val params = buildJsonObject {
                put("transitions", buildJsonArray {
                    add(buildJsonObject {
                        put("containerId", UUID.randomUUID().toString())
                        put("containerType", "task")
                        put("trigger", "complete")
                    })
                })
            }
            assertDoesNotThrow { tool.validateParams(params) }
        }

        @Test
        fun `should accept valid transitions array with optional summary`() {
            val params = buildJsonObject {
                put("transitions", buildJsonArray {
                    add(buildJsonObject {
                        put("containerId", UUID.randomUUID().toString())
                        put("containerType", "task")
                        put("trigger", "complete")
                        put("summary", "Finished implementation")
                    })
                })
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

            // Mock role lookups
            every { mockStatusProgressionService.getRoleForStatus("pending", "task", any()) } returns "queue"
            every { mockStatusProgressionService.getRoleForStatus("in-progress", "task", any()) } returns "work"

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
            assertEquals("queue", data["previousRole"]!!.jsonPrimitive.content)
            assertEquals("work", data["newRole"]!!.jsonPrimitive.content)
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

        @Test
        fun `request_transition response includes role boundary crossing`() = runBlocking {
            // Setup: in-progress task transitions to blocked via "block" trigger (work -> blocked boundary)
            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(inProgressTask)

            // Mock role lookups for work -> blocked boundary
            every { mockStatusProgressionService.getRoleForStatus("in-progress", "task", any()) } returns "work"
            every { mockStatusProgressionService.getRoleForStatus("blocked", "task", any()) } returns "blocked"

            // Mock the update
            val blockedTask = inProgressTask.copy(status = TaskStatus.BLOCKED)
            coEvery { mockTaskRepository.update(any()) } returns Result.Success(blockedTask)

            // Mock dependency repository for prerequisite validation
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
            assertEquals("in-progress", data["previousStatus"]!!.jsonPrimitive.content)
            assertEquals("blocked", data["newStatus"]!!.jsonPrimitive.content)
            assertEquals("block", data["trigger"]!!.jsonPrimitive.content)
            assertTrue(data["applied"]!!.jsonPrimitive.boolean)
            assertEquals("work", data["previousRole"]!!.jsonPrimitive.content)
            assertEquals("blocked", data["newRole"]!!.jsonPrimitive.content)
        }

        @Test
        fun `request_transition response omits role fields when roles are null`() = runBlocking {
            // Setup: cancel trigger where role lookup returns null
            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(inProgressTask)

            // getRoleForStatus returns null by default from setup

            val cancelledTask = inProgressTask.copy(status = TaskStatus.CANCELLED)
            coEvery { mockTaskRepository.update(any()) } returns Result.Success(cancelledTask)
            every { mockDependencyRepository.findByTaskId(any()) } returns emptyList()
            every { mockDependencyRepository.findByToTaskId(any()) } returns emptyList()

            val params = buildJsonObject {
                put("containerId", taskId.toString())
                put("containerType", "task")
                put("trigger", "cancel")
            }

            val result = tool.execute(params, context) as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean)

            val data = result["data"]!!.jsonObject
            assertEquals("cancelled", data["newStatus"]!!.jsonPrimitive.content)
            assertTrue(data["applied"]!!.jsonPrimitive.boolean)
            // Roles should be absent when getRoleForStatus returns null
            assertNull(data["previousRole"])
            assertNull(data["newRole"])
        }
    }

    @Nested
    inner class UnblockedTasksTests {

        private val taskAId = UUID.randomUUID()
        private val taskBId = UUID.randomUUID()
        private val taskCId = UUID.randomUUID()

        private fun createTask(
            id: UUID,
            title: String,
            status: TaskStatus = TaskStatus.IN_PROGRESS,
            summary: String = "A".repeat(350)
        ): Task = Task(
            id = id,
            title = title,
            description = "Description",
            summary = summary,
            status = status,
            priority = Priority.HIGH,
            complexity = 5,
            tags = listOf("backend"),
            featureId = featureId,
            projectId = projectId,
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )

        @Test
        fun `should report newly unblocked task on completion`() = runBlocking {
            val taskA = createTask(taskAId, "Task A", TaskStatus.IN_PROGRESS)
            val completedTaskA = taskA.copy(status = TaskStatus.COMPLETED)
            val taskB = createTask(taskBId, "Task B", TaskStatus.PENDING)

            // getById(taskAId) is called:
            // 1. fetchEntityDetails() - needs IN_PROGRESS
            // 2. StatusValidator.validateTaskPrerequisites for "completed" - needs task for summary check
            // 3. applyStatusChange() - needs IN_PROGRESS to copy
            // 4. verification gate check (trigger=complete) - needs task
            coEvery { mockTaskRepository.getById(taskAId) } returnsMany listOf(
                Result.Success(taskA),       // fetchEntityDetails
                Result.Success(taskA),       // StatusValidator prerequisite check (summary length)
                Result.Success(taskA),       // applyStatusChange
                Result.Success(taskA)        // verification gate check
            )

            coEvery { mockTaskRepository.update(any()) } returns Result.Success(completedTaskA)

            // StatusValidator prerequisite checks
            every { mockDependencyRepository.findByTaskId(any()) } returns emptyList()
            every { mockDependencyRepository.findByToTaskId(taskAId) } returns emptyList()

            // Mock CascadeService methods
            every { mockCascadeService.loadAutoCascadeConfig() } returns io.github.jpicklyk.mcptask.domain.model.workflow.AutoCascadeConfig(enabled = false)
            coEvery { mockCascadeService.detectCascadeEvents(any(), any()) } returns emptyList()
            coEvery { mockCascadeService.findNewlyUnblockedTasks(taskAId) } returns listOf(
                io.github.jpicklyk.mcptask.domain.model.workflow.UnblockedTask(taskId = taskBId, title = "Task B")
            )

            val params = buildJsonObject {
                put("containerId", taskAId.toString())
                put("containerType", "task")
                put("trigger", "complete")
            }

            val result = tool.execute(params, context) as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean)

            val data = result["data"]!!.jsonObject
            assertEquals("completed", data["newStatus"]!!.jsonPrimitive.content)
            assertTrue(data["applied"]!!.jsonPrimitive.boolean)

            // Verify unblockedTasks
            val unblockedTasks = data["unblockedTasks"]!!.jsonArray
            assertEquals(1, unblockedTasks.size)
            val unblocked = unblockedTasks[0].jsonObject
            assertEquals(taskBId.toString(), unblocked["taskId"]!!.jsonPrimitive.content)
            assertEquals("Task B", unblocked["title"]!!.jsonPrimitive.content)

            // Verify message mentions unblocked
            assertTrue(result["message"]!!.jsonPrimitive.content.contains("1 task(s) now unblocked"))
        }

        @Test
        fun `should not report task still blocked by another task`() = runBlocking {
            val taskA = createTask(taskAId, "Task A", TaskStatus.IN_PROGRESS)
            val cancelledTaskA = taskA.copy(status = TaskStatus.CANCELLED)
            val taskB = createTask(taskBId, "Task B", TaskStatus.PENDING)
            val taskC = createTask(taskCId, "Task C", TaskStatus.IN_PROGRESS)

            // getById(taskAId) calls:
            // 1. fetchEntityDetails - IN_PROGRESS
            // 2. applyStatusChange - IN_PROGRESS
            // 3. findNewlyUnblockedTasks blocker check - CANCELLED
            coEvery { mockTaskRepository.getById(taskAId) } returnsMany listOf(
                Result.Success(taskA),
                Result.Success(taskA),
                Result.Success(cancelledTaskA)
            )
            coEvery { mockTaskRepository.update(any()) } returns Result.Success(cancelledTaskA)

            every { mockDependencyRepository.findByTaskId(any()) } returns emptyList()
            every { mockDependencyRepository.findByToTaskId(taskAId) } returns emptyList()

            // outgoing BLOCKS deps from Task A -> Task B
            every { mockDependencyRepository.findByFromTaskId(taskAId) } returns listOf(
                Dependency(fromTaskId = taskAId, toTaskId = taskBId, type = DependencyType.BLOCKS)
            )

            // downstream Task B lookup
            coEvery { mockTaskRepository.getById(taskBId) } returns Result.Success(taskB)

            // All incoming blockers for Task B: both Task A and Task C block it
            every { mockDependencyRepository.findByToTaskId(taskBId) } returns listOf(
                Dependency(fromTaskId = taskAId, toTaskId = taskBId, type = DependencyType.BLOCKS),
                Dependency(fromTaskId = taskCId, toTaskId = taskBId, type = DependencyType.BLOCKS)
            )

            // Task C is still in-progress (not resolved)
            coEvery { mockTaskRepository.getById(taskCId) } returns Result.Success(taskC)

            val params = buildJsonObject {
                put("containerId", taskAId.toString())
                put("containerType", "task")
                put("trigger", "cancel")
            }

            val result = tool.execute(params, context) as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean)

            val data = result["data"]!!.jsonObject
            assertEquals("cancelled", data["newStatus"]!!.jsonPrimitive.content)

            // unblockedTasks should not be present or should be empty
            assertNull(data["unblockedTasks"], "Task B should NOT be unblocked since Task C still blocks it")
        }

        @Test
        fun `should report unblocked task on cancellation`() = runBlocking {
            val taskA = createTask(taskAId, "Task A", TaskStatus.IN_PROGRESS)
            val cancelledTaskA = taskA.copy(status = TaskStatus.CANCELLED)
            val taskB = createTask(taskBId, "Task B", TaskStatus.PENDING)

            // getById(taskAId) calls:
            // 1. fetchEntityDetails - IN_PROGRESS
            // 2. applyStatusChange - IN_PROGRESS
            coEvery { mockTaskRepository.getById(taskAId) } returnsMany listOf(
                Result.Success(taskA),
                Result.Success(taskA)
            )
            coEvery { mockTaskRepository.update(any()) } returns Result.Success(cancelledTaskA)

            every { mockDependencyRepository.findByTaskId(any()) } returns emptyList()
            every { mockDependencyRepository.findByToTaskId(taskAId) } returns emptyList()

            // Mock CascadeService methods
            every { mockCascadeService.loadAutoCascadeConfig() } returns io.github.jpicklyk.mcptask.domain.model.workflow.AutoCascadeConfig(enabled = false)
            coEvery { mockCascadeService.detectCascadeEvents(any(), any()) } returns emptyList()
            coEvery { mockCascadeService.findNewlyUnblockedTasks(taskAId) } returns listOf(
                io.github.jpicklyk.mcptask.domain.model.workflow.UnblockedTask(taskId = taskBId, title = "Task B")
            )

            val params = buildJsonObject {
                put("containerId", taskAId.toString())
                put("containerType", "task")
                put("trigger", "cancel")
            }

            val result = tool.execute(params, context) as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean)

            val data = result["data"]!!.jsonObject
            assertEquals("cancelled", data["newStatus"]!!.jsonPrimitive.content)

            // Verify unblockedTasks contains Task B
            val unblockedTasks = data["unblockedTasks"]!!.jsonArray
            assertEquals(1, unblockedTasks.size)
            assertEquals(taskBId.toString(), unblockedTasks[0].jsonObject["taskId"]!!.jsonPrimitive.content)
            assertEquals("Task B", unblockedTasks[0].jsonObject["title"]!!.jsonPrimitive.content)
        }

        @Test
        fun `should not include unblockedTasks for feature transitions`() = runBlocking {
            val feature = Feature(
                id = featureId,
                name = "Test Feature",
                description = "Test description",
                summary = "Test summary",
                status = FeatureStatus.IN_DEVELOPMENT,
                priority = Priority.HIGH,
                projectId = projectId,
                createdAt = Instant.now(),
                modifiedAt = Instant.now()
            )

            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            val onHoldFeature = feature.update(status = FeatureStatus.ON_HOLD)
            coEvery { mockFeatureRepository.update(any()) } returns Result.Success(onHoldFeature)

            // Mock dependency repos (used by StatusValidator)
            every { mockDependencyRepository.findByTaskId(any()) } returns emptyList()
            every { mockDependencyRepository.findByToTaskId(any()) } returns emptyList()

            val params = buildJsonObject {
                put("containerId", featureId.toString())
                put("containerType", "feature")
                put("trigger", "hold")
            }

            val result = tool.execute(params, context) as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean)

            val data = result["data"]!!.jsonObject
            assertTrue(data["applied"]!!.jsonPrimitive.boolean)

            // unblockedTasks should NOT be present for feature transitions
            assertNull(data["unblockedTasks"], "Feature transitions should not include unblockedTasks")
        }

        @Test
        fun `should handle no outgoing dependencies gracefully`() = runBlocking {
            val taskA = createTask(taskAId, "Task A", TaskStatus.IN_PROGRESS)
            val cancelledTaskA = taskA.copy(status = TaskStatus.CANCELLED)

            coEvery { mockTaskRepository.getById(taskAId) } returns Result.Success(taskA)
            coEvery { mockTaskRepository.update(any()) } returns Result.Success(cancelledTaskA)

            every { mockDependencyRepository.findByTaskId(any()) } returns emptyList()
            every { mockDependencyRepository.findByToTaskId(taskAId) } returns emptyList()

            // No outgoing dependencies
            every { mockDependencyRepository.findByFromTaskId(taskAId) } returns emptyList()

            val params = buildJsonObject {
                put("containerId", taskAId.toString())
                put("containerType", "task")
                put("trigger", "cancel")
            }

            val result = tool.execute(params, context) as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean)

            val data = result["data"]!!.jsonObject
            assertEquals("cancelled", data["newStatus"]!!.jsonPrimitive.content)

            // No unblockedTasks field should be present (empty list is not serialized)
            assertNull(data["unblockedTasks"], "Should not include unblockedTasks when no outgoing deps")
        }

        @Test
        fun `should skip RELATES_TO dependencies`() = runBlocking {
            val taskA = createTask(taskAId, "Task A", TaskStatus.IN_PROGRESS)
            val cancelledTaskA = taskA.copy(status = TaskStatus.CANCELLED)

            coEvery { mockTaskRepository.getById(taskAId) } returns Result.Success(taskA)
            coEvery { mockTaskRepository.update(any()) } returns Result.Success(cancelledTaskA)

            every { mockDependencyRepository.findByTaskId(any()) } returns emptyList()
            every { mockDependencyRepository.findByToTaskId(taskAId) } returns emptyList()

            // Only RELATES_TO outgoing deps from Task A
            every { mockDependencyRepository.findByFromTaskId(taskAId) } returns listOf(
                Dependency(fromTaskId = taskAId, toTaskId = taskBId, type = DependencyType.RELATES_TO)
            )

            val params = buildJsonObject {
                put("containerId", taskAId.toString())
                put("containerType", "task")
                put("trigger", "cancel")
            }

            val result = tool.execute(params, context) as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean)

            val data = result["data"]!!.jsonObject
            // No unblockedTasks since only RELATES_TO deps exist
            assertNull(data["unblockedTasks"], "RELATES_TO deps should not produce unblockedTasks")
        }

        @Test
        fun `should not include already-completed downstream tasks`() = runBlocking {
            val taskA = createTask(taskAId, "Task A", TaskStatus.IN_PROGRESS)
            val cancelledTaskA = taskA.copy(status = TaskStatus.CANCELLED)
            val taskB = createTask(taskBId, "Task B", TaskStatus.COMPLETED)

            coEvery { mockTaskRepository.getById(taskAId) } returns Result.Success(taskA)
            coEvery { mockTaskRepository.update(any()) } returns Result.Success(cancelledTaskA)

            every { mockDependencyRepository.findByTaskId(any()) } returns emptyList()
            every { mockDependencyRepository.findByToTaskId(taskAId) } returns emptyList()

            // outgoing BLOCKS deps from Task A -> Task B
            every { mockDependencyRepository.findByFromTaskId(taskAId) } returns listOf(
                Dependency(fromTaskId = taskAId, toTaskId = taskBId, type = DependencyType.BLOCKS)
            )

            // Task B is already completed
            coEvery { mockTaskRepository.getById(taskBId) } returns Result.Success(taskB)

            val params = buildJsonObject {
                put("containerId", taskAId.toString())
                put("containerType", "task")
                put("trigger", "cancel")
            }

            val result = tool.execute(params, context) as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean)

            val data = result["data"]!!.jsonObject
            // Task B is already completed, should not appear in unblockedTasks
            assertNull(data["unblockedTasks"], "Already-completed downstream tasks should not be reported as unblocked")
        }
    }

    @Nested
    inner class StatusRoleTransitionTests {
        @Test
        fun `should include previousRole and newRole for feature transition`() = runBlocking {
            val feature = Feature(
                id = featureId,
                name = "Test Feature",
                description = "Test description",
                summary = "Test summary",
                status = FeatureStatus.IN_DEVELOPMENT,
                priority = Priority.HIGH,
                projectId = projectId,
                tags = emptyList(),
                createdAt = Instant.now(),
                modifiedAt = Instant.now()
            )

            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)

            // Mock getNextStatus to resolve "start" trigger to target status
            coEvery { mockStatusProgressionService.getNextStatus(
                currentStatus = any(),
                containerType = any(),
                tags = any(),
                containerId = any()
            ) } returns NextStatusRecommendation.Ready(
                recommendedStatus = "testing",
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "completed"),
                currentPosition = 2,
                matchedTags = emptyList(),
                reason = "Next status in workflow"
            )

            // Mock role lookups for in-development -> testing boundary
            every { mockStatusProgressionService.getRoleForStatus("in-development", "feature", any()) } returns "work"
            every { mockStatusProgressionService.getRoleForStatus("testing", "feature", any()) } returns "review"

            // Mock getFlowPath for flow context in response
            every { mockStatusProgressionService.getFlowPath(any(), any(), any()) } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "testing", "completed"),
                currentPosition = 2,
                matchedTags = emptyList(),
                terminalStatuses = listOf("completed"),
                emergencyTransitions = listOf("blocked", "archived")
            )

            // Mock the update
            val testingFeature = feature.update(status = FeatureStatus.TESTING)
            coEvery { mockFeatureRepository.update(any()) } returns Result.Success(testingFeature)

            // Mock task repository for feature prerequisite validation (feature needs at least one task)
            val completedTask = Task(
                id = UUID.randomUUID(),
                title = "Completed Task",
                description = "Test",
                summary = "Test",
                status = TaskStatus.COMPLETED,
                priority = Priority.MEDIUM,
                complexity = 3,
                tags = emptyList(),
                featureId = featureId,
                projectId = projectId,
                createdAt = Instant.now(),
                modifiedAt = Instant.now()
            )
            coEvery { mockTaskRepository.findByFeature(featureId, any(), any(), any()) } returns Result.Success(listOf(completedTask))

            // Mock dependency repos (used by StatusValidator)
            every { mockDependencyRepository.findByTaskId(any()) } returns emptyList()
            every { mockDependencyRepository.findByToTaskId(any()) } returns emptyList()

            val params = buildJsonObject {
                put("containerId", featureId.toString())
                put("containerType", "feature")
                put("trigger", "start")
            }

            val result = tool.execute(params, context) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"]!!.jsonObject
            assertEquals("in-development", data["previousStatus"]!!.jsonPrimitive.content)
            assertEquals("testing", data["newStatus"]!!.jsonPrimitive.content)
            assertTrue(data["applied"]!!.jsonPrimitive.boolean)
            assertEquals("work", data["previousRole"]!!.jsonPrimitive.content)
            assertEquals("review", data["newRole"]!!.jsonPrimitive.content)
        }

        @Test
        fun `should include previousRole and newRole for project transition`() = runBlocking {
            val project = Project(
                id = projectId,
                name = "Test Project",
                description = "Test description",
                summary = "Test summary",
                status = ProjectStatus.PLANNING,
                tags = emptyList(),
                createdAt = Instant.now(),
                modifiedAt = Instant.now()
            )

            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(project)

            // Mock getNextStatus to resolve "start" trigger to target status
            coEvery { mockStatusProgressionService.getNextStatus(
                currentStatus = any(),
                containerType = any(),
                tags = any(),
                containerId = any()
            ) } returns NextStatusRecommendation.Ready(
                recommendedStatus = "in-development",
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "completed"),
                currentPosition = 1,
                matchedTags = emptyList(),
                reason = "Next status in workflow"
            )

            // Mock role lookups for planning -> in-development boundary
            every { mockStatusProgressionService.getRoleForStatus("planning", "project", any()) } returns "queue"
            every { mockStatusProgressionService.getRoleForStatus("in-development", "project", any()) } returns "work"

            // Mock getFlowPath for flow context in response
            every { mockStatusProgressionService.getFlowPath(any(), any(), any()) } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("planning", "in-development", "completed"),
                currentPosition = 1,
                matchedTags = emptyList(),
                terminalStatuses = listOf("completed"),
                emergencyTransitions = listOf("archived")
            )

            // Mock the update
            val inDevProject = project.update(status = ProjectStatus.IN_DEVELOPMENT)
            coEvery { mockProjectRepository.update(any()) } returns Result.Success(inDevProject)

            // Mock feature repository for project prerequisite validation
            coEvery { mockFeatureRepository.findByProject(projectId, any()) } returns Result.Success(emptyList())

            // Mock dependency repos (used by StatusValidator)
            every { mockDependencyRepository.findByTaskId(any()) } returns emptyList()
            every { mockDependencyRepository.findByToTaskId(any()) } returns emptyList()

            val params = buildJsonObject {
                put("containerId", projectId.toString())
                put("containerType", "project")
                put("trigger", "start")
            }

            val result = tool.execute(params, context) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"]!!.jsonObject
            assertEquals("planning", data["previousStatus"]!!.jsonPrimitive.content)
            assertEquals("in-development", data["newStatus"]!!.jsonPrimitive.content)
            assertTrue(data["applied"]!!.jsonPrimitive.boolean)
            assertEquals("queue", data["previousRole"]!!.jsonPrimitive.content)
            assertEquals("work", data["newRole"]!!.jsonPrimitive.content)
        }

        @Test
        fun `should correctly serialize review role for in-review status`() = runBlocking {
            val inReviewTask = Task(
                id = taskId,
                title = "Test Task",
                description = "Test description",
                summary = "Test summary",
                status = TaskStatus.IN_REVIEW,
                priority = Priority.HIGH,
                complexity = 5,
                tags = listOf("backend"),
                featureId = featureId,
                projectId = projectId,
                createdAt = Instant.now(),
                modifiedAt = Instant.now()
            )

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(inReviewTask)

            // Mock getNextStatus to resolve "start" trigger to target status
            coEvery { mockStatusProgressionService.getNextStatus(
                currentStatus = any(),
                containerType = any(),
                tags = any(),
                containerId = any()
            ) } returns NextStatusRecommendation.Ready(
                recommendedStatus = "testing",
                activeFlow = "with_review_flow",
                flowSequence = listOf("backlog", "pending", "in-progress", "in-review", "changes-requested", "testing", "completed"),
                currentPosition = 5,
                matchedTags = emptyList(),
                reason = "Next status in workflow"
            )

            // Mock role lookups for in-review -> testing boundary (both review role)
            every { mockStatusProgressionService.getRoleForStatus("in-review", "task", any()) } returns "review"
            every { mockStatusProgressionService.getRoleForStatus("testing", "task", any()) } returns "review"

            // Mock getFlowPath for flow context in response
            every { mockStatusProgressionService.getFlowPath(any(), any(), any()) } returns FlowPath(
                activeFlow = "with_review_flow",
                flowSequence = listOf("backlog", "pending", "in-progress", "in-review", "changes-requested", "testing", "completed"),
                currentPosition = 5,
                matchedTags = emptyList(),
                terminalStatuses = listOf("completed"),
                emergencyTransitions = listOf("blocked", "cancelled")
            )

            // Mock the update
            val testingTask = inReviewTask.copy(status = TaskStatus.TESTING)
            coEvery { mockTaskRepository.update(any()) } returns Result.Success(testingTask)

            // Mock dependency repos (used by StatusValidator)
            every { mockDependencyRepository.findByTaskId(any()) } returns emptyList()
            every { mockDependencyRepository.findByToTaskId(taskId) } returns emptyList()

            val params = buildJsonObject {
                put("containerId", taskId.toString())
                put("containerType", "task")
                put("trigger", "start")
            }

            val result = tool.execute(params, context) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"]!!.jsonObject
            assertEquals("in-review", data["previousStatus"]!!.jsonPrimitive.content)
            assertEquals("testing", data["newStatus"]!!.jsonPrimitive.content)
            assertTrue(data["applied"]!!.jsonPrimitive.boolean)
            assertEquals("review", data["previousRole"]!!.jsonPrimitive.content)
            assertEquals("review", data["newRole"]!!.jsonPrimitive.content)
        }
    }

    @Nested
    inner class BatchExecutionTests {
        @Test
        fun `should process batch transitions with mixed success and failure`() = runBlocking {
            val taskA = createTask(taskId, "Task A", TaskStatus.PENDING)
            val taskB = createTask(UUID.randomUUID(), "Task B", TaskStatus.IN_PROGRESS)

            val taskBId = taskB.id

            // Task A: successful completion
            coEvery { mockTaskRepository.getById(taskId) } returnsMany listOf(
                Result.Success(taskA),
                Result.Success(taskA),
                Result.Success(taskA)
            )
            coEvery { mockTaskRepository.update(match { it.id == taskId }) } returns Result.Success(taskA.copy(status = TaskStatus.COMPLETED))

            // Task B: failed (not found)
            coEvery { mockTaskRepository.getById(taskBId) } returns Result.Error(
                RepositoryError.NotFound(taskBId, EntityType.TASK, "Task not found")
            )

            every { mockDependencyRepository.findByTaskId(any()) } returns emptyList()
            every { mockDependencyRepository.findByToTaskId(any()) } returns emptyList()
            every { mockDependencyRepository.findByFromTaskId(any()) } returns emptyList()

            val params = buildJsonObject {
                put("transitions", buildJsonArray {
                    add(buildJsonObject {
                        put("containerId", taskId.toString())
                        put("containerType", "task")
                        put("trigger", "complete")
                    })
                    add(buildJsonObject {
                        put("containerId", taskBId.toString())
                        put("containerType", "task")
                        put("trigger", "start")
                    })
                })
            }

            val result = tool.execute(params, context) as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean)

            val data = result["data"]!!.jsonObject
            val summary = data["summary"]!!.jsonObject
            assertEquals(2, summary["total"]!!.jsonPrimitive.int)
            assertEquals(1, summary["succeeded"]!!.jsonPrimitive.int)
            assertEquals(1, summary["failed"]!!.jsonPrimitive.int)

            val results = data["results"]!!.jsonArray
            assertEquals(2, results.size)

            // First result should be success
            val firstResult = results[0].jsonObject
            assertTrue(firstResult["applied"]!!.jsonPrimitive.boolean)

            // Second result should be failure
            val secondResult = results[1].jsonObject
            assertFalse(secondResult["applied"]!!.jsonPrimitive.boolean)
            assertTrue(secondResult.containsKey("error"))
        }

        private fun createTask(
            id: UUID,
            title: String,
            status: TaskStatus = TaskStatus.IN_PROGRESS,
            summary: String = "A".repeat(350)
        ): Task = Task(
            id = id,
            title = title,
            description = "Description",
            summary = summary,
            status = status,
            priority = Priority.HIGH,
            complexity = 5,
            tags = listOf("backend"),
            featureId = featureId,
            projectId = projectId,
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )
    }

    @Nested
    inner class EnrichedResponseTests {
        @Test
        fun `should include flow context in success response`() = runBlocking {
            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(pendingTask)
            coEvery { mockStatusProgressionService.getNextStatus(
                currentStatus = any(),
                containerType = any(),
                tags = any(),
                containerId = any()
            ) } returns io.github.jpicklyk.mcptask.application.service.progression.NextStatusRecommendation.Ready(
                recommendedStatus = "in-progress",
                activeFlow = "default_flow",
                flowSequence = listOf("backlog", "pending", "in-progress", "testing", "completed"),
                currentPosition = 1,
                matchedTags = emptyList(),
                reason = "Next status in default_flow workflow"
            )

            every { mockStatusProgressionService.getRoleForStatus("pending", "task", any()) } returns "queue"
            every { mockStatusProgressionService.getRoleForStatus("in-progress", "task", any()) } returns "work"

            // Mock getFlowPath for enriched response
            every { mockStatusProgressionService.getFlowPath("task", any(), "pending") } returns
                io.github.jpicklyk.mcptask.application.service.progression.FlowPath(
                    activeFlow = "default_flow",
                    flowSequence = listOf("backlog", "pending", "in-progress", "testing", "completed"),
                    currentPosition = 1,
                    matchedTags = emptyList(),
                    terminalStatuses = listOf("completed", "cancelled"),
                    emergencyTransitions = listOf("blocked", "on-hold")
                )

            val updatedTask = pendingTask.copy(status = TaskStatus.IN_PROGRESS)
            coEvery { mockTaskRepository.update(any()) } returns Result.Success(updatedTask)

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
            assertTrue(data["applied"]!!.jsonPrimitive.boolean)

            // Verify flow context
            assertEquals("default_flow", data["activeFlow"]!!.jsonPrimitive.content)
            val flowSequence = data["flowSequence"]!!.jsonArray
            assertEquals(5, flowSequence.size)
            assertEquals("pending", flowSequence[1].jsonPrimitive.content)
            assertEquals(1, data["flowPosition"]!!.jsonPrimitive.int)
        }

        @Test
        fun `should include flow context in error response for blocked transition`() = runBlocking {
            val completedTask = pendingTask.copy(status = TaskStatus.COMPLETED)
            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(completedTask)

            // Mock getNextStatus to return Terminal (completed is terminal)
            coEvery { mockStatusProgressionService.getNextStatus(
                currentStatus = any(),
                containerType = any(),
                tags = any(),
                containerId = any()
            ) } returns io.github.jpicklyk.mcptask.application.service.progression.NextStatusRecommendation.Terminal(
                terminalStatus = "completed",
                activeFlow = "default_flow",
                reason = "Status 'completed' is terminal"
            )

            // Mock getFlowPath for error response enrichment
            every { mockStatusProgressionService.getFlowPath("task", any(), "completed") } returns
                io.github.jpicklyk.mcptask.application.service.progression.FlowPath(
                    activeFlow = "default_flow",
                    flowSequence = listOf("backlog", "pending", "in-progress", "testing", "completed"),
                    currentPosition = 4,
                    matchedTags = emptyList(),
                    terminalStatuses = listOf("completed", "cancelled"),
                    emergencyTransitions = listOf("blocked", "on-hold")
                )

            every { mockDependencyRepository.findByTaskId(any()) } returns emptyList()
            every { mockDependencyRepository.findByToTaskId(any()) } returns emptyList()

            val params = buildJsonObject {
                put("containerId", taskId.toString())
                put("containerType", "task")
                put("trigger", "start")
            }

            val result = tool.execute(params, context) as JsonObject
            assertFalse(result["success"]!!.jsonPrimitive.boolean)

            // Error response puts additionalData inside the error object
            val errorObj = result["error"]?.jsonObject
            assertNotNull(errorObj)
            val additionalData = errorObj!!["additionalData"]?.jsonObject
            assertNotNull(additionalData)

            // Verify flow context in error response
            assertEquals("default_flow", additionalData!!["activeFlow"]!!.jsonPrimitive.content)
            val flowSequence = additionalData["flowSequence"]!!.jsonArray
            assertEquals(5, flowSequence.size)
            assertEquals(4, additionalData["flowPosition"]!!.jsonPrimitive.int)
        }
    }

    // ==========================================
    // Auto-Cascade Tests
    // ==========================================

    @Nested
    inner class AutoCascadeTests {

        private val taskAId = UUID.randomUUID()
        private val taskBId = UUID.randomUUID()

        private fun createTask(
            id: UUID,
            title: String,
            status: TaskStatus = TaskStatus.IN_PROGRESS,
            summary: String = "A".repeat(350),
            fId: UUID? = featureId,
            pId: UUID? = projectId
        ): Task = Task(
            id = id,
            title = title,
            description = "Description",
            summary = summary,
            status = status,
            priority = Priority.HIGH,
            complexity = 5,
            tags = listOf("backend"),
            featureId = fId,
            projectId = pId,
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )

        private fun createFeature(
            id: UUID = featureId,
            name: String = "Test Feature",
            status: FeatureStatus = FeatureStatus.IN_DEVELOPMENT,
            pId: UUID? = projectId
        ): Feature = Feature(
            id = id,
            name = name,
            description = "Test description",
            summary = "Test summary",
            status = status,
            priority = Priority.HIGH,
            projectId = pId,
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )

        private fun createProject(
            id: UUID = projectId,
            name: String = "Test Project",
            status: ProjectStatus = ProjectStatus.IN_DEVELOPMENT
        ): Project = Project(
            id = id,
            name = name,
            description = "Test description",
            summary = "Test summary",
            status = status,
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )

        /**
         * Sets up common mock behaviors used by most cascade tests.
         *
         * This covers:
         * - DependencyRepository: findByTaskId, findByToTaskId, findByFromTaskId, deleteByTaskId
         * - StatusProgressionService: getFlowPath, getRoleForStatus
         * - FeatureRepository: getTaskCount (used by StatusValidator prereqs)
         * - TaskRepository: findByFeature (used by StatusValidator for completed prereqs)
         * - TaskRepository: findByFeatureId (used by WorkflowServiceImpl for countTasksByStatus)
         * - FeatureRepository: findByProject (used by StatusValidator for project completion prereqs)
         * - SectionRepository: getSectionsForEntity (cleanup)
         */
        private fun setupCommonMocks() {
            // Dependency mocks
            every { mockDependencyRepository.findByTaskId(any()) } returns emptyList()
            every { mockDependencyRepository.findByToTaskId(any()) } returns emptyList()
            every { mockDependencyRepository.findByFromTaskId(any()) } returns emptyList()
            every { mockDependencyRepository.deleteByTaskId(any()) } returns 0

            // StatusProgressionService mocks
            every { mockStatusProgressionService.getFlowPath(any(), any(), any()) } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("backlog", "pending", "in-progress", "testing", "completed"),
                currentPosition = 2,
                matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = listOf("blocked", "on-hold")
            )

            // StatusValidator prerequisite mocks (feature -> completed requires these)
            coEvery { mockFeatureRepository.getTaskCount(any()) } returns Result.Success(1)
            coEvery { mockTaskRepository.findByFeature(any(), any(), any(), any()) } returns Result.Success(emptyList())

            // WorkflowServiceImpl uses findByFeatureId (synchronous) for countTasksByStatus
            every { mockTaskRepository.findByFeatureId(any()) } returns emptyList()

            // StatusValidator prerequisite mocks (project -> completed)
            coEvery { mockFeatureRepository.findByProject(any(), any()) } returns Result.Success(emptyList())

            // Cleanup mocks
            coEvery { mockSectionRepository.getSectionsForEntity(any(), any()) } returns Result.Success(emptyList())
            coEvery { mockTaskRepository.delete(any()) } returns Result.Success(true)
        }

        @Test
        fun `auto-applies cascade when task completes and all tasks done`() = runBlocking {
            val task = createTask(taskAId, "Task A", TaskStatus.IN_PROGRESS)
            val completedTask = task.copy(status = TaskStatus.COMPLETED)
            val feature = createFeature(status = FeatureStatus.IN_DEVELOPMENT)

            setupCommonMocks()

            // Task lookups
            coEvery { mockTaskRepository.getById(taskAId) } returnsMany listOf(
                Result.Success(task),           // fetchEntityDetails
                Result.Success(task),           // StatusValidator prerequisite check (summary)
                Result.Success(task),           // applyStatusChange
                Result.Success(task)            // verification gate check
            )
            coEvery { mockTaskRepository.update(match { it.id == taskAId }) } returns Result.Success(completedTask)

            // StatusValidator for task -> completed
            every { mockDependencyRepository.findByTaskId(any()) } returns emptyList()
            every { mockDependencyRepository.findByToTaskId(taskAId) } returns emptyList()

            // Mock CascadeService to return applied cascade
            every { mockCascadeService.loadAutoCascadeConfig() } returns io.github.jpicklyk.mcptask.domain.model.workflow.AutoCascadeConfig(enabled = true, maxDepth = 3)
            coEvery { mockCascadeService.applyCascades(taskAId, "task", 0, 3) } returns listOf(
                io.github.jpicklyk.mcptask.domain.model.workflow.AppliedCascade(
                    event = "all_tasks_complete",
                    targetType = "feature",
                    targetId = featureId,
                    targetName = "Test Feature",
                    previousStatus = "in-development",
                    newStatus = "completed",
                    applied = true,
                    reason = "All tasks in feature are completed"
                )
            )
            coEvery { mockCascadeService.findNewlyUnblockedTasks(taskAId) } returns emptyList()

            val params = buildJsonObject {
                put("containerId", taskAId.toString())
                put("containerType", "task")
                put("trigger", "complete")
            }

            val result = tool.execute(params, context) as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean)

            val data = result["data"]!!.jsonObject
            assertEquals("completed", data["newStatus"]!!.jsonPrimitive.content)
            assertTrue(data["applied"]!!.jsonPrimitive.boolean)

            // Verify cascade events
            val cascadeEvents = data["cascadeEvents"]?.jsonArray
            assertNotNull(cascadeEvents, "cascadeEvents should be present")
            assertTrue(cascadeEvents!!.isNotEmpty(), "Should have at least one cascade event")

            val firstCascade = cascadeEvents[0].jsonObject
            assertEquals("all_tasks_complete", firstCascade["event"]!!.jsonPrimitive.content)
            assertEquals("feature", firstCascade["targetType"]!!.jsonPrimitive.content)
            assertEquals(featureId.toString(), firstCascade["targetId"]!!.jsonPrimitive.content)
            assertTrue(firstCascade["applied"]!!.jsonPrimitive.boolean, "Cascade should be applied (auto-cascade enabled)")
            assertTrue(firstCascade["automatic"]!!.jsonPrimitive.boolean, "Cascade should be marked automatic")
        }

        @Test
        fun `recursively cascades task to feature to project`() = runBlocking {
            val task = createTask(taskAId, "Task A", TaskStatus.IN_PROGRESS)
            val completedTask = task.copy(status = TaskStatus.COMPLETED)
            val feature = createFeature(status = FeatureStatus.IN_DEVELOPMENT)
            val completedFeature = feature.update(status = FeatureStatus.COMPLETED)
            val project = createProject(status = ProjectStatus.IN_DEVELOPMENT)
            val completedProject = project.update(status = ProjectStatus.COMPLETED)

            setupCommonMocks()

            // Task lookups
            coEvery { mockTaskRepository.getById(taskAId) } returnsMany listOf(
                Result.Success(task),           // fetchEntityDetails
                Result.Success(task),           // StatusValidator
                Result.Success(task),           // applyStatusChange
                Result.Success(task),           // verification gate
                Result.Success(completedTask),  // detectCascadesRaw task lookup
                Result.Success(completedTask),  // additional
                Result.Success(completedTask),  // additional
                Result.Success(completedTask)   // additional
            )
            coEvery { mockTaskRepository.update(match { it.id == taskAId }) } returns Result.Success(completedTask)

            // Feature lookups: cascade detection + application + recursive cascade detection
            coEvery { mockFeatureRepository.getById(featureId) } returnsMany listOf(
                Result.Success(feature),         // detectCascadesRaw -> detectTaskCascades
                Result.Success(feature),         // applyCascades -> fetchEntityDetails
                Result.Success(feature),         // applyCascades -> applyStatusChange
                Result.Success(completedFeature), // detectCascadesRaw for feature -> detectFeatureCascades
                Result.Success(completedFeature), // additional
                Result.Success(completedFeature)  // additional
            )
            coEvery { mockFeatureRepository.update(any()) } returns Result.Success(completedFeature)

            // All tasks done
            every { mockFeatureRepository.getTaskCountsByFeatureId(featureId) } returns TaskCounts(
                total = 1, pending = 0, inProgress = 0, completed = 1, cancelled = 0, testing = 0, blocked = 0
            )

            // StatusValidator for feature -> completed
            coEvery { mockTaskRepository.findByFeature(eq(featureId), any(), any(), any()) } returns Result.Success(
                listOf(completedTask)
            )

            // Project lookups: cascade detection from feature + application
            coEvery { mockProjectRepository.getById(projectId) } returnsMany listOf(
                Result.Success(project),          // detectFeatureCascades
                Result.Success(project),          // applyCascades -> fetchEntityDetails for project
                Result.Success(project),          // applyCascades -> applyStatusChange for project
                Result.Success(completedProject), // recursive check (no more cascades)
                Result.Success(completedProject)  // additional
            )
            coEvery { mockProjectRepository.update(any()) } returns Result.Success(completedProject)

            // All features done in project
            every { mockProjectRepository.getFeatureCountsByProjectId(projectId) } returns FeatureCounts(
                total = 1, completed = 1
            )

            // StatusValidator for project -> completed needs findByProject
            coEvery { mockFeatureRepository.findByProject(eq(projectId), any()) } returns Result.Success(
                listOf(completedFeature)
            )

            // Cleanup for feature
            every { mockTaskRepository.findByFeatureId(featureId) } returns listOf(completedTask)

            // Mock CascadeService for recursive cascades with childCascades
            every { mockCascadeService.loadAutoCascadeConfig() } returns io.github.jpicklyk.mcptask.domain.model.workflow.AutoCascadeConfig(enabled = true, maxDepth = 3)
            coEvery { mockCascadeService.applyCascades(taskAId, "task", 0, 3) } returns listOf(
                io.github.jpicklyk.mcptask.domain.model.workflow.AppliedCascade(
                    event = "all_tasks_complete",
                    targetType = "feature",
                    targetId = featureId,
                    targetName = "Test Feature",
                    previousStatus = "in-development",
                    newStatus = "completed",
                    applied = true,
                    reason = "All tasks completed",
                    childCascades = listOf(
                        io.github.jpicklyk.mcptask.domain.model.workflow.AppliedCascade(
                            event = "all_features_complete",
                            targetType = "project",
                            targetId = projectId,
                            targetName = "Test Project",
                            previousStatus = "in-development",
                            newStatus = "completed",
                            applied = true,
                            reason = "All features completed"
                        )
                    )
                )
            )
            coEvery { mockCascadeService.findNewlyUnblockedTasks(taskAId) } returns emptyList()

            val params = buildJsonObject {
                put("containerId", taskAId.toString())
                put("containerType", "task")
                put("trigger", "complete")
            }

            val result = tool.execute(params, context) as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean)

            val data = result["data"]!!.jsonObject
            assertTrue(data["applied"]!!.jsonPrimitive.boolean)

            // Check cascade chain: feature should cascade, and have childCascades for project
            val cascadeEvents = data["cascadeEvents"]?.jsonArray
            assertNotNull(cascadeEvents, "cascadeEvents should be present")
            assertTrue(cascadeEvents!!.isNotEmpty(), "Should have cascade events")

            val featureCascade = cascadeEvents[0].jsonObject
            assertEquals("all_tasks_complete", featureCascade["event"]!!.jsonPrimitive.content)
            assertEquals("feature", featureCascade["targetType"]!!.jsonPrimitive.content)
            assertTrue(featureCascade["applied"]!!.jsonPrimitive.boolean)

            // Check for child cascades (project advancement)
            val childCascades = featureCascade["childCascades"]?.jsonArray
            assertNotNull(childCascades, "Should have child cascades for project")
            assertTrue(childCascades!!.isNotEmpty(), "Project should cascade from feature")

            val projectCascade = childCascades[0].jsonObject
            assertEquals("all_features_complete", projectCascade["event"]!!.jsonPrimitive.content)
            assertEquals("project", projectCascade["targetType"]!!.jsonPrimitive.content)
            assertTrue(projectCascade["applied"]!!.jsonPrimitive.boolean)
        }

        @Test
        fun `skips cascade when target already at suggested status`() = runBlocking {
            // Feature already completed - cascade should be silently skipped
            val task = createTask(taskAId, "Task A", TaskStatus.IN_PROGRESS)
            val completedTask = task.copy(status = TaskStatus.COMPLETED)
            val feature = createFeature(status = FeatureStatus.COMPLETED)

            setupCommonMocks()

            coEvery { mockTaskRepository.getById(taskAId) } returnsMany listOf(
                Result.Success(task),
                Result.Success(task),
                Result.Success(task),
                Result.Success(task),
                Result.Success(completedTask),
                Result.Success(completedTask),
                Result.Success(completedTask)
            )
            coEvery { mockTaskRepository.update(match { it.id == taskAId }) } returns Result.Success(completedTask)

            // Feature already at "completed" - the suggested status
            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)

            // All tasks done
            every { mockFeatureRepository.getTaskCountsByFeatureId(featureId) } returns TaskCounts(
                total = 1, pending = 0, inProgress = 0, completed = 1, cancelled = 0, testing = 0, blocked = 0
            )

            val params = buildJsonObject {
                put("containerId", taskAId.toString())
                put("containerType", "task")
                put("trigger", "complete")
            }

            val result = tool.execute(params, context) as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean)

            val data = result["data"]!!.jsonObject
            assertTrue(data["applied"]!!.jsonPrimitive.boolean)

            // The cascade event for feature should be silently skipped (not in cascadeEvents)
            // because feature is already at the suggested status
            val cascadeEvents = data["cascadeEvents"]?.jsonArray
            assertTrue(
                cascadeEvents == null || cascadeEvents.isEmpty(),
                "Cascade events should be empty when target already at suggested status"
            )
        }

        @Test
        fun `handles cascade validation failure gracefully`() = runBlocking {
            val task = createTask(taskAId, "Task A", TaskStatus.IN_PROGRESS)
            val completedTask = task.copy(status = TaskStatus.COMPLETED)
            val feature = createFeature(status = FeatureStatus.IN_DEVELOPMENT)

            setupCommonMocks()

            coEvery { mockTaskRepository.getById(taskAId) } returnsMany listOf(
                Result.Success(task),
                Result.Success(task),
                Result.Success(task),
                Result.Success(task),
                Result.Success(completedTask),
                Result.Success(completedTask),
                Result.Success(completedTask)
            )
            coEvery { mockTaskRepository.update(match { it.id == taskAId }) } returns Result.Success(completedTask)

            // Feature lookups for cascade detection and validation
            coEvery { mockFeatureRepository.getById(featureId) } returnsMany listOf(
                Result.Success(feature),  // detectCascadesRaw
                Result.Success(feature),  // applyCascades -> fetchEntityDetails
                Result.Success(feature)   // additional
            )

            // All tasks done (cascade will fire)
            every { mockFeatureRepository.getTaskCountsByFeatureId(featureId) } returns TaskCounts(
                total = 1, pending = 0, inProgress = 0, completed = 1, cancelled = 0, testing = 0, blocked = 0
            )

            // Make cascade validation fail: StatusValidator for feature -> completed
            // needs findByFeature to show incomplete tasks
            val incompleteTask = createTask(UUID.randomUUID(), "Incomplete Task", TaskStatus.IN_PROGRESS)
            coEvery { mockTaskRepository.findByFeature(eq(featureId), any(), any(), any()) } returns Result.Success(
                listOf(incompleteTask)
            )

            // Mock CascadeService to return a failed cascade (validation failure)
            every { mockCascadeService.loadAutoCascadeConfig() } returns io.github.jpicklyk.mcptask.domain.model.workflow.AutoCascadeConfig(enabled = true, maxDepth = 3)
            coEvery { mockCascadeService.applyCascades(taskAId, "task", 0, 3) } returns listOf(
                io.github.jpicklyk.mcptask.domain.model.workflow.AppliedCascade(
                    event = "all_tasks_complete",
                    targetType = "feature",
                    targetId = featureId,
                    targetName = "Test Feature",
                    previousStatus = "in-development",
                    newStatus = "completed",
                    applied = false,
                    reason = "Validation failed",
                    error = "Transition blocked: Not all tasks in feature are completed"
                )
            )
            coEvery { mockCascadeService.findNewlyUnblockedTasks(taskAId) } returns emptyList()

            val params = buildJsonObject {
                put("containerId", taskAId.toString())
                put("containerType", "task")
                put("trigger", "complete")
            }

            val result = tool.execute(params, context) as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean, "Original task transition should succeed")

            val data = result["data"]!!.jsonObject
            assertTrue(data["applied"]!!.jsonPrimitive.boolean, "Original task transition should be applied")

            // The cascade should have been attempted but failed
            val cascadeEvents = data["cascadeEvents"]?.jsonArray
            assertNotNull(cascadeEvents, "cascadeEvents should be present with failed cascade")
            assertTrue(cascadeEvents!!.isNotEmpty(), "Should have cascade events")

            val failedCascade = cascadeEvents[0].jsonObject
            assertFalse(failedCascade["applied"]!!.jsonPrimitive.boolean, "Cascade should not be applied due to validation failure")
            assertNotNull(failedCascade["error"], "Should have error message explaining validation failure")
            val errorMsg = failedCascade["error"]!!.jsonPrimitive.content
            assertTrue(
                errorMsg.contains("blocked", ignoreCase = true) ||
                    errorMsg.contains("Transition blocked") ||
                    errorMsg.contains("Cannot transition") ||
                    errorMsg.contains("skip") ||
                    errorMsg.contains("not completed"),
                "Error should indicate transition was blocked, got: $errorMsg"
            )
        }

        @Test
        fun `handles cascade apply failure gracefully`() = runBlocking {
            val task = createTask(taskAId, "Task A", TaskStatus.IN_PROGRESS)
            val completedTask = task.copy(status = TaskStatus.COMPLETED)
            val feature = createFeature(status = FeatureStatus.IN_DEVELOPMENT)

            setupCommonMocks()

            coEvery { mockTaskRepository.getById(taskAId) } returnsMany listOf(
                Result.Success(task),
                Result.Success(task),
                Result.Success(task),
                Result.Success(task),
                Result.Success(completedTask),
                Result.Success(completedTask),
                Result.Success(completedTask)
            )
            coEvery { mockTaskRepository.update(match { it.id == taskAId }) } returns Result.Success(completedTask)

            // Feature lookups for cascade detection and application
            coEvery { mockFeatureRepository.getById(featureId) } returnsMany listOf(
                Result.Success(feature),  // detectCascadesRaw
                Result.Success(feature),  // applyCascades -> fetchEntityDetails
                Result.Success(feature),  // applyCascades -> applyStatusChange
                Result.Success(feature)   // additional
            )

            // All tasks done
            every { mockFeatureRepository.getTaskCountsByFeatureId(featureId) } returns TaskCounts(
                total = 1, pending = 0, inProgress = 0, completed = 1, cancelled = 0, testing = 0, blocked = 0
            )

            // StatusValidator for feature -> completed
            coEvery { mockTaskRepository.findByFeature(eq(featureId), any(), any(), any()) } returns Result.Success(
                listOf(completedTask)
            )

            // Feature update fails
            coEvery { mockFeatureRepository.update(any()) } returns Result.Error(
                RepositoryError.DatabaseError("Database connection lost")
            )

            // Mock CascadeService to return a failed cascade (update error)
            every { mockCascadeService.loadAutoCascadeConfig() } returns io.github.jpicklyk.mcptask.domain.model.workflow.AutoCascadeConfig(enabled = true, maxDepth = 3)
            coEvery { mockCascadeService.applyCascades(taskAId, "task", 0, 3) } returns listOf(
                io.github.jpicklyk.mcptask.domain.model.workflow.AppliedCascade(
                    event = "all_tasks_complete",
                    targetType = "feature",
                    targetId = featureId,
                    targetName = "Test Feature",
                    previousStatus = "in-development",
                    newStatus = "completed",
                    applied = false,
                    reason = "Update failed",
                    error = "Database connection lost"
                )
            )
            coEvery { mockCascadeService.findNewlyUnblockedTasks(taskAId) } returns emptyList()

            val params = buildJsonObject {
                put("containerId", taskAId.toString())
                put("containerType", "task")
                put("trigger", "complete")
            }

            val result = tool.execute(params, context) as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean, "Original task transition should succeed")

            val data = result["data"]!!.jsonObject
            assertTrue(data["applied"]!!.jsonPrimitive.boolean, "Original task transition should be applied")

            // The cascade should have failed during apply
            val cascadeEvents = data["cascadeEvents"]?.jsonArray
            assertNotNull(cascadeEvents, "cascadeEvents should be present")
            assertTrue(cascadeEvents!!.isNotEmpty(), "Should have cascade events")

            val failedCascade = cascadeEvents[0].jsonObject
            assertFalse(failedCascade["applied"]!!.jsonPrimitive.boolean, "Cascade should not be applied due to update failure")
            assertNotNull(failedCascade["error"], "Should have error message")
            val errorMsg = failedCascade["error"]!!.jsonPrimitive.content
            assertTrue(
                errorMsg.contains("Failed to update") || errorMsg.contains("Database") || errorMsg.contains("error"),
                "Error should indicate update failure, got: $errorMsg"
            )
        }

        @Test
        fun `runs completion cleanup on cascaded feature terminal`() = runBlocking {
            val task = createTask(taskAId, "Task A", TaskStatus.IN_PROGRESS)
            val completedTask = task.copy(status = TaskStatus.COMPLETED)
            val anotherTask = createTask(taskBId, "Task B", TaskStatus.COMPLETED)
            // Use "prototype" tag so rapid_prototype_flow is used, which maps
            // all_tasks_complete: in-development -> completed (terminal, triggers cleanup)
            val feature = Feature(
                id = featureId,
                name = "Test Feature",
                description = "Test description",
                summary = "Test summary",
                status = FeatureStatus.IN_DEVELOPMENT,
                priority = Priority.HIGH,
                projectId = projectId,
                tags = listOf("prototype"),
                createdAt = Instant.now(),
                modifiedAt = Instant.now()
            )
            val completedFeature = feature.update(status = FeatureStatus.COMPLETED)

            setupCommonMocks()

            // Task getById call sequence:
            // 1. fetchEntityDetails
            // 2. verification gate check (requiresVerification)
            // 3. applyStatusChange (reads current task to copy+update)
            // 4. detectCascadesRaw -> detectTaskCascades (needs completed task)
            coEvery { mockTaskRepository.getById(taskAId) } returnsMany listOf(
                Result.Success(task),           // fetchEntityDetails
                Result.Success(task),           // verification gate
                Result.Success(task),           // applyStatusChange
                Result.Success(completedTask),  // detectCascadesRaw -> detectTaskCascades
                Result.Success(completedTask),  // additional
                Result.Success(completedTask)   // additional
            )
            coEvery { mockTaskRepository.update(match { it.id == taskAId }) } returns Result.Success(completedTask)

            coEvery { mockFeatureRepository.getById(featureId) } returnsMany listOf(
                Result.Success(feature),         // detectCascadesRaw -> detectTaskCascades
                Result.Success(feature),         // applyCascades -> fetchEntityDetails
                Result.Success(feature),         // applyCascades -> applyStatusChange
                Result.Success(completedFeature), // recursive cascade detection (check for project)
                Result.Success(completedFeature)  // additional
            )
            coEvery { mockFeatureRepository.update(any()) } returns Result.Success(completedFeature)

            every { mockFeatureRepository.getTaskCountsByFeatureId(featureId) } returns TaskCounts(
                total = 2, pending = 0, inProgress = 0, completed = 2, cancelled = 0, testing = 0, blocked = 0
            )

            // StatusValidator for feature -> completed
            coEvery { mockTaskRepository.findByFeature(eq(featureId), any(), any(), any()) } returns Result.Success(
                listOf(completedTask, anotherTask)
            )

            // Project: not all features done, so no project cascade
            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(createProject())
            every { mockProjectRepository.getFeatureCountsByProjectId(projectId) } returns FeatureCounts(
                total = 3, completed = 1  // Not all done
            )

            // Cleanup mocks: feature reaching terminal triggers CompletionCleanupService
            // CompletionCleanupService.cleanupFeatureTasks calls findByFeatureId
            every { mockTaskRepository.findByFeatureId(featureId) } returns listOf(completedTask, anotherTask)

            // Mock CascadeService with cleanup result
            every { mockCascadeService.loadAutoCascadeConfig() } returns io.github.jpicklyk.mcptask.domain.model.workflow.AutoCascadeConfig(enabled = true, maxDepth = 3)
            coEvery { mockCascadeService.applyCascades(taskAId, "task", 0, 3) } returns listOf(
                io.github.jpicklyk.mcptask.domain.model.workflow.AppliedCascade(
                    event = "all_tasks_complete",
                    targetType = "feature",
                    targetId = featureId,
                    targetName = "Test Feature",
                    previousStatus = "in-development",
                    newStatus = "completed",
                    applied = true,
                    reason = "All tasks completed",
                    cleanup = io.github.jpicklyk.mcptask.domain.model.workflow.CascadeCleanupResult(
                        performed = true,
                        tasksDeleted = 0,
                        tasksRetained = 2,
                        retainedTaskIds = listOf(taskAId, taskBId),
                        sectionsDeleted = 0,
                        dependenciesDeleted = 0,
                        reason = "Feature completed with completed tasks retained"
                    )
                )
            )
            coEvery { mockCascadeService.findNewlyUnblockedTasks(taskAId) } returns emptyList()

            val params = buildJsonObject {
                put("containerId", taskAId.toString())
                put("containerType", "task")
                put("trigger", "complete")
            }

            val result = tool.execute(params, context) as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean)

            val data = result["data"]!!.jsonObject
            val cascadeEvents = data["cascadeEvents"]?.jsonArray
            assertNotNull(cascadeEvents)
            assertTrue(cascadeEvents!!.isNotEmpty())

            val featureCascade = cascadeEvents[0].jsonObject
            assertTrue(featureCascade["applied"]!!.jsonPrimitive.boolean)

            // Verify cleanup was performed
            val cleanup = featureCascade["cleanup"]?.jsonObject
            assertNotNull(cleanup, "Cleanup should be present for cascaded feature reaching terminal status")
            assertTrue(cleanup!!["performed"]!!.jsonPrimitive.boolean, "Cleanup should have been performed")
        }

        @Test
        fun `batch transitions auto-cascade with aggregation`() = runBlocking {
            val taskA = createTask(taskAId, "Task A", TaskStatus.IN_PROGRESS)
            val completedTaskA = taskA.copy(status = TaskStatus.COMPLETED)
            val taskB = createTask(taskBId, "Task B", TaskStatus.IN_PROGRESS)
            val completedTaskB = taskB.copy(status = TaskStatus.COMPLETED)
            val feature = createFeature(status = FeatureStatus.IN_DEVELOPMENT)
            val completedFeature = feature.update(status = FeatureStatus.COMPLETED)

            setupCommonMocks()

            // Task A lookups
            coEvery { mockTaskRepository.getById(taskAId) } returnsMany listOf(
                Result.Success(taskA),           // fetchEntityDetails
                Result.Success(taskA),           // StatusValidator
                Result.Success(taskA),           // applyStatusChange
                Result.Success(taskA),           // verification gate
                Result.Success(completedTaskA),  // detectCascadesRaw
                Result.Success(completedTaskA),  // additional
                Result.Success(completedTaskA)   // additional
            )
            coEvery { mockTaskRepository.update(match { it.id == taskAId }) } returns Result.Success(completedTaskA)

            // Task B lookups
            coEvery { mockTaskRepository.getById(taskBId) } returnsMany listOf(
                Result.Success(taskB),           // fetchEntityDetails
                Result.Success(taskB),           // StatusValidator
                Result.Success(taskB),           // applyStatusChange
                Result.Success(taskB),           // verification gate
                Result.Success(completedTaskB),  // detectCascadesRaw
                Result.Success(completedTaskB),  // additional
                Result.Success(completedTaskB)   // additional
            )
            coEvery { mockTaskRepository.update(match { it.id == taskBId }) } returns Result.Success(completedTaskB)

            // Feature: first task completes -> cascade fires and feature advances
            // Second task completes -> cascade detects feature already at completed, skips
            coEvery { mockFeatureRepository.getById(featureId) } returnsMany listOf(
                // First cascade: task A triggers all_tasks_complete
                Result.Success(feature),         // detectCascadesRaw for task A
                Result.Success(feature),         // applyCascades -> fetchEntityDetails
                Result.Success(feature),         // applyCascades -> applyStatusChange
                Result.Success(completedFeature), // recursive cascade detection
                Result.Success(completedFeature), // additional
                // Second cascade: task B detects all_tasks_complete but feature already completed
                Result.Success(completedFeature), // detectCascadesRaw for task B
                Result.Success(completedFeature), // applyCascades -> fetchEntityDetails (already at status, skip)
                Result.Success(completedFeature)  // additional
            )
            coEvery { mockFeatureRepository.update(any()) } returns Result.Success(completedFeature)

            // All tasks done from the start (both completing)
            every { mockFeatureRepository.getTaskCountsByFeatureId(featureId) } returns TaskCounts(
                total = 2, pending = 0, inProgress = 0, completed = 2, cancelled = 0, testing = 0, blocked = 0
            )

            // StatusValidator for feature -> completed
            coEvery { mockTaskRepository.findByFeature(eq(featureId), any(), any(), any()) } returns Result.Success(
                listOf(completedTaskA, completedTaskB)
            )

            // Project: not all features done
            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(createProject())
            every { mockProjectRepository.getFeatureCountsByProjectId(projectId) } returns FeatureCounts(
                total = 2, completed = 1
            )

            // Cleanup mocks
            every { mockTaskRepository.findByFeatureId(featureId) } returns listOf(completedTaskA, completedTaskB)

            // Mock CascadeService for batch transitions - first cascade applied, second skipped
            every { mockCascadeService.loadAutoCascadeConfig() } returns io.github.jpicklyk.mcptask.domain.model.workflow.AutoCascadeConfig(enabled = true, maxDepth = 3)
            // Task A: cascade applied
            coEvery { mockCascadeService.applyCascades(taskAId, "task", 0, 3) } returns listOf(
                io.github.jpicklyk.mcptask.domain.model.workflow.AppliedCascade(
                    event = "all_tasks_complete",
                    targetType = "feature",
                    targetId = featureId,
                    targetName = "Test Feature",
                    previousStatus = "in-development",
                    newStatus = "completed",
                    applied = true,
                    reason = "All tasks completed"
                )
            )
            // Task B: cascade skipped (feature already at target status)
            coEvery { mockCascadeService.applyCascades(taskBId, "task", 0, 3) } returns listOf(
                io.github.jpicklyk.mcptask.domain.model.workflow.AppliedCascade(
                    event = "all_tasks_complete",
                    targetType = "feature",
                    targetId = featureId,
                    targetName = "Test Feature",
                    previousStatus = "completed",
                    newStatus = "completed",
                    applied = false,
                    reason = "Target already at suggested status"
                )
            )
            coEvery { mockCascadeService.findNewlyUnblockedTasks(any()) } returns emptyList()

            val params = buildJsonObject {
                put("transitions", buildJsonArray {
                    add(buildJsonObject {
                        put("containerId", taskAId.toString())
                        put("containerType", "task")
                        put("trigger", "complete")
                    })
                    add(buildJsonObject {
                        put("containerId", taskBId.toString())
                        put("containerType", "task")
                        put("trigger", "complete")
                    })
                })
            }

            val result = tool.execute(params, context) as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean)

            val data = result["data"]!!.jsonObject
            val summary = data["summary"]!!.jsonObject
            assertEquals(2, summary["total"]!!.jsonPrimitive.int)
            assertEquals(2, summary["succeeded"]!!.jsonPrimitive.int)
            assertEquals(0, summary["failed"]!!.jsonPrimitive.int)

            // Only one cascade should actually be applied (first task triggers it,
            // second task's cascade finds feature already at target status and skips)
            val cascadesApplied = summary["cascadesApplied"]?.jsonPrimitive?.int ?: 0
            assertEquals(1, cascadesApplied, "Only one cascade should be counted (second is skipped)")
        }

        @Test
        fun `first_task_started cascades feature from planning to in-development`() = runBlocking {
            val task = createTask(taskAId, "Task A", TaskStatus.PENDING)
            val inProgressTask = task.copy(status = TaskStatus.IN_PROGRESS)
            val feature = createFeature(status = FeatureStatus.PLANNING)
            val inDevFeature = feature.update(status = FeatureStatus.IN_DEVELOPMENT)

            setupCommonMocks()

            // Mock start trigger resolution
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

            every { mockStatusProgressionService.getRoleForStatus("pending", "task", any()) } returns "queue"
            every { mockStatusProgressionService.getRoleForStatus("in-progress", "task", any()) } returns "work"

            // Task lookups
            coEvery { mockTaskRepository.getById(taskAId) } returnsMany listOf(
                Result.Success(task),            // fetchEntityDetails
                Result.Success(task),            // applyStatusChange
                Result.Success(inProgressTask),  // detectCascadesRaw -> task lookup
                Result.Success(inProgressTask),  // additional
                Result.Success(inProgressTask),  // additional
                Result.Success(inProgressTask)   // additional
            )
            coEvery { mockTaskRepository.update(match { it.id == taskAId }) } returns Result.Success(inProgressTask)

            // Feature lookups: cascade detection and application
            coEvery { mockFeatureRepository.getById(featureId) } returnsMany listOf(
                Result.Success(feature),        // detectCascadesRaw -> detectTaskCascades
                Result.Success(feature),        // applyCascades -> fetchEntityDetails
                Result.Success(feature),        // applyCascades -> applyStatusChange
                Result.Success(inDevFeature),   // recursive cascade detection
                Result.Success(inDevFeature)    // additional
            )
            coEvery { mockFeatureRepository.update(any()) } returns Result.Success(inDevFeature)

            // StatusValidator for feature -> in-development needs getTaskCount
            coEvery { mockFeatureRepository.getTaskCount(featureId) } returns Result.Success(3)

            // One task in-progress (the first one)
            every { mockFeatureRepository.getTaskCountsByFeatureId(featureId) } returns TaskCounts(
                total = 3, pending = 2, inProgress = 1, completed = 0, cancelled = 0, testing = 0, blocked = 0
            )
            // For countTasksByStatus in WorkflowServiceImpl: findByFeatureId returns tasks
            every { mockTaskRepository.findByFeatureId(featureId) } returns listOf(
                inProgressTask,
                createTask(UUID.randomUUID(), "Task B", TaskStatus.PENDING),
                createTask(UUID.randomUUID(), "Task C", TaskStatus.PENDING)
            )

            // Project lookup for recursive cascade detection from feature
            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(createProject())
            every { mockProjectRepository.getFeatureCountsByProjectId(projectId) } returns FeatureCounts(
                total = 2, completed = 0
            )

            // Mock CascadeService for first_task_started cascade
            every { mockCascadeService.loadAutoCascadeConfig() } returns io.github.jpicklyk.mcptask.domain.model.workflow.AutoCascadeConfig(enabled = true, maxDepth = 3)
            coEvery { mockCascadeService.applyCascades(taskAId, "task", 0, 3) } returns listOf(
                io.github.jpicklyk.mcptask.domain.model.workflow.AppliedCascade(
                    event = "first_task_started",
                    targetType = "feature",
                    targetId = featureId,
                    targetName = "Test Feature",
                    previousStatus = "planning",
                    newStatus = "in-development",
                    applied = true,
                    reason = "First task started"
                )
            )
            coEvery { mockCascadeService.findNewlyUnblockedTasks(taskAId) } returns emptyList()

            val params = buildJsonObject {
                put("containerId", taskAId.toString())
                put("containerType", "task")
                put("trigger", "start")
            }

            val result = tool.execute(params, context) as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean)

            val data = result["data"]!!.jsonObject
            assertEquals("in-progress", data["newStatus"]!!.jsonPrimitive.content)
            assertTrue(data["applied"]!!.jsonPrimitive.boolean)

            // Verify cascade event
            val cascadeEvents = data["cascadeEvents"]?.jsonArray
            assertNotNull(cascadeEvents, "cascadeEvents should be present")
            assertTrue(cascadeEvents!!.isNotEmpty(), "Should have cascade event for first_task_started")

            val firstCascade = cascadeEvents[0].jsonObject
            assertEquals("first_task_started", firstCascade["event"]!!.jsonPrimitive.content)
            assertEquals("feature", firstCascade["targetType"]!!.jsonPrimitive.content)
            assertTrue(firstCascade["applied"]!!.jsonPrimitive.boolean)
        }

        @Test
        fun `userSummary includes cascade count for single transition`() {
            // Build a mock result structure mimicking a successful single transition with cascades
            val mockResult = buildJsonObject {
                put("success", true)
                put("message", "Transitioned task")
                put("data", buildJsonObject {
                    put("containerId", taskAId.toString())
                    put("containerType", "task")
                    put("previousStatus", "in-progress")
                    put("newStatus", "completed")
                    put("trigger", "complete")
                    put("applied", true)
                    put("cascadeEvents", buildJsonArray {
                        add(buildJsonObject {
                            put("event", "all_tasks_complete")
                            put("targetType", "feature")
                            put("targetId", featureId.toString())
                            put("applied", true)
                            put("automatic", true)
                        })
                    })
                })
            }

            val params = buildJsonObject {
                put("containerId", taskAId.toString())
                put("containerType", "task")
                put("trigger", "complete")
            }

            val summary = tool.userSummary(params, mockResult, false)
            assertTrue(
                summary.contains("cascade") && summary.contains("applied"),
                "userSummary should mention cascades applied, got: $summary"
            )
            assertTrue(
                summary.contains("1"),
                "userSummary should mention count of cascades, got: $summary"
            )
        }

        @Test
        fun `userSummary includes cascade count for batch`() {
            val mockResult = buildJsonObject {
                put("success", true)
                put("message", "2 of 2 transitions applied")
                put("data", buildJsonObject {
                    put("results", buildJsonArray {
                        add(buildJsonObject { put("applied", true) })
                        add(buildJsonObject { put("applied", true) })
                    })
                    put("summary", buildJsonObject {
                        put("total", 2)
                        put("succeeded", 2)
                        put("failed", 0)
                        put("cascadesApplied", 3)
                    })
                })
            }

            val params = buildJsonObject {
                put("transitions", buildJsonArray {
                    add(buildJsonObject {
                        put("containerId", taskAId.toString())
                        put("containerType", "task")
                        put("trigger", "complete")
                    })
                })
            }

            val summary = tool.userSummary(params, mockResult, false)
            assertTrue(
                summary.contains("cascade"),
                "userSummary should mention cascades, got: $summary"
            )
            assertTrue(
                summary.contains("3"),
                "userSummary should mention the cascade count, got: $summary"
            )
        }

        @Test
        fun `userSummary handles single transition with string summary field without crashing`() {
            // Regression test: when a single-entity transition includes a "summary" string
            // (the caller's note), userSummary must not crash trying to parse it as a JsonObject.
            // The batch response also has a "summary" field but as a JsonObject {total, succeeded, failed}.
            val mockResult = buildJsonObject {
                put("success", true)
                put("message", "Transitioned task from 'pending' to 'completed'")
                put("data", buildJsonObject {
                    put("containerId", taskAId.toString())
                    put("containerType", "task")
                    put("previousStatus", "pending")
                    put("newStatus", "completed")
                    put("trigger", "complete")
                    put("applied", true)
                    put("summary", "Completed as part of feature work")  // String, NOT JsonObject
                })
            }

            val params = buildJsonObject {
                put("containerId", taskAId.toString())
                put("containerType", "task")
                put("trigger", "complete")
                put("summary", "Completed as part of feature work")
            }

            // This should NOT throw JsonLiteral is not a JsonObject
            val summary = tool.userSummary(params, mockResult, false)
            assertTrue(
                summary.contains("pending") && summary.contains("completed"),
                "userSummary should show status transition, got: $summary"
            )
        }

        @Test
        fun `auto-cascade disabled returns legacy format`() = runBlocking {
            // Create a temp config with auto_cascade.enabled=false
            // The StatusValidator also needs config for v2 mode, so include status_progression
            val tempDir = java.nio.file.Files.createTempDirectory("cascade-test")
            val configDir = tempDir.resolve(".taskorchestrator")
            java.nio.file.Files.createDirectories(configDir)
            val configFile = configDir.resolve("config.yaml")
            java.nio.file.Files.writeString(configFile, """
auto_cascade:
  enabled: false
  max_depth: 3
status_progression:
  tasks:
    default_flow: [backlog, pending, in-progress, testing, completed]
    terminal_statuses: [completed, cancelled, deferred]
    emergency_transitions: [blocked, on-hold, cancelled, deferred]
  features:
    default_flow: [draft, planning, in-development, testing, validating, completed]
    terminal_statuses: [completed, archived]
    emergency_transitions: [blocked, on-hold, archived]
status_validation:
  enforce_sequential: false
  allow_backward: true
  allow_emergency: true
  validate_prerequisites: false
""".trimIndent())

            val originalDir = System.getProperty("user.dir")
            try {
                System.setProperty("user.dir", tempDir.toString())

                val task = createTask(taskAId, "Task A", TaskStatus.IN_PROGRESS)
                val completedTask = task.copy(status = TaskStatus.COMPLETED)
                val feature = createFeature(status = FeatureStatus.IN_DEVELOPMENT)

                setupCommonMocks()

                // Task getById call sequence:
                // 1. fetchEntityDetails
                // 2. verification gate check (trigger == "complete")
                // 3. applyStatusChange
                // 4. detectCascadesRaw -> detectTaskCascades (needs completed task)
                coEvery { mockTaskRepository.getById(taskAId) } returnsMany listOf(
                    Result.Success(task),           // fetchEntityDetails
                    Result.Success(task),           // verification gate
                    Result.Success(task),           // applyStatusChange
                    Result.Success(completedTask),  // detectCascadesRaw -> detectTaskCascades
                    Result.Success(completedTask)   // additional
                )
                coEvery { mockTaskRepository.update(match { it.id == taskAId }) } returns Result.Success(completedTask)

                coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)

                every { mockFeatureRepository.getTaskCountsByFeatureId(featureId) } returns TaskCounts(
                    total = 1, pending = 0, inProgress = 0, completed = 1, cancelled = 0, testing = 0, blocked = 0
                )

                // For WorkflowServiceImpl's countTasksByStatus
                every { mockTaskRepository.findByFeatureId(featureId) } returns listOf(completedTask)

                // Mock CascadeService for legacy format (auto-cascade disabled)
                every { mockCascadeService.loadAutoCascadeConfig() } returns io.github.jpicklyk.mcptask.domain.model.workflow.AutoCascadeConfig(enabled = false, maxDepth = 3)
                coEvery { mockCascadeService.detectCascadeEvents(taskAId, io.github.jpicklyk.mcptask.domain.model.workflow.ContainerType.TASK) } returns listOf(
                    io.github.jpicklyk.mcptask.domain.model.workflow.CascadeEvent(
                        event = "all_tasks_complete",
                        targetType = io.github.jpicklyk.mcptask.domain.model.workflow.ContainerType.FEATURE,
                        targetId = featureId,
                        targetName = "Test Feature",
                        currentStatus = "in-development",
                        suggestedStatus = "completed",
                        flow = "default_flow",
                        automatic = false,
                        reason = "All tasks completed"
                    )
                )
                coEvery { mockCascadeService.findNewlyUnblockedTasks(taskAId) } returns emptyList()

                val params = buildJsonObject {
                    put("containerId", taskAId.toString())
                    put("containerType", "task")
                    put("trigger", "complete")
                }

                val result = tool.execute(params, context) as JsonObject
                assertTrue(result["success"]!!.jsonPrimitive.boolean)

                val data = result["data"]!!.jsonObject
                assertTrue(data["applied"]!!.jsonPrimitive.boolean)

                // With auto_cascade.enabled=false, cascadeEvents should use legacy format
                val cascadeEvents = data["cascadeEvents"]?.jsonArray
                assertNotNull(cascadeEvents, "Legacy cascade events should still be present")
                assertTrue(cascadeEvents!!.isNotEmpty(), "Should have legacy cascade events")

                val legacyEvent = cascadeEvents[0].jsonObject
                assertFalse(legacyEvent["applied"]!!.jsonPrimitive.boolean, "Legacy cascade should have applied=false")
                assertFalse(legacyEvent["automatic"]!!.jsonPrimitive.boolean, "Legacy cascade should have automatic=false")
                assertNotNull(legacyEvent["suggestedStatus"], "Legacy cascade should have suggestedStatus field")
            } finally {
                System.setProperty("user.dir", originalDir)
                // Clean up temp files
                java.nio.file.Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach { java.nio.file.Files.deleteIfExists(it) }
            }
        }

        @Test
        fun `no cascade events when task has no feature`() = runBlocking {
            // Task without featureId - no cascade possible
            val standaloneTask = createTask(taskAId, "Standalone Task", TaskStatus.IN_PROGRESS, fId = null, pId = null)
            val completedTask = standaloneTask.copy(status = TaskStatus.COMPLETED)

            setupCommonMocks()

            coEvery { mockTaskRepository.getById(taskAId) } returnsMany listOf(
                Result.Success(standaloneTask),
                Result.Success(standaloneTask),
                Result.Success(standaloneTask),
                Result.Success(standaloneTask),
                Result.Success(completedTask),
                Result.Success(completedTask)
            )
            coEvery { mockTaskRepository.update(match { it.id == taskAId }) } returns Result.Success(completedTask)

            val params = buildJsonObject {
                put("containerId", taskAId.toString())
                put("containerType", "task")
                put("trigger", "complete")
            }

            val result = tool.execute(params, context) as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean)

            val data = result["data"]!!.jsonObject
            assertTrue(data["applied"]!!.jsonPrimitive.boolean)

            // No cascade events for standalone tasks
            val cascadeEvents = data["cascadeEvents"]?.jsonArray
            assertTrue(
                cascadeEvents == null || cascadeEvents.isEmpty(),
                "Standalone tasks should not produce cascade events"
            )
        }

        @Test
        fun `cascade not triggered when not all tasks are complete`() = runBlocking {
            val task = createTask(taskAId, "Task A", TaskStatus.IN_PROGRESS)
            val completedTask = task.copy(status = TaskStatus.COMPLETED)
            val feature = createFeature(status = FeatureStatus.IN_DEVELOPMENT)

            setupCommonMocks()

            coEvery { mockTaskRepository.getById(taskAId) } returnsMany listOf(
                Result.Success(task),
                Result.Success(task),
                Result.Success(task),
                Result.Success(task),
                Result.Success(completedTask),
                Result.Success(completedTask),
                Result.Success(completedTask)
            )
            coEvery { mockTaskRepository.update(match { it.id == taskAId }) } returns Result.Success(completedTask)

            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)

            // Not all tasks done (still 1 pending)
            every { mockFeatureRepository.getTaskCountsByFeatureId(featureId) } returns TaskCounts(
                total = 3, pending = 1, inProgress = 1, completed = 1, cancelled = 0, testing = 0, blocked = 0
            )

            val params = buildJsonObject {
                put("containerId", taskAId.toString())
                put("containerType", "task")
                put("trigger", "complete")
            }

            val result = tool.execute(params, context) as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean)

            val data = result["data"]!!.jsonObject
            assertTrue(data["applied"]!!.jsonPrimitive.boolean)

            // No cascade events since not all tasks are done
            val cascadeEvents = data["cascadeEvents"]?.jsonArray
            assertTrue(
                cascadeEvents == null || cascadeEvents.isEmpty(),
                "Should not have cascade events when tasks are still incomplete"
            )
        }

        @Test
        fun `should pass custom maxDepth=1 to applyCascades`() = runBlocking {
            val task = createTask(taskAId, "Task A", TaskStatus.PENDING)
            val inProgressTask = task.copy(status = TaskStatus.IN_PROGRESS)

            setupCommonMocks()

            // Override cascade config with maxDepth=1
            every { mockCascadeService.loadAutoCascadeConfig() } returns io.github.jpicklyk.mcptask.domain.model.workflow.AutoCascadeConfig(enabled = true, maxDepth = 1)

            // Mock getNextStatus for start trigger
            coEvery { mockStatusProgressionService.getNextStatus(
                currentStatus = any(),
                containerType = any(),
                tags = any(),
                containerId = any()
            ) } returns NextStatusRecommendation.Ready(
                recommendedStatus = "in-progress",
                activeFlow = "default_flow",
                flowSequence = listOf("pending", "in-progress", "testing", "completed"),
                currentPosition = 1,
                matchedTags = emptyList(),
                reason = "Next status"
            )

            // Task lookups
            coEvery { mockTaskRepository.getById(taskAId) } returnsMany listOf(
                Result.Success(task),           // fetchEntityDetails
                Result.Success(task),           // applyStatusChange
                Result.Success(inProgressTask), // verification gate
                Result.Success(inProgressTask)  // additional
            )
            coEvery { mockTaskRepository.update(any()) } returns Result.Success(inProgressTask)

            every { mockDependencyRepository.findByTaskId(any()) } returns emptyList()
            every { mockDependencyRepository.findByToTaskId(any()) } returns emptyList()
            every { mockStatusProgressionService.getFlowPath(any(), any(), any()) } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("pending", "in-progress", "testing", "completed"),
                currentPosition = 1,
                matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = listOf("blocked", "on-hold")
            )

            coEvery { mockCascadeService.applyCascades(taskAId, "task", 0, 1) } returns emptyList()
            coEvery { mockCascadeService.findNewlyUnblockedTasks(taskAId) } returns emptyList()

            val params = buildJsonObject {
                put("containerId", taskAId.toString())
                put("containerType", "task")
                put("trigger", "start")
            }

            val result = tool.execute(params, context) as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean)

            // Verify applyCascades was called with maxDepth=1
            coVerify { mockCascadeService.applyCascades(taskAId, "task", 0, 1) }
        }

        @Test
        fun `should pass custom maxDepth=5 to applyCascades`() = runBlocking {
            val task = createTask(taskAId, "Task A", TaskStatus.PENDING)
            val inProgressTask = task.copy(status = TaskStatus.IN_PROGRESS)

            setupCommonMocks()

            // Override cascade config with maxDepth=5
            every { mockCascadeService.loadAutoCascadeConfig() } returns io.github.jpicklyk.mcptask.domain.model.workflow.AutoCascadeConfig(enabled = true, maxDepth = 5)

            // Mock getNextStatus for start trigger
            coEvery { mockStatusProgressionService.getNextStatus(
                currentStatus = any(),
                containerType = any(),
                tags = any(),
                containerId = any()
            ) } returns NextStatusRecommendation.Ready(
                recommendedStatus = "in-progress",
                activeFlow = "default_flow",
                flowSequence = listOf("pending", "in-progress", "testing", "completed"),
                currentPosition = 1,
                matchedTags = emptyList(),
                reason = "Next status"
            )

            // Task lookups
            coEvery { mockTaskRepository.getById(taskAId) } returnsMany listOf(
                Result.Success(task),           // fetchEntityDetails
                Result.Success(task),           // applyStatusChange
                Result.Success(inProgressTask), // verification gate
                Result.Success(inProgressTask)  // additional
            )
            coEvery { mockTaskRepository.update(any()) } returns Result.Success(inProgressTask)

            every { mockDependencyRepository.findByTaskId(any()) } returns emptyList()
            every { mockDependencyRepository.findByToTaskId(any()) } returns emptyList()
            every { mockStatusProgressionService.getFlowPath(any(), any(), any()) } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("pending", "in-progress", "testing", "completed"),
                currentPosition = 1,
                matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = listOf("blocked", "on-hold")
            )

            coEvery { mockCascadeService.applyCascades(taskAId, "task", 0, 5) } returns emptyList()
            coEvery { mockCascadeService.findNewlyUnblockedTasks(taskAId) } returns emptyList()

            val params = buildJsonObject {
                put("containerId", taskAId.toString())
                put("containerType", "task")
                put("trigger", "start")
            }

            val result = tool.execute(params, context) as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean)

            // Verify applyCascades was called with maxDepth=5
            coVerify { mockCascadeService.applyCascades(taskAId, "task", 0, 5) }
        }

        @Test
        fun `should not call applyCascades when auto_cascade disabled`() = runBlocking {
            val task = createTask(taskAId, "Task A", TaskStatus.PENDING)
            val inProgressTask = task.copy(status = TaskStatus.IN_PROGRESS)

            setupCommonMocks()

            // Default mock has auto_cascade disabled (enabled=false, maxDepth=3)
            // This is set in @BeforeEach setup()

            // Mock getNextStatus for start trigger
            coEvery { mockStatusProgressionService.getNextStatus(
                currentStatus = any(),
                containerType = any(),
                tags = any(),
                containerId = any()
            ) } returns NextStatusRecommendation.Ready(
                recommendedStatus = "in-progress",
                activeFlow = "default_flow",
                flowSequence = listOf("pending", "in-progress", "testing", "completed"),
                currentPosition = 1,
                matchedTags = emptyList(),
                reason = "Next status"
            )

            // Task lookups
            coEvery { mockTaskRepository.getById(taskAId) } returnsMany listOf(
                Result.Success(task),           // fetchEntityDetails
                Result.Success(task),           // applyStatusChange
                Result.Success(inProgressTask), // verification gate
                Result.Success(inProgressTask)  // additional
            )
            coEvery { mockTaskRepository.update(any()) } returns Result.Success(inProgressTask)

            every { mockDependencyRepository.findByTaskId(any()) } returns emptyList()
            every { mockDependencyRepository.findByToTaskId(any()) } returns emptyList()
            every { mockStatusProgressionService.getFlowPath(any(), any(), any()) } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("pending", "in-progress", "testing", "completed"),
                currentPosition = 1,
                matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = listOf("blocked", "on-hold")
            )

            val params = buildJsonObject {
                put("containerId", taskAId.toString())
                put("containerType", "task")
                put("trigger", "start")
            }

            val result = tool.execute(params, context) as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean)

            // Verify applyCascades was NOT called
            coVerify(exactly = 0) { mockCascadeService.applyCascades(any(), any(), any(), any()) }

            // Verify detectCascadeEvents WAS called (legacy behavior)
            coVerify { mockCascadeService.detectCascadeEvents(any(), any()) }
        }

        @Test
        fun `should include cascadeEvents with applied=false when auto_cascade disabled`() = runBlocking {
            val task = createTask(taskAId, "Task A", TaskStatus.IN_PROGRESS)
            val completedTask = task.copy(status = TaskStatus.COMPLETED)

            setupCommonMocks()

            // Default mock has auto_cascade disabled (enabled=false, maxDepth=3)
            // Override detectCascadeEvents to return non-empty suggestions
            coEvery { mockCascadeService.detectCascadeEvents(any(), any()) } returns listOf(
                io.github.jpicklyk.mcptask.domain.model.workflow.CascadeEvent(
                    event = "all_tasks_complete",
                    targetType = io.github.jpicklyk.mcptask.domain.model.workflow.ContainerType.FEATURE,
                    targetId = featureId,
                    targetName = "Test Feature",
                    currentStatus = "in-development",
                    suggestedStatus = "completed",
                    flow = "default_flow",
                    automatic = true,
                    reason = "All tasks completed"
                )
            )

            // Mock getNextStatus for complete trigger
            coEvery { mockStatusProgressionService.getNextStatus(
                currentStatus = any(),
                containerType = any(),
                tags = any(),
                containerId = any()
            ) } returns NextStatusRecommendation.Ready(
                recommendedStatus = "completed",
                activeFlow = "default_flow",
                flowSequence = listOf("pending", "in-progress", "testing", "completed"),
                currentPosition = 3,
                matchedTags = emptyList(),
                reason = "Next status"
            )

            // Task lookups
            coEvery { mockTaskRepository.getById(taskAId) } returnsMany listOf(
                Result.Success(task),           // fetchEntityDetails
                Result.Success(task),           // StatusValidator prerequisite check
                Result.Success(task),           // applyStatusChange
                Result.Success(task),           // verification gate
                Result.Success(completedTask),  // detectCascadeEvents
                Result.Success(completedTask)   // additional
            )
            coEvery { mockTaskRepository.update(any()) } returns Result.Success(completedTask)

            every { mockDependencyRepository.findByTaskId(any()) } returns emptyList()
            every { mockDependencyRepository.findByToTaskId(any()) } returns emptyList()
            every { mockStatusProgressionService.getFlowPath(any(), any(), any()) } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("pending", "in-progress", "testing", "completed"),
                currentPosition = 3,
                matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = listOf("blocked", "on-hold")
            )

            val params = buildJsonObject {
                put("containerId", taskAId.toString())
                put("containerType", "task")
                put("trigger", "complete")
            }

            val result = tool.execute(params, context) as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean)

            val data = result["data"]!!.jsonObject
            assertTrue(data["applied"]!!.jsonPrimitive.boolean)

            // Verify cascadeEvents are present
            val cascadeEvents = data["cascadeEvents"]?.jsonArray
            assertNotNull(cascadeEvents, "cascadeEvents should be present with detected suggestions")
            assertTrue(cascadeEvents!!.isNotEmpty(), "Should have cascade events from detectCascadeEvents")

            // Verify events are NOT applied (suggestions only)
            val firstCascade = cascadeEvents[0].jsonObject
            assertEquals("all_tasks_complete", firstCascade["event"]!!.jsonPrimitive.content)
            assertEquals("feature", firstCascade["targetType"]!!.jsonPrimitive.content)

            // When auto_cascade is disabled, cascadeEvents from detectCascadeEvents don't have "applied" field
            // They're suggestions, not applied cascades
            val appliedField = firstCascade["applied"]
            // Either the field is absent, or it's false
            assertTrue(
                appliedField == null || !appliedField.jsonPrimitive.boolean,
                "Cascade events should not be marked as applied when auto_cascade is disabled"
            )
        }
    }

    @Nested
    inner class RoleTransitionRecordingTests {
        @BeforeEach
        fun setupRoleTests() {
            // Success response for role transition recording (uses shared mockRoleTransitionRepository)
            coEvery { mockRoleTransitionRepository.create(any()) } returns Result.Success(
                RoleTransition(
                    id = UUID.randomUUID(),
                    entityId = taskId,
                    entityType = "task",
                    fromRole = "queue",
                    toRole = "work",
                    fromStatus = "pending",
                    toStatus = "in-progress",
                    transitionedAt = Instant.now(),
                    trigger = "start",
                    summary = null
                )
            )
        }

        @Test
        fun `records role transition when role changes`() = runBlocking {
            // Setup: Task moving from pending (queue) to in-progress (work)
            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(pendingTask)
            coEvery { mockTaskRepository.update(any()) } returns Result.Success(inProgressTask)
            every { mockDependencyRepository.findByTaskId(any()) } returns emptyList()
            every { mockDependencyRepository.findByToTaskId(any()) } returns emptyList()

            // Mock status progression service to return role changes
            coEvery { mockStatusProgressionService.getNextStatus(any(), any(), any()) } returns
                NextStatusRecommendation.Ready(
                    recommendedStatus = "in-progress",
                    activeFlow = "default_flow",
                    flowSequence = listOf("pending", "in-progress", "completed"),
                    currentPosition = 0,
                    matchedTags = emptyList(),
                    reason = "Next status in flow"
                )
            every { mockStatusProgressionService.getRoleForStatus("pending", "task", any()) } returns "queue"
            every { mockStatusProgressionService.getRoleForStatus("in-progress", "task", any()) } returns "work"
            every { mockStatusProgressionService.getFlowPath(any(), any(), any()) } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("pending", "in-progress", "completed"),
                currentPosition = 0,
                matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = emptyList()
            )

            val params = buildJsonObject {
                put("transitions", JsonArray(listOf(
                    buildJsonObject {
                        put("containerId", taskId.toString())
                        put("containerType", "task")
                        put("trigger", "start")
                    }
                )))
            }

            val result = tool.execute(params, context) as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean)

            // Verify role transition was recorded
            coVerify {
                mockRoleTransitionRepository.create(match {
                    it.entityId == taskId &&
                    it.entityType == "task" &&
                    it.fromRole == "queue" &&
                    it.toRole == "work" &&
                    it.fromStatus == "pending" &&
                    it.toStatus == "in-progress" &&
                    it.trigger == "start"
                })
            }
        }

        @Test
        fun `does not record role transition when role stays same`() = runBlocking {
            // Setup: Task in-progress but moving to same role (e.g., testing also in "work" role)
            val testingTask = inProgressTask.copy(status = TaskStatus.TESTING)
            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(inProgressTask)
            coEvery { mockTaskRepository.update(any()) } returns Result.Success(testingTask)
            every { mockDependencyRepository.findByTaskId(any()) } returns emptyList()
            every { mockDependencyRepository.findByToTaskId(any()) } returns emptyList()

            // Mock status progression service - both statuses in "work" role
            coEvery { mockStatusProgressionService.getNextStatus(any(), any(), any()) } returns
                NextStatusRecommendation.Ready(
                    recommendedStatus = "testing",
                    activeFlow = "with_testing_flow",
                    flowSequence = listOf("pending", "in-progress", "testing", "completed"),
                    currentPosition = 1,
                    matchedTags = emptyList(),
                    reason = "Next status in flow"
                )
            every { mockStatusProgressionService.getRoleForStatus("in-progress", "task", any()) } returns "work"
            every { mockStatusProgressionService.getRoleForStatus("testing", "task", any()) } returns "work"
            every { mockStatusProgressionService.getFlowPath(any(), any(), any()) } returns FlowPath(
                activeFlow = "with_testing_flow",
                flowSequence = listOf("pending", "in-progress", "testing", "completed"),
                currentPosition = 1,
                matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = emptyList()
            )

            val params = buildJsonObject {
                put("transitions", JsonArray(listOf(
                    buildJsonObject {
                        put("containerId", taskId.toString())
                        put("containerType", "task")
                        put("trigger", "start")
                    }
                )))
            }

            val result = tool.execute(params, context) as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean)

            // Verify no role transition was recorded (same role)
            coVerify(exactly = 0) {
                mockRoleTransitionRepository.create(any())
            }
        }

        @Test
        fun `does not fail transition if role transition recording fails`() = runBlocking {
            // Setup: Task moving from pending to in-progress
            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(pendingTask)
            coEvery { mockTaskRepository.update(any()) } returns Result.Success(inProgressTask)
            every { mockDependencyRepository.findByTaskId(any()) } returns emptyList()
            every { mockDependencyRepository.findByToTaskId(any()) } returns emptyList()

            // Mock status progression service
            coEvery { mockStatusProgressionService.getNextStatus(any(), any(), any()) } returns
                NextStatusRecommendation.Ready(
                    recommendedStatus = "in-progress",
                    activeFlow = "default_flow",
                    flowSequence = listOf("pending", "in-progress", "completed"),
                    currentPosition = 0,
                    matchedTags = emptyList(),
                    reason = "Next status in flow"
                )
            every { mockStatusProgressionService.getRoleForStatus("pending", "task", any()) } returns "queue"
            every { mockStatusProgressionService.getRoleForStatus("in-progress", "task", any()) } returns "work"
            every { mockStatusProgressionService.getFlowPath(any(), any(), any()) } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("pending", "in-progress", "completed"),
                currentPosition = 0,
                matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = emptyList()
            )

            // Role transition recording fails
            coEvery { mockRoleTransitionRepository.create(any()) } returns Result.Error(
                RepositoryError.DatabaseError("Database connection lost")
            )

            val params = buildJsonObject {
                put("transitions", JsonArray(listOf(
                    buildJsonObject {
                        put("containerId", taskId.toString())
                        put("containerType", "task")
                        put("trigger", "start")
                    }
                )))
            }

            val result = tool.execute(params, context) as JsonObject

            // Transition should still succeed
            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"]!!.jsonObject
            val results = data["results"]!!.jsonArray
            val firstResult = results[0].jsonObject
            assertTrue(firstResult["applied"]!!.jsonPrimitive.boolean)
            assertEquals("pending", firstResult["previousStatus"]!!.jsonPrimitive.content)
            assertEquals("in-progress", firstResult["newStatus"]!!.jsonPrimitive.content)
        }

        @Test
        fun `does not record role transition when either role is null`() = runBlocking {
            // Setup: Task moving to new status but role resolution fails
            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(pendingTask)
            coEvery { mockTaskRepository.update(any()) } returns Result.Success(inProgressTask)
            every { mockDependencyRepository.findByTaskId(any()) } returns emptyList()
            every { mockDependencyRepository.findByToTaskId(any()) } returns emptyList()

            // Mock status progression service - one role returns null
            coEvery { mockStatusProgressionService.getNextStatus(any(), any(), any()) } returns
                NextStatusRecommendation.Ready(
                    recommendedStatus = "in-progress",
                    activeFlow = "default_flow",
                    flowSequence = listOf("pending", "in-progress", "completed"),
                    currentPosition = 0,
                    matchedTags = emptyList(),
                    reason = "Next status in flow"
                )
            every { mockStatusProgressionService.getRoleForStatus("pending", "task", any()) } returns "queue"
            every { mockStatusProgressionService.getRoleForStatus("in-progress", "task", any()) } returns null // Role not found
            every { mockStatusProgressionService.getFlowPath(any(), any(), any()) } returns FlowPath(
                activeFlow = "default_flow",
                flowSequence = listOf("pending", "in-progress", "completed"),
                currentPosition = 0,
                matchedTags = emptyList(),
                terminalStatuses = listOf("completed", "cancelled"),
                emergencyTransitions = emptyList()
            )

            val params = buildJsonObject {
                put("transitions", JsonArray(listOf(
                    buildJsonObject {
                        put("containerId", taskId.toString())
                        put("containerType", "task")
                        put("trigger", "start")
                    }
                )))
            }

            val result = tool.execute(params, context) as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean)

            // Verify no role transition was recorded (null role)
            coVerify(exactly = 0) {
                mockRoleTransitionRepository.create(any())
            }
        }
    }
}
