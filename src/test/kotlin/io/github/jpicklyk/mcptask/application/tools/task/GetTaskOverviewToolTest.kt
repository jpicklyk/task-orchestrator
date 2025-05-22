package io.github.jpicklyk.mcptask.application.tools.task

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.repository.FeatureRepository
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.domain.repository.TaskRepository
import io.github.jpicklyk.mcptask.infrastructure.repository.RepositoryProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.*

class GetTaskOverviewToolTest {

    private val tool = GetTaskOverviewTool()
    private lateinit var mockContext: ToolExecutionContext
    private lateinit var mockTaskRepository: TaskRepository
    private lateinit var mockFeatureRepository: FeatureRepository

    @BeforeEach
    fun setup() {
        // Create mock repositories
        mockTaskRepository = mockk<TaskRepository>()
        mockFeatureRepository = mockk<FeatureRepository>()
        val mockRepositoryProvider = mockk<RepositoryProvider>()

        // Configure the repository provider to return the mock repositories
        every { mockRepositoryProvider.taskRepository() } returns mockTaskRepository
        every { mockRepositoryProvider.featureRepository() } returns mockFeatureRepository

        // Create test features
        val featureId1 = UUID.randomUUID()
        val featureId2 = UUID.randomUUID()

        val features = listOf(
            Feature(
                id = featureId1,
                name = "Feature 1",
                summary = "Feature 1 description",
                status = FeatureStatus.IN_DEVELOPMENT,
                priority = Priority.HIGH,
                createdAt = Instant.now(),
                modifiedAt = Instant.now()
            ),
            Feature(
                id = featureId2,
                name = "Feature 2",
                summary = "Feature 2 description",
                status = FeatureStatus.PLANNING,
                priority = Priority.MEDIUM,
                createdAt = Instant.now(),
                modifiedAt = Instant.now()
            )
        )

        // Create test tasks
        val tasks = listOf(
            Task(
                id = UUID.randomUUID(),
                featureId = featureId1,
                title = "Task 1",
                summary = "Task 1 summary",
                status = TaskStatus.PENDING,
                priority = Priority.HIGH,
                complexity = 5
            ),
            Task(
                id = UUID.randomUUID(),
                featureId = featureId1,
                title = "Task 2",
                summary = "Task 2 summary",
                status = TaskStatus.IN_PROGRESS,
                priority = Priority.MEDIUM,
                complexity = 3
            ),
            Task(
                id = UUID.randomUUID(),
                featureId = featureId2,
                title = "Task 3",
                summary = "Task 3 summary",
                status = TaskStatus.COMPLETED,
                priority = Priority.LOW,
                complexity = 7
            ),
            Task(
                id = UUID.randomUUID(),
                featureId = null,
                title = "Orphaned Task",
                summary = "Orphaned task summary",
                status = TaskStatus.PENDING,
                priority = Priority.HIGH,
                complexity = 4
            )
        )

        // Mock the repository responses
        coEvery { mockTaskRepository.findAll() } returns Result.Success(tasks)
        coEvery { mockFeatureRepository.findAll() } returns Result.Success(features)

        // Create the execution context with the mock repository provider
        mockContext = ToolExecutionContext(mockRepositoryProvider)
    }

    @Test
    fun `validate with valid summary length should not throw exceptions`() {
        val validParams = JsonObject(
            mapOf(
                "summaryLength" to JsonPrimitive(100)
            )
        )

        // Should not throw any exceptions
        tool.validateParams(validParams)
    }

    @Test
    fun `validate with empty params should not throw exceptions`() {
        val emptyParams = JsonObject(emptyMap())

        // Should not throw any exceptions as all parameters are optional
        tool.validateParams(emptyParams)
    }

    @Test
    fun `validate with invalid summary length should throw validation exception`() {
        val invalidParams = JsonObject(
            mapOf(
                "summaryLength" to JsonPrimitive(201)  // More than the max allowed 200
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(invalidParams)
        }

        assertTrue(exception.message!!.contains("Summary length must be between 0 and 200"))
    }

    @Test
    fun `execute with default parameters should return hierarchical overview`() = runBlocking {
        val defaultParams = JsonObject(emptyMap())

        val response = tool.execute(defaultParams, mockContext) as JsonObject
        val success = response["success"] as JsonPrimitive
        val message = response["message"] as JsonPrimitive
        val data = response["data"] as JsonObject

        // Verify response structure
        assertTrue(success.content.toBoolean())
        assertEquals("Task overview retrieved successfully", message.content)

        // Check data structure
        assertNotNull(data["features"])
        assertNotNull(data["orphanedTasks"])
        assertNotNull(data["counts"])

        // Check features array
        val features = data["features"] as JsonArray
        assertEquals(2, features.size)

        // Check first feature
        val firstFeature = features[0] as JsonObject
        assertNotNull(firstFeature["id"])
        assertNotNull(firstFeature["name"])
        assertNotNull(firstFeature["status"])
        assertNotNull(firstFeature["summary"])

        // Check tasks within first feature
        val featureTasks = firstFeature["tasks"] as JsonArray
        assertTrue(featureTasks.size > 0)

        // Check first task in feature
        val firstTask = featureTasks[0] as JsonObject
        assertNotNull(firstTask["id"])
        assertNotNull(firstTask["title"])
        assertNotNull(firstTask["status"])
        assertNotNull(firstTask["priority"])
        assertNotNull(firstTask["complexity"])
        assertNotNull(firstTask["summary"])

        // Check orphaned tasks
        val orphanedTasks = data["orphanedTasks"] as JsonArray
        assertEquals(1, orphanedTasks.size)

        // Check counts
        val counts = data["counts"] as JsonObject
        assertEquals(2, (counts["features"] as JsonPrimitive).content.toInt())
        assertEquals(4, (counts["tasks"] as JsonPrimitive).content.toInt())
        assertEquals(1, (counts["orphanedTasks"] as JsonPrimitive).content.toInt())
    }

    @Test
    fun `execute with custom summary length should truncate summaries`() = runBlocking {
        val customParams = JsonObject(
            mapOf(
                "summaryLength" to JsonPrimitive(10)
            )
        )

        val response = tool.execute(customParams, mockContext) as JsonObject
        val data = response["data"] as JsonObject
        val features = data["features"] as JsonArray
        val firstFeature = features[0] as JsonObject
        val summary = firstFeature["summary"] as JsonPrimitive

        // Either the summary is shorter than 10 chars or it ends with "..."
        assertTrue(summary.content.length <= 10 || summary.content.endsWith("..."))
    }

    @Test
    fun `execute with summary length 0 should exclude summaries`() = runBlocking {
        val noSummaryParams = JsonObject(
            mapOf(
                "summaryLength" to JsonPrimitive(0)
            )
        )

        val response = tool.execute(noSummaryParams, mockContext) as JsonObject
        val data = response["data"] as JsonObject
        val features = data["features"] as JsonArray
        val firstFeature = features[0] as JsonObject

        // Summary should not be included
        assertNull(firstFeature["summary"])
    }

    @Test
    fun `execute with repository errors should return error response`() = runBlocking {
        // Mock task repository to return an error
        coEvery { mockTaskRepository.findAll() } returns Result.Error(
            io.github.jpicklyk.mcptask.domain.repository.RepositoryError.DatabaseError("Database connection failed")
        )

        val response = tool.execute(JsonObject(emptyMap()), mockContext) as JsonObject
        val success = response["success"] as JsonPrimitive
        val error = response["error"] as JsonObject

        assertFalse(success.content.toBoolean())
        assertNotNull(error["code"])
        assertNotNull(error["details"])
    }
}