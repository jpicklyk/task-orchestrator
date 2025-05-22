package io.github.jpicklyk.mcptask.application.tools.feature

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.repository.*
import io.github.jpicklyk.mcptask.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
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
import java.time.Instant
import java.util.*

class GetFeatureToolTest {
    private lateinit var tool: GetFeatureTool
    private lateinit var context: ToolExecutionContext
    private lateinit var mockFeatureRepository: FeatureRepository
    private lateinit var mockTaskRepository: TaskRepository
    private lateinit var mockSectionRepository: SectionRepository
    private val testFeatureId = UUID.randomUUID()
    private val testTaskId1 = UUID.randomUUID()
    private val testTaskId2 = UUID.randomUUID()
    private lateinit var testFeature: Feature
    private lateinit var testSections: List<Section>

    @BeforeEach
    fun setup() {
        // Create mock repositories
        mockFeatureRepository = mockk<FeatureRepository>()
        mockTaskRepository = mockk<TaskRepository>()
        mockSectionRepository = mockk<SectionRepository>()
        val mockRepositoryProvider = mockk<RepositoryProvider>()

        // Configure the repository provider to return the mock repositories
        every { mockRepositoryProvider.featureRepository() } returns mockFeatureRepository
        every { mockRepositoryProvider.taskRepository() } returns mockTaskRepository
        every { mockRepositoryProvider.sectionRepository() } returns mockSectionRepository

        // Create a test feature
        testFeature = Feature(
            id = testFeatureId,
            name = "Test Feature",
            summary = "This is a test feature with a long description that might need truncation if requested with a summary view.",
            status = FeatureStatus.IN_DEVELOPMENT,
            priority = Priority.HIGH,
            createdAt = Instant.now(),
            modifiedAt = Instant.now(),
            tags = listOf("test", "kotlin", "mcp")
        )

        // Create test sections for the feature
        testSections = listOf(
            Section(
                id = UUID.randomUUID(),
                entityType = EntityType.FEATURE,
                entityId = testFeatureId,
                title = "Requirements",
                usageDescription = "Key requirements for this feature",
                content = "This feature should implement the following requirements...",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0
            ),
            Section(
                id = UUID.randomUUID(),
                entityType = EntityType.FEATURE,
                entityId = testFeatureId,
                title = "Implementation Notes",
                usageDescription = "Technical details for implementation",
                content = "Implementation should follow these guidelines...",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 1
            )
        )

        // Define behavior for findById method
        coEvery {
            mockFeatureRepository.getById(testFeatureId)
        } returns Result.Success(testFeature)

        // Define mock tasks for this feature
        val testTasks = listOf(
            Task(
                id = testTaskId1,
                title = "Task 1",
                summary = "Task 1 summary text",
                status = TaskStatus.IN_PROGRESS,
                priority = Priority.HIGH,
                complexity = 7
            ),
            Task(
                id = testTaskId2,
                title = "Task 2",
                summary = "Task 2 summary text",
                status = TaskStatus.PENDING,
                priority = Priority.MEDIUM,
                complexity = 5
            )
        )

        // Define behavior for findByFeature method
        coEvery {
            mockTaskRepository.findByFeature(testFeatureId)
        } returns Result.Success(testTasks)

        // Define behavior for getSectionsForEntity method
        coEvery {
            mockSectionRepository.getSectionsForEntity(EntityType.FEATURE, testFeatureId)
        } returns Result.Success(testSections)

        // Define behavior for non-existent feature
        coEvery {
            mockFeatureRepository.getById(neq(testFeatureId))
        } returns Result.Error(RepositoryError.NotFound(UUID.randomUUID(), EntityType.FEATURE, "Feature not found"))

        // Create the execution context with the mock repository provider
        context = ToolExecutionContext(mockRepositoryProvider)

        tool = GetFeatureTool()
    }

    @Test
    fun `test valid parameters validation`() {
        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(testFeatureId.toString()),
                "includeTasks" to JsonPrimitive("true"),
                "maxTaskCount" to JsonPrimitive(10),
                "includeSections" to JsonPrimitive("true")
            )
        )

        // Should not throw an exception
        assertDoesNotThrow { tool.validateParams(params) }
    }

    @Test
    fun `test missing required parameters validation`() {
        val params = JsonObject(
            mapOf(
                "includeTasks" to JsonPrimitive("true")
            )
        )

        // Should throw an exception for missing id
        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Missing required parameter: id"))
    }

    @Test
    fun `test invalid id format validation`() {
        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive("not-a-uuid")
            )
        )

        // Should throw an exception for invalid UUID
        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Invalid id format"))
    }

    @Test
    fun `test invalid maxTaskCount validation`() {
        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(testFeatureId.toString()),
                "maxTaskCount" to JsonPrimitive(0)
            )
        )

        // Should throw an exception for invalid maxTaskCount
        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("maxTaskCount must be at least 1"))
    }

    @Test
    fun `execute with valid ID should return feature details`() = runBlocking {
        // Create parameters with just the ID
        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(testFeatureId.toString())
            )
        )

        // Execute the tool
        val result = tool.execute(params, context)

        // Check that the result is a success response
        val resultObj = result as JsonObject
        assertEquals(true, (resultObj["success"] as JsonPrimitive).content.toBoolean())
        assertEquals("Feature retrieved successfully", (resultObj["message"] as JsonPrimitive).content)

        // Verify the returned data
        val data = resultObj["data"] as JsonObject
        assertEquals(testFeatureId.toString(), (data["id"] as JsonPrimitive).content)
        assertEquals("Test Feature", (data["name"] as JsonPrimitive).content)
        assertEquals("in_development", (data["status"] as JsonPrimitive).content)
        assertTrue(data.containsKey("summary"))
        assertTrue(data.containsKey("createdAt"))
        assertTrue(data.containsKey("modifiedAt"))

        // Tasks should not be included by default
        assertFalse(data.containsKey("tasks"))

        // Sections should not be included by default
        assertFalse(data.containsKey("sections"))
    }

    @Test
    fun `execute with includeTasks should return feature with tasks`() = runBlocking {
        // Create parameters with includeTasks=true
        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(testFeatureId.toString()),
                "includeTasks" to JsonPrimitive("true")
            )
        )

        // Execute the tool
        val result = tool.execute(params, context)

        // Check that the result is a success response
        val resultObj = result as JsonObject
        assertEquals(true, (resultObj["success"] as JsonPrimitive).content.toBoolean())

        // Verify the returned data
        val data = resultObj["data"] as JsonObject
        assertEquals(testFeatureId.toString(), (data["id"] as JsonPrimitive).content)

        // Tasks should be included
        assertTrue(data.containsKey("tasks"))
        val tasks = (data["tasks"] as JsonObject)["items"] as JsonArray
        assertEquals(2, tasks.size)

        // Verify first task details
        val task1 = tasks[0] as JsonObject
        assertNotNull(task1["id"])
        assertNotNull(task1["title"])
        assertNotNull(task1["status"])
        assertNotNull(task1["priority"])
        assertNotNull(task1["complexity"])
    }

    @Test
    fun `execute with includeSections should return feature with sections`() = runBlocking {
        // Create parameters with includeSections=true
        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(testFeatureId.toString()),
                "includeSections" to JsonPrimitive("true")
            )
        )

        // Execute the tool
        val result = tool.execute(params, context)

        // Check that the result is a success response
        val resultObj = result as JsonObject
        assertEquals(true, (resultObj["success"] as JsonPrimitive).content.toBoolean())

        // Verify the returned data
        val data = resultObj["data"] as JsonObject
        assertEquals(testFeatureId.toString(), (data["id"] as JsonPrimitive).content)

        // Sections should be included
        assertTrue(data.containsKey("sections"))
        val sections = data["sections"] as JsonArray
        assertEquals(2, sections.size)

        // Verify first section details
        val section1 = sections[0] as JsonObject
        assertNotNull(section1["id"])
        assertNotNull(section1["title"])
        assertNotNull(section1["content"])
        assertNotNull(section1["contentFormat"])
        assertNotNull(section1["ordinal"])

        // Verify section content matches
        assertEquals("Requirements", (section1["title"] as JsonPrimitive).content)
        assertEquals(
            "This feature should implement the following requirements...",
            (section1["content"] as JsonPrimitive).content
        )
        assertEquals("markdown", (section1["contentFormat"] as JsonPrimitive).content)
        assertEquals(0, (section1["ordinal"] as JsonPrimitive).content.toInt())
    }

    @Test
    fun `execute with summaryView should return simplified feature details`() = runBlocking {
        // Create parameters with summaryView=true and a very short maxsummaryLength
        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(testFeatureId.toString()),
                "summaryView" to JsonPrimitive("true"),
                "maxsummaryLength" to JsonPrimitive(20)
            )
        )

        // Execute the tool
        val result = tool.execute(params, context)

        // Check that the result is a success response
        val resultObj = result as JsonObject
        assertEquals(true, (resultObj["success"] as JsonPrimitive).content.toBoolean())

        // Verify the returned data
        val data = resultObj["data"] as JsonObject
        assertEquals(testFeatureId.toString(), (data["id"] as JsonPrimitive).content)

        // Summary should be truncated if too long
        assertTrue(data.containsKey("summary"))
        val summary = (data["summary"] as JsonPrimitive).content

        // The summary should be truncated to 20 characters plus "..."
        assertTrue(summary.endsWith("..."))
        assertEquals(23, summary.length) // 20 chars + "..."
    }

    @Test
    fun `execute with includeTaskCounts should return task counts by status`() = runBlocking {
        // Create parameters with includeTaskCounts=true
        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(testFeatureId.toString()),
                "includeTaskCounts" to JsonPrimitive("true")
            )
        )

        // Execute the tool
        val result = tool.execute(params, context)

        // Check that the result is a success response
        val resultObj = result as JsonObject
        assertEquals(true, (resultObj["success"] as JsonPrimitive).content.toBoolean())

        // Verify the returned data
        val data = resultObj["data"] as JsonObject
        assertTrue(data.containsKey("taskCounts"))

        val taskCounts = data["taskCounts"] as JsonObject
        assertEquals(2, (taskCounts["total"] as JsonPrimitive).content.toInt())

        val byStatus = taskCounts["byStatus"] as JsonObject
        assertTrue(byStatus.containsKey("in_progress"))
        assertTrue(byStatus.containsKey("pending"))
    }

    @Test
    fun `execute with non-existent ID should return error`() = runBlocking {
        // Create parameters with a non-existent feature ID
        val nonExistentId = UUID.randomUUID()
        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(nonExistentId.toString())
            )
        )

        // Execute the tool
        val result = tool.execute(params, context)

        // Check that the result is an error response
        val resultObj = result as JsonObject
        assertEquals(false, (resultObj["success"] as JsonPrimitive).content.toBoolean())
        assertTrue((resultObj["message"] as JsonPrimitive).content.contains("Feature not found"))

        // Error should contain the error code
        val error = resultObj["error"] as JsonObject
        assertEquals(ErrorCodes.RESOURCE_NOT_FOUND, (error["code"] as JsonPrimitive).content)
    }

    @Test
    fun `execute with maxTaskCount should limit returned tasks`() = runBlocking {
        // Create parameters with includeTasks=true and maxTaskCount=1
        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(testFeatureId.toString()),
                "includeTasks" to JsonPrimitive("true"),
                "maxTaskCount" to JsonPrimitive(1)
            )
        )

        // Execute the tool
        val result = tool.execute(params, context)

        // Check that the result is a success response
        val resultObj = result as JsonObject
        assertEquals(true, (resultObj["success"] as JsonPrimitive).content.toBoolean())

        // Verify the returned data
        val data = resultObj["data"] as JsonObject

        // Tasks should be included
        assertTrue(data.containsKey("tasks"))
        val tasks = (data["tasks"] as JsonObject)["items"] as JsonArray
        assertEquals(1, tasks.size) // Only one task should be returned

        // The hasMore flag should be true
        assertTrue(((data["tasks"] as JsonObject)["hasMore"] as JsonPrimitive).content.toBoolean())
    }

    @Test
    fun `execute with all options should return complete feature details`() = runBlocking {
        // Create parameters with all options enabled
        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(testFeatureId.toString()),
                "includeTasks" to JsonPrimitive("true"),
                "includeTaskCounts" to JsonPrimitive("true"),
                "includeSections" to JsonPrimitive("true")
            )
        )

        // Execute the tool
        val result = tool.execute(params, context)

        // Check that the result is a success response
        val resultObj = result as JsonObject
        assertEquals(true, (resultObj["success"] as JsonPrimitive).content.toBoolean())

        // Verify the returned data
        val data = resultObj["data"] as JsonObject
        assertEquals(testFeatureId.toString(), (data["id"] as JsonPrimitive).content)

        // Check all included components exist
        assertTrue(data.containsKey("sections"))
        assertTrue(data.containsKey("tasks"))
        assertTrue(data.containsKey("taskCounts"))

        // Verify sections
        val sections = data["sections"] as JsonArray
        assertEquals(2, sections.size)

        // Verify tasks
        val tasks = (data["tasks"] as JsonObject)["items"] as JsonArray
        assertEquals(2, tasks.size)

        // Verify task counts
        val taskCounts = data["taskCounts"] as JsonObject
        assertEquals(2, (taskCounts["total"] as JsonPrimitive).content.toInt())
    }
}