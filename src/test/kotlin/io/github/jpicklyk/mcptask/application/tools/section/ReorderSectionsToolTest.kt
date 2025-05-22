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
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReorderSectionsToolTest {

    private lateinit var tool: ReorderSectionsTool
    private lateinit var mockSectionRepository: SectionRepository
    private lateinit var mockRepositoryProvider: RepositoryProvider
    private lateinit var context: ToolExecutionContext
    private lateinit var entityId: UUID
    private lateinit var section1: Section
    private lateinit var section2: Section
    private lateinit var section3: Section
    private val sectionIds = mutableListOf<UUID>()

    @BeforeEach
    fun setUp() {
        entityId = UUID.randomUUID()

        // Create three sections for testing
        section1 = Section(
            id = UUID.randomUUID(),
            entityType = EntityType.TASK,
            entityId = entityId,
            title = "Section 1",
            usageDescription = "First section",
            content = "Content for section 1",
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 0
        )

        section2 = Section(
            id = UUID.randomUUID(),
            entityType = EntityType.TASK,
            entityId = entityId,
            title = "Section 2",
            usageDescription = "Second section",
            content = "Content for section 2",
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 1
        )

        section3 = Section(
            id = UUID.randomUUID(),
            entityType = EntityType.TASK,
            entityId = entityId,
            title = "Section 3",
            usageDescription = "Third section",
            content = "Content for section 3",
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 2
        )

        sectionIds.add(section1.id)
        sectionIds.add(section2.id)
        sectionIds.add(section3.id)

        // Create mocks
        mockSectionRepository = mockk<SectionRepository>()
        mockRepositoryProvider = mockk<RepositoryProvider>()
        every { mockRepositoryProvider.sectionRepository() } returns mockSectionRepository
        context = ToolExecutionContext(mockRepositoryProvider)
        tool = ReorderSectionsTool()

        // Setup default behavior
        coEvery { mockSectionRepository.getSectionsForEntity(EntityType.TASK, entityId) } returns
                Result.Success(listOf(section1, section2, section3))

        coEvery { mockSectionRepository.reorderSections(EntityType.TASK, entityId, any()) } returns
                Result.Success(true)
    }

    @Test
    fun `test validate params - valid parameters`() {
        val params = buildJsonObject {
            put("entityType", "TASK")
            put("entityId", entityId.toString())
            put("sectionOrder", "${section3.id},${section1.id},${section2.id}")
        }

        // Should not throw exception
        tool.validateParams(params)
    }

    @Test
    fun `test validate params - invalid entity type`() {
        val params = buildJsonObject {
            put("entityType", "INVALID")
            put("entityId", entityId.toString())
            put("sectionOrder", "${section3.id},${section1.id},${section2.id}")
        }

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Invalid entity type"))
    }

    @Test
    fun `test validate params - invalid entity ID format`() {
        val params = buildJsonObject {
            put("entityType", "TASK")
            put("entityId", "not-a-uuid")
            put("sectionOrder", "${section3.id},${section1.id},${section2.id}")
        }

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Invalid entity ID format"))
    }

    @Test
    fun `test validate params - empty section order`() {
        val params = buildJsonObject {
            put("entityType", "TASK")
            put("entityId", entityId.toString())
            put("sectionOrder", "")
        }

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Required parameter sectionOrder cannot be empty"))
    }

    @Test
    fun `test validate params - invalid section ID format`() {
        val params = buildJsonObject {
            put("entityType", "TASK")
            put("entityId", entityId.toString())
            put("sectionOrder", "${section1.id},not-a-uuid,${section3.id}")
        }

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Invalid section ID format"))
    }

    @Test
    fun `test validate params - duplicate section IDs`() {
        val params = buildJsonObject {
            put("entityType", "TASK")
            put("entityId", entityId.toString())
            put("sectionOrder", "${section1.id},${section1.id},${section3.id}")
        }

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Duplicate section IDs"))
    }

    @Test
    fun `test execute - successfully reorder sections`() = runBlocking {
        // New order: section3, section1, section2
        val newOrder = listOf(section3.id, section1.id, section2.id)
        val params = buildJsonObject {
            put("entityType", "TASK")
            put("entityId", entityId.toString())
            put("sectionOrder", "${section3.id},${section1.id},${section2.id}")
        }

        val result = tool.execute(params, context)

        assertTrue(result is JsonObject)
        assertTrue(result["success"]?.jsonPrimitive?.boolean == true)

        val data = result["data"]?.jsonObject
        assertFalse(data == null)

        assertEquals("TASK", data["entityType"]?.jsonPrimitive?.content)
        assertEquals(entityId.toString(), data["entityId"]?.jsonPrimitive?.content)
        assertEquals(3, data["sectionCount"]?.jsonPrimitive?.int)

        // Verify the correct order was passed to the repository
        coVerify {
            mockSectionRepository.reorderSections(
                EntityType.TASK,
                entityId,
                match { ids -> ids == newOrder }
            )
        }
    }

    @Test
    fun `test execute - entity not found`() = runBlocking {
        val nonExistentId = UUID.randomUUID()
        val params = buildJsonObject {
            put("entityType", "TASK")
            put("entityId", nonExistentId.toString())
            put("sectionOrder", "${section3.id},${section1.id},${section2.id}")
        }

        // Mock not found response
        coEvery { mockSectionRepository.getSectionsForEntity(EntityType.TASK, nonExistentId) } returns
                Result.Error(RepositoryError.NotFound(nonExistentId, EntityType.TASK, "Task not found"))

        val result = tool.execute(params, context)

        assertTrue(result is JsonObject)
        assertFalse(result["success"]?.jsonPrimitive?.boolean == true)
        assertEquals(ErrorCodes.RESOURCE_NOT_FOUND, result["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content)
    }

    @Test
    fun `test execute - invalid section IDs`() = runBlocking {
        val invalidId = UUID.randomUUID()
        val params = buildJsonObject {
            put("entityType", "TASK")
            put("entityId", entityId.toString())
            put("sectionOrder", "${section3.id},${invalidId},${section2.id}")
        }

        val result = tool.execute(params, context)

        assertTrue(result is JsonObject)
        assertFalse(result["success"]?.jsonPrimitive?.boolean == true)
        assertEquals(ErrorCodes.VALIDATION_ERROR, result["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content)
        assertTrue(result["error"]?.jsonObject?.get("details")?.jsonPrimitive?.content?.contains("do not belong") == true)
    }

    @Test
    fun `test execute - missing section IDs`() = runBlocking {
        val params = buildJsonObject {
            put("entityType", "TASK")
            put("entityId", entityId.toString())
            put("sectionOrder", "${section1.id},${section2.id}") // Missing section3
        }

        val result = tool.execute(params, context)

        assertTrue(result is JsonObject)
        assertFalse(result["success"]?.jsonPrimitive?.boolean == true)
        assertEquals(ErrorCodes.VALIDATION_ERROR, result["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content)
        assertTrue(result["error"]?.jsonObject?.get("details")?.jsonPrimitive?.content?.contains("missing") == true)
    }

    @Test
    fun `test execute - reorder operation failed`() = runBlocking {
        val params = buildJsonObject {
            put("entityType", "TASK")
            put("entityId", entityId.toString())
            put("sectionOrder", "${section3.id},${section1.id},${section2.id}")
        }

        // Mock database error
        coEvery { mockSectionRepository.reorderSections(any(), any(), any()) } returns
                Result.Error(RepositoryError.DatabaseError("Database error during reorder"))

        val result = tool.execute(params, context)

        assertTrue(result is JsonObject)
        assertFalse(result["success"]?.jsonPrimitive?.boolean == true)
        assertEquals(ErrorCodes.DATABASE_ERROR, result["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content)
    }
}