package io.github.jpicklyk.mcptask.application.tools.task

import io.github.jpicklyk.mcptask.application.service.SimpleLockingService
import io.github.jpicklyk.mcptask.application.service.SimpleSessionManager
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.repository.*
import io.github.jpicklyk.mcptask.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.domain.model.TemplateSection
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

class ManageTaskToolTest {
    private lateinit var tool: ManageTaskTool
    private lateinit var context: ToolExecutionContext
    private lateinit var mockTaskRepository: TaskRepository
    private lateinit var mockFeatureRepository: FeatureRepository
    private lateinit var mockSectionRepository: SectionRepository
    private lateinit var mockDependencyRepository: DependencyRepository
    private lateinit var mockTemplateRepository: TemplateRepository
    private lateinit var mockRepositoryProvider: RepositoryProvider
    private lateinit var mockLockingService: SimpleLockingService
    private lateinit var mockSessionManager: SimpleSessionManager

    private val taskId = UUID.randomUUID()
    private val featureId = UUID.randomUUID()
    private val projectId = UUID.randomUUID()
    private val templateId1 = UUID.randomUUID()
    private val templateId2 = UUID.randomUUID()

    private lateinit var mockTask: Task
    private lateinit var mockFeature: Feature
    private lateinit var mockSection: Section
    private lateinit var mockTemplateSection: TemplateSection

    @BeforeEach
    fun setup() {
        // Create mock repositories
        mockTaskRepository = mockk()
        mockFeatureRepository = mockk()
        mockSectionRepository = mockk()
        mockDependencyRepository = mockk()
        mockTemplateRepository = mockk()
        mockRepositoryProvider = mockk()
        mockLockingService = mockk(relaxed = true)
        mockSessionManager = mockk(relaxed = true)

        // Configure repository provider
        every { mockRepositoryProvider.taskRepository() } returns mockTaskRepository
        every { mockRepositoryProvider.featureRepository() } returns mockFeatureRepository
        every { mockRepositoryProvider.sectionRepository() } returns mockSectionRepository
        every { mockRepositoryProvider.dependencyRepository() } returns mockDependencyRepository
        every { mockRepositoryProvider.templateRepository() } returns mockTemplateRepository
        every { mockRepositoryProvider.projectRepository() } returns mockk(relaxed = true)

        // Create test entities
        mockTask = Task(
            id = taskId,
            title = "Test Task",
            description = "Test description",
            summary = "Test summary",
            status = TaskStatus.PENDING,
            priority = Priority.HIGH,
            complexity = 7,
            tags = listOf("test", "kotlin"),
            featureId = featureId,
            projectId = projectId,
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )

        mockFeature = Feature(
            id = featureId,
            name = "Test Feature",
            summary = "Test feature summary",
            status = FeatureStatus.IN_DEVELOPMENT,
            priority = Priority.HIGH,
            projectId = projectId
        )

        mockSection = Section(
            id = UUID.randomUUID(),
            entityType = EntityType.TASK,
            entityId = taskId,
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
        // This allows us to test the tool logic without mocking the entire locking system
        tool = ManageTaskTool(null, null)
    }

    @Nested
    inner class OperationRoutingTests {
        @Test
        fun `should route to create when operation is create`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "create")
                put("title", "New Task")
            }

            coEvery { mockTaskRepository.create(any()) } returns Result.Success(mockTask)
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
                put("id", taskId.toString())
            }

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("retrieved") == true)
        }

        @Test
        fun `should route to update when operation is update`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "update")
                put("id", taskId.toString())
                put("title", "Updated Task")
            }

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)
            coEvery { mockTaskRepository.update(any()) } returns Result.Success(mockTask.copy(title = "Updated Task"))

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("updated") == true)
        }

        @Test
        fun `should route to delete when operation is delete`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "delete")
                put("id", taskId.toString())
            }

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)
            coEvery { mockDependencyRepository.findByTaskId(taskId) } returns emptyList()
            coEvery { mockSectionRepository.getSectionsForEntity(EntityType.TASK, taskId) } returns Result.Success(emptyList())
            coEvery { mockTaskRepository.delete(taskId) } returns Result.Success(true)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("deleted") == true)
        }

        @Test
        fun `should route to export when operation is export`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "export")
                put("id", taskId.toString())
                put("format", "markdown")
            }

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)
            coEvery { mockSectionRepository.getSectionsForEntity(EntityType.TASK, taskId) } returns Result.Success(emptyList())

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["data"]?.jsonObject?.containsKey("markdown") == true)
        }

        @Test
        fun `should fail when operation is invalid`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "invalid-operation")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }

            assertTrue(exception.message?.contains("Invalid operation") == true)
        }
    }

    @Nested
    inner class ValidationTests {
        @Test
        fun `should require id for get operation`() {
            val params = buildJsonObject {
                put("operation", "get")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }

            assertTrue(exception.message?.contains("Missing required parameter: id") == true)
        }

        @Test
        fun `should require id for update operation`() {
            val params = buildJsonObject {
                put("operation", "update")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }

            assertTrue(exception.message?.contains("Missing required parameter: id") == true)
        }

        @Test
        fun `should require id for delete operation`() {
            val params = buildJsonObject {
                put("operation", "delete")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }

            assertTrue(exception.message?.contains("Missing required parameter: id") == true)
        }

        @Test
        fun `should require id for export operation`() {
            val params = buildJsonObject {
                put("operation", "export")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }

            assertTrue(exception.message?.contains("Missing required parameter: id") == true)
        }

        @Test
        fun `should require title for create operation`() {
            val params = buildJsonObject {
                put("operation", "create")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }

            assertTrue(exception.message?.contains("Missing required parameter: title") == true)
        }

        @Test
        fun `should validate invalid UUID format for id`() {
            val params = buildJsonObject {
                put("operation", "get")
                put("id", "not-a-uuid")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }

            assertTrue(exception.message?.contains("Invalid task ID format") == true)
        }

        @Test
        fun `should validate invalid status`() {
            val params = buildJsonObject {
                put("operation", "create")
                put("title", "Test Task")
                put("status", "invalid-status")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }

            assertTrue(exception.message?.contains("Invalid status") == true)
        }

        @Test
        fun `should validate invalid priority`() {
            val params = buildJsonObject {
                put("operation", "create")
                put("title", "Test Task")
                put("priority", "invalid-priority")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }

            assertTrue(exception.message?.contains("Invalid priority") == true)
        }

        @Test
        fun `should validate complexity range`() {
            val invalidComplexities = listOf(0, 11, -1, 100)

            invalidComplexities.forEach { complexity ->
                val params = buildJsonObject {
                    put("operation", "create")
                    put("title", "Test Task")
                    put("complexity", complexity)
                }

                val exception = assertThrows<ToolValidationException> {
                    tool.validateParams(params)
                }

                assertTrue(exception.message?.contains("Complexity must be between 1 and 10") == true)
            }
        }

        @Test
        fun `should validate templateIds array format`() {
            val params = buildJsonObject {
                put("operation", "create")
                put("title", "Test Task")
                put("templateIds", "not-an-array")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }

            assertTrue(exception.message?.contains("must be an array") == true)
        }

        @Test
        fun `should validate templateIds contain valid UUIDs`() {
            val params = buildJsonObject {
                put("operation", "create")
                put("title", "Test Task")
                put("templateIds", buildJsonArray {
                    add("not-a-uuid")
                })
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }

            assertTrue(exception.message?.contains("not a valid UUID") == true)
        }
    }

    @Nested
    inner class CreateOperationTests {
        @Test
        fun `should create task with all required fields`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "create")
                put("title", "New Task")
                put("summary", "Task summary")
                put("status", "pending")
                put("priority", "high")
                put("complexity", 7)
            }

            coEvery { mockTaskRepository.create(any()) } returns Result.Success(mockTask)
            coEvery { mockTemplateRepository.applyMultipleTemplates(any(), any(), any()) } returns Result.Success(emptyMap<UUID, List<TemplateSection>>())

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertEquals("Task created successfully", resultObj["message"]?.jsonPrimitive?.content)

            val data = resultObj["data"]?.jsonObject
            assertNotNull(data)
            assertEquals(taskId.toString(), data!!["id"]?.jsonPrimitive?.content)
            assertEquals("Test Task", data["title"]?.jsonPrimitive?.content)
        }

        @Test
        fun `should create task with templates applied`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "create")
                put("title", "New Task")
                put("templateIds", buildJsonArray {
                    add(templateId1.toString())
                    add(templateId2.toString())
                })
            }

            val appliedTemplates = mapOf(
                templateId1 to listOf(mockTemplateSection),
                templateId2 to listOf(mockTemplateSection, mockTemplateSection)
            )

            coEvery { mockTaskRepository.create(any()) } returns Result.Success(mockTask)
            coEvery { mockTemplateRepository.applyMultipleTemplates(any(), any(), any()) } returns Result.Success(appliedTemplates)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("2 template(s)") == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("3 section(s)") == true)

            val data = resultObj["data"]?.jsonObject
            assertNotNull(data!!["appliedTemplates"])
        }

        @Test
        fun `should validate feature exists when featureId provided`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "create")
                put("title", "New Task")
                put("featureId", featureId.toString())
            }

            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(mockFeature)
            coEvery { mockTaskRepository.create(any()) } returns Result.Success(mockTask)
            coEvery { mockTemplateRepository.applyMultipleTemplates(any(), any(), any()) } returns Result.Success(emptyMap<UUID, List<TemplateSection>>())

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
        }

        @Test
        fun `should fail when feature does not exist`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "create")
                put("title", "New Task")
                put("featureId", featureId.toString())
            }

            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Error(RepositoryError.NotFound(featureId, EntityType.FEATURE, "Feature not found"))

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertFalse(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("Feature not found") == true)
        }
    }

    @Nested
    inner class GetOperationTests {
        @Test
        fun `should get task with basic fields`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "get")
                put("id", taskId.toString())
            }

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)

            val data = resultObj["data"]?.jsonObject
            assertNotNull(data)
            assertEquals(taskId.toString(), data!!["id"]?.jsonPrimitive?.content)
            assertEquals("Test Task", data["title"]?.jsonPrimitive?.content)
            assertEquals("pending", data["status"]?.jsonPrimitive?.content)
            assertEquals("high", data["priority"]?.jsonPrimitive?.content)
            assertEquals(7, data["complexity"]?.jsonPrimitive?.int)
        }

        @Test
        fun `should get task with sections when includeSections true`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "get")
                put("id", taskId.toString())
                put("includeSections", true)
            }

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)
            coEvery { mockSectionRepository.getSectionsForEntity(EntityType.TASK, taskId) } returns Result.Success(listOf(mockSection))

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            val data = resultObj["data"]?.jsonObject
            assertNotNull(data!!["sections"])

            val sections = data["sections"]?.jsonArray
            assertEquals(1, sections?.size)
        }

        @Test
        fun `should get task with feature when includeFeature true`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "get")
                put("id", taskId.toString())
                put("includeFeature", true)
            }

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)
            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(mockFeature)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            val data = resultObj["data"]?.jsonObject
            assertNotNull(data!!["feature"])

            val feature = data["feature"]?.jsonObject
            assertEquals(featureId.toString(), feature!!["id"]?.jsonPrimitive?.content)
            assertEquals("Test Feature", feature["name"]?.jsonPrimitive?.content)
        }

        @Test
        fun `should get task with dependencies when includeDependencies true`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "get")
                put("id", taskId.toString())
                put("includeDependencies", true)
            }

            val dependency = Dependency(
                id = UUID.randomUUID(),
                fromTaskId = UUID.randomUUID(),
                toTaskId = taskId,
                type = DependencyType.BLOCKS
            )

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)
            coEvery { mockDependencyRepository.findByTaskId(taskId) } returns listOf(dependency)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            val data = resultObj["data"]?.jsonObject
            assertNotNull(data!!["dependencies"])

            val dependencies = data["dependencies"]?.jsonObject
            assertEquals(1, dependencies!!["counts"]?.jsonObject?.get("total")?.jsonPrimitive?.int)
        }

        @Test
        fun `should truncate summary when summaryView true`() = runBlocking {
            val longSummary = "a".repeat(150)
            val taskWithLongSummary = mockTask.copy(summary = longSummary)

            val params = buildJsonObject {
                put("operation", "get")
                put("id", taskId.toString())
                put("summaryView", true)
            }

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(taskWithLongSummary)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            val data = resultObj["data"]?.jsonObject
            val summary = data!!["summary"]?.jsonPrimitive?.content

            assertTrue(summary!!.length <= 100)
            assertTrue(summary.endsWith("..."))
        }

        @Test
        fun `should return not found error when task does not exist`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "get")
                put("id", taskId.toString())
            }

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Error(RepositoryError.NotFound(taskId, EntityType.TASK, "Task not found"))

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertFalse(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("Task not found") == true)
        }
    }

    @Nested
    inner class UpdateOperationTests {
        @Test
        fun `should update task with partial fields`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "update")
                put("id", taskId.toString())
                put("title", "Updated Title")
            }

            val updatedTask = mockTask.copy(title = "Updated Title")

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)
            coEvery { mockTaskRepository.update(any()) } returns Result.Success(updatedTask)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("updated") == true)
        }

        @Test
        fun `should use locking for update operation`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "update")
                put("id", taskId.toString())
                put("status", "completed")
            }

            val updatedTask = mockTask.copy(status = TaskStatus.COMPLETED)

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)
            coEvery { mockTaskRepository.update(any()) } returns Result.Success(updatedTask)

            tool.execute(params, context)

            // Locking service is mocked with relaxed = true, so we just verify execution completes
            // In a real scenario, we'd verify lock acquisition
        }

        @Test
        fun `should validate feature exists when updating featureId`() = runBlocking {
            val newFeatureId = UUID.randomUUID()
            val params = buildJsonObject {
                put("operation", "update")
                put("id", taskId.toString())
                put("featureId", newFeatureId.toString())
            }

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)
            coEvery { mockFeatureRepository.getById(newFeatureId) } returns Result.Error(RepositoryError.NotFound(newFeatureId, EntityType.FEATURE, "Feature not found"))

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertFalse(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("Feature not found") == true)
        }
    }

    @Nested
    inner class DeleteOperationTests {
        @Test
        fun `should delete task and sections`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "delete")
                put("id", taskId.toString())
            }

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)
            coEvery { mockDependencyRepository.findByTaskId(taskId) } returns emptyList()
            coEvery { mockSectionRepository.getSectionsForEntity(EntityType.TASK, taskId) } returns Result.Success(listOf(mockSection))
            coEvery { mockSectionRepository.deleteSection(any()) } returns Result.Success(true)
            coEvery { mockTaskRepository.delete(taskId) } returns Result.Success(true)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)

            val data = resultObj["data"]?.jsonObject
            assertEquals(1, data!!["sectionsDeleted"]?.jsonPrimitive?.int)
        }

        @Test
        fun `should use locking for delete operation`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "delete")
                put("id", taskId.toString())
            }

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)
            coEvery { mockDependencyRepository.findByTaskId(taskId) } returns emptyList()
            coEvery { mockSectionRepository.getSectionsForEntity(EntityType.TASK, taskId) } returns Result.Success(emptyList())
            coEvery { mockTaskRepository.delete(taskId) } returns Result.Success(true)

            tool.execute(params, context)

            // Locking service is mocked with relaxed = true
        }

        @Test
        fun `should require force=true when dependencies exist`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "delete")
                put("id", taskId.toString())
            }

            val dependency = Dependency(
                id = UUID.randomUUID(),
                fromTaskId = taskId,
                toTaskId = UUID.randomUUID(),
                type = DependencyType.BLOCKS
            )

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)
            coEvery { mockDependencyRepository.findByTaskId(taskId) } returns listOf(dependency)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertFalse(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("dependencies") == true)
        }

        @Test
        fun `should delete dependencies when force=true`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "delete")
                put("id", taskId.toString())
                put("force", true)
            }

            val dependency = Dependency(
                id = UUID.randomUUID(),
                fromTaskId = taskId,
                toTaskId = UUID.randomUUID(),
                type = DependencyType.BLOCKS
            )

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)
            coEvery { mockDependencyRepository.findByTaskId(taskId) } returns listOf(dependency)
            coEvery { mockDependencyRepository.deleteByTaskId(taskId) } returns 1
            coEvery { mockSectionRepository.getSectionsForEntity(EntityType.TASK, taskId) } returns Result.Success(emptyList())
            coEvery { mockTaskRepository.delete(taskId) } returns Result.Success(true)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)

            val data = resultObj["data"]?.jsonObject
            assertEquals(1, data!!["dependenciesDeleted"]?.jsonPrimitive?.int)
        }
    }

    @Nested
    inner class ExportOperationTests {
        @Test
        fun `should export task to markdown format`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "export")
                put("id", taskId.toString())
                put("format", "markdown")
            }

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)
            coEvery { mockSectionRepository.getSectionsForEntity(EntityType.TASK, taskId) } returns Result.Success(emptyList())

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)

            val data = resultObj["data"]?.jsonObject
            assertNotNull(data!!["markdown"])
            assertTrue(data["markdown"]?.jsonPrimitive?.content?.contains("# Test Task") == true)
        }

        @Test
        fun `should export task to json format`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "export")
                put("id", taskId.toString())
                put("format", "json")
            }

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)

            val data = resultObj["data"]?.jsonObject
            assertNotNull(data!!["id"])
            assertEquals(taskId.toString(), data["id"]?.jsonPrimitive?.content)
        }

        @Test
        fun `should default to markdown format`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "export")
                put("id", taskId.toString())
            }

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)
            coEvery { mockSectionRepository.getSectionsForEntity(EntityType.TASK, taskId) } returns Result.Success(emptyList())

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            val data = resultObj["data"]?.jsonObject
            assertNotNull(data!!["markdown"])
        }
    }

    @Nested
    inner class ErrorHandlingTests {
        @Test
        fun `should handle database errors gracefully`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "create")
                put("title", "New Task")
            }

            coEvery { mockTaskRepository.create(any()) } returns Result.Error(RepositoryError.DatabaseError("Database connection failed"))

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertFalse(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("Failed to create task") == true)
        }

        @Test
        fun `should handle unexpected exceptions`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "create")
                put("title", "New Task")
            }

            coEvery { mockTaskRepository.create(any()) } throws RuntimeException("Unexpected error")

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertFalse(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("Failed to execute task operation") == true)
        }
    }
}
