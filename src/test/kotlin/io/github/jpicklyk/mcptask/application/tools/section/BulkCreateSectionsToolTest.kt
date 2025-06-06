package io.github.jpicklyk.mcptask.application.tools.section

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.github.jpicklyk.mcptask.test.mock.*
import io.github.jpicklyk.mcptask.test.utils.ResponseTestUtils
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.*

class BulkCreateSectionsToolTest {

    private lateinit var tool: BulkCreateSectionsTool
    private lateinit var mockTaskRepository: MockTaskRepository
    private lateinit var mockFeatureRepository: MockFeatureRepository
    private lateinit var mockProjectRepository: MockProjectRepository
    private lateinit var mockSectionRepository: MockSectionRepository
    private lateinit var mockContext: ToolExecutionContext

    @BeforeEach
    fun setUp() {
        tool = BulkCreateSectionsTool()
        mockTaskRepository = MockTaskRepository()
        mockFeatureRepository = MockFeatureRepository()
        mockProjectRepository = MockProjectRepository()
        mockSectionRepository = MockSectionRepository()

        // Set up repository provider with our mocks
        val repositoryProvider = MockRepositoryProvider(
            taskRepository = mockTaskRepository,
            featureRepository = mockFeatureRepository,
            projectRepository = mockProjectRepository,
            sectionRepository = mockSectionRepository
        )

        // Create mock context
        mockContext = ToolExecutionContext(repositoryProvider)

        // Set up test data
        val testTask = Task(
            id = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            title = "Test Task",
            summary = "Test task summary",
            status = TaskStatus.PENDING,
            complexity = 3
        )
        mockTaskRepository.addTask(testTask)

        val testFeature = Feature(
            id = UUID.fromString("00000000-0000-0000-0000-000000000002"),
            name = "Test Feature",
            summary = "Test feature summary",
            status = FeatureStatus.IN_DEVELOPMENT
        )
        mockFeatureRepository.addFeature(testFeature)

        val testProject = Project(
            id = UUID.fromString("00000000-0000-0000-0000-000000000003"),
            name = "Test Project",
            summary = "Test project summary",
            status = ProjectStatus.PLANNING
        )
        mockProjectRepository.addProject(testProject)
    }

    @Test
    fun `validate params - valid input with task, feature, and project sections`() {
        // Create valid params with multiple entity type sections
        val params = JsonObject(
            mapOf(
                "sections" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "entityType" to JsonPrimitive("TASK"),
                                "entityId" to JsonPrimitive("00000000-0000-0000-0000-000000000001"),
                                "title" to JsonPrimitive("Task Section"),
                                "usageDescription" to JsonPrimitive("Task section description"),
                                "content" to JsonPrimitive("Task section content"),
                                "contentFormat" to JsonPrimitive("MARKDOWN"),
                                "ordinal" to JsonPrimitive(0),
                                "tags" to JsonPrimitive("tag1,tag2,tag3")
                            )
                        ),
                        JsonObject(
                            mapOf(
                                "entityType" to JsonPrimitive("FEATURE"),
                                "entityId" to JsonPrimitive("00000000-0000-0000-0000-000000000002"),
                                "title" to JsonPrimitive("Feature Section"),
                                "usageDescription" to JsonPrimitive("Feature section description"),
                                "content" to JsonPrimitive("Feature section content"),
                                "contentFormat" to JsonPrimitive("PLAIN_TEXT"),
                                "ordinal" to JsonPrimitive(1)
                            )
                        ),
                        JsonObject(
                            mapOf(
                                "entityType" to JsonPrimitive("PROJECT"),
                                "entityId" to JsonPrimitive("00000000-0000-0000-0000-000000000003"),
                                "title" to JsonPrimitive("Project Overview"),
                                "usageDescription" to JsonPrimitive("Project overview description"),
                                "content" to JsonPrimitive("Project overview content"),
                                "contentFormat" to JsonPrimitive("MARKDOWN"),
                                "ordinal" to JsonPrimitive(0),
                                "tags" to JsonPrimitive("overview,documentation")
                            )
                        )
                    )
                )
            )
        )

        // Should not throw an exception
        tool.validateParams(params)
    }

    @Test
    fun `validate params - missing sections array`() {
        val params = JsonObject(mapOf())

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }

        assertTrue(exception.message!!.contains("Missing required parameter: sections"))
    }

    @Test
    fun `validate params - empty sections array`() {
        val params = JsonObject(
            mapOf(
                "sections" to JsonArray(emptyList())
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }

        assertTrue(exception.message!!.contains("At least one section must be provided"))
    }

    @Test
    fun `validate params - missing required fields in section`() {
        val params = JsonObject(
            mapOf(
                "sections" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "entityType" to JsonPrimitive("TASK"),
                                // Missing entityId
                                "title" to JsonPrimitive("Task Section"),
                                "usageDescription" to JsonPrimitive("Task section description"),
                                "content" to JsonPrimitive("Task section content"),
                                "ordinal" to JsonPrimitive(0)
                            )
                        )
                    )
                )
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }

        assertTrue(exception.message!!.contains("missing required field: entityId"))
    }

    @Test
    fun `validate params - invalid entityType`() {
        val params = JsonObject(
            mapOf(
                "sections" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "entityType" to JsonPrimitive("INVALID"),
                                "entityId" to JsonPrimitive("00000000-0000-0000-0000-000000000001"),
                                "title" to JsonPrimitive("Task Section"),
                                "usageDescription" to JsonPrimitive("Task section description"),
                                "content" to JsonPrimitive("Task section content"),
                                "ordinal" to JsonPrimitive(0)
                            )
                        )
                    )
                )
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }

        assertTrue(exception.message!!.contains("Invalid entityType"))
    }

    @Test
    fun `validate params - invalid entityId`() {
        val params = JsonObject(
            mapOf(
                "sections" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "entityType" to JsonPrimitive("TASK"),
                                "entityId" to JsonPrimitive("not-a-uuid"),
                                "title" to JsonPrimitive("Task Section"),
                                "usageDescription" to JsonPrimitive("Task section description"),
                                "content" to JsonPrimitive("Task section content"),
                                "ordinal" to JsonPrimitive(0)
                            )
                        )
                    )
                )
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }

        assertTrue(exception.message!!.contains("Invalid entityId"))
    }

    @Test
    fun `execute - success with all sections created`() = runBlocking {
        // Setup mocks to return success for all sections
        mockSectionRepository.nextAddResult = { section ->
            Result.Success(section)
        }

        // Create valid params with sections for different entity types
        val params = JsonObject(
            mapOf(
                "sections" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "entityType" to JsonPrimitive("TASK"),
                                "entityId" to JsonPrimitive("00000000-0000-0000-0000-000000000001"),
                                "title" to JsonPrimitive("Task Section"),
                                "usageDescription" to JsonPrimitive("Task section description"),
                                "content" to JsonPrimitive("Task section content"),
                                "contentFormat" to JsonPrimitive("MARKDOWN"),
                                "ordinal" to JsonPrimitive(0),
                                "tags" to JsonPrimitive("tag1,tag2,tag3")
                            )
                        ),
                        JsonObject(
                            mapOf(
                                "entityType" to JsonPrimitive("FEATURE"),
                                "entityId" to JsonPrimitive("00000000-0000-0000-0000-000000000002"),
                                "title" to JsonPrimitive("Feature Section"),
                                "usageDescription" to JsonPrimitive("Feature section description"),
                                "content" to JsonPrimitive("Feature section content"),
                                "contentFormat" to JsonPrimitive("PLAIN_TEXT"),
                                "ordinal" to JsonPrimitive(1)
                            )
                        ),
                        JsonObject(
                            mapOf(
                                "entityType" to JsonPrimitive("PROJECT"),
                                "entityId" to JsonPrimitive("00000000-0000-0000-0000-000000000003"),
                                "title" to JsonPrimitive("Project Overview"),
                                "usageDescription" to JsonPrimitive("Project overview description"),
                                "content" to JsonPrimitive("Project overview content"),
                                "contentFormat" to JsonPrimitive("MARKDOWN"),
                                "ordinal" to JsonPrimitive(0),
                                "tags" to JsonPrimitive("overview,documentation")
                            )
                        )
                    )
                )
            )
        )

        // Execute the tool
        val result = tool.execute(params, mockContext)

        // Check that the response is successful
        assertTrue(ResponseTestUtils.isSuccessResponse(result))

        // Check a message
        assertEquals("3 sections created successfully", result.jsonObject["message"]?.jsonPrimitive?.content)

        // Check data
        val data = result.jsonObject["data"]?.jsonObject
        assertNotNull(data)
        assertEquals(3, data!!["count"]?.jsonPrimitive?.int)
        assertEquals(0, data["failed"]?.jsonPrimitive?.int)

        // Check sections created
        val items = data["items"]?.jsonArray
        assertEquals(3, items?.size)

        // Verify the section repository was called with correct parameters
        assertEquals(3, mockSectionRepository.addedSections.size)

        // Check task section
        val taskSection = mockSectionRepository.addedSections.find {
            it.entityType == EntityType.TASK && it.entityId == UUID.fromString("00000000-0000-0000-0000-000000000001")
        }
        assertNotNull(taskSection)
        assertEquals("Task Section", taskSection!!.title)
        assertEquals(ContentFormat.MARKDOWN, taskSection.contentFormat)
        assertEquals(listOf("tag1", "tag2", "tag3"), taskSection.tags)

        // Check project section
        val projectSection = mockSectionRepository.addedSections.find {
            it.entityType == EntityType.PROJECT && it.entityId == UUID.fromString("00000000-0000-0000-0000-000000000003")
        }
        assertNotNull(projectSection)
        assertEquals("Project Overview", projectSection!!.title)
        assertEquals(ContentFormat.MARKDOWN, projectSection.contentFormat)
        assertEquals(listOf("overview", "documentation"), projectSection.tags)
    }

    @Test
    fun `execute - entity not found failure`() = runBlocking {
        // Setup mocks to return success for the first section
        mockSectionRepository.nextAddResult = { section ->
            Result.Success(section)
        }

        // Create valid params with two sections, but second has an invalid entity ID
        val params = JsonObject(
            mapOf(
                "sections" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "entityType" to JsonPrimitive("TASK"),
                                "entityId" to JsonPrimitive("00000000-0000-0000-0000-000000000001"),
                                "title" to JsonPrimitive("Task Section"),
                                "usageDescription" to JsonPrimitive("Task section description"),
                                "content" to JsonPrimitive("Task section content"),
                                "contentFormat" to JsonPrimitive("MARKDOWN"),
                                "ordinal" to JsonPrimitive(0)
                            )
                        ),
                        JsonObject(
                            mapOf(
                                "entityType" to JsonPrimitive("PROJECT"),
                                "entityId" to JsonPrimitive("00000000-0000-0000-0000-000000000099"), // Non-existent project
                                "title" to JsonPrimitive("Project Section"),
                                "usageDescription" to JsonPrimitive("Project section description"),
                                "content" to JsonPrimitive("Project section content"),
                                "contentFormat" to JsonPrimitive("PLAIN_TEXT"),
                                "ordinal" to JsonPrimitive(1)
                            )
                        )
                    )
                )
            )
        )

        // Execute the tool
        val result = tool.execute(params, mockContext)

        // Check that the response is still successful (partial success)
        assertTrue(ResponseTestUtils.isSuccessResponse(result))

        // Check a message
        val message = result.jsonObject["message"]?.jsonPrimitive?.content
        assertTrue(message!!.contains("1 sections created successfully"))
        assertTrue(message.contains("1 failed"))

        // Check data
        val data = result.jsonObject["data"]?.jsonObject
        assertNotNull(data)
        assertEquals(1, data!!["count"]?.jsonPrimitive?.int)
        assertEquals(1, data["failed"]?.jsonPrimitive?.int)

        // Check sections created
        val items = data["items"]?.jsonArray
        assertEquals(1, items?.size)

        // Check failures
        val failures = data["failures"]?.jsonArray
        assertEquals(1, failures?.size)
        val failure = failures!![0].jsonObject
        assertEquals(1, failure["index"]?.jsonPrimitive?.int)
        val error = failure["error"]?.jsonObject
        assertEquals(ErrorCodes.RESOURCE_NOT_FOUND, error!!["code"]?.jsonPrimitive?.content)
    }

    @Test
    fun `execute - all sections fail`() = runBlocking {
        // Create a mock to make all entities not found
        mockTaskRepository.clearTasks()
        mockFeatureRepository.clearFeatures()
        mockProjectRepository.clearProjects()

        // Create valid params with sections, but all entities are invalid
        val params = JsonObject(
            mapOf(
                "sections" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "entityType" to JsonPrimitive("TASK"),
                                "entityId" to JsonPrimitive("00000000-0000-0000-0000-000000000001"),
                                "title" to JsonPrimitive("Task Section"),
                                "usageDescription" to JsonPrimitive("Task section description"),
                                "content" to JsonPrimitive("Task section content"),
                                "contentFormat" to JsonPrimitive("MARKDOWN"),
                                "ordinal" to JsonPrimitive(0)
                            )
                        ),
                        JsonObject(
                            mapOf(
                                "entityType" to JsonPrimitive("PROJECT"),
                                "entityId" to JsonPrimitive("00000000-0000-0000-0000-000000000003"),
                                "title" to JsonPrimitive("Project Section"),
                                "usageDescription" to JsonPrimitive("Project section description"),
                                "content" to JsonPrimitive("Project section content"),
                                "contentFormat" to JsonPrimitive("PLAIN_TEXT"),
                                "ordinal" to JsonPrimitive(1)
                            )
                        )
                    )
                )
            )
        )

        // Execute the tool
        val result = tool.execute(params, mockContext)

        // Check that the response is an error
        assertTrue(ResponseTestUtils.isErrorResponse(result))

        // Check a message
        assertEquals("Failed to create any sections", result.jsonObject["message"]?.jsonPrimitive?.content)

        // Check error
        val error = result.jsonObject["error"]?.jsonObject
        assertNotNull(error)
        assertEquals(ErrorCodes.INTERNAL_ERROR, error!!["code"]?.jsonPrimitive?.content)

        // Check failures in additional data
        val extraData = error["additionalData"]?.jsonObject
        assertNotNull(extraData)
        val failures = extraData!!["failures"]?.jsonArray
        assertEquals(2, failures?.size)
    }
}