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
import java.time.Instant
import java.util.*

class UpdateSectionToolTest {

    private lateinit var tool: UpdateSectionTool
    private lateinit var mockContext: ToolExecutionContext
    private lateinit var mockSectionRepository: SectionRepository
    private val testSectionId = UUID.randomUUID()
    private lateinit var testSection: Section
    private lateinit var updatedSection: Section

    @BeforeEach
    fun setup() {
        // Create tool instance
        tool = UpdateSectionTool()

        // Create mocks
        mockSectionRepository = mockk<SectionRepository>()
        val mockRepositoryProvider = mockk<RepositoryProvider>()
        every { mockRepositoryProvider.sectionRepository() } returns mockSectionRepository

        // Create a test section
        testSection = Section(
            id = testSectionId,
            entityType = EntityType.TASK,
            entityId = UUID.randomUUID(),
            title = "Original Title",
            usageDescription = "Original Description",
            content = "Original Content",
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 0,
            tags = listOf("original", "tag"),
            createdAt = Instant.now().minusSeconds(3600),
            modifiedAt = Instant.now().minusSeconds(1800)
        )

        // Create an updated section for mocking the update response
        updatedSection = testSection.copy(
            title = "Updated Title",
            usageDescription = "Updated Description",
            content = "Updated Content",
            contentFormat = ContentFormat.PLAIN_TEXT,
            ordinal = 1,
            tags = listOf("updated", "tags"),
            modifiedAt = Instant.now()
        )

        // Mock repository behaviors
        coEvery { mockSectionRepository.getSection(testSectionId) } returns Result.Success(testSection)
        coEvery { mockSectionRepository.getSection(neq(testSectionId)) } returns
                Result.Error(RepositoryError.NotFound(UUID.randomUUID(), EntityType.SECTION, "Section not found"))

        // Mock update method to return the updated section
        coEvery { mockSectionRepository.updateSection(any()) } answers {
            // Return a modified copy of the input section
            Result.Success(updatedSection)
        }

        // Create execution context
        mockContext = ToolExecutionContext(mockRepositoryProvider)
    }

    @Test
    fun `validate with valid parameters should not throw exceptions`() {
        val validParams = buildJsonObject {
            put("id", testSectionId.toString())
            put("title", "Updated Title")
            put("usageDescription", "Updated Description")
            put("content", "Updated Content")
            put("contentFormat", ContentFormat.PLAIN_TEXT.name)
            put("ordinal", 1)
            put("tags", "updated,tags")
        }

        // Should not throw any exceptions
        assertDoesNotThrow { tool.validateParams(validParams) }
    }

    @Test
    fun `validate with only ID should not throw exceptions`() {
        val minimalParams = buildJsonObject {
            put("id", testSectionId.toString())
        }

        // Should not throw any exceptions
        assertDoesNotThrow { tool.validateParams(minimalParams) }
    }

    @Test
    fun `validate without required ID should throw validation exception`() {
        val invalidParams = buildJsonObject {
            put("title", "Updated Title")
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
    fun `validate with invalid content format should throw validation exception`() {
        val invalidParams = buildJsonObject {
            put("id", testSectionId.toString())
            put("contentFormat", "INVALID_FORMAT")
        }

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(invalidParams)
        }

        assertTrue(exception.message!!.contains("Invalid content format"))
    }

    @Test
    fun `validate with negative ordinal should throw validation exception`() {
        val invalidParams = buildJsonObject {
            put("id", testSectionId.toString())
            put("ordinal", -1)
        }

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(invalidParams)
        }

        assertTrue(exception.message!!.contains("Ordinal must be a non-negative integer"))
    }

    @Test
    fun `execute with valid parameters should update section`() = runBlocking {
        val params = buildJsonObject {
            put("id", testSectionId.toString())
            put("title", "Updated Title")
            put("usageDescription", "Updated Description")
            put("content", "Updated Content")
            put("contentFormat", ContentFormat.PLAIN_TEXT.name)
            put("ordinal", 1)
            put("tags", "updated,tags")
        }

        val result = tool.execute(params, mockContext)

        // Convert to JsonObject for assertions
        val responseObj = result as JsonObject

        // Assert success
        assertEquals(true, responseObj["success"]?.jsonPrimitive?.content?.toBoolean(), "Success should be true")

        // Verify message
        assertTrue(
            responseObj["message"]?.jsonPrimitive?.content?.contains("Section updated successfully") ?: false,
            "Message should contain 'Section updated successfully'"
        )

        // Verify data - verify minimal response format for sections
        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data object should not be null")

        // Sections don't have status, so only id and modifiedAt are returned
        assertEquals(testSectionId.toString(), data?.get("id")?.jsonPrimitive?.content)
        assertNotNull(data?.get("modifiedAt"), "ModifiedAt should be present")

        // Verify only minimal fields are returned (optimization)
        assertEquals(2, data!!.size, "Response should only contain id and modifiedAt")
        assertNull(data["title"], "Title should not be in minimal response")
        assertNull(data["contentFormat"], "ContentFormat should not be in minimal response")
        assertNull(data["content"], "Content should not be in minimal response")
    }

    @Test
    fun `execute with non-existent ID should return error`() = runBlocking {
        val nonExistentId = UUID.randomUUID()
        val params = buildJsonObject {
            put("id", nonExistentId.toString())
            put("title", "Updated Title")
        }

        val result = tool.execute(params, mockContext)

        // Convert to JsonObject for assertions
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
        assertEquals("RESOURCE_NOT_FOUND", error?.get("code")?.jsonPrimitive?.content)
    }

    @Test
    fun `execute with database error should return error response`() = runBlocking {
        // Configure mockSectionRepository to return a database error for update
        coEvery {
            mockSectionRepository.updateSection(any())
        } returns Result.Error(RepositoryError.DatabaseError("Database connection failed"))

        val params = buildJsonObject {
            put("id", testSectionId.toString())
            put("title", "Updated Title")
        }

        val result = tool.execute(params, mockContext)

        // Convert to JsonObject for assertions
        val responseObj = result as JsonObject

        // Assert failure
        assertEquals(false, responseObj["success"]?.jsonPrimitive?.content?.toBoolean(), "Success should be false")

        // Verify message
        assertTrue(
            responseObj["message"]?.jsonPrimitive?.content?.contains("Failed to update section") ?: false,
            "Message should contain 'Failed to update section'"
        )

        // Verify error
        val error = responseObj["error"]?.jsonObject
        assertNotNull(error, "Error object should not be null")
        assertEquals("DATABASE_ERROR", error?.get("code")?.jsonPrimitive?.content)
    }
}