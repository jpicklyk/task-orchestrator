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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.*

class GetTaskToolTest {

    private val tool = GetTaskTool()
    private lateinit var mockContext: ToolExecutionContext
    private lateinit var mockTaskRepository: TaskRepository
    private lateinit var mockFeatureRepository: FeatureRepository
    private lateinit var mockSectionRepository: SectionRepository
    private lateinit var mockTask: Task
    private val taskId = UUID.randomUUID()
    private val featureId = UUID.randomUUID()
    private val mockSections = listOf(
        Section(
            id = UUID.randomUUID(),
            entityType = EntityType.TASK,
            entityId = taskId,
            title = "Test Section 1",
            usageDescription = "Test Usage 1",
            content = "Test Content 1",
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 0
        ),
        Section(
            id = UUID.randomUUID(),
            entityType = EntityType.TASK,
            entityId = taskId,
            title = "Test Section 2",
            usageDescription = "Test Usage 2",
            content = "Test Content 2",
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 1
        )
    )

    @BeforeEach
    fun setup() {
        // Create a mock task
        mockTask = Task(
            id = taskId,
            title = "Test Task",
            summary = "This is a test task description that is somewhat long to test truncation",
            status = TaskStatus.IN_PROGRESS,
            priority = Priority.HIGH,
            complexity = 7,
            createdAt = Instant.now().minusSeconds(3600),
            modifiedAt = Instant.now(),
            tags = listOf("test", "important", "development"),
            featureId = featureId
        )

        // Create mock repositories
        mockTaskRepository = mockk<TaskRepository>()
        mockFeatureRepository = mockk<FeatureRepository>()
        mockSectionRepository = mockk<SectionRepository>()
        val mockRepositoryProvider = mockk<RepositoryProvider>()

        // Configure the repository provider to return the mock repositories
        every { mockRepositoryProvider.taskRepository() } returns mockTaskRepository
        every { mockRepositoryProvider.featureRepository() } returns mockFeatureRepository
        every { mockRepositoryProvider.sectionRepository() } returns mockSectionRepository

        // Mock the task repository responses
        coEvery {
            mockTaskRepository.getById(taskId)
        } returns Result.Success(mockTask)

        coEvery {
            mockTaskRepository.getById(not(taskId))
        } returns Result.Error(RepositoryError.NotFound(UUID.randomUUID(), EntityType.TASK, "Task not found"))

        // Mock feature repository response for tests that include feature information
        coEvery {
            mockFeatureRepository.getById(featureId)
        } returns Result.Success(
            Feature(
                id = featureId,
                name = "Test Feature",
                summary = "Test Feature Description",
                status = FeatureStatus.IN_DEVELOPMENT
            )
        )

        // Default mock for section repository
        coEvery {
            mockSectionRepository.getSectionsForEntity(any(), any())
        } returns Result.Success(emptyList())

        // Specific mock for our task's sections
        coEvery {
            mockSectionRepository.getSectionsForEntity(EntityType.TASK, taskId)
        } returns Result.Success(mockSections)

        // Create the execution context with the mock repository provider
        mockContext = ToolExecutionContext(mockRepositoryProvider)
    }

    @Test
    fun `validate with valid parameters should not throw exceptions`() {
        val validParams = JsonObject(
            mapOf(
                "id" to JsonPrimitive(taskId.toString()),
                "includeSubtasks" to JsonPrimitive("true"),
                "includeDependencies" to JsonPrimitive("true"),
                "includeFeature" to JsonPrimitive("true"),
                "summaryView" to JsonPrimitive("false")
            )
        )

        // Should not throw any exceptions
        tool.validateParams(validParams)
    }

    @Test
    fun `validate with only required parameters should not throw exceptions`() {
        val minimalParams = JsonObject(
            mapOf(
                "id" to JsonPrimitive(taskId.toString())
            )
        )

        // Should not throw any exceptions
        tool.validateParams(minimalParams)
    }

    @Test
    fun `validate without required parameter should throw validation exception`() {
        val invalidParams = JsonObject(
            mapOf(
                "includeSubtasks" to JsonPrimitive(true)
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(invalidParams)
        }

        // Based on the implementation in TaskManagementTool, the error message is 
        // "Missing required parameter: id" not "Required parameter 'id' is missing"
        assertTrue(exception.message!!.contains("Missing required parameter: id"))
    }

    @Test
    fun `validate with invalid UUID should throw validation exception`() {
        val invalidParams = JsonObject(
            mapOf(
                "id" to JsonPrimitive("not-a-uuid")
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(invalidParams)
        }

        assertTrue(exception.message!!.contains("Invalid task ID format"))
    }

    @Test
    fun `execute with valid ID should return task details`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(taskId.toString()),
                "includeSections" to JsonPrimitive("true")
            )
        )

        val response = tool.execute(params, mockContext)

        // Step 1: Verify basic response structure
        assertTrue(response is JsonObject, "Response should be a JsonObject")
        val responseObj = response as JsonObject

        // Step 2: Verify success flag
        assertTrue(responseObj.containsKey("success"), "Response should contain success field")
        val success = responseObj["success"]
        assertTrue(success is JsonPrimitive, "Success should be a JsonPrimitive")
        assertTrue((success as JsonPrimitive).boolean, "Success should be true")

        // Step 3: Verify message field
        assertTrue(responseObj.containsKey("message"), "Response should contain message field")
        val message = responseObj["message"]
        assertTrue(message is JsonPrimitive, "Message should be a JsonPrimitive")
        assertEquals("Task retrieved successfully", (message as JsonPrimitive).content)

        // Step 4: Verify data field exists
        assertTrue(responseObj.containsKey("data"), "Response should contain data field")
        val data = responseObj["data"]
        assertTrue(data is JsonObject, "Data should be a JsonObject")
        val dataObj = data as JsonObject

        // Step 5: Verify basic task properties
        assertTrue(dataObj.containsKey("id"), "Data should contain id field")
        assertEquals(taskId.toString(), dataObj["id"]?.jsonPrimitive?.content)

        assertTrue(dataObj.containsKey("title"), "Data should contain title field")
        assertEquals("Test Task", dataObj["title"]?.jsonPrimitive?.content)

        assertTrue(dataObj.containsKey("summary"), "Data should contain summary field")
        assertEquals(
            "This is a test task description that is somewhat long to test truncation",
            dataObj["summary"]?.jsonPrimitive?.content
        )

        assertTrue(dataObj.containsKey("status"), "Data should contain status field")
        assertEquals(TaskStatus.IN_PROGRESS.name.lowercase(), dataObj["status"]?.jsonPrimitive?.content)

        assertTrue(dataObj.containsKey("priority"), "Data should contain priority field")
        assertEquals(Priority.HIGH.name.lowercase(), dataObj["priority"]?.jsonPrimitive?.content)

        assertTrue(dataObj.containsKey("complexity"), "Data should contain complexity field")
        assertEquals(7, dataObj["complexity"]?.jsonPrimitive?.int)

        // Step 6: Verify sections array
        assertTrue(dataObj.containsKey("sections"), "Data should contain sections field")
        val sections = dataObj["sections"]
        assertTrue(sections is JsonArray, "Sections should be a JsonArray")
        val sectionsArray = sections as JsonArray
        assertEquals(2, sectionsArray.size, "There should be 2 sections")

        // Step 7: Verify first section content
        val firstSection = sectionsArray[0].jsonObject
        assertTrue(firstSection.containsKey("title"), "Section should contain title field")
        assertEquals("Test Section 1", firstSection["title"]?.jsonPrimitive?.content)

        assertTrue(firstSection.containsKey("content"), "Section should contain content field")
        assertEquals("Test Content 1", firstSection["content"]?.jsonPrimitive?.content)

        assertTrue(firstSection.containsKey("contentFormat"), "Section should contain contentFormat field")
        assertEquals("markdown", firstSection["contentFormat"]?.jsonPrimitive?.content)
    }

    @Test
    fun `execute with summary view should return simplified task details`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(taskId.toString()),
                "summaryView" to JsonPrimitive("true"),
                "includeSections" to JsonPrimitive("true")
            )
        )

        val response = tool.execute(params, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        val success = responseObj["success"]?.jsonPrimitive?.boolean ?: false
        assertTrue(success, "Response should indicate success")

        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data object should not be null")

        // Verify basic task properties are present
        assertNotNull(data!!["id"], "ID should be present")
        assertEquals(taskId.toString(), data["id"]?.jsonPrimitive?.content)

        assertNotNull(data["title"], "Title should be present")
        assertEquals("Test Task", data["title"]?.jsonPrimitive?.content)

        assertNotNull(data["status"], "Status should be present")
        assertEquals(TaskStatus.IN_PROGRESS.name.lowercase(), data["status"]?.jsonPrimitive?.content)

        assertNotNull(data["priority"], "Priority should be present")
        assertEquals(Priority.HIGH.name.lowercase(), data["priority"]?.jsonPrimitive?.content)

        assertNotNull(data["complexity"], "Complexity should be present")
        assertEquals(7, data["complexity"]?.jsonPrimitive?.int)

        assertNotNull(data["createdAt"], "Created date should be present")
        assertNotNull(data["modifiedAt"], "Modified date should be present")

        // Verify summary is truncated if necessary
        if (data.containsKey("summary")) {
            val summary = data["summary"]?.jsonPrimitive?.content
            assertNotNull(summary, "Summary should be present")
            assertTrue(summary!!.length <= mockTask.summary.length, "Summary should be truncated or equal length")

            // If summary is truncated, it should end with "..."
            if (mockTask.summary.length > 100) {
                assertTrue(summary.endsWith("..."), "Long summary should be truncated with '...'")
                assertEquals("${mockTask.summary.take(97)}...", summary)
            } else {
                assertEquals(mockTask.summary, summary)
            }
        }

        // Verify sections are included and content is truncated if necessary
        val sections = data["sections"]?.jsonArray
        assertNotNull(sections, "Sections array should be present")
        assertEquals(2, sections!!.size, "There should be 2 sections")

        // Verify the first section
        val firstSection = sections[0].jsonObject
        assertNotNull(firstSection["title"], "Section title should be present")
        assertEquals("Test Section 1", firstSection["title"]?.jsonPrimitive?.content)

        // Content should be present
        assertNotNull(firstSection["content"], "Section content should be present")

        // Format should be present
        assertNotNull(firstSection["contentFormat"], "Content format should be present")
        assertEquals("markdown", firstSection["contentFormat"]?.jsonPrimitive?.content)
    }

    @Test
    fun `execute with includeFeature should include feature information`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(taskId.toString()),
                "includeFeature" to JsonPrimitive("true")
            )
        )

        val response = tool.execute(params, mockContext)

        // First verify basic success response
        assertTrue(response is JsonObject, "Response should be a JsonObject")
        val responseObj = response as JsonObject
        assertTrue(responseObj.containsKey("success"), "Response should contain success field")
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Success should be true")

        // Verify data field
        assertTrue(responseObj.containsKey("data"), "Response should contain data field")
        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data object should not be null")

        // Verify feature ID is included
        assertTrue(data!!.containsKey("featureId"), "Data should contain featureId field")
        assertEquals(featureId.toString(), data["featureId"]?.jsonPrimitive?.content, "Feature ID should match")

        // Verify feature information is included
        assertTrue(data.containsKey("feature"), "Response should include feature object")
        val feature = data["feature"]?.jsonObject
        assertNotNull(feature, "Feature object should not be null")

        // Verify feature properties
        assertTrue(feature!!.containsKey("id"), "Feature should contain id field")
        assertEquals(featureId.toString(), feature["id"]?.jsonPrimitive?.content, "Feature ID should match")

        assertTrue(feature.containsKey("name"), "Feature should contain name field")
        assertEquals("Test Feature", feature["name"]?.jsonPrimitive?.content, "Feature name should match")

        assertTrue(feature.containsKey("status"), "Feature should contain status field")
        assertEquals(
            FeatureStatus.IN_DEVELOPMENT.name.lowercase(),
            feature["status"]?.jsonPrimitive?.content,
            "Feature status should match"
        )

        // Verify feature summary is included (since summaryView is false by default)
        assertTrue(feature.containsKey("summary"), "Feature should contain summary field")
        assertEquals(
            "Test Feature Description",
            feature["summary"]?.jsonPrimitive?.content,
            "Feature summary should match"
        )
    }

    @Test
    fun `execute with non-existent ID should return error`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(UUID.randomUUID().toString())
            )
        )

        val response = tool.execute(params, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        val success = responseObj["success"]?.jsonPrimitive?.boolean ?: true
        assertFalse(success, "Response should indicate failure")

        // Check that the message field exists and contains expected text about the error
        val message = responseObj["message"]?.jsonPrimitive?.content
        assertNotNull(message)
        assertTrue(message!!.contains("Task not found"))
    }
}