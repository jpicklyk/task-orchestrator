package io.github.jpicklyk.mcptask.application.tools.task

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

class GetBlockedTasksToolTest {

    private lateinit var tool: GetBlockedTasksTool
    private lateinit var context: ToolExecutionContext
    private lateinit var mockTaskRepository: TaskRepository
    private lateinit var mockFeatureRepository: FeatureRepository
    private lateinit var mockProjectRepository: ProjectRepository
    private lateinit var mockSectionRepository: SectionRepository
    private lateinit var mockDependencyRepository: DependencyRepository
    private lateinit var mockTemplateRepository: TemplateRepository
    private lateinit var mockRepositoryProvider: RepositoryProvider

    private val projectId = UUID.randomUUID()
    private val featureId = UUID.randomUUID()

    @BeforeEach
    fun setup() {
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

        // Create tool
        tool = GetBlockedTasksTool()
    }

    /**
     * Helper to create a Task with sensible defaults.
     */
    private fun createTask(
        id: UUID = UUID.randomUUID(),
        title: String = "Test Task",
        description: String? = "Test description",
        summary: String = "Test summary",
        status: TaskStatus = TaskStatus.PENDING,
        priority: Priority = Priority.HIGH,
        complexity: Int = 5,
        tags: List<String> = listOf("backend"),
        featureId: UUID? = this.featureId,
        projectId: UUID? = this.projectId
    ): Task {
        val now = Instant.now()
        return Task(
            id = id,
            title = title,
            description = description,
            summary = summary,
            status = status,
            priority = priority,
            complexity = complexity,
            tags = tags,
            featureId = featureId,
            projectId = projectId,
            createdAt = now,
            modifiedAt = now
        )
    }

    /**
     * Helper to create a Dependency.
     */
    private fun createDependency(
        fromTaskId: UUID,
        toTaskId: UUID,
        type: DependencyType = DependencyType.BLOCKS
    ): Dependency {
        return Dependency(
            id = UUID.randomUUID(),
            fromTaskId = fromTaskId,
            toTaskId = toTaskId,
            type = type,
            createdAt = Instant.now()
        )
    }

    /**
     * Helper to parse a tool result as a JsonObject.
     */
    private fun parseResult(result: JsonElement): JsonObject {
        return result as JsonObject
    }

    /**
     * Helper to extract the data object from a successful response.
     */
    private fun extractData(result: JsonObject): JsonObject {
        return result["data"]!!.jsonObject
    }

    /**
     * Helper to extract the blockedTasks array from the data object.
     */
    private fun extractBlockedTasks(result: JsonObject): JsonArray {
        return extractData(result)["blockedTasks"]!!.jsonArray
    }

    @Nested
    inner class ValidationTests {

        @Test
        fun `should accept empty params since all parameters are optional`() {
            val params = buildJsonObject { }

            assertDoesNotThrow {
                tool.validateParams(params)
            }
        }

        @Test
        fun `should reject invalid projectId format`() {
            val params = buildJsonObject {
                put("projectId", "not-a-valid-uuid")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("Invalid projectId format"))
        }

        @Test
        fun `should reject invalid featureId format`() {
            val params = buildJsonObject {
                put("featureId", "not-a-valid-uuid")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("Invalid featureId format"))
        }

        @Test
        fun `should accept valid projectId`() {
            val params = buildJsonObject {
                put("projectId", projectId.toString())
            }

            assertDoesNotThrow {
                tool.validateParams(params)
            }
        }

        @Test
        fun `should accept valid featureId`() {
            val params = buildJsonObject {
                put("featureId", featureId.toString())
            }

            assertDoesNotThrow {
                tool.validateParams(params)
            }
        }

        @Test
        fun `should accept valid projectId and featureId together`() {
            val params = buildJsonObject {
                put("projectId", projectId.toString())
                put("featureId", featureId.toString())
            }

            assertDoesNotThrow {
                tool.validateParams(params)
            }
        }

        @Test
        fun `should accept includeTaskDetails boolean parameter`() {
            val params = buildJsonObject {
                put("includeTaskDetails", true)
            }

            assertDoesNotThrow {
                tool.validateParams(params)
            }
        }
    }

    @Nested
    inner class NoBlockedTasksTests {

        @Test
        fun `should return empty when no active tasks exist`() = runBlocking {
            val params = buildJsonObject { }

            // findAll returns empty list
            coEvery { mockTaskRepository.findAll(limit = any()) } returns Result.Success(emptyList())

            val result = parseResult(tool.execute(params, context))

            assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
            val data = extractData(result)
            assertEquals(0, data["totalBlocked"]?.jsonPrimitive?.int)
            assertTrue(extractBlockedTasks(result).isEmpty())
        }

        @Test
        fun `should return empty when tasks have no dependencies`() = runBlocking {
            val task1 = createTask(title = "Task 1", status = TaskStatus.PENDING)
            val task2 = createTask(title = "Task 2", status = TaskStatus.IN_PROGRESS)

            val params = buildJsonObject { }

            coEvery { mockTaskRepository.findAll(limit = any()) } returns Result.Success(listOf(task1, task2))
            every { mockDependencyRepository.findByToTaskId(task1.id) } returns emptyList()
            every { mockDependencyRepository.findByToTaskId(task2.id) } returns emptyList()

            val result = parseResult(tool.execute(params, context))

            assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
            val data = extractData(result)
            assertEquals(0, data["totalBlocked"]?.jsonPrimitive?.int)
            assertTrue(extractBlockedTasks(result).isEmpty())
        }

        @Test
        fun `should return empty when all dependencies are satisfied`() = runBlocking {
            val blockerTask = createTask(title = "Blocker Task", status = TaskStatus.COMPLETED)
            val blockedTask = createTask(title = "Blocked Task", status = TaskStatus.PENDING)
            val dependency = createDependency(fromTaskId = blockerTask.id, toTaskId = blockedTask.id)

            val params = buildJsonObject { }

            // findAll returns both tasks, but only PENDING/IN_PROGRESS are active
            coEvery { mockTaskRepository.findAll(limit = any()) } returns
                    Result.Success(listOf(blockerTask, blockedTask))
            every { mockDependencyRepository.findByToTaskId(blockedTask.id) } returns listOf(dependency)
            coEvery { mockTaskRepository.getById(blockerTask.id) } returns Result.Success(blockerTask)

            val result = parseResult(tool.execute(params, context))

            assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
            assertEquals(0, extractData(result)["totalBlocked"]?.jsonPrimitive?.int)
        }

        @Test
        fun `should not consider cancelled tasks as blocked`() = runBlocking {
            // A cancelled task should be filtered out from active tasks
            val cancelledTask = createTask(title = "Cancelled Task", status = TaskStatus.CANCELLED)
            val blockerTask = createTask(title = "Blocker", status = TaskStatus.PENDING)
            val dependency = createDependency(fromTaskId = blockerTask.id, toTaskId = cancelledTask.id)

            val params = buildJsonObject { }

            coEvery { mockTaskRepository.findAll(limit = any()) } returns
                    Result.Success(listOf(cancelledTask, blockerTask))
            // Only PENDING/IN_PROGRESS tasks are checked; cancelledTask is filtered out before dependency check
            // blockerTask has no incoming dependencies
            every { mockDependencyRepository.findByToTaskId(blockerTask.id) } returns emptyList()

            val result = parseResult(tool.execute(params, context))

            assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
            assertEquals(0, extractData(result)["totalBlocked"]?.jsonPrimitive?.int)
        }

        @Test
        fun `should not consider completed tasks as blocked`() = runBlocking {
            val completedTask = createTask(title = "Completed Task", status = TaskStatus.COMPLETED)

            val params = buildJsonObject { }

            coEvery { mockTaskRepository.findAll(limit = any()) } returns
                    Result.Success(listOf(completedTask))

            val result = parseResult(tool.execute(params, context))

            assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
            assertEquals(0, extractData(result)["totalBlocked"]?.jsonPrimitive?.int)
        }
    }

    @Nested
    inner class BlockedTasksTests {

        @Test
        fun `should identify task blocked by pending dependency`() = runBlocking {
            val blockerTask = createTask(title = "Blocker Task", status = TaskStatus.PENDING, priority = Priority.HIGH)
            val blockedTask = createTask(title = "Blocked Task", status = TaskStatus.PENDING, priority = Priority.MEDIUM)
            val dependency = createDependency(fromTaskId = blockerTask.id, toTaskId = blockedTask.id)

            val params = buildJsonObject { }

            coEvery { mockTaskRepository.findAll(limit = any()) } returns
                    Result.Success(listOf(blockerTask, blockedTask))
            every { mockDependencyRepository.findByToTaskId(blockedTask.id) } returns listOf(dependency)
            every { mockDependencyRepository.findByToTaskId(blockerTask.id) } returns emptyList()
            coEvery { mockTaskRepository.getById(blockerTask.id) } returns Result.Success(blockerTask)

            val result = parseResult(tool.execute(params, context))

            assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
            val data = extractData(result)
            assertEquals(1, data["totalBlocked"]?.jsonPrimitive?.int)

            val blockedTasks = extractBlockedTasks(result)
            assertEquals(1, blockedTasks.size)

            val blockedEntry = blockedTasks[0].jsonObject
            assertEquals(blockedTask.id.toString(), blockedEntry["taskId"]?.jsonPrimitive?.content)
            assertEquals("Blocked Task", blockedEntry["title"]?.jsonPrimitive?.content)
        }

        @Test
        fun `should identify task blocked by in-progress dependency`() = runBlocking {
            val blockerTask = createTask(title = "In Progress Blocker", status = TaskStatus.IN_PROGRESS)
            val blockedTask = createTask(title = "Waiting Task", status = TaskStatus.PENDING)
            val dependency = createDependency(fromTaskId = blockerTask.id, toTaskId = blockedTask.id)

            val params = buildJsonObject { }

            coEvery { mockTaskRepository.findAll(limit = any()) } returns
                    Result.Success(listOf(blockerTask, blockedTask))
            every { mockDependencyRepository.findByToTaskId(blockedTask.id) } returns listOf(dependency)
            every { mockDependencyRepository.findByToTaskId(blockerTask.id) } returns emptyList()
            coEvery { mockTaskRepository.getById(blockerTask.id) } returns Result.Success(blockerTask)

            val result = parseResult(tool.execute(params, context))

            assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
            assertEquals(1, extractData(result)["totalBlocked"]?.jsonPrimitive?.int)

            val blockedEntry = extractBlockedTasks(result)[0].jsonObject
            assertEquals(blockedTask.id.toString(), blockedEntry["taskId"]?.jsonPrimitive?.content)

            // Verify blocker info
            val blockers = blockedEntry["blockedBy"]!!.jsonArray
            assertEquals(1, blockers.size)
            val blockerInfo = blockers[0].jsonObject
            assertEquals(blockerTask.id.toString(), blockerInfo["taskId"]?.jsonPrimitive?.content)
            assertEquals("in-progress", blockerInfo["status"]?.jsonPrimitive?.content)
        }

        @Test
        fun `should not count completed blocker as blocking`() = runBlocking {
            val completedBlocker = createTask(title = "Done Blocker", status = TaskStatus.COMPLETED)
            val pendingTask = createTask(title = "Pending Task", status = TaskStatus.PENDING)
            val dependency = createDependency(fromTaskId = completedBlocker.id, toTaskId = pendingTask.id)

            val params = buildJsonObject { }

            coEvery { mockTaskRepository.findAll(limit = any()) } returns
                    Result.Success(listOf(completedBlocker, pendingTask))
            every { mockDependencyRepository.findByToTaskId(pendingTask.id) } returns listOf(dependency)
            coEvery { mockTaskRepository.getById(completedBlocker.id) } returns Result.Success(completedBlocker)

            val result = parseResult(tool.execute(params, context))

            assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
            assertEquals(0, extractData(result)["totalBlocked"]?.jsonPrimitive?.int)
        }

        @Test
        fun `should not count cancelled blocker as blocking`() = runBlocking {
            val cancelledBlocker = createTask(title = "Cancelled Blocker", status = TaskStatus.CANCELLED)
            val pendingTask = createTask(title = "Pending Task", status = TaskStatus.PENDING)
            val dependency = createDependency(fromTaskId = cancelledBlocker.id, toTaskId = pendingTask.id)

            val params = buildJsonObject { }

            coEvery { mockTaskRepository.findAll(limit = any()) } returns
                    Result.Success(listOf(cancelledBlocker, pendingTask))
            every { mockDependencyRepository.findByToTaskId(pendingTask.id) } returns listOf(dependency)
            coEvery { mockTaskRepository.getById(cancelledBlocker.id) } returns Result.Success(cancelledBlocker)

            val result = parseResult(tool.execute(params, context))

            assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
            assertEquals(0, extractData(result)["totalBlocked"]?.jsonPrimitive?.int)
        }

        @Test
        fun `should include blocker count in response`() = runBlocking {
            val blocker1 = createTask(title = "Blocker 1", status = TaskStatus.PENDING)
            val blocker2 = createTask(title = "Blocker 2", status = TaskStatus.IN_PROGRESS)
            val blockedTask = createTask(title = "Blocked Task", status = TaskStatus.PENDING)
            val dep1 = createDependency(fromTaskId = blocker1.id, toTaskId = blockedTask.id)
            val dep2 = createDependency(fromTaskId = blocker2.id, toTaskId = blockedTask.id)

            val params = buildJsonObject { }

            coEvery { mockTaskRepository.findAll(limit = any()) } returns
                    Result.Success(listOf(blocker1, blocker2, blockedTask))
            every { mockDependencyRepository.findByToTaskId(blockedTask.id) } returns listOf(dep1, dep2)
            every { mockDependencyRepository.findByToTaskId(blocker1.id) } returns emptyList()
            every { mockDependencyRepository.findByToTaskId(blocker2.id) } returns emptyList()
            coEvery { mockTaskRepository.getById(blocker1.id) } returns Result.Success(blocker1)
            coEvery { mockTaskRepository.getById(blocker2.id) } returns Result.Success(blocker2)

            val result = parseResult(tool.execute(params, context))

            assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
            val blockedEntry = extractBlockedTasks(result)[0].jsonObject
            assertEquals(2, blockedEntry["blockerCount"]?.jsonPrimitive?.int)
            assertEquals(2, blockedEntry["blockedBy"]!!.jsonArray.size)
        }

        @Test
        fun `should include basic task info in blocked task entry`() = runBlocking {
            val blockerTask = createTask(title = "Blocker", status = TaskStatus.PENDING, priority = Priority.LOW)
            val blockedTask = createTask(
                title = "Blocked",
                status = TaskStatus.IN_PROGRESS,
                priority = Priority.HIGH,
                complexity = 8
            )
            val dependency = createDependency(fromTaskId = blockerTask.id, toTaskId = blockedTask.id)

            val params = buildJsonObject { }

            coEvery { mockTaskRepository.findAll(limit = any()) } returns
                    Result.Success(listOf(blockerTask, blockedTask))
            every { mockDependencyRepository.findByToTaskId(blockedTask.id) } returns listOf(dependency)
            every { mockDependencyRepository.findByToTaskId(blockerTask.id) } returns emptyList()
            coEvery { mockTaskRepository.getById(blockerTask.id) } returns Result.Success(blockerTask)

            val result = parseResult(tool.execute(params, context))

            val blockedEntry = extractBlockedTasks(result)[0].jsonObject
            assertEquals(blockedTask.id.toString(), blockedEntry["taskId"]?.jsonPrimitive?.content)
            assertEquals("Blocked", blockedEntry["title"]?.jsonPrimitive?.content)
            assertEquals("in-progress", blockedEntry["status"]?.jsonPrimitive?.content)
            assertEquals("high", blockedEntry["priority"]?.jsonPrimitive?.content)
            assertEquals(8, blockedEntry["complexity"]?.jsonPrimitive?.int)
        }
    }

    @Nested
    inner class ScopeFilterTests {

        @Test
        fun `should filter by featureId`() = runBlocking {
            val task = createTask(title = "Feature Task", status = TaskStatus.PENDING, featureId = featureId)

            val params = buildJsonObject {
                put("featureId", featureId.toString())
            }

            coEvery { mockTaskRepository.findByFeature(featureId, limit = any()) } returns
                    Result.Success(listOf(task))
            every { mockDependencyRepository.findByToTaskId(task.id) } returns emptyList()

            val result = parseResult(tool.execute(params, context))

            assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(result["message"]?.jsonPrimitive?.content?.contains("0 blocked") == true)
        }

        @Test
        fun `should filter by projectId`() = runBlocking {
            val task = createTask(title = "Project Task", status = TaskStatus.IN_PROGRESS, projectId = projectId)

            val params = buildJsonObject {
                put("projectId", projectId.toString())
            }

            coEvery { mockTaskRepository.findByProject(projectId, limit = any()) } returns
                    Result.Success(listOf(task))
            every { mockDependencyRepository.findByToTaskId(task.id) } returns emptyList()

            val result = parseResult(tool.execute(params, context))

            assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
        }

        @Test
        fun `should search all tasks when no filter provided`() = runBlocking {
            val task = createTask(title = "Any Task", status = TaskStatus.PENDING)

            val params = buildJsonObject { }

            coEvery { mockTaskRepository.findAll(limit = any()) } returns Result.Success(listOf(task))
            every { mockDependencyRepository.findByToTaskId(task.id) } returns emptyList()

            val result = parseResult(tool.execute(params, context))

            assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
        }

        @Test
        fun `should prefer featureId over projectId when both provided`() = runBlocking {
            // The tool implementation checks featureId first, then projectId
            val task = createTask(title = "Feature Scoped", status = TaskStatus.PENDING)

            val params = buildJsonObject {
                put("featureId", featureId.toString())
                put("projectId", projectId.toString())
            }

            // Only findByFeature should be called, not findByProject
            coEvery { mockTaskRepository.findByFeature(featureId, limit = any()) } returns
                    Result.Success(listOf(task))
            every { mockDependencyRepository.findByToTaskId(task.id) } returns emptyList()

            val result = parseResult(tool.execute(params, context))

            assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
        }

        @Test
        fun `should handle repository error gracefully`() = runBlocking {
            val params = buildJsonObject { }

            coEvery { mockTaskRepository.findAll(limit = any()) } returns
                    Result.Error(RepositoryError.DatabaseError("DB connection failed"))

            val result = parseResult(tool.execute(params, context))

            // Tool handles errors by returning empty list, resulting in 0 blocked
            assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
            assertEquals(0, extractData(result)["totalBlocked"]?.jsonPrimitive?.int)
        }
    }

    @Nested
    inner class DetailTests {

        @Test
        fun `should include full details when includeTaskDetails is true`() = runBlocking {
            val blockerTask = createTask(
                title = "Blocker",
                status = TaskStatus.PENDING,
                complexity = 7,
                featureId = featureId
            )
            val blockedTask = createTask(
                title = "Blocked Task",
                summary = "This task is blocked",
                status = TaskStatus.IN_PROGRESS,
                tags = listOf("api", "urgent"),
                featureId = featureId
            )
            val dependency = createDependency(fromTaskId = blockerTask.id, toTaskId = blockedTask.id)

            val params = buildJsonObject {
                put("includeTaskDetails", true)
            }

            coEvery { mockTaskRepository.findAll(limit = any()) } returns
                    Result.Success(listOf(blockerTask, blockedTask))
            every { mockDependencyRepository.findByToTaskId(blockedTask.id) } returns listOf(dependency)
            every { mockDependencyRepository.findByToTaskId(blockerTask.id) } returns emptyList()
            coEvery { mockTaskRepository.getById(blockerTask.id) } returns Result.Success(blockerTask)

            val result = parseResult(tool.execute(params, context))

            val blockedEntry = extractBlockedTasks(result)[0].jsonObject

            // Blocked task should include extra details
            assertEquals("This task is blocked", blockedEntry["summary"]?.jsonPrimitive?.content)
            assertEquals(featureId.toString(), blockedEntry["featureId"]?.jsonPrimitive?.content)
            assertTrue(blockedEntry.containsKey("tags"))
            val tags = blockedEntry["tags"]!!.jsonArray
            assertEquals(2, tags.size)

            // Blocker task details should also be enriched
            val blockerInfo = blockedEntry["blockedBy"]!!.jsonArray[0].jsonObject
            assertTrue(blockerInfo.containsKey("complexity"))
            assertEquals(7, blockerInfo["complexity"]?.jsonPrimitive?.int)
            assertTrue(blockerInfo.containsKey("featureId"))
        }

        @Test
        fun `should exclude details when includeTaskDetails is false`() = runBlocking {
            val blockerTask = createTask(
                title = "Blocker",
                status = TaskStatus.PENDING,
                complexity = 7,
                featureId = featureId
            )
            val blockedTask = createTask(
                title = "Blocked Task",
                summary = "This task is blocked",
                status = TaskStatus.IN_PROGRESS,
                tags = listOf("api", "urgent"),
                featureId = featureId
            )
            val dependency = createDependency(fromTaskId = blockerTask.id, toTaskId = blockedTask.id)

            val params = buildJsonObject {
                put("includeTaskDetails", false)
            }

            coEvery { mockTaskRepository.findAll(limit = any()) } returns
                    Result.Success(listOf(blockerTask, blockedTask))
            every { mockDependencyRepository.findByToTaskId(blockedTask.id) } returns listOf(dependency)
            every { mockDependencyRepository.findByToTaskId(blockerTask.id) } returns emptyList()
            coEvery { mockTaskRepository.getById(blockerTask.id) } returns Result.Success(blockerTask)

            val result = parseResult(tool.execute(params, context))

            val blockedEntry = extractBlockedTasks(result)[0].jsonObject

            // Without details, summary/featureId/tags should NOT be present on blocked task
            assertFalse(blockedEntry.containsKey("summary"))
            assertFalse(blockedEntry.containsKey("featureId"))
            assertFalse(blockedEntry.containsKey("tags"))

            // Blocker info should also lack detail fields
            val blockerInfo = blockedEntry["blockedBy"]!!.jsonArray[0].jsonObject
            assertFalse(blockerInfo.containsKey("complexity"))
            assertFalse(blockerInfo.containsKey("featureId"))
        }

        @Test
        fun `should default includeTaskDetails to false when not specified`() = runBlocking {
            val blockerTask = createTask(title = "Blocker", status = TaskStatus.PENDING)
            val blockedTask = createTask(
                title = "Blocked",
                summary = "Summary here",
                status = TaskStatus.PENDING,
                tags = listOf("test")
            )
            val dependency = createDependency(fromTaskId = blockerTask.id, toTaskId = blockedTask.id)

            // No includeTaskDetails in params
            val params = buildJsonObject { }

            coEvery { mockTaskRepository.findAll(limit = any()) } returns
                    Result.Success(listOf(blockerTask, blockedTask))
            every { mockDependencyRepository.findByToTaskId(blockedTask.id) } returns listOf(dependency)
            every { mockDependencyRepository.findByToTaskId(blockerTask.id) } returns emptyList()
            coEvery { mockTaskRepository.getById(blockerTask.id) } returns Result.Success(blockerTask)

            val result = parseResult(tool.execute(params, context))

            val blockedEntry = extractBlockedTasks(result)[0].jsonObject

            // Details should be excluded by default
            assertFalse(blockedEntry.containsKey("summary"))
            assertFalse(blockedEntry.containsKey("tags"))
        }
    }

    @Nested
    inner class MixedDependencyTests {

        @Test
        fun `should handle task with mix of satisfied and unsatisfied dependencies`() = runBlocking {
            val completedBlocker = createTask(title = "Done Blocker", status = TaskStatus.COMPLETED)
            val pendingBlocker = createTask(title = "Pending Blocker", status = TaskStatus.PENDING)
            val blockedTask = createTask(title = "Partially Blocked", status = TaskStatus.PENDING)
            val dep1 = createDependency(fromTaskId = completedBlocker.id, toTaskId = blockedTask.id)
            val dep2 = createDependency(fromTaskId = pendingBlocker.id, toTaskId = blockedTask.id)

            val params = buildJsonObject { }

            coEvery { mockTaskRepository.findAll(limit = any()) } returns
                    Result.Success(listOf(completedBlocker, pendingBlocker, blockedTask))
            every { mockDependencyRepository.findByToTaskId(blockedTask.id) } returns listOf(dep1, dep2)
            every { mockDependencyRepository.findByToTaskId(pendingBlocker.id) } returns emptyList()
            coEvery { mockTaskRepository.getById(completedBlocker.id) } returns Result.Success(completedBlocker)
            coEvery { mockTaskRepository.getById(pendingBlocker.id) } returns Result.Success(pendingBlocker)

            val result = parseResult(tool.execute(params, context))

            assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
            assertEquals(1, extractData(result)["totalBlocked"]?.jsonPrimitive?.int)

            val blockedEntry = extractBlockedTasks(result)[0].jsonObject
            assertEquals(blockedTask.id.toString(), blockedEntry["taskId"]?.jsonPrimitive?.content)

            // Only 1 incomplete blocker (the pending one); completed blocker should not appear
            assertEquals(1, blockedEntry["blockerCount"]?.jsonPrimitive?.int)
            val blockers = blockedEntry["blockedBy"]!!.jsonArray
            assertEquals(1, blockers.size)
            assertEquals(pendingBlocker.id.toString(), blockers[0].jsonObject["taskId"]?.jsonPrimitive?.content)
        }

        @Test
        fun `should handle multiple blocked tasks`() = runBlocking {
            val blocker = createTask(title = "Single Blocker", status = TaskStatus.IN_PROGRESS)
            val blockedTask1 = createTask(title = "Blocked Task 1", status = TaskStatus.PENDING)
            val blockedTask2 = createTask(title = "Blocked Task 2", status = TaskStatus.PENDING)
            val dep1 = createDependency(fromTaskId = blocker.id, toTaskId = blockedTask1.id)
            val dep2 = createDependency(fromTaskId = blocker.id, toTaskId = blockedTask2.id)

            val params = buildJsonObject { }

            coEvery { mockTaskRepository.findAll(limit = any()) } returns
                    Result.Success(listOf(blocker, blockedTask1, blockedTask2))
            every { mockDependencyRepository.findByToTaskId(blocker.id) } returns emptyList()
            every { mockDependencyRepository.findByToTaskId(blockedTask1.id) } returns listOf(dep1)
            every { mockDependencyRepository.findByToTaskId(blockedTask2.id) } returns listOf(dep2)
            coEvery { mockTaskRepository.getById(blocker.id) } returns Result.Success(blocker)

            val result = parseResult(tool.execute(params, context))

            assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
            assertEquals(2, extractData(result)["totalBlocked"]?.jsonPrimitive?.int)

            val blockedTasks = extractBlockedTasks(result)
            assertEquals(2, blockedTasks.size)

            // Both blocked tasks should reference the same blocker
            val blockerIds = blockedTasks.map { entry ->
                entry.jsonObject["blockedBy"]!!.jsonArray[0].jsonObject["taskId"]?.jsonPrimitive?.content
            }.toSet()
            assertEquals(setOf(blocker.id.toString()), blockerIds)
        }

        @Test
        fun `should handle chain of blocked tasks`() = runBlocking {
            // A -> B -> C where A blocks B and B blocks C
            val taskA = createTask(title = "Task A", status = TaskStatus.PENDING)
            val taskB = createTask(title = "Task B", status = TaskStatus.PENDING)
            val taskC = createTask(title = "Task C", status = TaskStatus.PENDING)
            val depAB = createDependency(fromTaskId = taskA.id, toTaskId = taskB.id)
            val depBC = createDependency(fromTaskId = taskB.id, toTaskId = taskC.id)

            val params = buildJsonObject { }

            coEvery { mockTaskRepository.findAll(limit = any()) } returns
                    Result.Success(listOf(taskA, taskB, taskC))
            every { mockDependencyRepository.findByToTaskId(taskA.id) } returns emptyList()
            every { mockDependencyRepository.findByToTaskId(taskB.id) } returns listOf(depAB)
            every { mockDependencyRepository.findByToTaskId(taskC.id) } returns listOf(depBC)
            coEvery { mockTaskRepository.getById(taskA.id) } returns Result.Success(taskA)
            coEvery { mockTaskRepository.getById(taskB.id) } returns Result.Success(taskB)

            val result = parseResult(tool.execute(params, context))

            assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
            // Both B and C are blocked
            assertEquals(2, extractData(result)["totalBlocked"]?.jsonPrimitive?.int)
        }

        @Test
        fun `should handle blocker task not found in repository`() = runBlocking {
            val blockedTask = createTask(title = "Blocked Task", status = TaskStatus.PENDING)
            val missingBlockerId = UUID.randomUUID()
            val dependency = createDependency(fromTaskId = missingBlockerId, toTaskId = blockedTask.id)

            val params = buildJsonObject { }

            coEvery { mockTaskRepository.findAll(limit = any()) } returns
                    Result.Success(listOf(blockedTask))
            every { mockDependencyRepository.findByToTaskId(blockedTask.id) } returns listOf(dependency)
            coEvery { mockTaskRepository.getById(missingBlockerId) } returns
                    Result.Error(RepositoryError.NotFound(missingBlockerId, EntityType.TASK, "Task not found"))

            val result = parseResult(tool.execute(params, context))

            // Tool should handle the error gracefully and not crash
            assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
            // Since the blocker couldn't be retrieved, no incomplete blockers found
            assertEquals(0, extractData(result)["totalBlocked"]?.jsonPrimitive?.int)
        }

        @Test
        fun `should only consider PENDING and IN_PROGRESS tasks as potentially blocked`() = runBlocking {
            // Create tasks in various statuses - only PENDING and IN_PROGRESS are active
            val pendingTask = createTask(title = "Pending", status = TaskStatus.PENDING)
            val inProgressTask = createTask(title = "In Progress", status = TaskStatus.IN_PROGRESS)
            val completedTask = createTask(title = "Completed", status = TaskStatus.COMPLETED)
            val cancelledTask = createTask(title = "Cancelled", status = TaskStatus.CANCELLED)
            val deferredTask = createTask(title = "Deferred", status = TaskStatus.DEFERRED)

            val blocker = createTask(title = "Blocker", status = TaskStatus.PENDING)

            // Create dependencies for all tasks
            val dep1 = createDependency(fromTaskId = blocker.id, toTaskId = pendingTask.id)
            val dep2 = createDependency(fromTaskId = blocker.id, toTaskId = inProgressTask.id)
            val dep3 = createDependency(fromTaskId = blocker.id, toTaskId = completedTask.id)
            val dep4 = createDependency(fromTaskId = blocker.id, toTaskId = cancelledTask.id)
            val dep5 = createDependency(fromTaskId = blocker.id, toTaskId = deferredTask.id)

            val params = buildJsonObject { }

            coEvery { mockTaskRepository.findAll(limit = any()) } returns
                    Result.Success(listOf(pendingTask, inProgressTask, completedTask, cancelledTask, deferredTask, blocker))
            // Only pending and in-progress tasks should have their dependencies checked
            every { mockDependencyRepository.findByToTaskId(pendingTask.id) } returns listOf(dep1)
            every { mockDependencyRepository.findByToTaskId(inProgressTask.id) } returns listOf(dep2)
            every { mockDependencyRepository.findByToTaskId(blocker.id) } returns emptyList()
            coEvery { mockTaskRepository.getById(blocker.id) } returns Result.Success(blocker)

            val result = parseResult(tool.execute(params, context))

            assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
            // Only pendingTask and inProgressTask should be considered blocked
            assertEquals(2, extractData(result)["totalBlocked"]?.jsonPrimitive?.int)
        }

        @Test
        fun `should format status names with hyphens in response`() = runBlocking {
            val blockerTask = createTask(title = "Blocker", status = TaskStatus.IN_PROGRESS)
            val blockedTask = createTask(title = "Blocked", status = TaskStatus.IN_PROGRESS)
            val dependency = createDependency(fromTaskId = blockerTask.id, toTaskId = blockedTask.id)

            val params = buildJsonObject { }

            coEvery { mockTaskRepository.findAll(limit = any()) } returns
                    Result.Success(listOf(blockerTask, blockedTask))
            every { mockDependencyRepository.findByToTaskId(blockedTask.id) } returns listOf(dependency)
            every { mockDependencyRepository.findByToTaskId(blockerTask.id) } returns emptyList()
            coEvery { mockTaskRepository.getById(blockerTask.id) } returns Result.Success(blockerTask)

            val result = parseResult(tool.execute(params, context))

            val blockedEntry = extractBlockedTasks(result)[0].jsonObject
            // IN_PROGRESS should become "in-progress" in the response
            assertEquals("in-progress", blockedEntry["status"]?.jsonPrimitive?.content)

            val blockerInfo = blockedEntry["blockedBy"]!!.jsonArray[0].jsonObject
            assertEquals("in-progress", blockerInfo["status"]?.jsonPrimitive?.content)
        }

        @Test
        fun `should include correct message with blocked task count`() = runBlocking {
            val blocker = createTask(title = "Blocker", status = TaskStatus.PENDING)
            val blocked1 = createTask(title = "Blocked 1", status = TaskStatus.PENDING)
            val blocked2 = createTask(title = "Blocked 2", status = TaskStatus.PENDING)
            val dep1 = createDependency(fromTaskId = blocker.id, toTaskId = blocked1.id)
            val dep2 = createDependency(fromTaskId = blocker.id, toTaskId = blocked2.id)

            val params = buildJsonObject { }

            coEvery { mockTaskRepository.findAll(limit = any()) } returns
                    Result.Success(listOf(blocker, blocked1, blocked2))
            every { mockDependencyRepository.findByToTaskId(blocker.id) } returns emptyList()
            every { mockDependencyRepository.findByToTaskId(blocked1.id) } returns listOf(dep1)
            every { mockDependencyRepository.findByToTaskId(blocked2.id) } returns listOf(dep2)
            coEvery { mockTaskRepository.getById(blocker.id) } returns Result.Success(blocker)

            val result = parseResult(tool.execute(params, context))

            val message = result["message"]?.jsonPrimitive?.content
            assertNotNull(message)
            assertTrue(message!!.contains("2 blocked task"))
        }
    }
}
