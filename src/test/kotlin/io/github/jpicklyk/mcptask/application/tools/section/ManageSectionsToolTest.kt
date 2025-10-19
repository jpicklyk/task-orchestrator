package io.github.jpicklyk.mcptask.application.tools.section

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
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.*

class ManageSectionsToolTest {
    private lateinit var tool: ManageSectionsTool
    private lateinit var context: ToolExecutionContext
    private lateinit var mockSectionRepository: SectionRepository
    private lateinit var mockTaskRepository: TaskRepository
    private lateinit var mockFeatureRepository: FeatureRepository
    private lateinit var mockProjectRepository: ProjectRepository
    private lateinit var mockTemplateRepository: TemplateRepository
    private lateinit var mockRepositoryProvider: RepositoryProvider

    private val sectionId = UUID.randomUUID()
    private val taskId = UUID.randomUUID()
    private val featureId = UUID.randomUUID()

    private lateinit var mockSection: Section
    private lateinit var mockTask: Task

    @BeforeEach
    fun setup() {
        // Create mock repositories
        mockSectionRepository = mockk()
        mockTaskRepository = mockk()
        mockFeatureRepository = mockk()
        mockProjectRepository = mockk()
        mockTemplateRepository = mockk()
        mockRepositoryProvider = mockk()

        // Configure repository provider
        every { mockRepositoryProvider.sectionRepository() } returns mockSectionRepository
        every { mockRepositoryProvider.taskRepository() } returns mockTaskRepository
        every { mockRepositoryProvider.featureRepository() } returns mockFeatureRepository
        every { mockRepositoryProvider.projectRepository() } returns mockProjectRepository
        every { mockRepositoryProvider.templateRepository() } returns mockTemplateRepository

        // Create test entities
        mockSection = Section(
            id = sectionId,
            entityType = EntityType.TASK,
            entityId = taskId,
            title = "Test Section",
            usageDescription = "Test usage description",
            content = "Test content",
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 0,
            tags = listOf("test"),
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )

        mockTask = Task(
            id = taskId,
            title = "Test Task",
            status = TaskStatus.PENDING,
            priority = Priority.MEDIUM,
            complexity = 5
        )

        // Create execution context
        context = ToolExecutionContext(mockRepositoryProvider)

        // Create tool with null locking service to bypass locking in unit tests
        tool = ManageSectionsTool(null, null)
    }

    @Nested
    inner class ValidationTests {
        @Test
        fun `should require operation parameter`() {
            val params = buildJsonObject {}

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("operation"))
        }

        @Test
        fun `should reject invalid operation`() {
            val params = buildJsonObject {
                put("operation", "invalid")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("Invalid operation"))
        }

        @Test
        fun `should require id for update operation`() {
            val params = buildJsonObject {
                put("operation", "update")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("id"))
        }

        @Test
        fun `should require entityType for add operation`() {
            val params = buildJsonObject {
                put("operation", "add")
                put("entityId", taskId.toString())
                put("title", "New Section")
                put("usageDescription", "Usage")
                put("content", "Content")
                put("ordinal", 0)
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("entityType"))
        }

        @Test
        fun `should require oldText and newText for updateText operation`() {
            val params = buildJsonObject {
                put("operation", "updateText")
                put("id", sectionId.toString())
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("oldText") || exception.message!!.contains("newText"))
        }

        @Test
        fun `should require sections array for bulkCreate operation`() {
            val params = buildJsonObject {
                put("operation", "bulkCreate")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("sections"))
        }

        @Test
        fun `should require ids array for bulkDelete operation`() {
            val params = buildJsonObject {
                put("operation", "bulkDelete")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("ids"))
        }

        @Test
        fun `should accept valid add params`() {
            val params = buildJsonObject {
                put("operation", "add")
                put("entityType", "TASK")
                put("entityId", taskId.toString())
                put("title", "New Section")
                put("usageDescription", "Usage description")
                put("content", "Section content")
                put("ordinal", 0)
            }

            assertDoesNotThrow {
                tool.validateParams(params)
            }
        }
    }

    @Nested
    inner class AddOperationTests {
        @Test
        fun `should add section successfully`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "add")
                put("entityType", "TASK")
                put("entityId", taskId.toString())
                put("title", "New Section")
                put("usageDescription", "Usage description")
                put("content", "Section content")
                put("contentFormat", "MARKDOWN")
                put("ordinal", 0)
                put("tags", "test,section")
            }

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)
            coEvery { mockSectionRepository.addSection(any(), any(), any()) } returns Result.Success(mockSection)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("added") == true)
            assertEquals(sectionId.toString(), resultObj["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content)
        }

        @Test
        fun `should fail add when entity not found`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "add")
                put("entityType", "TASK")
                put("entityId", taskId.toString())
                put("title", "New Section")
                put("usageDescription", "Usage description")
                put("content", "Section content")
                put("ordinal", 0)
            }

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Error(RepositoryError.NotFound(taskId, EntityType.TASK, "Task not found"))

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertFalse(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("not found") == true)
        }
    }

    @Nested
    inner class UpdateOperationTests {
        @Test
        fun `should update section successfully`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "update")
                put("id", sectionId.toString())
                put("title", "Updated Title")
                put("content", "Updated content")
            }

            coEvery { mockSectionRepository.getSection(sectionId) } returns Result.Success(mockSection)
            coEvery { mockSectionRepository.updateSection(any()) } returns Result.Success(mockSection.copy(title = "Updated Title"))

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("updated") == true)
        }

        @Test
        fun `should fail update when section not found`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "update")
                put("id", sectionId.toString())
                put("title", "Updated Title")
            }

            coEvery { mockSectionRepository.getSection(sectionId) } returns Result.Error(RepositoryError.NotFound(sectionId, EntityType.SECTION, "Section not found"))

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertFalse(resultObj["success"]?.jsonPrimitive?.boolean == true)
        }
    }

    @Nested
    inner class UpdateTextOperationTests {
        @Test
        fun `should update text successfully`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "updateText")
                put("id", sectionId.toString())
                put("oldText", "Test content")
                put("newText", "Updated content")
            }

            coEvery { mockSectionRepository.getSection(sectionId) } returns Result.Success(mockSection)
            coEvery { mockSectionRepository.updateSection(any()) } returns Result.Success(
                mockSection.copy(content = "Updated content")
            )

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("text updated") == true)
        }

        @Test
        fun `should fail when old text not found`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "updateText")
                put("id", sectionId.toString())
                put("oldText", "Nonexistent text")
                put("newText", "Updated content")
            }

            coEvery { mockSectionRepository.getSection(sectionId) } returns Result.Success(mockSection)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertFalse(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("not found") == true)
        }
    }

    @Nested
    inner class UpdateMetadataOperationTests {
        @Test
        fun `should update metadata successfully`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "updateMetadata")
                put("id", sectionId.toString())
                put("title", "Updated Title")
                put("ordinal", 5)
                put("tags", "updated,tags")
            }

            coEvery { mockSectionRepository.getSection(sectionId) } returns Result.Success(mockSection)
            coEvery { mockSectionRepository.updateSection(any()) } returns Result.Success(
                mockSection.copy(title = "Updated Title", ordinal = 5)
            )

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("metadata updated") == true)
        }

        @Test
        fun `should reject empty title`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "updateMetadata")
                put("id", sectionId.toString())
                put("title", "")
            }

            coEvery { mockSectionRepository.getSection(sectionId) } returns Result.Success(mockSection)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertFalse(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("cannot be empty") == true)
        }
    }

    @Nested
    inner class DeleteOperationTests {
        @Test
        fun `should delete section successfully`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "delete")
                put("id", sectionId.toString())
            }

            coEvery { mockSectionRepository.getSection(sectionId) } returns Result.Success(mockSection)
            coEvery { mockSectionRepository.deleteSection(sectionId) } returns Result.Success(true)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("deleted") == true)
            assertTrue(resultObj["data"]?.jsonObject?.get("deleted")?.jsonPrimitive?.boolean == true)
        }
    }

    @Nested
    inner class ReorderOperationTests {
        @Test
        fun `should reorder sections successfully`() = runBlocking {
            val section1 = mockSection
            val section2 = mockSection.copy(id = UUID.randomUUID(), ordinal = 1)
            val sectionOrder = "${section2.id},${section1.id}"

            val params = buildJsonObject {
                put("operation", "reorder")
                put("entityType", "TASK")
                put("entityId", taskId.toString())
                put("sectionOrder", sectionOrder)
            }

            coEvery { mockSectionRepository.getSectionsForEntity(EntityType.TASK, taskId) } returns
                Result.Success(listOf(section1, section2))
            coEvery { mockSectionRepository.reorderSections(any(), any(), any()) } returns Result.Success(true)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("reordered") == true)
        }

        @Test
        fun `should fail reorder when missing sections`() = runBlocking {
            val section1 = mockSection
            val section2 = mockSection.copy(id = UUID.randomUUID(), ordinal = 1)
            val sectionOrder = "${section2.id}" // Missing section1

            val params = buildJsonObject {
                put("operation", "reorder")
                put("entityType", "TASK")
                put("entityId", taskId.toString())
                put("sectionOrder", sectionOrder)
            }

            coEvery { mockSectionRepository.getSectionsForEntity(EntityType.TASK, taskId) } returns
                Result.Success(listOf(section1, section2))

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertFalse(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("Missing") == true)
        }
    }

    @Nested
    inner class BulkCreateOperationTests {
        @Test
        fun `should bulk create sections successfully`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "bulkCreate")
                put("sections", buildJsonArray {
                    add(buildJsonObject {
                        put("entityType", "TASK")
                        put("entityId", taskId.toString())
                        put("title", "Section 1")
                        put("usageDescription", "Usage 1")
                        put("content", "Content 1")
                        put("ordinal", 0)
                    })
                    add(buildJsonObject {
                        put("entityType", "TASK")
                        put("entityId", taskId.toString())
                        put("title", "Section 2")
                        put("usageDescription", "Usage 2")
                        put("content", "Content 2")
                        put("ordinal", 1)
                    })
                })
            }

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)
            coEvery { mockSectionRepository.addSection(any(), any(), any()) } returns Result.Success(mockSection)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertEquals(2, resultObj["data"]?.jsonObject?.get("count")?.jsonPrimitive?.int)
            assertEquals(0, resultObj["data"]?.jsonObject?.get("failed")?.jsonPrimitive?.int)
        }

        @Test
        fun `should handle partial failures in bulk create`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "bulkCreate")
                put("sections", buildJsonArray {
                    add(buildJsonObject {
                        put("entityType", "TASK")
                        put("entityId", taskId.toString())
                        put("title", "Section 1")
                        put("usageDescription", "Usage 1")
                        put("content", "Content 1")
                        put("ordinal", 0)
                    })
                    add(buildJsonObject {
                        put("entityType", "TASK")
                        put("entityId", UUID.randomUUID().toString()) // Non-existent task
                        put("title", "Section 2")
                        put("usageDescription", "Usage 2")
                        put("content", "Content 2")
                        put("ordinal", 1)
                    })
                })
            }

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)
            coEvery { mockTaskRepository.getById(not(taskId)) } returns Result.Error(RepositoryError.NotFound(UUID.randomUUID(), EntityType.TASK, "Task not found"))
            coEvery { mockSectionRepository.addSection(any(), any(), any()) } returns Result.Success(mockSection)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertEquals(1, resultObj["data"]?.jsonObject?.get("count")?.jsonPrimitive?.int)
            assertEquals(1, resultObj["data"]?.jsonObject?.get("failed")?.jsonPrimitive?.int)
        }
    }

    @Nested
    inner class BulkUpdateOperationTests {
        @Test
        fun `should bulk update sections successfully`() = runBlocking {
            val section2 = mockSection.copy(id = UUID.randomUUID())

            val params = buildJsonObject {
                put("operation", "bulkUpdate")
                put("sections", buildJsonArray {
                    add(buildJsonObject {
                        put("id", sectionId.toString())
                        put("title", "Updated Section 1")
                    })
                    add(buildJsonObject {
                        put("id", section2.id.toString())
                        put("title", "Updated Section 2")
                    })
                })
            }

            coEvery { mockSectionRepository.getSection(sectionId) } returns Result.Success(mockSection)
            coEvery { mockSectionRepository.getSection(section2.id) } returns Result.Success(section2)
            coEvery { mockSectionRepository.updateSection(any()) } returns Result.Success(mockSection)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertEquals(2, resultObj["data"]?.jsonObject?.get("count")?.jsonPrimitive?.int)
            assertEquals(0, resultObj["data"]?.jsonObject?.get("failed")?.jsonPrimitive?.int)
        }
    }

    @Nested
    inner class BulkDeleteOperationTests {
        @Test
        fun `should bulk delete sections successfully`() = runBlocking {
            val section2Id = UUID.randomUUID()

            val params = buildJsonObject {
                put("operation", "bulkDelete")
                put("ids", buildJsonArray {
                    add(sectionId.toString())
                    add(section2Id.toString())
                })
            }

            coEvery { mockSectionRepository.deleteSection(any()) } returns Result.Success(true)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertEquals(2, resultObj["data"]?.jsonObject?.get("count")?.jsonPrimitive?.int)
            assertEquals(0, resultObj["data"]?.jsonObject?.get("failed")?.jsonPrimitive?.int)
        }

        @Test
        fun `should handle partial failures in bulk delete`() = runBlocking {
            val section2Id = UUID.randomUUID()

            val params = buildJsonObject {
                put("operation", "bulkDelete")
                put("ids", buildJsonArray {
                    add(sectionId.toString())
                    add(section2Id.toString())
                })
            }

            coEvery { mockSectionRepository.deleteSection(sectionId) } returns Result.Success(true)
            coEvery { mockSectionRepository.deleteSection(section2Id) } returns
                Result.Error(RepositoryError.NotFound(section2Id, EntityType.SECTION, "Section not found"))

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertEquals(1, resultObj["data"]?.jsonObject?.get("count")?.jsonPrimitive?.int)
            assertEquals(1, resultObj["data"]?.jsonObject?.get("failed")?.jsonPrimitive?.int)
        }
    }
}
