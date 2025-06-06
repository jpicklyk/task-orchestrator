package io.github.jpicklyk.mcptask.application.tools.section

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.domain.model.ContentFormat
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Section
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.domain.repository.SectionRepository
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
import java.util.*

class DeleteSectionToolTest {

    private lateinit var tool: DeleteSectionTool
    private lateinit var mockContext: ToolExecutionContext
    private lateinit var mockSectionRepository: SectionRepository
    private val testSectionId = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        // Create a mock repository provider and repositories
        mockSectionRepository = mockk<SectionRepository>()
        val mockRepositoryProvider = mockk<RepositoryProvider>()

        // Configure the repository provider to return the mock repositories
        every { mockRepositoryProvider.sectionRepository() } returns mockSectionRepository

        // Create a sample section for the mock response
        val mockSection = Section(
            id = testSectionId,
            entityType = EntityType.TASK,
            entityId = UUID.randomUUID(),
            title = "Test Section",
            usageDescription = "Section for testing",
            content = "Section content",
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 0
        )

        // Mock the getSection method to return the mock section for the valid ID
        coEvery { mockSectionRepository.getSection(testSectionId) } returns Result.Success(mockSection)

        // Mock the getSection method to return a not found error for any other ID
        coEvery { mockSectionRepository.getSection(neq(testSectionId)) } returns
                Result.Error(RepositoryError.NotFound(UUID.randomUUID(), EntityType.SECTION, "Section not found"))

        // Mock the delete method to return true (successful deletion) for the valid ID
        coEvery { mockSectionRepository.deleteSection(testSectionId) } returns Result.Success(true)

        // Create the execution context with the mock repository provider
        mockContext = ToolExecutionContext(mockRepositoryProvider)

        tool = DeleteSectionTool()
    }

    @Test
    fun `validate with valid parameters should not throw exceptions`() {
        val validParams = buildJsonObject {
            put("id", testSectionId.toString())
        }

        // Should not throw any exceptions
        assertDoesNotThrow { tool.validateParams(validParams) }
    }

    @Test
    fun `validate without required ID should throw validation exception`() {
        val invalidParams = buildJsonObject {
            put("hardDelete", "true")
        }

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(invalidParams)
        }

        assertTrue(exception.message!!.contains("Missing required parameter: id"))
    }

    @Test
    fun `validate with invalid UUID should throw validation exception`() {
        val invalidParams = buildJsonObject {
            put("id", "not-a-uuid")
        }

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(invalidParams)
        }

        assertTrue(exception.message!!.contains("Invalid section ID format"))
    }

    @Test
    fun `execute with valid ID should delete section`() = runBlocking {
        val params = buildJsonObject {
            put("id", testSectionId.toString())
        }

        val result = tool.execute(params, mockContext)

        // Convert result to JsonObject for assertions
        val responseObj = result as JsonObject

        // Assert success
        assertEquals(true, responseObj["success"]?.jsonPrimitive?.content?.toBoolean(), "Success should be true")

        // Verify message
        assertTrue(
            responseObj["message"]?.jsonPrimitive?.content?.contains("Section deleted successfully") ?: false,
            "Message should contain 'Section deleted successfully'"
        )

        // Verify data
        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data object should not be null")
        assertEquals(testSectionId.toString(), data!!["id"]?.jsonPrimitive?.content)
        assertEquals("true", data["deleted"]?.jsonPrimitive?.content)
    }

    @Test
    fun `execute with non-existent ID should return error`() = runBlocking {
        val nonExistentId = UUID.randomUUID()
        val params = buildJsonObject {
            put("id", nonExistentId.toString())
        }

        val result = tool.execute(params, mockContext)

        // Convert result to JsonObject for assertions
        val responseObj = result as JsonObject

        // Assert failure
        assertEquals(false, responseObj["success"]?.jsonPrimitive?.content?.toBoolean(), "Success should be false")

        // Verify message
        assertTrue(
            responseObj["message"]?.jsonPrimitive?.content?.contains("Section not found") ?: false,
            "Message should contain 'Section not found'"
        )

        // Verify error
        val error = responseObj["error"]?.jsonObject
        assertNotNull(error, "Error object should not be null")
        assertEquals("RESOURCE_NOT_FOUND", error!!["code"]?.jsonPrimitive?.content)
    }

    @Test
    fun `execute with database error should return error response`() = runBlocking {
        // Configure mockSectionRepository to return a database error
        coEvery {
            mockSectionRepository.deleteSection(testSectionId)
        } returns Result.Error(RepositoryError.DatabaseError("Database connection failed"))

        val params = buildJsonObject {
            put("id", testSectionId.toString())
        }

        val result = tool.execute(params, mockContext)

        // Convert result to JsonObject for assertions
        val responseObj = result as JsonObject

        // Assert failure
        assertEquals(false, responseObj["success"]?.jsonPrimitive?.content?.toBoolean(), "Success should be false")

        // Verify message
        assertTrue(
            responseObj["message"]?.jsonPrimitive?.content?.contains("Failed to delete section") ?: false,
            "Message should contain 'Failed to delete section'"
        )

        // Verify error
        val error = responseObj["error"]?.jsonObject
        assertNotNull(error, "Error object should not be null")
        assertEquals("DATABASE_ERROR", error!!["code"]?.jsonPrimitive?.content)
    }
}