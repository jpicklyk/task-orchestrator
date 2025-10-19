package io.github.jpicklyk.mcptask.application.tools.task

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.domain.model.Priority
import io.github.jpicklyk.mcptask.domain.model.StatusFilter
import io.github.jpicklyk.mcptask.domain.model.Task
import io.github.jpicklyk.mcptask.domain.model.TaskStatus
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.domain.repository.TaskRepository
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

class SearchTasksToolTest {

    private val tool = SearchTasksTool()
    private lateinit var mockContext: ToolExecutionContext
    private lateinit var mockTaskRepository: TaskRepository

    // Test data variables
    private val featureId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000")
    private val projectId = UUID.fromString("661f9511-f30c-52e5-b827-557766551111")

    // Create test tasks with different attributes for testing
    private val task1 = Task(
        id = UUID.fromString("770e8400-e29b-41d4-a716-446655440001"),
        title = "API Authentication",
        summary = "Implement OAuth 2.0 authentication flow",
        status = TaskStatus.PENDING,
        priority = Priority.HIGH,
        tags = listOf("api", "security"),
        complexity = 7,
        createdAt = Instant.parse("2025-05-01T10:00:00Z"),
        modifiedAt = Instant.parse("2025-05-01T11:00:00Z")
    )

    private val task2 = Task(
        id = UUID.fromString("770e8400-e29b-41d4-a716-446655440002"),
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

    private val task3 = Task(
        id = UUID.fromString("770e8400-e29b-41d4-a716-446655440003"),
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

    private val task4 = Task(
        id = UUID.fromString("770e8400-e29b-41d4-a716-446655440004"),
        title = "Payment Integration",
        summary = "Integrate with Stripe for payment processing",
        status = TaskStatus.DEFERRED,
        priority = Priority.HIGH,
        tags = listOf("api", "payment"),
        complexity = 8,
        featureId = featureId,
        projectId = projectId,
        createdAt = Instant.parse("2025-05-04T10:00:00Z"),
        modifiedAt = Instant.parse("2025-05-04T11:00:00Z")
    )

    private val allTasks = listOf(task1, task2, task3, task4)

    @BeforeEach
    fun setup() {
        // Create mock repositories
        mockTaskRepository = mockk<TaskRepository>()
        val mockRepositoryProvider = mockk<RepositoryProvider>()

        // Configure repository provider to return the mock task repository
        every { mockRepositoryProvider.taskRepository() } returns mockTaskRepository

        // Setup default repository responses
        coEvery {
            mockTaskRepository.findAll(any())
        } returns Result.Success(allTasks)

        // Mock basic filtering
        coEvery {
            mockTaskRepository.findByFilters(
                textQuery = any(),
                statusFilter = any(),
                priorityFilter = any(),
                tags = any(),
                projectId = any(),
                limit = any(),
            )
        } answers {
            // Extract arguments
            val textQuery = arg<String?>(0)
            val statusFilter = arg<StatusFilter<TaskStatus>?>(1)
            val priorityFilter = arg<StatusFilter<Priority>?>(2)
            val tags = arg<List<String>?>(3)

            // Filter tasks based on the provided criteria
            val filteredTasks = allTasks.filter { task ->
                (textQuery == null ||
                        task.title.contains(textQuery, ignoreCase = true) ||
                        task.summary.contains(textQuery, ignoreCase = true)) &&
                        (statusFilter == null || statusFilter.matches(task.status)) &&
                        (priorityFilter == null || priorityFilter.matches(task.priority)) &&
                        (tags == null || tags.isEmpty() || tags.any { tag -> task.tags.contains(tag) })
            }

            Result.Success(filteredTasks)
        }

        // Mock feature-specific filtering
        coEvery {
            mockTaskRepository.findByFeature(
                featureId = any(),
                statusFilter = any(),
                priorityFilter = any(),
                limit = any(),
            )
        } answers {
            val featureId = arg<UUID>(0)
            val statusFilter = arg<StatusFilter<TaskStatus>?>(1)
            val priorityFilter = arg<StatusFilter<Priority>?>(2)

            val filteredTasks = allTasks.filter { task ->
                task.featureId == featureId &&
                        (statusFilter == null || statusFilter.matches(task.status)) &&
                        (priorityFilter == null || priorityFilter.matches(task.priority))
            }

            Result.Success(filteredTasks)
        }

        // Mock feature with filters
        coEvery {
            mockTaskRepository.findByFeatureAndFilters(
                featureId = any(),
                statusFilter = any(),
                priorityFilter = any(),
                tags = any(),
                textQuery = any(),
                complexityMin = any(),
                complexityMax = any(),
                limit = any(),
            )
        } answers {
            val featureId = arg<UUID>(0)
            val statusFilter = arg<StatusFilter<TaskStatus>?>(1)
            val priorityFilter = arg<StatusFilter<Priority>?>(2)
            val tags = arg<List<String>?>(3)
            val textQuery = arg<String?>(4)

            val filteredTasks = allTasks.filter { task ->
                task.featureId == featureId &&
                        (statusFilter == null || statusFilter.matches(task.status)) &&
                        (priorityFilter == null || priorityFilter.matches(task.priority)) &&
                        (tags == null || tags.isEmpty() || tags.any { tag -> task.tags.contains(tag) }) &&
                        (textQuery == null ||
                                task.title.contains(textQuery, ignoreCase = true) ||
                                task.summary.contains(textQuery, ignoreCase = true))
            }

            Result.Success(filteredTasks)
        }

        // Mock project-specific filtering
        coEvery {
            mockTaskRepository.findByProject(
                projectId = any(),
                limit = any(),
            )
        } answers {
            val projectId = arg<UUID>(0)

            val filteredTasks = allTasks.filter { task ->
                task.projectId == projectId
            }

            Result.Success(filteredTasks)
        }

        // Mock project with filters
        coEvery {
            mockTaskRepository.findByProjectAndFilters(
                projectId = any(),
                statusFilter = any(),
                priorityFilter = any(),
                tags = any(),
                textQuery = any(),
                limit = any(),
            )
        } answers {
            val projectId = arg<UUID>(0)
            val statusFilter = arg<StatusFilter<TaskStatus>?>(1)
            val priorityFilter = arg<StatusFilter<Priority>?>(2)
            val tags = arg<List<String>?>(3)
            val textQuery = arg<String?>(4)

            val filteredTasks = allTasks.filter { task ->
                task.projectId == projectId &&
                        (statusFilter == null || statusFilter.matches(task.status)) &&
                        (priorityFilter == null || priorityFilter.matches(task.priority)) &&
                        (tags == null || tags.isEmpty() || tags.any { tag -> task.tags.contains(tag) }) &&
                        (textQuery == null ||
                                task.title.contains(textQuery, ignoreCase = true) ||
                                task.summary.contains(textQuery, ignoreCase = true))
            }

            Result.Success(filteredTasks)
        }

        // Create the execution context with the mock repository provider
        mockContext = ToolExecutionContext(mockRepositoryProvider)
    }

    @Nested
    inner class ParameterValidationTests {
        @Test
        fun `validateParams with valid parameters should not throw exceptions`() {
            val validParams = JsonObject(
                mapOf(
                    "query" to JsonPrimitive("authentication"),
                    "status" to JsonPrimitive("pending"),
                    "priority" to JsonPrimitive("high"),
                    "featureId" to JsonPrimitive(featureId.toString()),
                    "projectId" to JsonPrimitive(projectId.toString()),
                    "tag" to JsonPrimitive("api"),
                    "limit" to JsonPrimitive(20),
                    "offset" to JsonPrimitive(0),
                    "sortBy" to JsonPrimitive("createdAt"),
                    "sortDirection" to JsonPrimitive("asc")
                )
            )

            // Should not throw any exceptions
            tool.validateParams(validParams)
        }

        @Test
        fun `validateParams with empty parameters should not throw exceptions`() {
            val emptyParams = JsonObject(mapOf())
            tool.validateParams(emptyParams)
        }

        @Test
        fun `validateParams with invalid status should throw validation exception`() {
            val invalidParams = JsonObject(
                mapOf(
                    "status" to JsonPrimitive("invalid-status")
                )
            )

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(invalidParams)
            }

            assertTrue(exception.message!!.contains("Invalid status"))
        }

        @Test
        fun `validateParams with invalid priority should throw validation exception`() {
            val invalidParams = JsonObject(
                mapOf(
                    "priority" to JsonPrimitive("invalid-priority")
                )
            )

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(invalidParams)
            }

            assertTrue(exception.message!!.contains("Invalid priority"))
        }

        @Test
        fun `validateParams with invalid featureId should throw validation exception`() {
            val invalidParams = JsonObject(
                mapOf(
                    "featureId" to JsonPrimitive("not-a-uuid")
                )
            )

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(invalidParams)
            }

            assertTrue(exception.message!!.contains("Invalid featureId format"))
        }

        @Test
        fun `validateParams with invalid projectId should throw validation exception`() {
            val invalidParams = JsonObject(
                mapOf(
                    "projectId" to JsonPrimitive("not-a-uuid")
                )
            )

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(invalidParams)
            }

            assertTrue(exception.message!!.contains("Invalid projectId format"))
        }

        @Test
        fun `validateParams with out-of-range limit should throw validation exception`() {
            // Test with limit below minimum
            val belowMinParams = JsonObject(
                mapOf(
                    "limit" to JsonPrimitive(0)
                )
            )

            var exception = assertThrows<ToolValidationException> {
                tool.validateParams(belowMinParams)
            }

            assertTrue(exception.message!!.contains("Limit must be between 1 and 100"))

            // Test with limit above maximum
            val aboveMaxParams = JsonObject(
                mapOf(
                    "limit" to JsonPrimitive(101)
                )
            )

            exception = assertThrows<ToolValidationException> {
                tool.validateParams(aboveMaxParams)
            }

            assertTrue(exception.message!!.contains("Limit must be between 1 and 100"))
        }

        @Test
        fun `validateParams with negative offset should throw validation exception`() {
            val invalidParams = JsonObject(
                mapOf(
                    "offset" to JsonPrimitive(-1)
                )
            )

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(invalidParams)
            }

            assertTrue(exception.message!!.contains("Offset must be a non-negative integer"))
        }

        @Test
        fun `validateParams with invalid sortBy should throw validation exception`() {
            val invalidParams = JsonObject(
                mapOf(
                    "sortBy" to JsonPrimitive("invalid-field")
                )
            )

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(invalidParams)
            }

            assertTrue(exception.message!!.contains("Invalid sortBy value"))
        }

        @Test
        fun `validateParams with invalid sortDirection should throw validation exception`() {
            val invalidParams = JsonObject(
                mapOf(
                    "sortDirection" to JsonPrimitive("invalid-direction")
                )
            )

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(invalidParams)
            }

            assertTrue(exception.message!!.contains("Invalid sortDirection value"))
        }
    }

    @Nested
    inner class BasicSearchTests {
        @Test
        fun `execute with no parameters should return all tasks`() = runBlocking {
            val emptyParams = JsonObject(mapOf())

            val response = tool.execute(emptyParams, mockContext)
            assertTrue(response is JsonObject)

            val responseObj = response as JsonObject
            val success = responseObj["success"]?.jsonPrimitive?.boolean ?: false
            assertTrue(success)

            val data = responseObj["data"]?.jsonObject
            assertNotNull(data)

            val items = data!!["items"]?.jsonArray
            assertNotNull(items)
            assertEquals(allTasks.size, items!!.size)

            // Success message should indicate found tasks
            val message = responseObj["message"]?.jsonPrimitive?.content
            assertTrue(message!!.contains("Found"))
        }

        @Test
        fun `execute with text query should return matching tasks`() = runBlocking {
            val params = JsonObject(
                mapOf(
                    "query" to JsonPrimitive("authentication")
                )
            )

            // Create filtered task list for authentication query
            val authTasks = listOf(task1) // Only task1 contains "authentication"

            // Setup specific mock for authentication query
            coEvery {
                mockTaskRepository.findByFilters(
                    textQuery = eq("authentication"),
                    statusFilter = null,
                    priorityFilter = null,
                    tags = null,
                    projectId = null,
                    limit = any(),
                )
            } returns Result.Success(authTasks)

            val response = tool.execute(params, mockContext)
            val responseObj = response as JsonObject
            val success = responseObj["success"]?.jsonPrimitive?.boolean ?: false
            assertTrue(success)

            val data = responseObj["data"]?.jsonObject
            val items = data!!["items"]?.jsonArray

            // Should only return tasks with "authentication" in title or summary
            assertTrue(items!!.size > 0)

            // Verify the first result contains the search term
            val firstItem = items[0].jsonObject
            val title = firstItem["title"]?.jsonPrimitive?.content ?: ""
            val summary = firstItem["summary"]?.jsonPrimitive?.content ?: ""
            assertTrue(
                title.contains("authentication", ignoreCase = true) ||
                        summary.contains("authentication", ignoreCase = true)
            )
        }

        @Test
        fun `execute with empty search results should return appropriate message`() = runBlocking {
            // Create a search that won't match any tasks
            val params = JsonObject(
                mapOf(
                    "query" to JsonPrimitive("nonexistent task name")
                )
            )

            // Mock empty results
            coEvery {
                mockTaskRepository.findByFilters(
                    textQuery = eq("nonexistent task name"),
                    statusFilter = null,
                    priorityFilter = null,
                    tags = null,
                    projectId = null,
                    limit = any(),
                )
            } returns Result.Success(emptyList())

            val response = tool.execute(params, mockContext)
            val responseObj = response as JsonObject
            val success = responseObj["success"]?.jsonPrimitive?.boolean ?: false
            assertTrue(success)

            val message = responseObj["message"]?.jsonPrimitive?.content
            assertEquals("No tasks found matching the criteria", message)

            val data = responseObj["data"]?.jsonObject
            val items = data!!["items"]?.jsonArray
            assertEquals(0, items!!.size)
        }
    }

    @Nested
    inner class FilteringTests {
        @Test
        fun `execute with status filter should return tasks with matching status`() = runBlocking {
            val params = JsonObject(
                mapOf(
                    "status" to JsonPrimitive("pending")
                )
            )

            val response = tool.execute(params, mockContext)
            val responseObj = response as JsonObject
            val success = responseObj["success"]?.jsonPrimitive?.boolean ?: false
            assertTrue(success)

            val data = responseObj["data"]?.jsonObject
            val items = data!!["items"]?.jsonArray

            // Verify all returned items have "pending" status
            items!!.forEach { item ->
                val status = item.jsonObject["status"]?.jsonPrimitive?.content
                assertEquals("pending", status)
            }
        }

        @Test
        fun `execute with priority filter should return tasks with matching priority`() = runBlocking {
            val params = JsonObject(
                mapOf(
                    "priority" to JsonPrimitive("high")
                )
            )

            val response = tool.execute(params, mockContext)
            val responseObj = response as JsonObject
            val success = responseObj["success"]?.jsonPrimitive?.boolean ?: false
            assertTrue(success)

            val data = responseObj["data"]?.jsonObject
            val items = data!!["items"]?.jsonArray

            // Verify all returned items have "high" priority
            items!!.forEach { item ->
                val priority = item.jsonObject["priority"]?.jsonPrimitive?.content
                assertEquals("high", priority)
            }
        }

        @Test
        fun `execute with tag filter should return tasks with matching tag`() = runBlocking {
            val params = JsonObject(
                mapOf(
                    "tag" to JsonPrimitive("api")
                )
            )

            val response = tool.execute(params, mockContext)
            val responseObj = response as JsonObject
            val success = responseObj["success"]?.jsonPrimitive?.boolean ?: false
            assertTrue(success)

            val data = responseObj["data"]?.jsonObject
            val items = data!!["items"]?.jsonArray

            // Should only include tasks with the "api" tag
            assertTrue(items!!.size > 0)

            // Verify returned items contain the tag
            items.forEach { item ->
                val tags = item.jsonObject["tags"]?.jsonArray
                assertTrue(tags!!.any { it.jsonPrimitive.content == "api" })
            }
        }

        @Test
        fun `execute with featureId filter should return tasks for the feature`() = runBlocking {
            val params = JsonObject(
                mapOf(
                    "featureId" to JsonPrimitive(featureId.toString())
                )
            )

            val response = tool.execute(params, mockContext)
            val responseObj = response as JsonObject
            val success = responseObj["success"]?.jsonPrimitive?.boolean ?: false
            assertTrue(success)

            val data = responseObj["data"]?.jsonObject
            val items = data!!["items"]?.jsonArray

            // Verify all returned items have the specified featureId
            items!!.forEach { item ->
                val taskFeatureId = item.jsonObject["featureId"]?.jsonPrimitive?.content
                assertEquals(featureId.toString(), taskFeatureId)
            }
        }

        @Test
        fun `execute with projectId filter should return tasks for the project`() = runBlocking {
            val params = JsonObject(
                mapOf(
                    "projectId" to JsonPrimitive(projectId.toString())
                )
            )

            val response = tool.execute(params, mockContext)
            val responseObj = response as JsonObject
            val success = responseObj["success"]?.jsonPrimitive?.boolean ?: false
            assertTrue(success)

            val data = responseObj["data"]?.jsonObject
            val items = data!!["items"]?.jsonArray

            // Verify all returned items have the specified projectId
            items!!.forEach { item ->
                val taskProjectId = item.jsonObject["projectId"]?.jsonPrimitive?.content
                assertEquals(projectId.toString(), taskProjectId)
            }
        }

        @Test
        fun `execute with combined filters should return tasks matching all criteria`() = runBlocking {
            val params = JsonObject(
                mapOf(
                    "status" to JsonPrimitive("in-progress"),
                    "priority" to JsonPrimitive("medium"),
                    "tag" to JsonPrimitive("ui")
                )
            )

            // Create a result with task2 which matches all criteria
            val filteredTasks = listOf(task2)

            // Setup specific mock for combined filters
            coEvery {
                mockTaskRepository.findByFilters(
                    textQuery = null,
                    statusFilter = StatusFilter(include = listOf(TaskStatus.IN_PROGRESS)),
                    priorityFilter = StatusFilter(include = listOf(Priority.MEDIUM)),
                    tags = eq(listOf("ui")),
                    projectId = null,
                    limit = any(),
                )
            } returns Result.Success(filteredTasks)

            val response = tool.execute(params, mockContext)
            val responseObj = response as JsonObject
            val success = responseObj["success"]?.jsonPrimitive?.boolean ?: false
            assertTrue(success)

            val data = responseObj["data"]?.jsonObject
            val items = data!!["items"]?.jsonArray

            // Verify all returned items match all criteria
            items!!.forEach { item ->
                val status = item.jsonObject["status"]?.jsonPrimitive?.content
                val priority = item.jsonObject["priority"]?.jsonPrimitive?.content
                val tags = item.jsonObject["tags"]?.jsonArray

                // Use "in_progress" which is what the SearchTasksTool uses internally
                assertEquals("in_progress", status)
                assertEquals("medium", priority)
                assertTrue(tags!!.any { it.jsonPrimitive.content == "ui" })
            }
        }
    }

    @Nested
    inner class SortingAndPaginationTests {
        @Test
        fun `execute with custom sorting should return properly sorted results`() = runBlocking {
            // Test sorting by complexity in ascending order
            val params = JsonObject(
                mapOf(
                    "sortBy" to JsonPrimitive("complexity"),
                    "sortDirection" to JsonPrimitive("asc")
                )
            )

            val response = tool.execute(params, mockContext)
            val responseObj = response as JsonObject
            val data = responseObj["data"]?.jsonObject
            val items = data!!["items"]?.jsonArray

            // Verify items are sorted by complexity in ascending order
            for (i in 0 until items!!.size - 1) {
                val currentComplexity = items[i].jsonObject["complexity"]?.jsonPrimitive?.int ?: 0
                val nextComplexity = items[i + 1].jsonObject["complexity"]?.jsonPrimitive?.int ?: 0
                assertTrue(currentComplexity <= nextComplexity)
            }
        }

        @Test
        fun `execute with pagination should return the correct page of results`() = runBlocking {
            // Request second page with 2 items per page
            val params = JsonObject(
                mapOf(
                    "limit" to JsonPrimitive(2),
                    "offset" to JsonPrimitive(2)
                )
            )

            val response = tool.execute(params, mockContext)
            val responseObj = response as JsonObject
            val data = responseObj["data"]?.jsonObject

            // Check items
            val items = data!!["items"]?.jsonArray
            assertEquals(2, items!!.size) // Should return 2 items

            // Check pagination info
            val pagination = data["pagination"]?.jsonObject
            assertNotNull(pagination)
            assertEquals(2, pagination!!["page"]?.jsonPrimitive?.int)
            assertEquals(2, pagination["pageSize"]?.jsonPrimitive?.int)
            assertEquals(4, pagination["totalItems"]?.jsonPrimitive?.int) // 4 total items
            assertEquals(2, pagination["totalPages"]?.jsonPrimitive?.int) // 2 pages with 2 items per page
            assertFalse(pagination["hasNext"]?.jsonPrimitive?.boolean ?: false) // No more pages
            assertTrue(pagination["hasPrevious"]?.jsonPrimitive?.boolean ?: false) // Has previous page
        }

        @Test
        fun `execute with partial page should return correct pagination information`() = runBlocking {
            // Request with 3 items per page, which will result in 2 pages (4 items total)
            val params = JsonObject(
                mapOf(
                    "limit" to JsonPrimitive(3),
                    "offset" to JsonPrimitive(0)
                )
            )

            val response = tool.execute(params, mockContext)
            val responseObj = response as JsonObject
            val data = responseObj["data"]?.jsonObject

            // Check items
            val items = data!!["items"]?.jsonArray
            assertEquals(3, items!!.size) // Should return 3 items

            // Check pagination info
            val pagination = data["pagination"]?.jsonObject
            assertNotNull(pagination)
            assertEquals(1, pagination!!["page"]?.jsonPrimitive?.int)
            assertEquals(3, pagination["pageSize"]?.jsonPrimitive?.int)
            assertEquals(4, pagination["totalItems"]?.jsonPrimitive?.int) // 4 total items
            assertEquals(2, pagination["totalPages"]?.jsonPrimitive?.int) // 2 pages with 3 items per page
            assertTrue(pagination["hasNext"]?.jsonPrimitive?.boolean ?: false) // Has next page
            assertFalse(pagination["hasPrevious"]?.jsonPrimitive?.boolean ?: false) // No previous page
        }
    }

    @Nested
    inner class ErrorHandlingTests {
        @Test
        fun `execute should handle database errors gracefully`() = runBlocking {
            // Mock a database error
            coEvery {
                mockTaskRepository.findAll(any())
            } returns Result.Error(RepositoryError.DatabaseError("Database connection failed"))

            val emptyParams = JsonObject(mapOf())
            val response = tool.execute(emptyParams, mockContext)
            val responseObj = response as JsonObject

            // Verify error response
            assertFalse(responseObj["success"]?.jsonPrimitive?.boolean ?: true)
            val message = responseObj["message"]?.jsonPrimitive?.content
            assertTrue(message!!.contains("Failed to search tasks"))

            val error = responseObj["error"]?.jsonObject
            assertNotNull(error)
            assertEquals("DATABASE_ERROR", error!!["code"]?.jsonPrimitive?.content)
        }

        @Test
        fun `execute should handle unexpected exceptions gracefully`() = runBlocking {
            // Mock an unexpected exception
            coEvery {
                mockTaskRepository.findAll(any())
            } throws RuntimeException("Unexpected error")

            val emptyParams = JsonObject(mapOf())
            val response = tool.execute(emptyParams, mockContext)
            val responseObj = response as JsonObject

            // Verify error response
            assertFalse(responseObj["success"]?.jsonPrimitive?.boolean ?: true)
            val message = responseObj["message"]?.jsonPrimitive?.content
            assertEquals("Failed to search tasks", message)

            val error = responseObj["error"]?.jsonObject
            assertNotNull(error)
            assertEquals("INTERNAL_ERROR", error!!["code"]?.jsonPrimitive?.content)
            assertEquals("Unexpected error", error["details"]?.jsonPrimitive?.content)
        }
    }
}