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
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateSectionMetadataToolTest {

    private lateinit var tool: UpdateSectionMetadataTool
    private lateinit var mockSectionRepository: SectionRepository
    private lateinit var mockRepositoryProvider: RepositoryProvider
    private lateinit var context: ToolExecutionContext
    private lateinit var sectionId: UUID
    private lateinit var entityId: UUID
    private lateinit var originalSection: Section
    private lateinit var updatedSection: Section

    @BeforeEach
    fun setUp() {
        sectionId = UUID.randomUUID()
        entityId = UUID.randomUUID()
        originalSection = Section(
            id = sectionId,
            entityType = EntityType.TASK,
            entityId = entityId,
            title = "Original Title",
            usageDescription = "Original usage description",
            content = "Original content",
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 0,
            tags = listOf("original", "tag"),
            createdAt = Instant.now().minusSeconds(3600),
            modifiedAt = Instant.now().minusSeconds(1800)
        )

        updatedSection = originalSection.copy(
            title = "Updated Title",
            usageDescription = "Updated usage description",
            contentFormat = ContentFormat.PLAIN_TEXT,
            ordinal = 1,
            tags = listOf("new", "tags"),
            modifiedAt = Instant.now()
        )

        // Create mocks
        mockSectionRepository = mockk<SectionRepository>()
        mockRepositoryProvider = mockk<RepositoryProvider>()
        every { mockRepositoryProvider.sectionRepository() } returns mockSectionRepository
        context = ToolExecutionContext(mockRepositoryProvider)
        tool = UpdateSectionMetadataTool()

        // Setup default behavior
        coEvery { mockSectionRepository.getSection(sectionId) } returns Result.Success(originalSection)
        coEvery { mockSectionRepository.getSection(neq(sectionId)) } returns
                Result.Error(RepositoryError.NotFound(UUID.randomUUID(), EntityType.SECTION, "Section not found"))
        coEvery { mockSectionRepository.updateSection(any()) } returns Result.Success(updatedSection)
    }

    @Test
    fun `test validate params - valid parameters`() {
        val params = buildJsonObject {
            put("id", sectionId.toString())
            put("title", "Updated Title")
            put("usageDescription", "Updated usage description")
            put("contentFormat", "PLAIN_TEXT")
            put("ordinal", 1)
            put("tags", "new,tags")
        }

        // Should not throw exception
        tool.validateParams(params)
    }

    @Test
    fun `test validate params - invalid ID format`() {
        val params = buildJsonObject {
            put("id", "not-a-uuid")
            put("title", "Updated Title")
        }

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Invalid section ID format"))
    }

    @Test
    fun `test validate params - invalid content format`() {
        val params = buildJsonObject {
            put("id", sectionId.toString())
            put("contentFormat", "INVALID")
        }

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Invalid content format"))
    }

    @Test
    fun `test validate params - invalid ordinal`() {
        val params = buildJsonObject {
            put("id", sectionId.toString())
            put("ordinal", -1)
        }

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Ordinal must be a non-negative integer"))
    }

    @Test
    fun `test execute - successfully update section metadata`() = runBlocking {
        val params = buildJsonObject {
            put("id", sectionId.toString())
            put("title", "Updated Title")
            put("usageDescription", "Updated usage description")
            put("contentFormat", "PLAIN_TEXT")
            put("ordinal", 1)
            put("tags", "new,tags")
        }

        val result = tool.execute(params, context)

        assertTrue(result is JsonObject)
        assertTrue(result["success"]?.jsonPrimitive?.boolean == true)

        val data = result["data"]?.jsonObject
        assertFalse(data == null)

        val section = data["section"]?.jsonObject
        assertFalse(section == null)

        assertEquals("Updated Title", section["title"]?.jsonPrimitive?.content)
        assertEquals("Updated usage description", section["usageDescription"]?.jsonPrimitive?.content)
        assertEquals("plain_text", section["contentFormat"]?.jsonPrimitive?.content)
        assertEquals(1, section["ordinal"]?.jsonPrimitive?.int)

        val tags = section["tags"]?.jsonArray
        assertFalse(tags == null)
        assertEquals(2, tags.size)
        assertEquals("new", tags[0].jsonPrimitive.content)
        assertEquals("tags", tags[1].jsonPrimitive.content)
    }

    @Test
    fun `test execute - section not found`() = runBlocking {
        val nonExistentId = UUID.randomUUID()
        val params = buildJsonObject {
            put("id", nonExistentId.toString())
            put("title", "Updated Title")
        }

        coEvery { mockSectionRepository.getSection(nonExistentId) } returns
                Result.Error(RepositoryError.NotFound(nonExistentId, EntityType.SECTION, "Section not found"))

        val result = tool.execute(params, context)

        assertTrue(result is JsonObject)
        assertFalse(result["success"]?.jsonPrimitive?.boolean == true)
        assertEquals(ErrorCodes.RESOURCE_NOT_FOUND, result["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content)
    }

    @Test
    fun `test execute - empty title is invalid`() = runBlocking {
        val params = buildJsonObject {
            put("id", sectionId.toString())
            put("title", "")
        }

        val result = tool.execute(params, context)

        assertTrue(result is JsonObject)
        assertFalse(result["success"]?.jsonPrimitive?.boolean == true)
        assertEquals(ErrorCodes.VALIDATION_ERROR, result["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content)
    }

    @Test
    fun `test execute - empty usage description is invalid`() = runBlocking {
        val params = buildJsonObject {
            put("id", sectionId.toString())
            put("usageDescription", "")
        }

        val result = tool.execute(params, context)

        assertTrue(result is JsonObject)
        assertFalse(result["success"]?.jsonPrimitive?.boolean == true)
        assertEquals(ErrorCodes.VALIDATION_ERROR, result["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content)
    }
}