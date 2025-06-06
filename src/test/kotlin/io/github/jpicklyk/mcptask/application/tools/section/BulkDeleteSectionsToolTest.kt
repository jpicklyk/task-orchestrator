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

class BulkDeleteSectionsToolTest {

    private lateinit var tool: BulkDeleteSectionsTool
    private lateinit var mockSectionRepository: MockSectionRepository
    private lateinit var mockContext: ToolExecutionContext
    private val testSection1Id = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val testSection2Id = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val nonExistentSectionId = UUID.fromString("99999999-9999-9999-9999-999999999999")

    @BeforeEach
    fun setUp() {
        tool = BulkDeleteSectionsTool()
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
            title = "Task Section 1",
            usageDescription = "Task section description 1",
            content = "Task section content 1",
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 0,
            tags = listOf("test", "section1"),
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )

        val testSection2 = Section(
            id = testSection2Id,
            entityType = EntityType.FEATURE,
            entityId = UUID.fromString("00000000-0000-0000-0000-000000000002"),
            title = "Feature Section 1",
            usageDescription = "Feature section description 1",
            content = "Feature section content 1",
            contentFormat = ContentFormat.PLAIN_TEXT,
            ordinal = 0,
            tags = emptyList(),
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )

        mockSectionRepository.addSection(testSection1)
        mockSectionRepository.addSection(testSection2)
    }

    @Test
    fun `validate params - valid input with multiple section IDs`() {
        // Create valid params with two section IDs
        val params = JsonObject(
            mapOf(
                "ids" to JsonArray(
                    listOf(
                        JsonPrimitive(testSection1Id.toString()),
                        JsonPrimitive(testSection2Id.toString())
                    )
                ),
                "hardDelete" to JsonPrimitive(true)
            )
        )

        // Should not throw an exception
        tool.validateParams(params)
    }

    @Test
    fun `validate params - missing ids array`() {
        val params = JsonObject(mapOf())

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }

        assertTrue(exception.message!!.contains("Missing required parameter: ids"))
    }

    @Test
    fun `validate params - empty ids array`() {
        val params = JsonObject(
            mapOf(
                "ids" to JsonArray(emptyList())
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }

        assertTrue(exception.message!!.contains("At least one section ID must be provided"))
    }

    @Test
    fun `validate params - invalid UUID format`() {
        val params = JsonObject(
            mapOf(
                "ids" to JsonArray(
                    listOf(
                        JsonPrimitive("not-a-uuid")
                    )
                )
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }

        assertTrue(exception.message!!.contains("Invalid section ID"))
    }

    @Test
    fun `execute - success with all sections deleted`() = runBlocking {
        // Setup mock to return success for all section deletions
        mockSectionRepository.nextDeleteResult = { _ ->
            Result.Success(true)
        }

        // Create valid params with two section IDs
        val params = JsonObject(
            mapOf(
                "ids" to JsonArray(
                    listOf(
                        JsonPrimitive(testSection1Id.toString()),
                        JsonPrimitive(testSection2Id.toString())
                    )
                ),
                "hardDelete" to JsonPrimitive(false)
            )
        )

        // Execute the tool
        val result = tool.execute(params, mockContext)

        // Check that the response is successful
        assertTrue(ResponseTestUtils.isSuccessResponse(result))

        // Check message
        assertEquals("2 sections deleted successfully", result.jsonObject["message"]?.jsonPrimitive?.content)

        // Check data
        val data = result.jsonObject["data"]?.jsonObject
        assertNotNull(data)
        assertEquals(2, data!!["count"]?.jsonPrimitive?.int)
        assertEquals(0, data["failed"]?.jsonPrimitive?.int)
        assertFalse(data["hardDelete"]?.jsonPrimitive?.boolean ?: true)

        // Check IDs in response
        val ids = data["ids"]?.jsonArray
        assertEquals(2, ids?.size)
        assertTrue(ids!!.map { it.jsonPrimitive.content }.contains(testSection1Id.toString()))
        assertTrue(ids.map { it.jsonPrimitive.content }.contains(testSection2Id.toString()))

        // Verify section repository was called with correct parameters
        assertEquals(2, mockSectionRepository.deletedSectionIds.size)
        assertTrue(mockSectionRepository.deletedSectionIds.contains(testSection1Id))
        assertTrue(mockSectionRepository.deletedSectionIds.contains(testSection2Id))
    }

    @Test
    fun `execute - section not found failure`() = runBlocking {
        // Setup mock to return success for first section, not found for second
        mockSectionRepository.nextDeleteResult = { id ->
            if (id == testSection1Id) {
                Result.Success(true)
            } else {
                Result.Error(RepositoryError.NotFound(id, EntityType.SECTION, "Section not found"))
            }
        }

        // Create valid params with two section IDs, one that exists and one that doesn't
        val params = JsonObject(
            mapOf(
                "ids" to JsonArray(
                    listOf(
                        JsonPrimitive(testSection1Id.toString()),
                        JsonPrimitive(nonExistentSectionId.toString())
                    )
                )
            )
        )

        // Execute the tool
        val result = tool.execute(params, mockContext)

        // This is a partial success, so it should still be a success response
        assertTrue(ResponseTestUtils.isSuccessResponse(result))

        // Check message
        val message = result.jsonObject["message"]?.jsonPrimitive?.content
        assertTrue(message!!.contains("1 sections deleted successfully"))
        assertTrue(message.contains("1 failed"))

        // Check data
        val data = result.jsonObject["data"]?.jsonObject
        assertNotNull(data)
        assertEquals(1, data!!["count"]?.jsonPrimitive?.int)
        assertEquals(1, data["failed"]?.jsonPrimitive?.int)

        // Check failures
        val failures = data["failures"]?.jsonArray
        assertEquals(1, failures?.size)
        val failure = failures!![0].jsonObject
        assertEquals(nonExistentSectionId.toString(), failure["id"]?.jsonPrimitive?.content)
        val error = failure["error"]?.jsonObject
        assertEquals(ErrorCodes.RESOURCE_NOT_FOUND, error!!["code"]?.jsonPrimitive?.content)
    }

    @Test
    fun `execute - all sections fail`() = runBlocking {
        // Setup mock to return not found for all sections
        mockSectionRepository.nextGetSectionResult = { id ->
            Result.Error(RepositoryError.NotFound(id, EntityType.SECTION, "Section not found"))
        }

        // Create valid params with two section IDs that don't exist
        val params = JsonObject(
            mapOf(
                "ids" to JsonArray(
                    listOf(
                        JsonPrimitive(nonExistentSectionId.toString()),
                        JsonPrimitive(UUID.randomUUID().toString())
                    )
                )
            )
        )

        // Execute the tool
        val result = tool.execute(params, mockContext)

        // Since all deletions failed, this should be an error response
        assertTrue(ResponseTestUtils.isErrorResponse(result))

        // Check message
        assertEquals("Failed to delete any sections", result.jsonObject["message"]?.jsonPrimitive?.content)

        // Check error
        val error = result.jsonObject["error"]?.jsonObject
        assertNotNull(error)
        assertEquals(ErrorCodes.OPERATION_FAILED, error!!["code"]?.jsonPrimitive?.content)

        // Check failures in additional data
        val extraData = error["additionalData"]?.jsonObject
        assertNotNull(extraData)
        val failures = extraData!!["failures"]?.jsonArray
        assertEquals(2, failures?.size)
    }

    @Test
    fun `execute - delete operation fails without not found error`() = runBlocking {
        // Setup mock to return error for deletion
        mockSectionRepository.nextDeleteResult = { _ ->
            Result.Error(RepositoryError.DatabaseError("Database connection failed"))
        }

        // Create valid params with one section ID
        val params = JsonObject(
            mapOf(
                "ids" to JsonArray(
                    listOf(
                        JsonPrimitive(testSection1Id.toString())
                    )
                )
            )
        )

        // Execute the tool
        val result = tool.execute(params, mockContext)

        // Should be an error response since the only delete operation failed
        assertTrue(ResponseTestUtils.isErrorResponse(result))

        // Check error details
        val error = result.jsonObject["error"]?.jsonObject
        assertEquals(ErrorCodes.OPERATION_FAILED, error!!["code"]?.jsonPrimitive?.content)

        // Check additional data
        val extraData = error["additionalData"]?.jsonObject
        val failures = extraData!!["failures"]?.jsonArray
        assertEquals(1, failures?.size)
        val failure = failures!![0].jsonObject
        assertEquals(ErrorCodes.DATABASE_ERROR, failure["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content)
    }
}