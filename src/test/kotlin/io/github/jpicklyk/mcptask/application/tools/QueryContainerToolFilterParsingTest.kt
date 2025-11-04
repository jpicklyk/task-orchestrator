package io.github.jpicklyk.mcptask.application.tools

import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.infrastructure.database.repository.SQLiteFeatureRepository
import io.github.jpicklyk.mcptask.infrastructure.database.repository.SQLiteProjectRepository
import io.github.jpicklyk.mcptask.infrastructure.database.repository.SQLiteTaskRepository
import io.github.jpicklyk.mcptask.infrastructure.repository.RepositoryProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.*

/**
 * Comprehensive integration tests for filter parsing functionality in QueryContainerTool.
 *
 * Tests validate that filter string parsing works correctly end-to-end through:
 * - Tool parameter validation
 * - Filter string parsing (parseStatusFilter, parsePriorityFilter)
 * - Repository SQL query generation
 * - Result marshalling
 *
 * These tests address the CRITICAL GAP identified in test coverage review:
 * No tests existed for parsing comma-separated filter strings like:
 * - status="pending,in-progress" → StatusFilter(include=[PENDING, IN_PROGRESS]) → SQL: WHERE status IN ('pending', 'in_progress')
 * - status="!completed" → StatusFilter(exclude=[COMPLETED]) → SQL: WHERE status NOT IN ('completed')
 * - priority="high,medium" → StatusFilter(include=[HIGH, MEDIUM]) → SQL: WHERE priority IN ('high', 'medium')
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueryContainerToolFilterParsingTest {
    private lateinit var database: Database
    private lateinit var databaseManager: DatabaseManager
    private lateinit var tool: QueryContainerTool
    private lateinit var context: ToolExecutionContext
    private lateinit var repositoryProvider: RepositoryProvider

    private val testProjectId = UUID.randomUUID()
    private val testFeatureId = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        // Create unique in-memory database for each test
        val dbName = "test_${System.nanoTime()}"
        database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        databaseManager = DatabaseManager(database)
        databaseManager.updateSchema()

        // Initialize repository provider
        repositoryProvider = object : RepositoryProvider {
            private val taskRepo = SQLiteTaskRepository(databaseManager)
            private val featureRepo = SQLiteFeatureRepository(databaseManager)
            private val projectRepo = SQLiteProjectRepository(databaseManager)

            override fun taskRepository() = taskRepo
            override fun featureRepository() = featureRepo
            override fun projectRepository() = projectRepo
            override fun sectionRepository() = throw UnsupportedOperationException()
            override fun dependencyRepository() = throw UnsupportedOperationException()
            override fun templateRepository() = throw UnsupportedOperationException()
        }

        context = ToolExecutionContext(repositoryProvider)
        tool = QueryContainerTool(null, null)

        // Create test project
        runBlocking {
            repositoryProvider.projectRepository().create(
                Project(
                    id = testProjectId,
                    name = "Test Project",
                    summary = "Project for filter parsing tests",
                    status = ProjectStatus.IN_DEVELOPMENT,
                    tags = listOf("test")
                )
            )
        }

        // Create test feature
        runBlocking {
            repositoryProvider.featureRepository().create(
                Feature(
                    id = testFeatureId,
                    projectId = testProjectId,
                    name = "Test Feature",
                    summary = "Feature for filter parsing tests",
                    status = FeatureStatus.IN_DEVELOPMENT,
                    priority = Priority.HIGH,
                    tags = listOf("test")
                )
            )
        }

        // Create diverse test tasks
        runBlocking {
            listOf(
                Task(
                    id = UUID.randomUUID(),
                    title = "High Priority Pending Task",
                    summary = "Test task 1",
                    status = TaskStatus.PENDING,
                    priority = Priority.HIGH,
                    complexity = 5,
                    tags = listOf("backend"),
                    projectId = testProjectId,
                    featureId = testFeatureId,
                    createdAt = Instant.now(),
                    modifiedAt = Instant.now()
                ),
                Task(
                    id = UUID.randomUUID(),
                    title = "Medium Priority In Progress Task",
                    summary = "Test task 2",
                    status = TaskStatus.IN_PROGRESS,
                    priority = Priority.MEDIUM,
                    complexity = 3,
                    tags = listOf("frontend"),
                    projectId = testProjectId,
                    featureId = testFeatureId,
                    createdAt = Instant.now(),
                    modifiedAt = Instant.now()
                ),
                Task(
                    id = UUID.randomUUID(),
                    title = "Low Priority Completed Task",
                    summary = "Test task 3",
                    status = TaskStatus.COMPLETED,
                    priority = Priority.LOW,
                    complexity = 2,
                    tags = listOf("docs"),
                    projectId = testProjectId,
                    createdAt = Instant.now(),
                    modifiedAt = Instant.now()
                ),
                Task(
                    id = UUID.randomUUID(),
                    title = "High Priority Cancelled Task",
                    summary = "Test task 4",
                    status = TaskStatus.CANCELLED,
                    priority = Priority.HIGH,
                    complexity = 8,
                    tags = listOf("backend"),
                    projectId = testProjectId,
                    createdAt = Instant.now(),
                    modifiedAt = Instant.now()
                ),
                Task(
                    id = UUID.randomUUID(),
                    title = "Medium Priority Deferred Task",
                    summary = "Test task 5",
                    status = TaskStatus.DEFERRED,
                    priority = Priority.MEDIUM,
                    complexity = 4,
                    tags = listOf("enhancement"),
                    featureId = testFeatureId,
                    createdAt = Instant.now(),
                    modifiedAt = Instant.now()
                )
            ).forEach { task ->
                repositoryProvider.taskRepository().create(task)
            }
        }
    }

    @Nested
    inner class TaskStatusFilterParsingTests {
        @Test
        fun `should parse single value task status filter`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "search")
                put("containerType", "task")
                put("status", "pending")
            }

            val result = tool.execute(params, context)
            val resultObj = result.jsonObject

            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true, "Request should succeed")
            val data = resultObj["data"]?.jsonObject
            val items = data?.get("items")?.jsonArray

            assertEquals(1, items?.size, "Should find 1 pending task")
            assertEquals("pending", items?.get(0)?.jsonObject?.get("status")?.jsonPrimitive?.content)
        }

        @Test
        fun `should parse comma-separated multi-value task status filter`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "search")
                put("containerType", "task")
                put("status", "pending,in-progress")
            }

            val result = tool.execute(params, context)
            val resultObj = result.jsonObject

            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            val items = resultObj["data"]?.jsonObject?.get("items")?.jsonArray

            assertEquals(2, items?.size, "Should find 2 tasks (pending + in-progress)")
            val statuses = items?.map { it.jsonObject["status"]?.jsonPrimitive?.content }
            assertTrue(statuses?.contains("pending") == true)
            assertTrue(statuses?.contains("in-progress") == true)
        }

        @Test
        fun `should parse negation task status filter`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "search")
                put("containerType", "task")
                put("status", "!completed")
            }

            val result = tool.execute(params, context)
            val resultObj = result.jsonObject

            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            val items = resultObj["data"]?.jsonObject?.get("items")?.jsonArray

            assertEquals(4, items?.size, "Should find 4 tasks (all except completed)")
            val statuses = items?.map { it.jsonObject["status"]?.jsonPrimitive?.content }
            assertFalse(statuses?.contains("completed") == true)
        }

        @Test
        fun `should parse multi-negation task status filter`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "search")
                put("containerType", "task")
                put("status", "!completed,!cancelled")
            }

            val result = tool.execute(params, context)
            val resultObj = result.jsonObject

            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            val items = resultObj["data"]?.jsonObject?.get("items")?.jsonArray

            assertEquals(3, items?.size, "Should find 3 tasks (pending, in-progress, deferred)")
            val statuses = items?.map { it.jsonObject["status"]?.jsonPrimitive?.content }
            assertFalse(statuses?.contains("completed") == true)
            assertFalse(statuses?.contains("cancelled") == true)
        }

        @Test
        fun `should handle whitespace in task status filter values`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "search")
                put("containerType", "task")
                put("status", " pending , in-progress ")
            }

            val result = tool.execute(params, context)
            val resultObj = result.jsonObject

            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            val items = resultObj["data"]?.jsonObject?.get("items")?.jsonArray
            assertEquals(2, items?.size, "Whitespace should be trimmed correctly")
        }

        @Test
        fun `should reject invalid task status value`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "search")
                put("containerType", "task")
                put("status", "invalid-status")
            }

            val result = tool.execute(params, context)
            val resultObj = result.jsonObject

            assertFalse(resultObj["success"]?.jsonPrimitive?.boolean == true, "Should fail with invalid status")
            // The error message may be generic ("Failed to execute container query") which is acceptable
        }

        @Test
        fun `should handle case-insensitive task status values`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "search")
                put("containerType", "task")
                put("status", "PENDING,IN-PROGRESS")
            }

            val result = tool.execute(params, context)
            val resultObj = result.jsonObject

            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            val items = resultObj["data"]?.jsonObject?.get("items")?.jsonArray
            assertEquals(2, items?.size, "Case-insensitive parsing should work")
        }
    }

    @Nested
    inner class PriorityFilterParsingTests {
        @Test
        fun `should parse single value priority filter`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "search")
                put("containerType", "task")
                put("priority", "high")
            }

            val result = tool.execute(params, context)
            val resultObj = result.jsonObject

            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            val items = resultObj["data"]?.jsonObject?.get("items")?.jsonArray

            assertEquals(2, items?.size, "Should find 2 high priority tasks")
            items?.forEach { task ->
                assertEquals("high", task.jsonObject["priority"]?.jsonPrimitive?.content)
            }
        }

        @Test
        fun `should parse comma-separated multi-value priority filter`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "search")
                put("containerType", "task")
                put("priority", "high,medium")
            }

            val result = tool.execute(params, context)
            val resultObj = result.jsonObject

            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            val items = resultObj["data"]?.jsonObject?.get("items")?.jsonArray

            assertEquals(4, items?.size, "Should find 4 tasks (2 high + 2 medium)")
            val priorities = items?.map { it.jsonObject["priority"]?.jsonPrimitive?.content }
            assertTrue(priorities?.contains("high") == true)
            assertTrue(priorities?.contains("medium") == true)
        }

        @Test
        fun `should parse negation priority filter`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "search")
                put("containerType", "task")
                put("priority", "!low")
            }

            val result = tool.execute(params, context)
            val resultObj = result.jsonObject

            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            val items = resultObj["data"]?.jsonObject?.get("items")?.jsonArray

            assertEquals(4, items?.size, "Should find 4 tasks (all except low)")
            val priorities = items?.map { it.jsonObject["priority"]?.jsonPrimitive?.content }
            assertFalse(priorities?.contains("low") == true)
        }

        @Test
        fun `should reject invalid priority value`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "search")
                put("containerType", "task")
                put("priority", "invalid")
            }

            val result = tool.execute(params, context)
            val resultObj = result.jsonObject

            assertFalse(resultObj["success"]?.jsonPrimitive?.boolean == true)
        }
    }

    @Nested
    inner class CombinedFilterParsingTests {
        @Test
        fun `should handle both status and priority multi-value filters together`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "search")
                put("containerType", "task")
                put("status", "pending,in-progress")
                put("priority", "high,medium")
            }

            val result = tool.execute(params, context)
            val resultObj = result.jsonObject

            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            val items = resultObj["data"]?.jsonObject?.get("items")?.jsonArray

            assertEquals(2, items?.size, "Should find tasks matching both criteria")
        }

        @Test
        fun `should handle negation in both status and priority filters`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "search")
                put("containerType", "task")
                put("status", "!completed,!cancelled")
                put("priority", "!low")
            }

            val result = tool.execute(params, context)
            val resultObj = result.jsonObject

            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            val items = resultObj["data"]?.jsonObject?.get("items")?.jsonArray

            assertEquals(3, items?.size, "Should find 3 tasks (pending high + in-progress medium + deferred medium)")
        }
    }

    @Nested
    inner class EdgeCaseTests {
        @Test
        fun `should handle empty string status parameter`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "search")
                put("containerType", "task")
                put("status", "")
            }

            val result = tool.execute(params, context)
            val resultObj = result.jsonObject

            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            // Should return all tasks when empty filter provided
        }
    }
}
