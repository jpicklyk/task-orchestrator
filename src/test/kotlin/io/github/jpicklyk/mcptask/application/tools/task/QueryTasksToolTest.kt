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

class QueryTasksToolTest {
    private lateinit var tool: QueryTasksTool
    private lateinit var context: ToolExecutionContext
    private lateinit var mockTaskRepository: TaskRepository
    private lateinit var mockFeatureRepository: FeatureRepository
    private lateinit var mockDependencyRepository: DependencyRepository
    private lateinit var mockRepositoryProvider: RepositoryProvider

    private val featureId = UUID.randomUUID()
    private val projectId = UUID.randomUUID()

    // Test tasks with different attributes
    private lateinit var task1: Task
    private lateinit var task2: Task
    private lateinit var task3: Task
    private lateinit var task4: Task
    private lateinit var allTasks: List<Task>

    @BeforeEach
    fun setup() {
        // Create mock repositories
        mockTaskRepository = mockk()
        mockFeatureRepository = mockk()
        mockDependencyRepository = mockk()
        mockRepositoryProvider = mockk()

        // Configure repository provider
        every { mockRepositoryProvider.taskRepository() } returns mockTaskRepository
        every { mockRepositoryProvider.featureRepository() } returns mockFeatureRepository
        every { mockRepositoryProvider.dependencyRepository() } returns mockDependencyRepository

        // Create test tasks
        task1 = Task(
            id = UUID.randomUUID(),
            title = "API Authentication",
            summary = "Implement OAuth 2.0 authentication flow",
            status = TaskStatus.PENDING,
            priority = Priority.HIGH,
            tags = listOf("api", "security"),
            complexity = 7,
            createdAt = Instant.parse("2025-05-01T10:00:00Z"),
            modifiedAt = Instant.parse("2025-05-01T11:00:00Z")
        )

        task2 = Task(
            id = UUID.randomUUID(),
            title = "User Dashboard",
            summary = "Create responsive user dashboard",
            status = TaskStatus.IN_PROGRESS,
            priority = Priority.MEDIUM,
            tags = listOf("ui", "frontend"),
            complexity = 5,
            featureId = featureId,
            createdAt = Instant.parse("2025-05-02T10:00:00Z"),
            modifiedAt = Instant.parse("2025-05-02T11:00:00Z")
        )

        task3 = Task(
            id = UUID.randomUUID(),
            title = "Database Optimization",
            summary = "Improve query performance for user reports",
            status = TaskStatus.COMPLETED,
            priority = Priority.LOW,
            tags = listOf("database", "performance"),
            complexity = 4,
            projectId = projectId,
            createdAt = Instant.parse("2025-05-03T10:00:00Z"),
            modifiedAt = Instant.parse("2025-05-03T11:00:00Z")
        )

        task4 = Task(
            id = UUID.randomUUID(),
            title = "Payment Integration",
            summary = "Integrate with Stripe for payment processing",
            status = TaskStatus.PENDING,
            priority = Priority.HIGH,
            tags = listOf("api", "payment"),
            complexity = 8,
            featureId = featureId,
            projectId = projectId,
            createdAt = Instant.parse("2025-05-04T10:00:00Z"),
            modifiedAt = Instant.parse("2025-05-04T11:00:00Z")
        )

        allTasks = listOf(task1, task2, task3, task4)

        // Create execution context
        context = ToolExecutionContext(mockRepositoryProvider)

        // Create tool
        tool = QueryTasksTool()
    }

    @Nested
    inner class QueryTypeRoutingTests {
        @Test
        fun `should route to search when queryType is search`() = runBlocking {
            val params = buildJsonObject {
                put("queryType", "search")
            }

            coEvery { mockTaskRepository.findAll(any()) } returns Result.Success(allTasks)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertNotNull(resultObj["data"]?.jsonObject?.get("items"))
        }

        @Test
        fun `should route to blocked when queryType is blocked`() = runBlocking {
            val params = buildJsonObject {
                put("queryType", "blocked")
            }

            coEvery { mockTaskRepository.findAll(any()) } returns Result.Success(allTasks)
            coEvery { mockDependencyRepository.findByToTaskId(any()) } returns emptyList()

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertNotNull(resultObj["data"]?.jsonObject?.get("blockedTasks"))
        }

        @Test
        fun `should route to next when queryType is next`() = runBlocking {
            val params = buildJsonObject {
                put("queryType", "next")
            }

            coEvery { mockTaskRepository.findAll(any()) } returns Result.Success(allTasks)
            coEvery { mockDependencyRepository.findByToTaskId(any()) } returns emptyList()

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertNotNull(resultObj["data"]?.jsonObject?.get("recommendations"))
        }

        @Test
        fun `should route to bulkUpdate when queryType is bulkUpdate`() = runBlocking {
            val params = buildJsonObject {
                put("queryType", "bulkUpdate")
                put("tasks", buildJsonArray {
                    add(buildJsonObject {
                        put("id", task1.id.toString())
                        put("status", "completed")
                    })
                })
            }

            coEvery { mockTaskRepository.getById(task1.id) } returns Result.Success(task1)
            coEvery { mockTaskRepository.update(any()) } returns Result.Success(task1.copy(status = TaskStatus.COMPLETED))

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
        }

        @Test
        fun `should fail when queryType is invalid`() {
            val params = buildJsonObject {
                put("queryType", "invalid-query")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }

            assertTrue(exception.message?.contains("Invalid queryType") == true)
        }
    }

    @Nested
    inner class SearchQueryTests {
        @Test
        fun `should search tasks by status`() = runBlocking {
            val params = buildJsonObject {
                put("queryType", "search")
                put("status", "pending")
            }

            val pendingTasks = allTasks.filter { it.status == TaskStatus.PENDING }

            coEvery {
                mockTaskRepository.findByFilters(
                    textQuery = null,
                    status = TaskStatus.PENDING,
                    priority = null,
                    tags = null,
                    projectId = null,
                    limit = any()
                )
            } returns Result.Success(pendingTasks)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)

            val items = resultObj["data"]?.jsonObject?.get("items")?.jsonArray
            items?.forEach { item ->
                assertEquals("pending", item.jsonObject["status"]?.jsonPrimitive?.content)
            }
        }

        @Test
        fun `should search tasks by priority`() = runBlocking {
            val params = buildJsonObject {
                put("queryType", "search")
                put("priority", "high")
            }

            val highPriorityTasks = allTasks.filter { it.priority == Priority.HIGH }

            coEvery {
                mockTaskRepository.findByFilters(
                    textQuery = null,
                    status = null,
                    priority = Priority.HIGH,
                    tags = null,
                    projectId = null,
                    limit = any()
                )
            } returns Result.Success(highPriorityTasks)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            val items = resultObj["data"]?.jsonObject?.get("items")?.jsonArray

            items?.forEach { item ->
                assertEquals("high", item.jsonObject["priority"]?.jsonPrimitive?.content)
            }
        }

        @Test
        fun `should search tasks by tag`() = runBlocking {
            val params = buildJsonObject {
                put("queryType", "search")
                put("tag", "api")
            }

            val apiTasks = allTasks.filter { it.tags.contains("api") }

            coEvery {
                mockTaskRepository.findByFilters(
                    textQuery = null,
                    status = null,
                    priority = null,
                    tags = listOf("api"),
                    projectId = null,
                    limit = any()
                )
            } returns Result.Success(apiTasks)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            val items = resultObj["data"]?.jsonObject?.get("items")?.jsonArray

            items?.forEach { item ->
                val tags = item.jsonObject["tags"]?.jsonArray
                assertTrue(tags?.any { it.jsonPrimitive.content == "api" } == true)
            }
        }

        @Test
        fun `should search tasks by text query`() = runBlocking {
            val params = buildJsonObject {
                put("queryType", "search")
                put("query", "authentication")
            }

            val authTasks = allTasks.filter {
                it.title.contains("authentication", ignoreCase = true) ||
                        it.summary.contains("authentication", ignoreCase = true)
            }

            coEvery {
                mockTaskRepository.findByFilters(
                    textQuery = "authentication",
                    status = null,
                    priority = null,
                    tags = null,
                    projectId = null,
                    limit = any()
                )
            } returns Result.Success(authTasks)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            val items = resultObj["data"]?.jsonObject?.get("items")?.jsonArray

            assertTrue(items!!.size > 0)
        }

        @Test
        fun `should paginate search results`() = runBlocking {
            val params = buildJsonObject {
                put("queryType", "search")
                put("limit", 2)
                put("offset", 2)  // offset=2 gives page 2 with limit=2
            }

            coEvery { mockTaskRepository.findAll(any()) } returns Result.Success(allTasks)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            val pagination = resultObj["data"]?.jsonObject?.get("pagination")?.jsonObject

            assertNotNull(pagination)
            assertEquals(2, pagination!!["page"]?.jsonPrimitive?.int)
            assertEquals(2, pagination["pageSize"]?.jsonPrimitive?.int)
            assertEquals(4, pagination["totalItems"]?.jsonPrimitive?.int)
        }

        @Test
        fun `should sort search results`() = runBlocking {
            val params = buildJsonObject {
                put("queryType", "search")
                put("sortBy", "complexity")
                put("sortDirection", "asc")
            }

            coEvery { mockTaskRepository.findAll(any()) } returns Result.Success(allTasks)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            val items = resultObj["data"]?.jsonObject?.get("items")?.jsonArray

            // Verify sorting
            for (i in 0 until (items!!.size - 1)) {
                val current = items[i].jsonObject["complexity"]?.jsonPrimitive?.int ?: 0
                val next = items[i + 1].jsonObject["complexity"]?.jsonPrimitive?.int ?: 0
                assertTrue(current <= next)
            }
        }
    }

    @Nested
    inner class BlockedQueryTests {
        @Test
        fun `should find tasks blocked by incomplete dependencies`() = runBlocking {
            val params = buildJsonObject {
                put("queryType", "blocked")
            }

            val blockerTask = task1
            val blockedTask = task2

            val dependency = Dependency(
                id = UUID.randomUUID(),
                fromTaskId = blockerTask.id,
                toTaskId = blockedTask.id,
                type = DependencyType.BLOCKS
            )

            coEvery { mockTaskRepository.findAll(any()) } returns Result.Success(listOf(blockedTask, blockerTask))
            coEvery { mockDependencyRepository.findByToTaskId(blockedTask.id) } returns listOf(dependency)
            coEvery { mockDependencyRepository.findByToTaskId(blockerTask.id) } returns emptyList()
            coEvery { mockTaskRepository.getById(blockerTask.id) } returns Result.Success(blockerTask)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            val blockedTasks = resultObj["data"]?.jsonObject?.get("blockedTasks")?.jsonArray

            assertTrue(blockedTasks!!.size > 0)
            assertEquals(blockedTask.id.toString(), blockedTasks[0].jsonObject["taskId"]?.jsonPrimitive?.content)
        }

        @Test
        fun `should not include tasks with all dependencies completed`() = runBlocking {
            val params = buildJsonObject {
                put("queryType", "blocked")
            }

            val completedBlocker = task3.copy(status = TaskStatus.COMPLETED)
            val unblockedTask = task4

            val dependency = Dependency(
                id = UUID.randomUUID(),
                fromTaskId = completedBlocker.id,
                toTaskId = unblockedTask.id,
                type = DependencyType.BLOCKS
            )

            coEvery { mockTaskRepository.findAll(any()) } returns Result.Success(listOf(unblockedTask, completedBlocker))
            coEvery { mockDependencyRepository.findByToTaskId(unblockedTask.id) } returns listOf(dependency)
            coEvery { mockDependencyRepository.findByToTaskId(completedBlocker.id) } returns emptyList()
            coEvery { mockTaskRepository.getById(completedBlocker.id) } returns Result.Success(completedBlocker)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            val blockedTasks = resultObj["data"]?.jsonObject?.get("blockedTasks")?.jsonArray

            assertEquals(0, blockedTasks!!.size)
        }

        @Test
        fun `should filter blocked tasks by feature`() = runBlocking {
            val params = buildJsonObject {
                put("queryType", "blocked")
                put("featureId", featureId.toString())
            }

            val featureTasks = allTasks.filter { it.featureId == featureId }

            coEvery { mockTaskRepository.findByFeature(featureId, any(), any(), any()) } returns Result.Success(featureTasks)
            coEvery { mockDependencyRepository.findByToTaskId(any()) } returns emptyList()
            every { mockRepositoryProvider.dependencyRepository() } returns mockDependencyRepository

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            // Verify it found the feature tasks (none should be blocked since no dependencies)
            val blockedTasks = resultObj["data"]?.jsonObject?.get("blockedTasks")?.jsonArray
            assertEquals(0, blockedTasks?.size ?: -1)
        }
    }

    @Nested
    inner class NextQueryTests {
        @Test
        fun `should recommend next task by priority and complexity`() = runBlocking {
            val params = buildJsonObject {
                put("queryType", "next")
            }

            val pendingTasks = allTasks.filter { it.status == TaskStatus.PENDING }

            coEvery { mockTaskRepository.findAll(any()) } returns Result.Success(pendingTasks)
            coEvery { mockDependencyRepository.findByToTaskId(any()) } returns emptyList()

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            val recommendations = resultObj["data"]?.jsonObject?.get("recommendations")?.jsonArray

            assertTrue(recommendations!!.size > 0)

            // First recommendation should be high priority task
            val firstRec = recommendations[0].jsonObject
            assertEquals("high", firstRec["priority"]?.jsonPrimitive?.content)
        }

        @Test
        fun `should exclude blocked tasks from recommendations`() = runBlocking {
            val params = buildJsonObject {
                put("queryType", "next")
            }

            val blockerTask = task1
            val blockedTask = task4

            val dependency = Dependency(
                id = UUID.randomUUID(),
                fromTaskId = blockerTask.id,
                toTaskId = blockedTask.id,
                type = DependencyType.BLOCKS
            )

            coEvery { mockTaskRepository.findAll(any()) } returns Result.Success(listOf(blockerTask, blockedTask))
            coEvery { mockDependencyRepository.findByToTaskId(blockerTask.id) } returns emptyList()
            coEvery { mockDependencyRepository.findByToTaskId(blockedTask.id) } returns listOf(dependency)
            coEvery { mockTaskRepository.getById(blockerTask.id) } returns Result.Success(blockerTask)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            val recommendations = resultObj["data"]?.jsonObject?.get("recommendations")?.jsonArray

            // Should only recommend unblocked task
            assertEquals(1, recommendations!!.size)
            assertEquals(blockerTask.id.toString(), recommendations[0].jsonObject["taskId"]?.jsonPrimitive?.content)
        }

        @Test
        fun `should limit results when limit parameter provided`() = runBlocking {
            val params = buildJsonObject {
                put("queryType", "next")
                put("limit", 2)
            }

            val pendingTasks = allTasks.filter { it.status == TaskStatus.PENDING }

            coEvery { mockTaskRepository.findAll(any()) } returns Result.Success(pendingTasks)
            coEvery { mockDependencyRepository.findByToTaskId(any()) } returns emptyList()

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            val recommendations = resultObj["data"]?.jsonObject?.get("recommendations")?.jsonArray

            assertTrue(recommendations!!.size <= 2)
        }
    }

    @Nested
    inner class BulkUpdateQueryTests {
        @Test
        fun `should update multiple tasks atomically`() = runBlocking {
            val params = buildJsonObject {
                put("queryType", "bulkUpdate")
                put("tasks", buildJsonArray {
                    add(buildJsonObject {
                        put("id", task1.id.toString())
                        put("status", "completed")
                    })
                    add(buildJsonObject {
                        put("id", task2.id.toString())
                        put("priority", "high")
                    })
                })
            }

            coEvery { mockTaskRepository.getById(task1.id) } returns Result.Success(task1)
            coEvery { mockTaskRepository.getById(task2.id) } returns Result.Success(task2)
            coEvery { mockTaskRepository.update(any()) } answers {
                Result.Success(firstArg())
            }

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)

            val data = resultObj["data"]?.jsonObject
            assertEquals(2, data!!["updated"]?.jsonPrimitive?.int)
            assertEquals(0, data["failed"]?.jsonPrimitive?.int)
        }

        @Test
        fun `should validate max 100 tasks`() {
            val tasksArray = buildJsonArray {
                repeat(101) {
                    add(buildJsonObject {
                        put("id", UUID.randomUUID().toString())
                        put("status", "completed")
                    })
                }
            }

            val params = buildJsonObject {
                put("queryType", "bulkUpdate")
                put("tasks", tasksArray)
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }

            assertTrue(exception.message?.contains("Maximum 100 tasks") == true)
        }

        @Test
        fun `should handle partial failures in bulk update`() = runBlocking {
            val nonExistentId = UUID.randomUUID()

            val params = buildJsonObject {
                put("queryType", "bulkUpdate")
                put("tasks", buildJsonArray {
                    add(buildJsonObject {
                        put("id", task1.id.toString())
                        put("status", "completed")
                    })
                    add(buildJsonObject {
                        put("id", nonExistentId.toString())
                        put("status", "completed")
                    })
                })
            }

            coEvery { mockTaskRepository.getById(task1.id) } returns Result.Success(task1)
            coEvery { mockTaskRepository.getById(nonExistentId) } returns Result.Error(RepositoryError.NotFound(nonExistentId, EntityType.TASK, "Task not found"))
            coEvery { mockTaskRepository.update(any()) } returns Result.Success(task1.copy(status = TaskStatus.COMPLETED))

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)

            val data = resultObj["data"]?.jsonObject
            assertEquals(1, data!!["updated"]?.jsonPrimitive?.int)
            assertEquals(1, data["failed"]?.jsonPrimitive?.int)
        }

        @Test
        fun `should validate feature exists when updating featureId`() = runBlocking {
            val invalidFeatureId = UUID.randomUUID()

            val params = buildJsonObject {
                put("queryType", "bulkUpdate")
                put("tasks", buildJsonArray {
                    add(buildJsonObject {
                        put("id", task1.id.toString())
                        put("featureId", invalidFeatureId.toString())
                    })
                })
            }

            coEvery { mockTaskRepository.getById(task1.id) } returns Result.Success(task1)
            coEvery { mockFeatureRepository.getById(invalidFeatureId) } returns Result.Error(RepositoryError.NotFound(invalidFeatureId, EntityType.FEATURE, "Feature not found"))

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            // When all tasks fail, the tool returns an error response (success=false)
            assertEquals(false, resultObj["success"]?.jsonPrimitive?.boolean)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("Failed to update any tasks") == true)
        }
    }

    @Nested
    inner class ValidationTests {
        @Test
        fun `should validate search parameters`() {
            val validParams = buildJsonObject {
                put("queryType", "search")
                put("status", "pending")
                put("priority", "high")
                put("limit", 50)
                put("offset", 10)
            }

            assertDoesNotThrow { tool.validateParams(validParams) }
        }

        @Test
        fun `should reject invalid status in search`() {
            val params = buildJsonObject {
                put("queryType", "search")
                put("status", "invalid-status")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }

            assertTrue(exception.message?.contains("Invalid status") == true)
        }

        @Test
        fun `should reject invalid priority in search`() {
            val params = buildJsonObject {
                put("queryType", "search")
                put("priority", "invalid-priority")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }

            assertTrue(exception.message?.contains("Invalid priority") == true)
        }

        @Test
        fun `should reject out of range limit`() {
            val paramsBelow = buildJsonObject {
                put("queryType", "search")
                put("limit", 0)
            }

            assertThrows<ToolValidationException> {
                tool.validateParams(paramsBelow)
            }

            val paramsAbove = buildJsonObject {
                put("queryType", "search")
                put("limit", 101)
            }

            assertThrows<ToolValidationException> {
                tool.validateParams(paramsAbove)
            }
        }

        @Test
        fun `should reject negative offset`() {
            val params = buildJsonObject {
                put("queryType", "search")
                put("offset", -1)
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }

            assertTrue(exception.message?.contains("non-negative") == true)
        }

        @Test
        fun `should validate bulkUpdate tasks array`() {
            val params = buildJsonObject {
                put("queryType", "bulkUpdate")
                put("tasks", buildJsonArray { })
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }

            assertTrue(exception.message?.contains("At least one task") == true)
        }

        @Test
        fun `should validate bulkUpdate task objects have id`() {
            val params = buildJsonObject {
                put("queryType", "bulkUpdate")
                put("tasks", buildJsonArray {
                    add(buildJsonObject {
                        put("status", "completed")
                    })
                })
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }

            assertTrue(exception.message?.contains("missing required field: id") == true)
        }

        @Test
        fun `should validate bulkUpdate tasks have at least one field to update`() {
            val params = buildJsonObject {
                put("queryType", "bulkUpdate")
                put("tasks", buildJsonArray {
                    add(buildJsonObject {
                        put("id", UUID.randomUUID().toString())
                    })
                })
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }

            assertTrue(exception.message?.contains("no fields to update") == true)
        }
    }

    @Nested
    inner class ErrorHandlingTests {
        @Test
        fun `should handle database errors in search`() = runBlocking {
            val params = buildJsonObject {
                put("queryType", "search")
            }

            coEvery { mockTaskRepository.findAll(any()) } returns Result.Error(RepositoryError.DatabaseError("Connection failed"))

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertFalse(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("Failed to search") == true)
        }

        @Test
        fun `should handle unexpected exceptions`() = runBlocking {
            val params = buildJsonObject {
                put("queryType", "search")
            }

            coEvery { mockTaskRepository.findAll(any()) } throws RuntimeException("Unexpected error")

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertFalse(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("Failed to search") == true)
        }

        @Test
        fun `should handle all bulk update failures`() = runBlocking {
            val params = buildJsonObject {
                put("queryType", "bulkUpdate")
                put("tasks", buildJsonArray {
                    add(buildJsonObject {
                        put("id", UUID.randomUUID().toString())
                        put("status", "completed")
                    })
                })
            }

            coEvery { mockTaskRepository.getById(any()) } answers {
                val id = firstArg<UUID>()
                Result.Error(RepositoryError.NotFound(id, EntityType.TASK, "Task not found"))
            }

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertFalse(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("Failed to update") == true)
        }
    }
}
