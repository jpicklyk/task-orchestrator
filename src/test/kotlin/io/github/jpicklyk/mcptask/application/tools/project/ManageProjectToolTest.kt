package io.github.jpicklyk.mcptask.application.tools.project

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

class ManageProjectToolTest {
    private lateinit var tool: ManageProjectTool
    private lateinit var context: ToolExecutionContext
    private lateinit var mockProjectRepository: ProjectRepository
    private lateinit var mockFeatureRepository: FeatureRepository
    private lateinit var mockTaskRepository: TaskRepository
    private lateinit var mockSectionRepository: SectionRepository
    private lateinit var mockRepositoryProvider: RepositoryProvider

    private val projectId = UUID.randomUUID()

    private lateinit var mockProject: Project
    private lateinit var mockFeature: Feature
    private lateinit var mockTask: Task
    private lateinit var mockSection: Section

    @BeforeEach
    fun setup() {
        // Create mock repositories
        mockProjectRepository = mockk()
        mockFeatureRepository = mockk()
        mockTaskRepository = mockk()
        mockSectionRepository = mockk()
        mockRepositoryProvider = mockk()

        // Configure repository provider
        every { mockRepositoryProvider.projectRepository() } returns mockProjectRepository
        every { mockRepositoryProvider.featureRepository() } returns mockFeatureRepository
        every { mockRepositoryProvider.taskRepository() } returns mockTaskRepository
        every { mockRepositoryProvider.sectionRepository() } returns mockSectionRepository

        // Create test entities
        mockProject = Project(
            id = projectId,
            name = "Test Project",
            description = "Test description",
            summary = "Test summary",
            status = ProjectStatus.PLANNING,
            tags = listOf("test", "kotlin"),
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )

        mockFeature = Feature(
            id = UUID.randomUUID(),
            name = "Test Feature",
            summary = "Test feature summary",
            status = FeatureStatus.IN_DEVELOPMENT,
            priority = Priority.HIGH,
            projectId = projectId
        )

        mockTask = Task(
            id = UUID.randomUUID(),
            title = "Test Task",
            summary = "Test task summary",
            status = TaskStatus.PENDING,
            priority = Priority.MEDIUM,
            complexity = 5,
            projectId = projectId
        )

        mockSection = Section(
            id = UUID.randomUUID(),
            entityType = EntityType.PROJECT,
            entityId = projectId,
            title = "Test Section",
            usageDescription = "Test usage",
            content = "Test content",
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 0
        )

        // Create execution context
        context = ToolExecutionContext(mockRepositoryProvider)

        // Create tool with null locking service to bypass locking in unit tests
        tool = ManageProjectTool(null, null)
    }

    @Nested
    inner class OperationRoutingTests {
        @Test
        fun `should route to create when operation is create`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "create")
                put("name", "New Project")
            }

            coEvery { mockProjectRepository.create(any()) } returns Result.Success(mockProject)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("created") == true)
        }

        @Test
        fun `should route to get when operation is get`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "get")
                put("id", projectId.toString())
            }

            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(mockProject)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("retrieved") == true)
        }

        @Test
        fun `should route to update when operation is update`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "update")
                put("id", projectId.toString())
                put("name", "Updated Project")
            }

            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(mockProject)
            coEvery { mockProjectRepository.update(any()) } returns Result.Success(mockProject.copy(name = "Updated Project"))

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("updated") == true)
        }

        @Test
        fun `should route to delete when operation is delete`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "delete")
                put("id", projectId.toString())
            }

            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(mockProject)
            coEvery { mockFeatureRepository.findByProject(projectId) } returns Result.Success(emptyList())
            coEvery { mockTaskRepository.findByProject(projectId) } returns Result.Success(emptyList())
            coEvery { mockSectionRepository.getSectionsForEntity(EntityType.PROJECT, projectId) } returns Result.Success(emptyList())
            coEvery { mockProjectRepository.delete(projectId) } returns Result.Success(true)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("deleted") == true)
        }

        @Test
        fun `should route to export when operation is export`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "export")
                put("id", projectId.toString())
                put("format", "markdown")
            }

            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(mockProject)
            coEvery { mockSectionRepository.getSectionsForEntity(EntityType.PROJECT, projectId) } returns Result.Success(emptyList())

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("markdown") == true)
        }

        @Test
        fun `should return error for invalid operation`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "invalid")
            }

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertFalse(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("Invalid operation") == true)
        }
    }

    @Nested
    inner class ValidationTests {
        @Test
        fun `should validate operation parameter is required`() {
            val params = buildJsonObject {}

            assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
        }

        @Test
        fun `should validate operation is valid`() {
            val params = buildJsonObject {
                put("operation", "invalid_operation")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message?.contains("Invalid operation") == true)
        }

        @Test
        fun `should validate id is required for get operation`() {
            val params = buildJsonObject {
                put("operation", "get")
            }

            assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
        }

        @Test
        fun `should validate id format for get operation`() {
            val params = buildJsonObject {
                put("operation", "get")
                put("id", "not-a-uuid")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message?.contains("Invalid project ID format") == true)
        }

        @Test
        fun `should validate name is required for create operation`() {
            val params = buildJsonObject {
                put("operation", "create")
            }

            assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
        }

        @Test
        fun `should validate status if provided`() {
            val params = buildJsonObject {
                put("operation", "create")
                put("name", "Test Project")
                put("status", "invalid-status")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message?.contains("Invalid status") == true)
        }

        @Test
        fun `should accept valid status values`() {
            val validStatuses = listOf("planning", "in-development", "completed", "archived")

            validStatuses.forEach { status ->
                val params = buildJsonObject {
                    put("operation", "create")
                    put("name", "Test Project")
                    put("status", status)
                }

                // Should not throw
                tool.validateParams(params)
            }
        }
    }

    @Nested
    inner class CreateOperationTests {
        @Test
        fun `should create project with all fields`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "create")
                put("name", "New Project")
                put("description", "Project description")
                put("summary", "Project summary")
                put("status", "planning")
                put("tags", "tag1,tag2,tag3")
            }

            coEvery { mockProjectRepository.create(any()) } returns Result.Success(mockProject)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            val data = resultObj["data"]?.jsonObject
            assertNotNull(data)
            assertEquals(mockProject.id.toString(), data?.get("id")?.jsonPrimitive?.content)
            assertEquals(mockProject.name, data?.get("name")?.jsonPrimitive?.content)
        }

        @Test
        fun `should create project with minimal fields`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "create")
                put("name", "Minimal Project")
            }

            coEvery { mockProjectRepository.create(any()) } returns Result.Success(mockProject)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
        }

        @Test
        fun `should handle repository error during create`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "create")
                put("name", "New Project")
            }

            coEvery { mockProjectRepository.create(any()) } returns Result.Error(
                RepositoryError.DatabaseError("Database error")
            )

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertFalse(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("Failed to create project") == true)
        }
    }

    @Nested
    inner class GetOperationTests {
        @Test
        fun `should get project with basic info`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "get")
                put("id", projectId.toString())
            }

            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(mockProject)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            val data = resultObj["data"]?.jsonObject
            assertNotNull(data)
            assertEquals(projectId.toString(), data?.get("id")?.jsonPrimitive?.content)
        }

        @Test
        fun `should get project with features`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "get")
                put("id", projectId.toString())
                put("includeFeatures", true)
            }

            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(mockProject)
            coEvery { mockFeatureRepository.findByProject(projectId) } returns Result.Success(listOf(mockFeature))

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            val data = resultObj["data"]?.jsonObject
            assertNotNull(data?.get("features"))
            assertEquals(1, data?.get("featureCount")?.jsonPrimitive?.int)
        }

        @Test
        fun `should get project with tasks`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "get")
                put("id", projectId.toString())
                put("includeTasks", true)
            }

            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(mockProject)
            coEvery { mockTaskRepository.findByProject(projectId) } returns Result.Success(listOf(mockTask))

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            val data = resultObj["data"]?.jsonObject
            assertNotNull(data?.get("tasks"))
            assertEquals(1, data?.get("taskCount")?.jsonPrimitive?.int)
        }

        @Test
        fun `should get project with sections`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "get")
                put("id", projectId.toString())
                put("includeSections", true)
            }

            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(mockProject)
            coEvery { mockSectionRepository.getSectionsForEntity(EntityType.PROJECT, projectId) } returns
                Result.Success(listOf(mockSection))

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            val data = resultObj["data"]?.jsonObject
            val sections = data?.get("sections")?.jsonArray
            assertNotNull(sections)
            assertEquals(1, sections?.size)
        }

        @Test
        fun `should handle project not found`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "get")
                put("id", projectId.toString())
            }

            coEvery { mockProjectRepository.getById(projectId) } returns Result.Error(
                RepositoryError.NotFound(projectId, EntityType.PROJECT, "Project not found")
            )

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertFalse(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("not found") == true)
        }
    }

    @Nested
    inner class UpdateOperationTests {
        @Test
        fun `should update project`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "update")
                put("id", projectId.toString())
                put("name", "Updated Name")
                put("status", "completed")
            }

            val updatedProject = mockProject.copy(
                name = "Updated Name",
                status = ProjectStatus.COMPLETED
            )

            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(mockProject)
            coEvery { mockProjectRepository.update(any()) } returns Result.Success(updatedProject)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("updated") == true)
        }

        @Test
        fun `should handle project not found during update`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "update")
                put("id", projectId.toString())
                put("name", "Updated Name")
            }

            coEvery { mockProjectRepository.getById(projectId) } returns Result.Error(
                RepositoryError.NotFound(projectId, EntityType.PROJECT, "Project not found")
            )

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertFalse(resultObj["success"]?.jsonPrimitive?.boolean == true)
        }
    }

    @Nested
    inner class DeleteOperationTests {
        @Test
        fun `should delete project without children`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "delete")
                put("id", projectId.toString())
            }

            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(mockProject)
            coEvery { mockFeatureRepository.findByProject(projectId) } returns Result.Success(emptyList())
            coEvery { mockTaskRepository.findByProject(projectId) } returns Result.Success(emptyList())
            coEvery { mockSectionRepository.getSectionsForEntity(EntityType.PROJECT, projectId) } returns
                Result.Success(emptyList())
            coEvery { mockProjectRepository.delete(projectId) } returns Result.Success(true)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            val data = resultObj["data"]?.jsonObject
            assertEquals(true, data?.get("deleted")?.jsonPrimitive?.boolean)
        }

        @Test
        fun `should fail to delete project with children when force is false`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "delete")
                put("id", projectId.toString())
                put("force", false)
            }

            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(mockProject)
            coEvery { mockFeatureRepository.findByProject(projectId) } returns Result.Success(listOf(mockFeature))
            coEvery { mockTaskRepository.findByProject(projectId) } returns Result.Success(listOf(mockTask))

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertFalse(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("features or tasks") == true)
        }

        @Test
        fun `should delete project with children when force is true`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "delete")
                put("id", projectId.toString())
                put("force", true)
            }

            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(mockProject)
            coEvery { mockFeatureRepository.findByProject(projectId) } returns Result.Success(listOf(mockFeature))
            coEvery { mockTaskRepository.findByProject(projectId) } returns Result.Success(listOf(mockTask))
            coEvery { mockTaskRepository.delete(any()) } returns Result.Success(true)
            coEvery { mockFeatureRepository.delete(any()) } returns Result.Success(true)
            coEvery { mockSectionRepository.getSectionsForEntity(EntityType.PROJECT, projectId) } returns
                Result.Success(emptyList())
            coEvery { mockProjectRepository.delete(projectId) } returns Result.Success(true)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            val data = resultObj["data"]?.jsonObject
            assertEquals(1, data?.get("featuresDeleted")?.jsonPrimitive?.int)
            assertEquals(1, data?.get("tasksDeleted")?.jsonPrimitive?.int)
        }
    }

    @Nested
    inner class ExportOperationTests {
        @Test
        fun `should export project as markdown`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "export")
                put("id", projectId.toString())
                put("format", "markdown")
            }

            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(mockProject)
            coEvery { mockSectionRepository.getSectionsForEntity(EntityType.PROJECT, projectId) } returns
                Result.Success(emptyList())

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            val data = resultObj["data"]?.jsonObject
            assertNotNull(data?.get("markdown"))
        }

        @Test
        fun `should export project as json`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "export")
                put("id", projectId.toString())
                put("format", "json")
            }

            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(mockProject)

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
        }

        @Test
        fun `should reject invalid export format`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "export")
                put("id", projectId.toString())
                put("format", "xml")
            }

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertFalse(resultObj["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("Invalid format") == true)
        }
    }
}
