package io.github.jpicklyk.mcptask.application.tools.section

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.domain.model.ContentFormat
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Section
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.github.jpicklyk.mcptask.test.mock.MockRepositoryProvider
import io.github.jpicklyk.mcptask.test.mock.MockSectionRepository
import io.github.jpicklyk.mcptask.test.utils.ResponseTestUtils
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.*

class BulkUpdateSectionsToolTest {

    private lateinit var tool: BulkUpdateSectionsTool
    private lateinit var mockSectionRepository: MockSectionRepository
    private lateinit var mockContext: ToolExecutionContext
    private val testSection1Id = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val testSection2Id = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val nonExistentSectionId = UUID.fromString("99999999-9999-9999-9999-999999999999")

    @BeforeEach
    fun setUp() {
        tool = BulkUpdateSectionsTool()
        mockSectionRepository = MockSectionRepository()

        // Set up repository provider with our mocks
        val repositoryProvider = MockRepositoryProvider(
            sectionRepository = mockSectionRepository
        )

        // Create mock context
        mockContext = ToolExecutionContext(repositoryProvider)

        // Set up test data
        val testSection1 = Section(
            id = testSection1Id,
            entityType = EntityType.TASK,
            entityId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            title = "Original Task Section",
            usageDescription = "Original task section description",
            content = "Original task section content",
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 0,
            tags = listOf("original", "tag"),
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )

        val testSection2 = Section(
            id = testSection2Id,
            entityType = EntityType.FEATURE,
            entityId = UUID.fromString("00000000-0000-0000-0000-000000000002"),
            title = "Original Feature Section",
            usageDescription = "Original feature section description",
            content = "Original feature section content",
            contentFormat = ContentFormat.PLAIN_TEXT,
            ordinal = 1,
            tags = emptyList(),
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )

        mockSectionRepository.addSection(testSection1)
        mockSectionRepository.addSection(testSection2)
    }

    @Test
    fun `validate params - valid input with multiple sections`() {
        // Create valid params with two sections
        val params = JsonObject(
            mapOf(
                "sections" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(testSection1Id.toString()),
                                "title" to JsonPrimitive("Updated Task Section"),
                                "content" to JsonPrimitive("Updated task section content")
                            )
                        ),
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(testSection2Id.toString()),
                                "title" to JsonPrimitive("Updated Feature Section"),
                                "contentFormat" to JsonPrimitive("CODE"),
                                "tags" to JsonPrimitive("new,tags")
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
    fun `validate params - missing id field`() {
        val params = JsonObject(
            mapOf(
                "sections" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "title" to JsonPrimitive("Updated Task Section"),
                                "content" to JsonPrimitive("Updated task section content")
                            )
                        )
                    )
                )
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }

        assertTrue(exception.message!!.contains("missing required field: id"))
    }

    @Test
    fun `validate params - invalid id format`() {
        val params = JsonObject(
            mapOf(
                "sections" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive("not-a-uuid"),
                                "title" to JsonPrimitive("Updated Task Section")
                            )
                        )
                    )
                )
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }

        assertTrue(exception.message!!.contains("Invalid id"))
    }

    @Test
    fun `validate params - no fields to update`() {
        val params = JsonObject(
            mapOf(
                "sections" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(testSection1Id.toString())
                                // No update fields
                            )
                        )
                    )
                )
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }

        assertTrue(exception.message!!.contains("has no fields to update"))
    }

    @Test
    fun `execute - success with all sections updated`() = runBlocking {
        // Setup mock to return success for all section updates
        mockSectionRepository.nextUpdateResult = { section ->
            Result.Success(section)
        }

        // Create valid params with two sections to update
        val params = JsonObject(
            mapOf(
                "sections" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(testSection1Id.toString()),
                                "title" to JsonPrimitive("Updated Task Section"),
                                "content" to JsonPrimitive("Updated task section content")
                            )
                        ),
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(testSection2Id.toString()),
                                "title" to JsonPrimitive("Updated Feature Section"),
                                "contentFormat" to JsonPrimitive("CODE"),
                                "tags" to JsonPrimitive("new,tags")
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

        // Check message
        assertEquals("2 sections updated successfully", result.jsonObject["message"]?.jsonPrimitive?.content)

        // Check data
        val data = result.jsonObject["data"]?.jsonObject
        assertNotNull(data)
        assertEquals(2, data!!["count"]?.jsonPrimitive?.int)
        assertEquals(0, data["failed"]?.jsonPrimitive?.int)

        // Check sections in response
        val items = data["items"]?.jsonArray
        assertEquals(2, items?.size)

        // Verify section repository was called with correct parameters
        assertEquals(2, mockSectionRepository.updatedSections.size)

        // Check the first updated section
        val updatedSection1 = mockSectionRepository.updatedSections.find { it.id == testSection1Id }
        assertNotNull(updatedSection1)
        assertEquals("Updated Task Section", updatedSection1!!.title)
        assertEquals("Updated task section content", updatedSection1.content)

        // Check the second updated section
        val updatedSection2 = mockSectionRepository.updatedSections.find { it.id == testSection2Id }
        assertNotNull(updatedSection2)
        assertEquals("Updated Feature Section", updatedSection2!!.title)
        assertEquals(ContentFormat.CODE, updatedSection2.contentFormat)
        assertEquals(listOf("new", "tags"), updatedSection2.tags)
    }

    @Test
    fun `execute - section not found failure`() = runBlocking {
        // Create valid params with one existing section and one non-existent section
        val params = JsonObject(
            mapOf(
                "sections" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(testSection1Id.toString()),
                                "title" to JsonPrimitive("Updated Task Section")
                            )
                        ),
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(nonExistentSectionId.toString()),
                                "title" to JsonPrimitive("Non-existent Section")
                            )
                        )
                    )
                )
            )
        )

        // Configure mock repository to return not found for the non-existent section
        mockSectionRepository.nextGetSectionResult = { id ->
            if (id == nonExistentSectionId) {
                Result.Error(RepositoryError.NotFound(id, EntityType.SECTION, "Section not found"))
            } else {
                val section = mockSectionRepository.getExistingSection(id)
                if (section != null) {
                    Result.Success(section)
                } else {
                    Result.Error(RepositoryError.NotFound(id, EntityType.SECTION, "Section not found"))
                }
            }
        }

        mockSectionRepository.nextUpdateResult = { section ->
            Result.Success(section)
        }

        // Execute the tool
        val result = tool.execute(params, mockContext)

        // This is a partial success, so response should still be a success
        assertTrue(ResponseTestUtils.isSuccessResponse(result))

        // Check message for partial success
        val message = result.jsonObject["message"]?.jsonPrimitive?.content
        assertTrue(message!!.contains("1 sections updated successfully"))
        assertTrue(message.contains("1 failed"))

        // Check data
        val data = result.jsonObject["data"]?.jsonObject
        assertNotNull(data)
        assertEquals(1, data!!["count"]?.jsonPrimitive?.int)
        assertEquals(1, data["failed"]?.jsonPrimitive?.int)

        // Check updated sections
        val items = data["items"]?.jsonArray
        assertEquals(1, items?.size)

        // Check failures
        val failures = data["failures"]?.jsonArray
        assertEquals(1, failures?.size)
        val failure = failures!![0].jsonObject
        assertEquals(1, failure["index"]?.jsonPrimitive?.int)
        val error = failure["error"]?.jsonObject
        assertEquals(ErrorCodes.RESOURCE_NOT_FOUND, error!!["code"]?.jsonPrimitive?.content)

        // Verify only one section was updated
        assertEquals(1, mockSectionRepository.updatedSections.size)
        assertEquals(testSection1Id, mockSectionRepository.updatedSections[0].id)
    }

    @Test
    fun `execute - all sections fail`() = runBlocking {
        // Configure a repository to return not found for all sections
        mockSectionRepository.nextGetSectionResult = { _ ->
            Result.Error(RepositoryError.NotFound(UUID.randomUUID(), EntityType.SECTION, "Section not found"))
        }

        // Create valid params with two sections that don't exist
        val params = JsonObject(
            mapOf(
                "sections" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(nonExistentSectionId.toString()),
                                "title" to JsonPrimitive("Non-existent Section 1")
                            )
                        ),
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(UUID.randomUUID().toString()),
                                "title" to JsonPrimitive("Non-existent Section 2")
                            )
                        )
                    )
                )
            )
        )

        // Execute the tool
        val result = tool.execute(params, mockContext)

        // All updates failed, so this should be an error response
        assertTrue(ResponseTestUtils.isErrorResponse(result))

        // Check message
        assertEquals("Failed to update any sections", result.jsonObject["message"]?.jsonPrimitive?.content)

        // Check error
        val error = result.jsonObject["error"]?.jsonObject
        assertNotNull(error)
        assertEquals(ErrorCodes.OPERATION_FAILED, error!!["code"]?.jsonPrimitive?.content)

        // Check failures in additional data
        val extraData = error["additionalData"]?.jsonObject
        assertNotNull(extraData)
        val failures = extraData!!["failures"]?.jsonArray
        assertEquals(2, failures?.size)

        // Verify no sections were updated
        assertEquals(0, mockSectionRepository.updatedSections.size)
    }

    @Test
    fun `execute - update operation fails with validation error`() = runBlocking {
        // Configure repository to return validation error for updates
        mockSectionRepository.nextUpdateResult = { _ ->
            Result.Error(RepositoryError.ValidationError("Title cannot be empty"))
        }

        // Create valid params with one section with invalid data
        val params = JsonObject(
            mapOf(
                "sections" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(testSection1Id.toString()),
                                "title" to JsonPrimitive("") // Empty title will cause a validation error
                            )
                        )
                    )
                )
            )
        )

        // Execute the tool
        val result = tool.execute(params, mockContext)

        // This should be an error response since the only update operation failed
        assertTrue(ResponseTestUtils.isErrorResponse(result))

        // Check message
        assertEquals("Failed to update any sections", result.jsonObject["message"]?.jsonPrimitive?.content)

        // Check error
        val error = result.jsonObject["error"]?.jsonObject
        assertNotNull(error)
        assertEquals(ErrorCodes.OPERATION_FAILED, error!!["code"]?.jsonPrimitive?.content)

        // Check failures
        val extraData = error["additionalData"]?.jsonObject
        assertNotNull(extraData)
        val failures = extraData!!["failures"]?.jsonArray
        assertNotNull(failures)
        assertEquals(1, failures!!.size)
        val failure = failures[0].jsonObject
        val failureError = failure["error"]?.jsonObject
        assertNotNull(failureError)
        assertEquals(ErrorCodes.VALIDATION_ERROR, failureError!!["code"]?.jsonPrimitive?.content)
    }
}