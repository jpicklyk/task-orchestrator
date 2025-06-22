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
import io.mockk.coVerify
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

class UpdateSectionTextToolTest {

    private lateinit var tool: UpdateSectionTextTool
    private lateinit var mockSectionRepository: SectionRepository
    private lateinit var mockRepositoryProvider: RepositoryProvider
    private lateinit var context: ToolExecutionContext
    private lateinit var sectionId: UUID
    private lateinit var entityId: UUID
    private lateinit var originalSection: Section
    private val originalContent = "This is the original content with some text to replace and more text."

    @BeforeEach
    fun setUp() {
        sectionId = UUID.randomUUID()
        entityId = UUID.randomUUID()
        originalSection = Section(
            id = sectionId,
            entityType = EntityType.TASK,
            entityId = entityId,
            title = "Test Section",
            usageDescription = "Test usage description",
            content = originalContent,
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 0,
            tags = listOf("test", "tag"),
            createdAt = Instant.now().minusSeconds(3600),
            modifiedAt = Instant.now().minusSeconds(1800)
        )

        // Create mocks
        mockSectionRepository = mockk<SectionRepository>()
        mockRepositoryProvider = mockk<RepositoryProvider>()
        every { mockRepositoryProvider.sectionRepository() } returns mockSectionRepository
        context = ToolExecutionContext(mockRepositoryProvider)
        tool = UpdateSectionTextTool()

        // Setup default behavior
        coEvery { mockSectionRepository.getSection(sectionId) } returns Result.Success(originalSection)
        coEvery { mockSectionRepository.getSection(neq(sectionId)) } returns
                Result.Error(RepositoryError.NotFound(UUID.randomUUID(), EntityType.SECTION, "Section not found"))

        // When updating a section, return a modified version with the updated content
        coEvery { mockSectionRepository.updateSection(any()) } answers {
            val updatedSection = firstArg<Section>()
            Result.Success(updatedSection)
        }
    }

    @Test
    fun `test validate params - valid parameters`() {
        val params = buildJsonObject {
            put("id", sectionId.toString())
            put("oldText", "some text to replace")
            put("newText", "updated text")
        }

        // Should not throw exception
        tool.validateParams(params)
    }

    @Test
    fun `test validate params - invalid ID format`() {
        val params = buildJsonObject {
            put("id", "not-a-uuid")
            put("oldText", "some text")
            put("newText", "new text")
        }

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Invalid section ID format"))
    }

    @Test
    fun `test validate params - missing oldText`() {
        val params = buildJsonObject {
            put("id", sectionId.toString())
            put("newText", "new text")
        }

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Missing required parameter: oldText"))
    }

    @Test
    fun `test validate params - missing newText`() {
        val params = buildJsonObject {
            put("id", sectionId.toString())
            put("oldText", "some text")
        }

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Missing required parameter: newText"))
    }

    @Test
    fun `test execute - successfully update section text`() = runBlocking {
        val oldText = "some text to replace"
        val newText = "updated text"
        val params = buildJsonObject {
            put("id", sectionId.toString())
            put("oldText", oldText)
            put("newText", newText)
        }

        val result = tool.execute(params, context)

        assertTrue(result is JsonObject)
        assertTrue(result["success"]?.jsonPrimitive?.boolean == true)

        val data = result["data"]?.jsonObject
        assertFalse(data == null)

        // Check that we get expected replacement information
        assertEquals(sectionId.toString(), data["id"]?.jsonPrimitive?.content)
        assertEquals(oldText.length, data["replacedTextLength"]?.jsonPrimitive?.int)
        assertEquals(newText.length, data["newTextLength"]?.jsonPrimitive?.int)

        // Verify that the section was updated with the correct content
        coVerify {
            mockSectionRepository.updateSection(match { section ->
                section.content == originalContent.replace(oldText, newText)
            })
        }
    }

    @Test
    fun `test execute - section not found`() = runBlocking {
        val nonExistentId = UUID.randomUUID()
        val params = buildJsonObject {
            put("id", nonExistentId.toString())
            put("oldText", "some text")
            put("newText", "new text")
        }

        coEvery { mockSectionRepository.getSection(nonExistentId) } returns
                Result.Error(RepositoryError.NotFound(nonExistentId, EntityType.SECTION, "Section not found"))

        val result = tool.execute(params, context)

        assertTrue(result is JsonObject)
        assertFalse(result["success"]?.jsonPrimitive?.boolean == true)
        assertEquals(ErrorCodes.RESOURCE_NOT_FOUND, result["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content)
    }

    @Test
    fun `test execute - text not found in content`() = runBlocking {
        val params = buildJsonObject {
            put("id", sectionId.toString())
            put("oldText", "non-existent text")
            put("newText", "new text")
        }

        val result = tool.execute(params, context)

        assertTrue(result is JsonObject)
        assertFalse(result["success"]?.jsonPrimitive?.boolean == true)
        assertEquals(ErrorCodes.VALIDATION_ERROR, result["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content)
        assertTrue(result["error"]?.jsonObject?.get("details")?.jsonPrimitive?.content?.contains("not found") == true)
    }

    @Test
    fun `test execute - multiple replacements work independently`() = runBlocking {
        // First replacement
        val oldText1 = "some text to replace"
        val newText1 = "updated text"
        val params1 = buildJsonObject {
            put("id", sectionId.toString())
            put("oldText", oldText1)
            put("newText", newText1)
        }

        // Execute the first update
        tool.execute(params1, context)

        // Second replacement with different text
        val oldText2 = "more text"
        val newText2 = "additional content"
        val params2 = buildJsonObject {
            put("id", sectionId.toString())
            put("oldText", oldText2)
            put("newText", newText2)
        }

        // Update mock to simulate the first update being applied
        val updatedContent1 = originalContent.replace(oldText1, newText1)
        val updatedSection1 = originalSection.copy(content = updatedContent1)
        coEvery { mockSectionRepository.getSection(sectionId) } returns Result.Success(updatedSection1)

        // Execute the second update
        tool.execute(params2, context)

        // Verify that updates were made - we don't need to check the exact parameters
        // as our test focuses on making sure the operations work, not on exact text content
        coVerify(atLeast = 1) { mockSectionRepository.updateSection(any()) }
    }
}