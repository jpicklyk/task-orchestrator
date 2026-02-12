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

        // Default mock for role lookups (returns null unless overridden)
        every { mockStatusProgressionService.getRoleForStatus(any(), any(), any()) } returns null

        context = ToolExecutionContext(mockRepositoryProvider)
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

            // getById(taskAId) is called multiple times:
            // 1. fetchEntityDetails() - needs IN_PROGRESS
            // 2. StatusValidator.validateTaskPrerequisites for "completed" - needs task for summary check
            // 3. applyStatusChange() - needs IN_PROGRESS to copy
            // 4. verification gate check (trigger=complete) - needs task
            // 5. findNewlyUnblockedTasks() blocker check - needs COMPLETED
            coEvery { mockTaskRepository.getById(taskAId) } returnsMany listOf(
                Result.Success(taskA),       // fetchEntityDetails
                Result.Success(taskA),       // StatusValidator prerequisite check (summary length)
                Result.Success(taskA),       // applyStatusChange
                Result.Success(taskA),       // verification gate check
                Result.Success(completedTaskA) // findNewlyUnblockedTasks blocker check
            )

            coEvery { mockTaskRepository.update(any()) } returns Result.Success(completedTaskA)

            // StatusValidator prerequisite checks
            every { mockDependencyRepository.findByTaskId(any()) } returns emptyList()
            every { mockDependencyRepository.findByToTaskId(taskAId) } returns emptyList()

            // findNewlyUnblockedTasks: outgoing BLOCKS deps from Task A
            every { mockDependencyRepository.findByFromTaskId(taskAId) } returns listOf(
                Dependency(fromTaskId = taskAId, toTaskId = taskBId, type = DependencyType.BLOCKS)
            )

            // findNewlyUnblockedTasks: downstream task B lookup
            coEvery { mockTaskRepository.getById(taskBId) } returns Result.Success(taskB)

            // findNewlyUnblockedTasks: all incoming blockers for Task B
            every { mockDependencyRepository.findByToTaskId(taskBId) } returns listOf(
                Dependency(fromTaskId = taskAId, toTaskId = taskBId, type = DependencyType.BLOCKS)
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
            // 3. findNewlyUnblockedTasks blocker check - CANCELLED
            coEvery { mockTaskRepository.getById(taskAId) } returnsMany listOf(
                Result.Success(taskA),
                Result.Success(taskA),
                Result.Success(cancelledTaskA)
            )
            coEvery { mockTaskRepository.update(any()) } returns Result.Success(cancelledTaskA)

            every { mockDependencyRepository.findByTaskId(any()) } returns emptyList()
            every { mockDependencyRepository.findByToTaskId(taskAId) } returns emptyList()

            // outgoing BLOCKS deps from Task A
            every { mockDependencyRepository.findByFromTaskId(taskAId) } returns listOf(
                Dependency(fromTaskId = taskAId, toTaskId = taskBId, type = DependencyType.BLOCKS)
            )

            // downstream Task B lookup
            coEvery { mockTaskRepository.getById(taskBId) } returns Result.Success(taskB)

            // All incoming blockers for Task B: only Task A
            every { mockDependencyRepository.findByToTaskId(taskBId) } returns listOf(
                Dependency(fromTaskId = taskAId, toTaskId = taskBId, type = DependencyType.BLOCKS)
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
}
