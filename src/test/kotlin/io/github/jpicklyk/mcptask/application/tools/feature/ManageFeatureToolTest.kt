package io.github.jpicklyk.mcptask.application.tools.feature

import io.github.jpicklyk.mcptask.application.service.SimpleLockingService
import io.github.jpicklyk.mcptask.application.service.SimpleSessionManager
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

class ManageFeatureToolTest {
    private lateinit var tool: ManageFeatureTool
    private lateinit var context: ToolExecutionContext
    private lateinit var mockFeatureRepository: FeatureRepository
    private lateinit var mockProjectRepository: ProjectRepository
    private lateinit var mockTaskRepository: TaskRepository
    private lateinit var mockSectionRepository: SectionRepository
    private lateinit var mockTemplateRepository: TemplateRepository
    private lateinit var mockRepositoryProvider: RepositoryProvider
    private lateinit var mockLockingService: SimpleLockingService
    private lateinit var mockSessionManager: SimpleSessionManager

    private val featureId = UUID.randomUUID()
    private val projectId = UUID.randomUUID()
    private val templateId1 = UUID.randomUUID()
    private val templateId2 = UUID.randomUUID()

    private lateinit var mockFeature: Feature
    private lateinit var mockProject: Project
    private lateinit var mockSection: Section
    private lateinit var mockTemplateSection: TemplateSection

    @BeforeEach
    fun setup() {
        // Create mock repositories
        mockFeatureRepository = mockk()
        mockProjectRepository = mockk()
        mockTaskRepository = mockk()
        mockSectionRepository = mockk()
        mockTemplateRepository = mockk()
        mockRepositoryProvider = mockk()
        mockLockingService = mockk(relaxed = true)
        mockSessionManager = mockk(relaxed = true)

        // Configure repository provider
        every { mockRepositoryProvider.featureRepository() } returns mockFeatureRepository
        every { mockRepositoryProvider.projectRepository() } returns mockProjectRepository
        every { mockRepositoryProvider.taskRepository() } returns mockTaskRepository
        every { mockRepositoryProvider.sectionRepository() } returns mockSectionRepository
        every { mockRepositoryProvider.templateRepository() } returns mockTemplateRepository
        every { mockRepositoryProvider.dependencyRepository() } returns mockk(relaxed = true)

        // Create test entities
        mockFeature = Feature(
            id = featureId,
            name = "Test Feature",
            description = "Test description",
            summary = "Test summary",
            status = FeatureStatus.PLANNING,
            priority = Priority.HIGH,
            tags = listOf("test", "kotlin"),
            projectId = projectId,
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )

        mockProject = Project(
            id = projectId,
            name = "Test Project",
            summary = "Test project summary",
            status = ProjectStatus.IN_DEVELOPMENT
        )

        mockSection = Section(
            id = UUID.randomUUID(),
            entityType = EntityType.FEATURE,
            entityId = featureId,
            title = "Test Section",
            usageDescription = "Test usage",
            content = "Test content",
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 0
        )

        mockTemplateSection = TemplateSection(
            id = UUID.randomUUID(),
            templateId = templateId1,
            title = "Test Template Section",
            usageDescription = "Test usage",
            contentSample = "Test content",
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 0
        )

        // Create execution context
        context = ToolExecutionContext(mockRepositoryProvider)

        // Create tool with null locking service to bypass locking in unit tests
        tool = ManageFeatureTool(null, null)
    }

    @Nested
    inner class OperationRoutingTests {
        @Test
        fun `should route to create when operation is create`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "create")
                put("name", "New Feature")
            }

            coEvery { mockFeatureRepository.create(any()) } returns Result.Success(mockFeature)
            coEvery { mockTemplateRepository.applyMultipleTemplates(any(), any(), any()) } returns Result.Success(emptyMap<UUID, List<TemplateSection>>())

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("created") == true)
        }

        @Test
        fun `should route to get when operation is get`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "get")
                put("id", featureId.toString())
            }

            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(mockFeature)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("retrieved") == true)
        }

        @Test
        fun `should route to update when operation is update`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "update")
                put("id", featureId.toString())
                put("name", "Updated Feature")
            }

            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(mockFeature)
            coEvery { mockFeatureRepository.update(any()) } returns Result.Success(mockFeature.copy(name = "Updated Feature"))

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("updated") == true)
        }

        @Test
        fun `should route to delete when operation is delete`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "delete")
                put("id", featureId.toString())
            }

            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(mockFeature)
            coEvery { mockTaskRepository.findByFeature(featureId, limit = 1000) } returns Result.Success(emptyList())
            coEvery { mockSectionRepository.getSectionsForEntity(EntityType.FEATURE, featureId) } returns Result.Success(emptyList())
            coEvery { mockFeatureRepository.delete(featureId) } returns Result.Success(true)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("deleted") == true)
        }

        @Test
        fun `should route to export when operation is export`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "export")
                put("id", featureId.toString())
                put("format", "markdown")
            }

            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(mockFeature)
            coEvery { mockSectionRepository.getSectionsForEntity(EntityType.FEATURE, featureId) } returns Result.Success(emptyList())

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
        }
    }

    @Nested
    inner class ValidationTests {
        @Test
        fun `should reject invalid operation`() {
            val params = buildJsonObject {
                put("operation", "invalid")
            }

            assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
        }

        @Test
        fun `should require id for get operation`() {
            val params = buildJsonObject {
                put("operation", "get")
            }

            assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
        }

        @Test
        fun `should require id for update operation`() {
            val params = buildJsonObject {
                put("operation", "update")
            }

            assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
        }

        @Test
        fun `should require id for delete operation`() {
            val params = buildJsonObject {
                put("operation", "delete")
            }

            assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
        }

        @Test
        fun `should require name for create operation`() {
            val params = buildJsonObject {
                put("operation", "create")
            }

            assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
        }

        @Test
        fun `should reject blank name for create operation`() {
            val params = buildJsonObject {
                put("operation", "create")
                put("name", "   ")
            }

            assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
        }

        @Test
        fun `should reject invalid UUID for id`() {
            val params = buildJsonObject {
                put("operation", "get")
                put("id", "not-a-uuid")
            }

            assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
        }

        @Test
        fun `should reject invalid status`() {
            val params = buildJsonObject {
                put("operation", "create")
                put("name", "Test Feature")
                put("status", "invalid-status")
            }

            assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
        }

        @Test
        fun `should reject invalid priority`() {
            val params = buildJsonObject {
                put("operation", "create")
                put("name", "Test Feature")
                put("priority", "invalid-priority")
            }

            assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
        }

        @Test
        fun `should accept valid status values`() {
            val validStatuses = listOf("planning", "in-development", "completed", "archived")
            validStatuses.forEach { status ->
                val params = buildJsonObject {
                    put("operation", "create")
                    put("name", "Test Feature")
                    put("status", status)
                }
                assertDoesNotThrow { tool.validateParams(params) }
            }
        }

        @Test
        fun `should accept valid priority values`() {
            val validPriorities = listOf("high", "medium", "low")
            validPriorities.forEach { priority ->
                val params = buildJsonObject {
                    put("operation", "create")
                    put("name", "Test Feature")
                    put("priority", priority)
                }
                assertDoesNotThrow { tool.validateParams(params) }
            }
        }
    }

    @Nested
    inner class CreateOperationTests {
        @Test
        fun `should create feature with minimal parameters`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "create")
                put("name", "Test Feature")
            }

            coEvery { mockFeatureRepository.create(any()) } returns Result.Success(mockFeature)
            coEvery { mockTemplateRepository.applyMultipleTemplates(any(), any(), any()) } returns Result.Success(emptyMap<UUID, List<TemplateSection>>())

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            val data = resultObj["data"]?.jsonObject
            assertNotNull(data)
            assertEquals("Test Feature", data?.get("name")?.jsonPrimitive?.content)
        }

        @Test
        fun `should create feature with all parameters`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "create")
                put("name", "Full Feature")
                put("description", "Full description")
                put("summary", "Full summary")
                put("status", "in-development")
                put("priority", "high")
                put("projectId", projectId.toString())
                put("tags", "tag1,tag2,tag3")
            }

            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(mockProject)
            coEvery { mockFeatureRepository.create(any()) } returns Result.Success(mockFeature)
            coEvery { mockTemplateRepository.applyMultipleTemplates(any(), any(), any()) } returns Result.Success(emptyMap<UUID, List<TemplateSection>>())

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
        }

        @Test
        fun `should apply templates when creating feature`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "create")
                put("name", "Feature with Templates")
                put("templateIds", buildJsonArray {
                    add(templateId1.toString())
                    add(templateId2.toString())
                })
            }

            val templateResults = mapOf(
                templateId1 to listOf(mockTemplateSection),
                templateId2 to listOf(mockTemplateSection)
            )

            coEvery { mockFeatureRepository.create(any()) } returns Result.Success(mockFeature)
            coEvery { mockTemplateRepository.applyMultipleTemplates(any(), EntityType.FEATURE, any()) } returns Result.Success(templateResults)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("template") == true)

            val data = resultObj["data"]?.jsonObject
            val appliedTemplates = data?.get("appliedTemplates")?.jsonArray
            assertNotNull(appliedTemplates)
            assertEquals(2, appliedTemplates?.size)
        }

        @Test
        fun `should fail when project does not exist`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "create")
                put("name", "Feature")
                put("projectId", projectId.toString())
            }

            coEvery { mockProjectRepository.getById(projectId) } returns Result.Error(RepositoryError.NotFound(projectId, EntityType.PROJECT, "Project not found"))

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertFalse(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("not found") == true)
        }
    }

    @Nested
    inner class GetOperationTests {
        @Test
        fun `should get feature by id`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "get")
                put("id", featureId.toString())
            }

            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(mockFeature)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            val data = resultObj["data"]?.jsonObject
            assertEquals(featureId.toString(), data?.get("id")?.jsonPrimitive?.content)
            assertEquals("Test Feature", data?.get("name")?.jsonPrimitive?.content)
        }

        @Test
        fun `should include sections when requested`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "get")
                put("id", featureId.toString())
                put("includeSections", true)
            }

            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(mockFeature)
            coEvery { mockSectionRepository.getSectionsForEntity(EntityType.FEATURE, featureId) } returns Result.Success(listOf(mockSection))

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            val data = resultObj["data"]?.jsonObject
            val sections = data?.get("sections")?.jsonArray
            assertNotNull(sections)
            assertEquals(1, sections?.size)
        }

        @Test
        fun `should include project when requested`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "get")
                put("id", featureId.toString())
                put("includeProject", true)
            }

            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(mockFeature)
            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(mockProject)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            val data = resultObj["data"]?.jsonObject
            val project = data?.get("project")?.jsonObject
            assertNotNull(project)
            assertEquals("Test Project", project?.get("name")?.jsonPrimitive?.content)
        }

        @Test
        fun `should truncate summary in summary view mode`() = runBlocking {
            val longSummary = "a".repeat(200)
            val featureWithLongSummary = mockFeature.copy(summary = longSummary)

            val params = buildJsonObject {
                put("operation", "get")
                put("id", featureId.toString())
                put("summaryView", true)
            }

            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(featureWithLongSummary)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            val data = resultObj["data"]?.jsonObject
            val summary = data?.get("summary")?.jsonPrimitive?.content
            assertNotNull(summary)
            assertTrue(summary!!.length <= 100)
            assertTrue(summary.endsWith("..."))
        }

        @Test
        fun `should fail when feature not found`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "get")
                put("id", featureId.toString())
            }

            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Error(RepositoryError.NotFound(featureId, EntityType.FEATURE, "Feature not found"))

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertFalse(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("not found") == true)
        }
    }

    @Nested
    inner class UpdateOperationTests {
        @Test
        fun `should update feature name`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "update")
                put("id", featureId.toString())
                put("name", "Updated Feature")
            }

            val updatedFeature = mockFeature.copy(name = "Updated Feature")

            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(mockFeature)
            coEvery { mockFeatureRepository.update(any()) } returns Result.Success(updatedFeature)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("updated") == true)
        }

        @Test
        fun `should update feature status`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "update")
                put("id", featureId.toString())
                put("status", "completed")
            }

            val updatedFeature = mockFeature.copy(status = FeatureStatus.COMPLETED)

            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(mockFeature)
            coEvery { mockFeatureRepository.update(any()) } returns Result.Success(updatedFeature)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            val data = resultObj["data"]?.jsonObject
            assertEquals("completed", data?.get("status")?.jsonPrimitive?.content)
        }

        @Test
        fun `should fail when feature not found for update`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "update")
                put("id", featureId.toString())
                put("name", "Updated")
            }

            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Error(RepositoryError.NotFound(featureId, EntityType.FEATURE, "Feature not found"))

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertFalse(resultObj["success"]?.jsonPrimitive?.boolean == true)
        }
    }

    @Nested
    inner class DeleteOperationTests {
        @Test
        fun `should delete feature without tasks`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "delete")
                put("id", featureId.toString())
            }

            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(mockFeature)
            coEvery { mockTaskRepository.findByFeature(featureId, limit = 1000) } returns Result.Success(emptyList())
            coEvery { mockSectionRepository.getSectionsForEntity(EntityType.FEATURE, featureId) } returns Result.Success(emptyList())
            coEvery { mockFeatureRepository.delete(featureId) } returns Result.Success(true)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            val data = resultObj["data"]?.jsonObject
            assertEquals(true, data?.get("deleted")?.jsonPrimitive?.boolean)
        }

        @Test
        fun `should fail to delete feature with tasks when force is false`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "delete")
                put("id", featureId.toString())
            }

            val mockTask = Task(
                id = UUID.randomUUID(),
                title = "Test Task",
                summary = "Test",
                featureId = featureId
            )

            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(mockFeature)
            coEvery { mockTaskRepository.findByFeature(featureId, limit = 1000) } returns Result.Success(listOf(mockTask))

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertFalse(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("tasks") == true)
        }

        @Test
        fun `should delete feature with tasks when force is true`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "delete")
                put("id", featureId.toString())
                put("force", true)
            }

            val mockTask = Task(
                id = UUID.randomUUID(),
                title = "Test Task",
                summary = "Test",
                featureId = featureId
            )

            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(mockFeature)
            coEvery { mockTaskRepository.findByFeature(featureId, limit = 1000) } returns Result.Success(listOf(mockTask))
            coEvery { mockTaskRepository.delete(any()) } returns Result.Success(true)
            coEvery { mockSectionRepository.getSectionsForEntity(EntityType.FEATURE, featureId) } returns Result.Success(emptyList())
            coEvery { mockFeatureRepository.delete(featureId) } returns Result.Success(true)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            val data = resultObj["data"]?.jsonObject
            assertEquals(1, data?.get("tasksDeleted")?.jsonPrimitive?.int)
        }

        @Test
        fun `should delete sections when deleteSections is true`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "delete")
                put("id", featureId.toString())
                put("deleteSections", true)
            }

            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(mockFeature)
            coEvery { mockTaskRepository.findByFeature(featureId, limit = 1000) } returns Result.Success(emptyList())
            coEvery { mockSectionRepository.getSectionsForEntity(EntityType.FEATURE, featureId) } returns Result.Success(listOf(mockSection))
            coEvery { mockSectionRepository.deleteSection(any()) } returns Result.Success(true)
            coEvery { mockFeatureRepository.delete(featureId) } returns Result.Success(true)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            val data = resultObj["data"]?.jsonObject
            assertEquals(1, data?.get("sectionsDeleted")?.jsonPrimitive?.int)
        }
    }

    @Nested
    inner class ExportOperationTests {
        @Test
        fun `should export feature as markdown`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "export")
                put("id", featureId.toString())
                put("format", "markdown")
            }

            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(mockFeature)
            coEvery { mockSectionRepository.getSectionsForEntity(EntityType.FEATURE, featureId) } returns Result.Success(listOf(mockSection))

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            val data = resultObj["data"]?.jsonObject
            assertNotNull(data?.get("markdown"))
        }

        @Test
        fun `should export feature as json`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "export")
                put("id", featureId.toString())
                put("format", "json")
            }

            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(mockFeature)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            val data = resultObj["data"]?.jsonObject
            assertEquals(featureId.toString(), data?.get("id")?.jsonPrimitive?.content)
        }

        @Test
        fun `should reject invalid export format`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "export")
                put("id", featureId.toString())
                put("format", "invalid")
            }

            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(mockFeature)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertFalse(resultObj["success"]?.jsonPrimitive?.boolean == true)
        }
    }
}
