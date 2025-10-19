package io.github.jpicklyk.mcptask.application.tools

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

class QueryContainerToolTest {
    private lateinit var tool: QueryContainerTool
    private lateinit var context: ToolExecutionContext
    private lateinit var mockTaskRepository: TaskRepository
    private lateinit var mockFeatureRepository: FeatureRepository
    private lateinit var mockProjectRepository: ProjectRepository
    private lateinit var mockSectionRepository: SectionRepository
    private lateinit var mockDependencyRepository: DependencyRepository
    private lateinit var mockTemplateRepository: TemplateRepository
    private lateinit var mockRepositoryProvider: RepositoryProvider

    private val taskId = UUID.randomUUID()
    private val featureId = UUID.randomUUID()
    private val projectId = UUID.randomUUID()

    private lateinit var mockTask: Task
    private lateinit var mockFeature: Feature
    private lateinit var mockProject: Project
    private lateinit var mockSection: Section

    @BeforeEach
    fun setup() {
        // Create mock repositories
        mockTaskRepository = mockk()
        mockFeatureRepository = mockk()
        mockProjectRepository = mockk()
        mockSectionRepository = mockk()
        mockDependencyRepository = mockk()
        mockTemplateRepository = mockk()
        mockRepositoryProvider = mockk()

        // Configure repository provider
        every { mockRepositoryProvider.taskRepository() } returns mockTaskRepository
        every { mockRepositoryProvider.featureRepository() } returns mockFeatureRepository
        every { mockRepositoryProvider.projectRepository() } returns mockProjectRepository
        every { mockRepositoryProvider.sectionRepository() } returns mockSectionRepository
        every { mockRepositoryProvider.dependencyRepository() } returns mockDependencyRepository
        every { mockRepositoryProvider.templateRepository() } returns mockTemplateRepository

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
            projectId = projectId,
            tags = listOf("test"),
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )

        mockProject = Project(
            id = projectId,
            name = "Test Project",
            summary = "Test project summary",
            status = ProjectStatus.IN_DEVELOPMENT,
            tags = listOf("test"),
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )

        mockSection = Section(
            id = UUID.randomUUID(),
            entityType = EntityType.TASK,
            entityId = taskId,
            title = "Test Section",
            content = "Test content",
            usageDescription = "Test usage",
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 0,
            tags = emptyList()
        )

        // Create execution context
        context = ToolExecutionContext(mockRepositoryProvider)

        // Create tool (read-only, no locking needed)
        tool = QueryContainerTool(null, null)
    }

    @Nested
    inner class ValidationTests {
        @Test
        fun `should require operation parameter`() {
            val params = buildJsonObject {
                put("containerType", "task")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("operation"))
        }

        @Test
        fun `should require containerType parameter`() {
            val params = buildJsonObject {
                put("operation", "get")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("containerType"))
        }

        @Test
        fun `should reject invalid operation`() {
            val params = buildJsonObject {
                put("operation", "invalid")
                put("containerType", "task")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("Invalid operation"))
        }

        @Test
        fun `should reject invalid containerType`() {
            val params = buildJsonObject {
                put("operation", "get")
                put("containerType", "invalid")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("Invalid containerType"))
        }

        @Test
        fun `should require id for get operation`() {
            val params = buildJsonObject {
                put("operation", "get")
                put("containerType", "task")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("id"))
        }

        @Test
        fun `should require id for export operation`() {
            val params = buildJsonObject {
                put("operation", "export")
                put("containerType", "task")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("id"))
        }

        @Test
        fun `should accept valid get params`() {
            val params = buildJsonObject {
                put("operation", "get")
                put("containerType", "task")
                put("id", taskId.toString())
            }

            assertDoesNotThrow {
                tool.validateParams(params)
            }
        }

        @Test
        fun `should accept valid search params`() {
            val params = buildJsonObject {
                put("operation", "search")
                put("containerType", "task")
            }

            assertDoesNotThrow {
                tool.validateParams(params)
            }
        }

        @Test
        fun `should accept valid overview params`() {
            val params = buildJsonObject {
                put("operation", "overview")
                put("containerType", "task")
            }

            assertDoesNotThrow {
                tool.validateParams(params)
            }
        }

        @Test
        fun `should reject invalid status for task`() {
            val params = buildJsonObject {
                put("operation", "search")
                put("containerType", "task")
                put("status", "invalid-status")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("Invalid status"))
        }

        @Test
        fun `should reject invalid priority`() {
            val params = buildJsonObject {
                put("operation", "search")
                put("containerType", "task")
                put("priority", "invalid-priority")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("Invalid priority"))
        }

        @Test
        fun `should reject invalid projectId UUID`() {
            val params = buildJsonObject {
                put("operation", "search")
                put("containerType", "task")
                put("projectId", "not-a-uuid")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("Invalid projectId"))
        }

        @Test
        fun `should reject invalid featureId UUID`() {
            val params = buildJsonObject {
                put("operation", "search")
                put("containerType", "task")
                put("featureId", "not-a-uuid")
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("Invalid featureId"))
        }

        @Test
        fun `should reject summaryLength out of range - negative`() {
            val params = buildJsonObject {
                put("operation", "overview")
                put("containerType", "task")
                put("summaryLength", -1)
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("Summary length must be between 0 and 200"))
        }

        @Test
        fun `should reject summaryLength out of range - too large`() {
            val params = buildJsonObject {
                put("operation", "overview")
                put("containerType", "task")
                put("summaryLength", 201)
            }

            val exception = assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("Summary length must be between 0 and 200"))
        }
    }

    @Nested
    inner class GetOperationTests {
        @Nested
        inner class TaskGetTests {
            @Test
            fun `should get task without sections`() = runBlocking {
                val params = buildJsonObject {
                    put("operation", "get")
                    put("containerType", "task")
                    put("id", taskId.toString())
                }

                coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)

                val result = tool.execute(params, context)

                val resultObj = result.jsonObject
                assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
                assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("retrieved") == true)
                assertEquals(taskId.toString(), resultObj["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content)
                assertNull(resultObj["data"]?.jsonObject?.get("sections"))
            }

            @Test
            fun `should get task with sections`() = runBlocking {
                val params = buildJsonObject {
                    put("operation", "get")
                    put("containerType", "task")
                    put("id", taskId.toString())
                    put("includeSections", true)
                }

                coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)
                coEvery { mockSectionRepository.getSectionsForEntity(EntityType.TASK, taskId) } returns
                    Result.Success(listOf(mockSection))

                val result = tool.execute(params, context)

                val resultObj = result.jsonObject
                assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
                val sections = resultObj["data"]?.jsonObject?.get("sections")?.jsonArray
                assertNotNull(sections)
                assertEquals(1, sections?.size)
            }

            @Test
            fun `should handle task not found`() = runBlocking {
                val params = buildJsonObject {
                    put("operation", "get")
                    put("containerType", "task")
                    put("id", taskId.toString())
                }

                coEvery { mockTaskRepository.getById(taskId) } returns
                    Result.Error(RepositoryError.NotFound(taskId, EntityType.TASK, "Task not found"))

                val result = tool.execute(params, context)

                val resultObj = result.jsonObject
                assertFalse(resultObj["success"]?.jsonPrimitive?.boolean == true)
                assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("not found") == true)
            }
        }

        @Nested
        inner class FeatureGetTests {
            @Test
            fun `should get feature without sections`() = runBlocking {
                val params = buildJsonObject {
                    put("operation", "get")
                    put("containerType", "feature")
                    put("id", featureId.toString())
                }

                coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(mockFeature)
                coEvery { mockTaskRepository.findByFeature(featureId) } returns Result.Success(listOf(mockTask))

                val result = tool.execute(params, context)

                val resultObj = result.jsonObject
                assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
                assertEquals(featureId.toString(), resultObj["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content)
            }

            @Test
            fun `should get feature with sections`() = runBlocking {
                val params = buildJsonObject {
                    put("operation", "get")
                    put("containerType", "feature")
                    put("id", featureId.toString())
                    put("includeSections", true)
                }

                val featureSection = mockSection.copy(entityType = EntityType.FEATURE, entityId = featureId)

                coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(mockFeature)
                coEvery { mockTaskRepository.findByFeature(featureId) } returns Result.Success(listOf(mockTask))
                coEvery { mockSectionRepository.getSectionsForEntity(EntityType.FEATURE, featureId) } returns
                    Result.Success(listOf(featureSection))

                val result = tool.execute(params, context)

                val resultObj = result.jsonObject
                assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
                val sections = resultObj["data"]?.jsonObject?.get("sections")?.jsonArray
                assertNotNull(sections)
                assertEquals(1, sections?.size)
            }

            @Test
            fun `should handle feature not found`() = runBlocking {
                val params = buildJsonObject {
                    put("operation", "get")
                    put("containerType", "feature")
                    put("id", featureId.toString())
                }

                coEvery { mockFeatureRepository.getById(featureId) } returns
                    Result.Error(RepositoryError.NotFound(featureId, EntityType.FEATURE, "Feature not found"))

                val result = tool.execute(params, context)

                val resultObj = result.jsonObject
                assertFalse(resultObj["success"]?.jsonPrimitive?.boolean == true)
            }
        }

        @Nested
        inner class ProjectGetTests {
            @Test
            fun `should get project without sections`() = runBlocking {
                val params = buildJsonObject {
                    put("operation", "get")
                    put("containerType", "project")
                    put("id", projectId.toString())
                }

                coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(mockProject)
                coEvery { mockTaskRepository.findByFilters(
                    projectId = projectId,
                    statusFilter = null,
                    priorityFilter = null,
                    tags = null,
                    textQuery = null,
                    limit = any()
                ) } returns Result.Success(listOf(mockTask))

                val result = tool.execute(params, context)

                val resultObj = result.jsonObject
                assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
                assertEquals(projectId.toString(), resultObj["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content)
            }

            @Test
            fun `should get project with sections`() = runBlocking {
                val params = buildJsonObject {
                    put("operation", "get")
                    put("containerType", "project")
                    put("id", projectId.toString())
                    put("includeSections", true)
                }

                val projectSection = mockSection.copy(entityType = EntityType.PROJECT, entityId = projectId)

                coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(mockProject)
                coEvery { mockTaskRepository.findByFilters(
                    projectId = projectId,
                    statusFilter = null,
                    priorityFilter = null,
                    tags = null,
                    textQuery = null,
                    limit = any()
                ) } returns Result.Success(listOf(mockTask))
                coEvery { mockSectionRepository.getSectionsForEntity(EntityType.PROJECT, projectId) } returns
                    Result.Success(listOf(projectSection))

                val result = tool.execute(params, context)

                val resultObj = result.jsonObject
                assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
                val sections = resultObj["data"]?.jsonObject?.get("sections")?.jsonArray
                assertNotNull(sections)
                assertEquals(1, sections?.size)
            }

            @Test
            fun `should handle project not found`() = runBlocking {
                val params = buildJsonObject {
                    put("operation", "get")
                    put("containerType", "project")
                    put("id", projectId.toString())
                }

                coEvery { mockProjectRepository.getById(projectId) } returns
                    Result.Error(RepositoryError.NotFound(projectId, EntityType.PROJECT, "Project not found"))

                val result = tool.execute(params, context)

                val resultObj = result.jsonObject
                assertFalse(resultObj["success"]?.jsonPrimitive?.boolean == true)
            }
        }
    }

    @Nested
    inner class SearchOperationTests {
        @Nested
        inner class TaskSearchTests {
            @Test
            fun `should search tasks with no filters`() = runBlocking {
                val params = buildJsonObject {
                    put("operation", "search")
                    put("containerType", "task")
                }

                coEvery { mockTaskRepository.findAll(20) } returns Result.Success(listOf(mockTask))

                val result = tool.execute(params, context)

                val resultObj = result.jsonObject
                assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
                assertEquals(1, resultObj["data"]?.jsonObject?.get("count")?.jsonPrimitive?.int)
            }

            @Test
            fun `should search tasks with query`() = runBlocking {
                val params = buildJsonObject {
                    put("operation", "search")
                    put("containerType", "task")
                    put("query", "Test")
                }

                coEvery { mockTaskRepository.findByFilters(null, null, null, null, "Test", 20) } returns
                    Result.Success(listOf(mockTask))

                val result = tool.execute(params, context)

                val resultObj = result.jsonObject
                assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
                assertEquals(1, resultObj["data"]?.jsonObject?.get("count")?.jsonPrimitive?.int)
            }

            @Test
            fun `should search tasks with status filter`() = runBlocking {
                val params = buildJsonObject {
                    put("operation", "search")
                    put("containerType", "task")
                    put("status", "pending")
                }

                coEvery { mockTaskRepository.findByFilters(null, StatusFilter(include = listOf(TaskStatus.PENDING)), null, null, null, 20) } returns
                    Result.Success(listOf(mockTask))

                val result = tool.execute(params, context)

                val resultObj = result.jsonObject
                assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            }

            @Test
            fun `should search tasks with priority filter`() = runBlocking {
                val params = buildJsonObject {
                    put("operation", "search")
                    put("containerType", "task")
                    put("priority", "high")
                }

                coEvery { mockTaskRepository.findByFilters(null, null, StatusFilter(include = listOf(Priority.HIGH)), null, null, 20) } returns
                    Result.Success(listOf(mockTask))

                val result = tool.execute(params, context)

                val resultObj = result.jsonObject
                assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            }

            @Test
            fun `should search tasks with tags filter`() = runBlocking {
                val params = buildJsonObject {
                    put("operation", "search")
                    put("containerType", "task")
                    put("tags", "test,kotlin")
                }

                coEvery { mockTaskRepository.findByFilters(null, null, null, listOf("test", "kotlin"), null, 20) } returns
                    Result.Success(listOf(mockTask))

                val result = tool.execute(params, context)

                val resultObj = result.jsonObject
                assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            }

            @Test
            fun `should search tasks with projectId filter`() = runBlocking {
                val params = buildJsonObject {
                    put("operation", "search")
                    put("containerType", "task")
                    put("projectId", projectId.toString())
                }

                coEvery { mockTaskRepository.findByFilters(projectId, null, null, null, null, 20) } returns
                    Result.Success(listOf(mockTask))

                val result = tool.execute(params, context)

                val resultObj = result.jsonObject
                assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            }

            @Test
            fun `should search tasks with featureId filter`() = runBlocking {
                val params = buildJsonObject {
                    put("operation", "search")
                    put("containerType", "task")
                    put("featureId", featureId.toString())
                }

                coEvery {
                    mockTaskRepository.findByFeatureAndFilters(
                        featureId = featureId,
                        statusFilter = null,
                        priorityFilter = null,
                        tags = null,
                        textQuery = null,
                        limit = 20
                    )
                } returns Result.Success(listOf(mockTask))

                val result = tool.execute(params, context)

                val resultObj = result.jsonObject
                assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            }

            @Test
            fun `should search tasks with custom limit`() = runBlocking {
                val params = buildJsonObject {
                    put("operation", "search")
                    put("containerType", "task")
                    put("limit", 50)
                }

                coEvery { mockTaskRepository.findAll(50) } returns Result.Success(listOf(mockTask))

                val result = tool.execute(params, context)

                val resultObj = result.jsonObject
                assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            }

            @Test
            fun `should handle empty search results`() = runBlocking {
                val params = buildJsonObject {
                    put("operation", "search")
                    put("containerType", "task")
                }

                coEvery { mockTaskRepository.findAll(20) } returns Result.Success(emptyList())

                val result = tool.execute(params, context)

                val resultObj = result.jsonObject
                assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
                assertEquals(0, resultObj["data"]?.jsonObject?.get("count")?.jsonPrimitive?.int)
            }
        }

        @Nested
        inner class FeatureSearchTests {
            @Test
            fun `should search features with no filters`() = runBlocking {
                val params = buildJsonObject {
                    put("operation", "search")
                    put("containerType", "feature")
                }

                coEvery { mockFeatureRepository.findAll(20) } returns Result.Success(listOf(mockFeature))

                val result = tool.execute(params, context)

                val resultObj = result.jsonObject
                assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
                assertEquals(1, resultObj["data"]?.jsonObject?.get("count")?.jsonPrimitive?.int)
            }

            @Test
            fun `should search features with query`() = runBlocking {
                val params = buildJsonObject {
                    put("operation", "search")
                    put("containerType", "feature")
                    put("query", "Test")
                }

                coEvery { mockFeatureRepository.findByFilters(null, null, null, null, "Test", 20) } returns
                    Result.Success(listOf(mockFeature))

                val result = tool.execute(params, context)

                val resultObj = result.jsonObject
                assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            }

            @Test
            fun `should search features with status filter`() = runBlocking {
                val params = buildJsonObject {
                    put("operation", "search")
                    put("containerType", "feature")
                    put("status", "in-development")
                }

                coEvery { mockFeatureRepository.findByFilters(null, StatusFilter(include = listOf(FeatureStatus.IN_DEVELOPMENT)), null, null, null, 20) } returns
                    Result.Success(listOf(mockFeature))

                val result = tool.execute(params, context)

                val resultObj = result.jsonObject
                assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            }

            @Test
            fun `should search features with priority filter`() = runBlocking {
                val params = buildJsonObject {
                    put("operation", "search")
                    put("containerType", "feature")
                    put("priority", "high")
                }

                coEvery { mockFeatureRepository.findByFilters(null, null, StatusFilter(include = listOf(Priority.HIGH)), null, null, 20) } returns
                    Result.Success(listOf(mockFeature))

                val result = tool.execute(params, context)

                val resultObj = result.jsonObject
                assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            }

            @Test
            fun `should search features with projectId filter`() = runBlocking {
                val params = buildJsonObject {
                    put("operation", "search")
                    put("containerType", "feature")
                    put("projectId", projectId.toString())
                }

                coEvery { mockFeatureRepository.findByFilters(projectId, null, null, null, null, 20) } returns
                    Result.Success(listOf(mockFeature))

                val result = tool.execute(params, context)

                val resultObj = result.jsonObject
                assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            }
        }

        @Nested
        inner class ProjectSearchTests {
            @Test
            fun `should search projects with no filters`() = runBlocking {
                val params = buildJsonObject {
                    put("operation", "search")
                    put("containerType", "project")
                }

                coEvery { mockProjectRepository.findAll(20) } returns Result.Success(listOf(mockProject))

                val result = tool.execute(params, context)

                val resultObj = result.jsonObject
                assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
                assertEquals(1, resultObj["data"]?.jsonObject?.get("count")?.jsonPrimitive?.int)
            }

            @Test
            fun `should search projects with query`() = runBlocking {
                val params = buildJsonObject {
                    put("operation", "search")
                    put("containerType", "project")
                    put("query", "Test")
                }

                coEvery { mockProjectRepository.findByFilters(null, null, null, null, "Test", 20) } returns
                    Result.Success(listOf(mockProject))

                val result = tool.execute(params, context)

                val resultObj = result.jsonObject
                assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            }

            @Test
            fun `should search projects with status filter`() = runBlocking {
                val params = buildJsonObject {
                    put("operation", "search")
                    put("containerType", "project")
                    put("status", "in-development")
                }

                coEvery { mockProjectRepository.findByFilters(null, StatusFilter(include = listOf(ProjectStatus.IN_DEVELOPMENT)), null, null, null, 20) } returns
                    Result.Success(listOf(mockProject))

                val result = tool.execute(params, context)

                val resultObj = result.jsonObject
                assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            }

            @Test
            fun `should search projects with tags filter`() = runBlocking {
                val params = buildJsonObject {
                    put("operation", "search")
                    put("containerType", "project")
                    put("tags", "test")
                }

                coEvery { mockProjectRepository.findByFilters(null, null, null, listOf("test"), null, 20) } returns
                    Result.Success(listOf(mockProject))

                val result = tool.execute(params, context)

                val resultObj = result.jsonObject
                assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
            }
        }
    }

    @Nested
    inner class ExportOperationTests {
        @Nested
        inner class TaskExportTests {
            @Test
            fun `should export task to markdown`() = runBlocking {
                val params = buildJsonObject {
                    put("operation", "export")
                    put("containerType", "task")
                    put("id", taskId.toString())
                }

                coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)
                coEvery { mockSectionRepository.getSectionsForEntity(EntityType.TASK, taskId) } returns
                    Result.Success(listOf(mockSection))

                val result = tool.execute(params, context)

                val resultObj = result.jsonObject
                assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
                assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("exported") == true)
                assertNotNull(resultObj["data"]?.jsonObject?.get("markdown"))
                assertEquals("task", resultObj["data"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
            }

            @Test
            fun `should handle task not found on export`() = runBlocking {
                val params = buildJsonObject {
                    put("operation", "export")
                    put("containerType", "task")
                    put("id", taskId.toString())
                }

                coEvery { mockTaskRepository.getById(taskId) } returns
                    Result.Error(RepositoryError.NotFound(taskId, EntityType.TASK, "Task not found"))

                val result = tool.execute(params, context)

                val resultObj = result.jsonObject
                assertFalse(resultObj["success"]?.jsonPrimitive?.boolean == true)
            }
        }

        @Nested
        inner class FeatureExportTests {
            @Test
            fun `should export feature to markdown`() = runBlocking {
                val params = buildJsonObject {
                    put("operation", "export")
                    put("containerType", "feature")
                    put("id", featureId.toString())
                }

                val featureSection = mockSection.copy(entityType = EntityType.FEATURE, entityId = featureId)

                coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(mockFeature)
                coEvery { mockSectionRepository.getSectionsForEntity(EntityType.FEATURE, featureId) } returns
                    Result.Success(listOf(featureSection))

                val result = tool.execute(params, context)

                val resultObj = result.jsonObject
                assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
                assertNotNull(resultObj["data"]?.jsonObject?.get("markdown"))
                assertEquals("feature", resultObj["data"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
            }

            @Test
            fun `should handle feature not found on export`() = runBlocking {
                val params = buildJsonObject {
                    put("operation", "export")
                    put("containerType", "feature")
                    put("id", featureId.toString())
                }

                coEvery { mockFeatureRepository.getById(featureId) } returns
                    Result.Error(RepositoryError.NotFound(featureId, EntityType.FEATURE, "Feature not found"))

                val result = tool.execute(params, context)

                val resultObj = result.jsonObject
                assertFalse(resultObj["success"]?.jsonPrimitive?.boolean == true)
            }
        }

        @Nested
        inner class ProjectExportTests {
            @Test
            fun `should export project to markdown`() = runBlocking {
                val params = buildJsonObject {
                    put("operation", "export")
                    put("containerType", "project")
                    put("id", projectId.toString())
                }

                val projectSection = mockSection.copy(entityType = EntityType.PROJECT, entityId = projectId)

                coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(mockProject)
                coEvery { mockSectionRepository.getSectionsForEntity(EntityType.PROJECT, projectId) } returns
                    Result.Success(listOf(projectSection))

                val result = tool.execute(params, context)

                val resultObj = result.jsonObject
                assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
                assertNotNull(resultObj["data"]?.jsonObject?.get("markdown"))
                assertEquals("project", resultObj["data"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
            }

            @Test
            fun `should handle project not found on export`() = runBlocking {
                val params = buildJsonObject {
                    put("operation", "export")
                    put("containerType", "project")
                    put("id", projectId.toString())
                }

                coEvery { mockProjectRepository.getById(projectId) } returns
                    Result.Error(RepositoryError.NotFound(projectId, EntityType.PROJECT, "Project not found"))

                val result = tool.execute(params, context)

                val resultObj = result.jsonObject
                assertFalse(resultObj["success"]?.jsonPrimitive?.boolean == true)
            }
        }
    }

    @Nested
    inner class OverviewOperationTests {
        @Nested
        inner class TaskOverviewTests {
            @Test
            fun `should get task overview with default summary length`() = runBlocking {
                val params = buildJsonObject {
                    put("operation", "overview")
                    put("containerType", "task")
                }

                coEvery { mockTaskRepository.findAll(100) } returns Result.Success(listOf(mockTask))

                val result = tool.execute(params, context)

                val resultObj = result.jsonObject
                assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
                assertEquals(1, resultObj["data"]?.jsonObject?.get("count")?.jsonPrimitive?.int)
                val items = resultObj["data"]?.jsonObject?.get("items")?.jsonArray
                assertNotNull(items)
                assertEquals(1, items?.size)
            }

            @Test
            fun `should get task overview with custom summary length`() = runBlocking {
                val longSummaryTask = mockTask.copy(
                    summary = "This is a very long summary that should be truncated to the specified length when overview is called"
                )

                val params = buildJsonObject {
                    put("operation", "overview")
                    put("containerType", "task")
                    put("summaryLength", 20)
                }

                coEvery { mockTaskRepository.findAll(100) } returns Result.Success(listOf(longSummaryTask))

                val result = tool.execute(params, context)

                val resultObj = result.jsonObject
                assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
                val items = resultObj["data"]?.jsonObject?.get("items")?.jsonArray
                val summary = items?.get(0)?.jsonObject?.get("summary")?.jsonPrimitive?.content
                assertTrue(summary!!.length <= 20)
                assertTrue(summary.endsWith("..."))
            }

            @Test
            fun `should get task overview with zero summary length`() = runBlocking {
                val params = buildJsonObject {
                    put("operation", "overview")
                    put("containerType", "task")
                    put("summaryLength", 0)
                }

                coEvery { mockTaskRepository.findAll(100) } returns Result.Success(listOf(mockTask))

                val result = tool.execute(params, context)

                val resultObj = result.jsonObject
                assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
                val items = resultObj["data"]?.jsonObject?.get("items")?.jsonArray
                val summary = items?.get(0)?.jsonObject?.get("summary")
                assertNull(summary)
            }

            @Test
            fun `should handle empty task overview`() = runBlocking {
                val params = buildJsonObject {
                    put("operation", "overview")
                    put("containerType", "task")
                }

                coEvery { mockTaskRepository.findAll(100) } returns Result.Success(emptyList())

                val result = tool.execute(params, context)

                val resultObj = result.jsonObject
                assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
                assertEquals(0, resultObj["data"]?.jsonObject?.get("count")?.jsonPrimitive?.int)
            }
        }

        @Nested
        inner class FeatureOverviewTests {
            @Test
            fun `should get feature overview with default summary length`() = runBlocking {
                val params = buildJsonObject {
                    put("operation", "overview")
                    put("containerType", "feature")
                }

                coEvery { mockFeatureRepository.findAll(100) } returns Result.Success(listOf(mockFeature))

                val result = tool.execute(params, context)

                val resultObj = result.jsonObject
                assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
                assertEquals(1, resultObj["data"]?.jsonObject?.get("count")?.jsonPrimitive?.int)
            }

            @Test
            fun `should get feature overview with custom summary length`() = runBlocking {
                val longSummaryFeature = mockFeature.copy(
                    summary = "This is a very long summary that should be truncated to the specified length"
                )

                val params = buildJsonObject {
                    put("operation", "overview")
                    put("containerType", "feature")
                    put("summaryLength", 20)
                }

                coEvery { mockFeatureRepository.findAll(100) } returns Result.Success(listOf(longSummaryFeature))

                val result = tool.execute(params, context)

                val resultObj = result.jsonObject
                assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
                val items = resultObj["data"]?.jsonObject?.get("items")?.jsonArray
                val summary = items?.get(0)?.jsonObject?.get("summary")?.jsonPrimitive?.content
                assertTrue(summary!!.length <= 20)
            }
        }

        @Nested
        inner class ProjectOverviewTests {
            @Test
            fun `should get project overview with default summary length`() = runBlocking {
                val params = buildJsonObject {
                    put("operation", "overview")
                    put("containerType", "project")
                }

                coEvery { mockProjectRepository.findAll(100) } returns Result.Success(listOf(mockProject))

                val result = tool.execute(params, context)

                val resultObj = result.jsonObject
                assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
                assertEquals(1, resultObj["data"]?.jsonObject?.get("count")?.jsonPrimitive?.int)
            }

            @Test
            fun `should get project overview with custom summary length`() = runBlocking {
                val longSummaryProject = mockProject.copy(
                    summary = "This is a very long summary that should be truncated to the specified length"
                )

                val params = buildJsonObject {
                    put("operation", "overview")
                    put("containerType", "project")
                    put("summaryLength", 20)
                }

                coEvery { mockProjectRepository.findAll(100) } returns Result.Success(listOf(longSummaryProject))

                val result = tool.execute(params, context)

                val resultObj = result.jsonObject
                assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
                val items = resultObj["data"]?.jsonObject?.get("items")?.jsonArray
                val summary = items?.get(0)?.jsonObject?.get("summary")?.jsonPrimitive?.content
                assertTrue(summary!!.length <= 20)
            }
        }
    }

    @Nested
    inner class EdgeCaseTests {
        @Test
        fun `should handle database error on get`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "get")
                put("containerType", "task")
                put("id", taskId.toString())
            }

            coEvery { mockTaskRepository.getById(taskId) } returns
                Result.Error(RepositoryError.DatabaseError("Database connection failed"))

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertFalse(resultObj["success"]?.jsonPrimitive?.boolean == true)
        }

        @Test
        fun `should handle database error on search`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "search")
                put("containerType", "task")
            }

            coEvery { mockTaskRepository.findAll(20) } returns
                Result.Error(RepositoryError.DatabaseError("Database connection failed"))

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertFalse(resultObj["success"]?.jsonPrimitive?.boolean == true)
        }

        @Test
        fun `should handle sections fetch error gracefully on get`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "get")
                put("containerType", "task")
                put("id", taskId.toString())
                put("includeSections", true)
            }

            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)
            coEvery { mockSectionRepository.getSectionsForEntity(EntityType.TASK, taskId) } returns
                Result.Error(RepositoryError.DatabaseError("Section fetch failed"))

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            // Should still succeed but with empty sections
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
        }

        @Test
        fun `should handle empty tags gracefully`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "search")
                put("containerType", "task")
                put("tags", "")
            }

            coEvery { mockTaskRepository.findAll(20) } returns Result.Success(listOf(mockTask))

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
        }

        @Test
        fun `should parse status with hyphens correctly`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "search")
                put("containerType", "task")
                put("status", "in-progress")
            }

            coEvery { mockTaskRepository.findByFilters(null, StatusFilter(include = listOf(TaskStatus.IN_PROGRESS)), null, null, null, 20) } returns
                Result.Success(listOf(mockTask))

            assertDoesNotThrow {
                runBlocking {
                    tool.execute(params, context)
                }
            }
        }

        @Test
        fun `should parse status with underscores correctly`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "search")
                put("containerType", "task")
                put("status", "in_progress")
            }

            coEvery { mockTaskRepository.findByFilters(null, StatusFilter(include = listOf(TaskStatus.IN_PROGRESS)), null, null, null, 20) } returns
                Result.Success(listOf(mockTask))

            assertDoesNotThrow {
                runBlocking {
                    tool.execute(params, context)
                }
            }
        }

        @Test
        fun `should handle multiple tags with spaces`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "search")
                put("containerType", "task")
                put("tags", "test, kotlin, unit-test")
            }

            coEvery { mockTaskRepository.findByFilters(null, null, null, listOf("test", "kotlin", "unit-test"), null, 20) } returns
                Result.Success(listOf(mockTask))

            val result = tool.execute(params, context)

            val resultObj = result.jsonObject
            assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
        }
    }
}
