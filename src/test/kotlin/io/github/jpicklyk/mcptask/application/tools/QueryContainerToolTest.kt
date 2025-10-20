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
    inner class ScopedOverviewTests {
        @Nested
        inner class ScopedProjectOverviewTests {
            @Test
            fun `test scoped project overview with valid id returns project with features`() = runBlocking {
                val params = buildJsonObject {
                    put("operation", "overview")
                    put("containerType", "project")
                    put("id", projectId.toString())
                }

                val feature2 = mockFeature.copy(id = UUID.randomUUID(), name = "Feature 2")
                val feature3 = mockFeature.copy(id = UUID.randomUUID(), name = "Feature 3")

                coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(mockProject)
                coEvery { mockFeatureRepository.findByProject(projectId, 100) } returns
                    Result.Success(listOf(mockFeature, feature2, feature3))
                coEvery { mockTaskRepository.findByFilters(
                    projectId = projectId,
                    statusFilter = null,
                    priorityFilter = null,
                    tags = null,
                    textQuery = null,
                    limit = 1000
                ) } returns Result.Success(listOf(mockTask))

                val result = tool.execute(params, context)

                val response = result.jsonObject
                assertTrue(response["success"]?.jsonPrimitive?.boolean == true)

                val data = response["data"]?.jsonObject
                assertNotNull(data)
                assertEquals(projectId.toString(), data!!["id"]?.jsonPrimitive?.content)
                assertEquals(mockProject.name, data["name"]?.jsonPrimitive?.content)
                assertEquals(mockProject.status.name.lowercase().replace('_', '-'), data["status"]?.jsonPrimitive?.content)

                val features = data["features"]?.jsonArray
                assertNotNull(features)
                assertEquals(3, features!!.size)

                val taskCounts = data["taskCounts"]?.jsonObject
                assertNotNull(taskCounts)
                assertEquals(1, taskCounts!!["total"]?.jsonPrimitive?.int)
            }

            @Test
            fun `test scoped project overview with no features returns empty array`() = runBlocking {
                val params = buildJsonObject {
                    put("operation", "overview")
                    put("containerType", "project")
                    put("id", projectId.toString())
                }

                coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(mockProject)
                coEvery { mockFeatureRepository.findByProject(projectId, 100) } returns Result.Success(emptyList())
                coEvery { mockTaskRepository.findByFilters(
                    projectId = projectId,
                    statusFilter = null,
                    priorityFilter = null,
                    tags = null,
                    textQuery = null,
                    limit = 1000
                ) } returns Result.Success(emptyList())

                val result = tool.execute(params, context)

                val response = result.jsonObject
                assertTrue(response["success"]?.jsonPrimitive?.boolean == true)

                val data = response["data"]?.jsonObject
                val features = data?.get("features")?.jsonArray
                assertNotNull(features)
                assertEquals(0, features!!.size)

                val taskCounts = data?.get("taskCounts")?.jsonObject
                assertEquals(0, taskCounts?.get("total")?.jsonPrimitive?.int)
            }

            @Test
            fun `test scoped project overview with invalid id returns not found error`() = runBlocking {
                val nonExistentId = UUID.randomUUID()
                val params = buildJsonObject {
                    put("operation", "overview")
                    put("containerType", "project")
                    put("id", nonExistentId.toString())
                }

                coEvery { mockProjectRepository.getById(nonExistentId) } returns
                    Result.Error(RepositoryError.NotFound(nonExistentId, EntityType.PROJECT, "Project not found"))

                val result = tool.execute(params, context)

                val response = result.jsonObject
                assertFalse(response["success"]?.jsonPrimitive?.boolean == true)
                assertTrue(response["message"]?.jsonPrimitive?.content?.contains("not found", ignoreCase = true) == true)
            }

            @Test
            fun `test scoped project overview truncates summary when summaryLength specified`() = runBlocking {
                val longSummary = "This is a very long summary that should be truncated to the specified length when overview is called with a summaryLength parameter"
                val projectWithLongSummary = mockProject.copy(summary = longSummary)

                val params = buildJsonObject {
                    put("operation", "overview")
                    put("containerType", "project")
                    put("id", projectId.toString())
                    put("summaryLength", 50)
                }

                coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(projectWithLongSummary)
                coEvery { mockFeatureRepository.findByProject(projectId, 100) } returns Result.Success(emptyList())
                coEvery { mockTaskRepository.findByFilters(
                    projectId = projectId,
                    statusFilter = null,
                    priorityFilter = null,
                    tags = null,
                    textQuery = null,
                    limit = 1000
                ) } returns Result.Success(emptyList())

                val result = tool.execute(params, context)

                val response = result.jsonObject
                val data = response["data"]?.jsonObject
                val summary = data?.get("summary")?.jsonPrimitive?.content

                assertNotNull(summary)
                assertTrue(summary!!.length <= 50)
                assertTrue(summary.endsWith("..."))
            }

            @Test
            fun `test scoped project overview includes task counts`() = runBlocking {
                val completedTask1 = mockTask.copy(id = UUID.randomUUID(), status = TaskStatus.COMPLETED)
                val completedTask2 = mockTask.copy(id = UUID.randomUUID(), status = TaskStatus.COMPLETED)
                val completedTask3 = mockTask.copy(id = UUID.randomUUID(), status = TaskStatus.COMPLETED)
                val pendingTask1 = mockTask.copy(id = UUID.randomUUID(), status = TaskStatus.PENDING)
                val pendingTask2 = mockTask.copy(id = UUID.randomUUID(), status = TaskStatus.PENDING)

                val params = buildJsonObject {
                    put("operation", "overview")
                    put("containerType", "project")
                    put("id", projectId.toString())
                }

                coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(mockProject)
                coEvery { mockFeatureRepository.findByProject(projectId, 100) } returns Result.Success(listOf(mockFeature))
                coEvery { mockTaskRepository.findByFilters(
                    projectId = projectId,
                    statusFilter = null,
                    priorityFilter = null,
                    tags = null,
                    textQuery = null,
                    limit = 1000
                ) } returns Result.Success(listOf(completedTask1, completedTask2, completedTask3, pendingTask1, pendingTask2))

                val result = tool.execute(params, context)

                val response = result.jsonObject
                val data = response["data"]?.jsonObject
                val taskCounts = data?.get("taskCounts")?.jsonObject
                assertNotNull(taskCounts)
                assertEquals(5, taskCounts!!["total"]?.jsonPrimitive?.int)

                val byStatus = taskCounts["byStatus"]?.jsonObject
                assertNotNull(byStatus)
                assertEquals(3, byStatus!!["completed"]?.jsonPrimitive?.int)
                assertEquals(2, byStatus["pending"]?.jsonPrimitive?.int)
            }
        }

        @Nested
        inner class ScopedFeatureOverviewTests {
            @Test
            fun `test scoped feature overview with valid id returns feature with tasks`() = runBlocking {
                val task2 = mockTask.copy(id = UUID.randomUUID(), title = "Task 2")
                val task3 = mockTask.copy(id = UUID.randomUUID(), title = "Task 3")
                val task4 = mockTask.copy(id = UUID.randomUUID(), title = "Task 4")
                val task5 = mockTask.copy(id = UUID.randomUUID(), title = "Task 5")

                val params = buildJsonObject {
                    put("operation", "overview")
                    put("containerType", "feature")
                    put("id", featureId.toString())
                }

                coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(mockFeature)
                coEvery { mockTaskRepository.findByFeature(featureId, null, null, 20) } returns
                    Result.Success(listOf(mockTask, task2, task3, task4, task5))
                coEvery { mockTaskRepository.findByFeature(featureId, null, null, 100) } returns
                    Result.Success(listOf(mockTask, task2, task3, task4, task5))

                val result = tool.execute(params, context)

                val response = result.jsonObject
                assertTrue(response["success"]?.jsonPrimitive?.boolean == true)

                val data = response["data"]?.jsonObject
                assertNotNull(data)
                assertEquals(featureId.toString(), data!!["id"]?.jsonPrimitive?.content)
                assertEquals(mockFeature.name, data["name"]?.jsonPrimitive?.content)
                assertEquals(mockFeature.status.name.lowercase().replace('_', '-'), data["status"]?.jsonPrimitive?.content)
                assertEquals(mockFeature.priority.name.lowercase(), data["priority"]?.jsonPrimitive?.content)

                val tasks = data["tasks"]?.jsonArray
                assertNotNull(tasks)
                assertEquals(5, tasks!!.size)

                val taskCounts = data["taskCounts"]?.jsonObject
                assertNotNull(taskCounts)
                assertEquals(5, taskCounts!!["total"]?.jsonPrimitive?.int)
            }

            @Test
            fun `test scoped feature overview with no tasks returns empty array`() = runBlocking {
                val params = buildJsonObject {
                    put("operation", "overview")
                    put("containerType", "feature")
                    put("id", featureId.toString())
                }

                coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(mockFeature)
                coEvery { mockTaskRepository.findByFeature(featureId, null, null, 20) } returns Result.Success(emptyList())
                coEvery { mockTaskRepository.findByFeature(featureId, null, null, 100) } returns Result.Success(emptyList())

                val result = tool.execute(params, context)

                val response = result.jsonObject
                assertTrue(response["success"]?.jsonPrimitive?.boolean == true)

                val data = response["data"]?.jsonObject
                val tasks = data?.get("tasks")?.jsonArray
                assertNotNull(tasks)
                assertEquals(0, tasks!!.size)

                val taskCounts = data?.get("taskCounts")?.jsonObject
                assertEquals(0, taskCounts?.get("total")?.jsonPrimitive?.int)
            }

            @Test
            fun `test scoped feature overview with invalid id returns not found error`() = runBlocking {
                val nonExistentId = UUID.randomUUID()
                val params = buildJsonObject {
                    put("operation", "overview")
                    put("containerType", "feature")
                    put("id", nonExistentId.toString())
                }

                coEvery { mockFeatureRepository.getById(nonExistentId) } returns
                    Result.Error(RepositoryError.NotFound(nonExistentId, EntityType.FEATURE, "Feature not found"))

                val result = tool.execute(params, context)

                val response = result.jsonObject
                assertFalse(response["success"]?.jsonPrimitive?.boolean == true)
                assertTrue(response["message"]?.jsonPrimitive?.content?.contains("not found", ignoreCase = true) == true)
            }

            @Test
            fun `test scoped feature overview includes projectId when feature belongs to project`() = runBlocking {
                val params = buildJsonObject {
                    put("operation", "overview")
                    put("containerType", "feature")
                    put("id", featureId.toString())
                }

                coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(mockFeature)
                coEvery { mockTaskRepository.findByFeature(featureId, null, null, 20) } returns Result.Success(emptyList())
                coEvery { mockTaskRepository.findByFeature(featureId, null, null, 100) } returns Result.Success(emptyList())

                val result = tool.execute(params, context)

                val response = result.jsonObject
                val data = response["data"]?.jsonObject
                assertNotNull(data)

                val projectIdField = data!!["projectId"]?.jsonPrimitive?.content
                assertEquals(projectId.toString(), projectIdField)
            }

            @Test
            fun `test scoped feature overview truncates summary`() = runBlocking {
                val longSummary = "This is a very long summary that needs to be truncated"
                val featureWithLongSummary = mockFeature.copy(summary = longSummary)

                val params = buildJsonObject {
                    put("operation", "overview")
                    put("containerType", "feature")
                    put("id", featureId.toString())
                    put("summaryLength", 30)
                }

                coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(featureWithLongSummary)
                coEvery { mockTaskRepository.findByFeature(featureId, null, null, 20) } returns Result.Success(emptyList())
                coEvery { mockTaskRepository.findByFeature(featureId, null, null, 100) } returns Result.Success(emptyList())

                val result = tool.execute(params, context)

                val response = result.jsonObject
                val data = response["data"]?.jsonObject
                val summary = data?.get("summary")?.jsonPrimitive?.content

                assertNotNull(summary)
                assertTrue(summary!!.length <= 30)
                assertTrue(summary.endsWith("..."))
            }

            @Test
            fun `test scoped feature overview task counts match actual tasks`() = runBlocking {
                val completedTasks = (1..6).map { mockTask.copy(id = UUID.randomUUID(), status = TaskStatus.COMPLETED) }
                val inProgressTasks = (1..3).map { mockTask.copy(id = UUID.randomUUID(), status = TaskStatus.IN_PROGRESS) }
                val pendingTasks = (1..1).map { mockTask.copy(id = UUID.randomUUID(), status = TaskStatus.PENDING) }
                val allTasks = completedTasks + inProgressTasks + pendingTasks

                val params = buildJsonObject {
                    put("operation", "overview")
                    put("containerType", "feature")
                    put("id", featureId.toString())
                }

                coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(mockFeature)
                coEvery { mockTaskRepository.findByFeature(featureId, null, null, 20) } returns Result.Success(allTasks)
                coEvery { mockTaskRepository.findByFeature(featureId, null, null, 100) } returns Result.Success(allTasks)

                val result = tool.execute(params, context)

                val response = result.jsonObject
                val data = response["data"]?.jsonObject
                val taskCounts = data?.get("taskCounts")?.jsonObject

                assertNotNull(taskCounts)
                assertEquals(10, taskCounts!!["total"]?.jsonPrimitive?.int)

                val byStatus = taskCounts["byStatus"]?.jsonObject
                assertNotNull(byStatus)
                assertEquals(6, byStatus!!["completed"]?.jsonPrimitive?.int)
                assertEquals(3, byStatus["in-progress"]?.jsonPrimitive?.int)
                assertEquals(1, byStatus["pending"]?.jsonPrimitive?.int)
            }
        }

        @Nested
        inner class ScopedTaskOverviewTests {
            @Test
            fun `test scoped task overview with valid id returns task with dependencies`() = runBlocking {
                val params = buildJsonObject {
                    put("operation", "overview")
                    put("containerType", "task")
                    put("id", taskId.toString())
                }

                coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)
                coEvery { mockDependencyRepository.findByFromTaskId(taskId) } returns emptyList()
                coEvery { mockDependencyRepository.findByToTaskId(taskId) } returns emptyList()

                val result = tool.execute(params, context)

                val response = result.jsonObject
                assertTrue(response["success"]?.jsonPrimitive?.boolean == true)

                val data = response["data"]?.jsonObject
                assertNotNull(data)
                assertEquals(taskId.toString(), data!!["id"]?.jsonPrimitive?.content)
                assertEquals(mockTask.title, data["title"]?.jsonPrimitive?.content)

                val dependencies = data["dependencies"]?.jsonObject
                assertNotNull(dependencies)
            }

            @Test
            fun `test scoped task overview with no dependencies returns empty arrays`() = runBlocking {
                val params = buildJsonObject {
                    put("operation", "overview")
                    put("containerType", "task")
                    put("id", taskId.toString())
                }

                coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)
                coEvery { mockDependencyRepository.findByFromTaskId(taskId) } returns emptyList()
                coEvery { mockDependencyRepository.findByToTaskId(taskId) } returns emptyList()

                val result = tool.execute(params, context)

                val response = result.jsonObject
                val data = response["data"]?.jsonObject
                val dependencies = data?.get("dependencies")?.jsonObject

                assertNotNull(dependencies)
                val blocking = dependencies!!["blocking"]?.jsonArray
                val blockedBy = dependencies["blockedBy"]?.jsonArray

                assertNotNull(blocking)
                assertNotNull(blockedBy)
                assertEquals(0, blocking!!.size)
                assertEquals(0, blockedBy!!.size)
            }

            @Test
            fun `test scoped task overview with blocking dependencies`() = runBlocking {
                val blockedTaskId = UUID.randomUUID()
                val blockedTask = mockTask.copy(id = blockedTaskId, title = "Blocked Task", status = TaskStatus.PENDING)

                val dependency = Dependency(
                    fromTaskId = taskId,
                    toTaskId = blockedTaskId,
                    createdAt = Instant.now()
                )

                val params = buildJsonObject {
                    put("operation", "overview")
                    put("containerType", "task")
                    put("id", taskId.toString())
                }

                coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)
                coEvery { mockDependencyRepository.findByFromTaskId(taskId) } returns listOf(dependency)
                coEvery { mockDependencyRepository.findByToTaskId(taskId) } returns emptyList()
                coEvery { mockTaskRepository.getById(blockedTaskId) } returns Result.Success(blockedTask)

                val result = tool.execute(params, context)

                val response = result.jsonObject
                val data = response["data"]?.jsonObject
                val dependencies = data?.get("dependencies")?.jsonObject

                assertNotNull(dependencies)
                val blocking = dependencies!!["blocking"]?.jsonArray
                assertNotNull(blocking)
                assertEquals(1, blocking!!.size)

                val blockedTaskData = blocking[0].jsonObject
                assertEquals(blockedTaskId.toString(), blockedTaskData["id"]?.jsonPrimitive?.content)
                assertEquals("Blocked Task", blockedTaskData["title"]?.jsonPrimitive?.content)
                assertEquals("pending", blockedTaskData["status"]?.jsonPrimitive?.content)
            }

            @Test
            fun `test scoped task overview with invalid id returns not found error`() = runBlocking {
                val nonExistentId = UUID.randomUUID()
                val params = buildJsonObject {
                    put("operation", "overview")
                    put("containerType", "task")
                    put("id", nonExistentId.toString())
                }

                coEvery { mockTaskRepository.getById(nonExistentId) } returns
                    Result.Error(RepositoryError.NotFound(nonExistentId, EntityType.TASK, "Task not found"))

                val result = tool.execute(params, context)

                val response = result.jsonObject
                assertFalse(response["success"]?.jsonPrimitive?.boolean == true)
                assertTrue(response["message"]?.jsonPrimitive?.content?.contains("not found", ignoreCase = true) == true)
            }

            @Test
            fun `test scoped task overview includes featureId when task belongs to feature`() = runBlocking {
                val params = buildJsonObject {
                    put("operation", "overview")
                    put("containerType", "task")
                    put("id", taskId.toString())
                }

                coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(mockTask)
                coEvery { mockDependencyRepository.findByFromTaskId(taskId) } returns emptyList()
                coEvery { mockDependencyRepository.findByToTaskId(taskId) } returns emptyList()

                val result = tool.execute(params, context)

                val response = result.jsonObject
                val data = response["data"]?.jsonObject
                assertNotNull(data)

                val featureIdField = data!!["featureId"]?.jsonPrimitive?.content
                assertEquals(featureId.toString(), featureIdField)
            }
        }

        @Nested
        inner class RoutingAndEdgeCaseTests {
            @Test
            fun `test overview with id routes to scoped path`() = runBlocking {
                val params = buildJsonObject {
                    put("operation", "overview")
                    put("containerType", "feature")
                    put("id", featureId.toString())
                }

                coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(mockFeature)
                coEvery { mockTaskRepository.findByFeature(featureId, null, null, 20) } returns Result.Success(listOf(mockTask))
                coEvery { mockTaskRepository.findByFeature(featureId, null, null, 100) } returns Result.Success(listOf(mockTask))

                val result = tool.execute(params, context)

                val response = result.jsonObject
                assertTrue(response["success"]?.jsonPrimitive?.boolean == true)

                val data = response["data"]?.jsonObject
                assertNotNull(data)

                // Scoped format has tasks array and taskCounts, not items array
                assertNotNull(data!!["tasks"])
                assertNotNull(data["taskCounts"])
                assertNull(data["items"])
            }

            @Test
            fun `test overview without id routes to global path`() = runBlocking {
                val params = buildJsonObject {
                    put("operation", "overview")
                    put("containerType", "feature")
                }

                coEvery { mockFeatureRepository.findAll(100) } returns Result.Success(listOf(mockFeature))

                val result = tool.execute(params, context)

                val response = result.jsonObject
                assertTrue(response["success"]?.jsonPrimitive?.boolean == true)

                val data = response["data"]?.jsonObject
                assertNotNull(data)

                // Global format has items array, not tasks/features
                assertNotNull(data!!["items"])
                assertNotNull(data["count"])
                assertNull(data["tasks"])
                assertNull(data["features"])
            }

            @Test
            fun `test scoped overview with invalid UUID format returns validation error`() = runBlocking {
                val params = buildJsonObject {
                    put("operation", "overview")
                    put("containerType", "feature")
                    put("id", "invalid-uuid-format")
                }

                val result = tool.execute(params, context)

                val response = result.jsonObject
                assertFalse(response["success"]?.jsonPrimitive?.boolean == true)
                assertTrue(response["message"]?.jsonPrimitive?.content?.contains("Invalid", ignoreCase = true) == true)
                assertTrue(response["message"]?.jsonPrimitive?.content?.contains("UUID", ignoreCase = true) == true)
            }

            @Test
            fun `test scoped overview with summaryLength=0 excludes summary`() = runBlocking {
                val params = buildJsonObject {
                    put("operation", "overview")
                    put("containerType", "feature")
                    put("id", featureId.toString())
                    put("summaryLength", 0)
                }

                coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(mockFeature)
                coEvery { mockTaskRepository.findByFeature(featureId, null, null, 20) } returns Result.Success(emptyList())
                coEvery { mockTaskRepository.findByFeature(featureId, null, null, 100) } returns Result.Success(emptyList())

                val result = tool.execute(params, context)

                val response = result.jsonObject
                val data = response["data"]?.jsonObject
                assertNotNull(data)

                // When summaryLength is 0, summary field should NOT be present
                assertNull(data!!["summary"])
            }
        }
    }

    @Nested
    inner class BackwardCompatibilityTests {
        @Test
        fun `test global project overview without id returns all projects`() = runBlocking {
            // Setup: Create multiple projects
            val project1 = mockProject.copy(id = UUID.randomUUID(), name = "Project 1", status = ProjectStatus.PLANNING)
            val project2 = mockProject.copy(id = UUID.randomUUID(), name = "Project 2", status = ProjectStatus.IN_DEVELOPMENT)
            val project3 = mockProject.copy(id = UUID.randomUUID(), name = "Project 3", status = ProjectStatus.COMPLETED)

            val params = buildJsonObject {
                put("operation", "overview")
                put("containerType", "project")
                // No id parameter - testing global overview
            }

            coEvery { mockProjectRepository.findAll(100) } returns
                Result.Success(listOf(project1, project2, project3))

            val result = tool.execute(params, context)

            val response = result.jsonObject
            assertTrue(response["success"]?.jsonPrimitive?.boolean == true)

            val data = response["data"]?.jsonObject
            assertNotNull(data)

            val items = data!!["items"]?.jsonArray
            assertNotNull(items)
            assertEquals(3, items!!.size)
            assertEquals(3, data["count"]?.jsonPrimitive?.int)

            // Verify each item has correct fields and NO features array
            items.forEach { item ->
                val projectObj = item.jsonObject
                assertNotNull(projectObj["id"])
                assertNotNull(projectObj["name"])
                assertNotNull(projectObj["status"])
                assertNotNull(projectObj["summary"])
                assertNotNull(projectObj["tags"])
                // Global overview should NOT have features array (that's scoped only)
                assertNull(projectObj["features"])
                assertNull(projectObj["taskCounts"])
            }
        }

        @Test
        fun `test global feature overview without id returns all features`() = runBlocking {
            // Setup: Create multiple features across different projects
            val feature1 = mockFeature.copy(id = UUID.randomUUID(), name = "Feature 1", priority = Priority.HIGH)
            val feature2 = mockFeature.copy(id = UUID.randomUUID(), name = "Feature 2", priority = Priority.MEDIUM)
            val feature3 = mockFeature.copy(id = UUID.randomUUID(), name = "Feature 3", priority = Priority.LOW)
            val feature4 = mockFeature.copy(id = UUID.randomUUID(), name = "Feature 4", priority = Priority.HIGH)
            val feature5 = mockFeature.copy(id = UUID.randomUUID(), name = "Feature 5", priority = Priority.MEDIUM)

            val params = buildJsonObject {
                put("operation", "overview")
                put("containerType", "feature")
                // No id parameter - testing global overview
            }

            coEvery { mockFeatureRepository.findAll(100) } returns
                Result.Success(listOf(feature1, feature2, feature3, feature4, feature5))

            val result = tool.execute(params, context)

            val response = result.jsonObject
            assertTrue(response["success"]?.jsonPrimitive?.boolean == true)

            val data = response["data"]?.jsonObject
            assertNotNull(data)

            val items = data!!["items"]?.jsonArray
            assertNotNull(items)
            assertEquals(5, items!!.size)
            assertEquals(5, data["count"]?.jsonPrimitive?.int)

            // Verify each item has correct fields and NO tasks array
            items.forEach { item ->
                val featureObj = item.jsonObject
                assertNotNull(featureObj["id"])
                assertNotNull(featureObj["name"])
                assertNotNull(featureObj["status"])
                assertNotNull(featureObj["priority"])
                assertNotNull(featureObj["summary"])
                assertNotNull(featureObj["tags"])
                assertNotNull(featureObj["projectId"])
                // Global overview should NOT have tasks array (that's scoped only)
                assertNull(featureObj["tasks"])
                assertNull(featureObj["taskCounts"])
            }
        }

        @Test
        fun `test global task overview without id returns all tasks`() = runBlocking {
            // Setup: Create multiple tasks across different features
            val task1 = mockTask.copy(id = UUID.randomUUID(), title = "Task 1", complexity = 5)
            val task2 = mockTask.copy(id = UUID.randomUUID(), title = "Task 2", complexity = 3)
            val task3 = mockTask.copy(id = UUID.randomUUID(), title = "Task 3", complexity = 8)
            val task4 = mockTask.copy(id = UUID.randomUUID(), title = "Task 4", complexity = 2)
            val task5 = mockTask.copy(id = UUID.randomUUID(), title = "Task 5", complexity = 7)
            val task6 = mockTask.copy(id = UUID.randomUUID(), title = "Task 6", complexity = 4)
            val task7 = mockTask.copy(id = UUID.randomUUID(), title = "Task 7", complexity = 6)
            val task8 = mockTask.copy(id = UUID.randomUUID(), title = "Task 8", complexity = 9)
            val task9 = mockTask.copy(id = UUID.randomUUID(), title = "Task 9", complexity = 1)
            val task10 = mockTask.copy(id = UUID.randomUUID(), title = "Task 10", complexity = 10)

            val params = buildJsonObject {
                put("operation", "overview")
                put("containerType", "task")
                // No id parameter - testing global overview
            }

            coEvery { mockTaskRepository.findAll(100) } returns
                Result.Success(listOf(task1, task2, task3, task4, task5, task6, task7, task8, task9, task10))

            val result = tool.execute(params, context)

            val response = result.jsonObject
            assertTrue(response["success"]?.jsonPrimitive?.boolean == true)

            val data = response["data"]?.jsonObject
            assertNotNull(data)

            val items = data!!["items"]?.jsonArray
            assertNotNull(items)
            assertEquals(10, items!!.size)
            assertEquals(10, data["count"]?.jsonPrimitive?.int)

            // Verify each item has correct fields and NO dependencies object
            items.forEach { item ->
                val taskObj = item.jsonObject
                assertNotNull(taskObj["id"])
                assertNotNull(taskObj["title"])
                assertNotNull(taskObj["status"])
                assertNotNull(taskObj["priority"])
                assertNotNull(taskObj["complexity"])
                assertNotNull(taskObj["summary"])
                assertNotNull(taskObj["tags"])
                assertNotNull(taskObj["featureId"])
                // Global overview should NOT have dependencies object (that's scoped only)
                assertNull(taskObj["dependencies"])
            }
        }

        @Test
        fun `test global overview respects default summaryLength of 100`() = runBlocking {
            // Create project with summary > 100 characters
            val longSummary = "This is a very long summary that is definitely more than one hundred characters long. " +
                    "It should be truncated to exactly 100 characters with ellipsis when using default summaryLength."
            val projectWithLongSummary = mockProject.copy(summary = longSummary)

            val params = buildJsonObject {
                put("operation", "overview")
                put("containerType", "project")
                // No summaryLength parameter - should use default of 100
            }

            coEvery { mockProjectRepository.findAll(100) } returns
                Result.Success(listOf(projectWithLongSummary))

            val result = tool.execute(params, context)

            val response = result.jsonObject
            assertTrue(response["success"]?.jsonPrimitive?.boolean == true)

            val data = response["data"]?.jsonObject
            val items = data?.get("items")?.jsonArray
            val summary = items?.get(0)?.jsonObject?.get("summary")?.jsonPrimitive?.content

            assertNotNull(summary)
            assertTrue(summary!!.length <= 100)
            assertTrue(summary.endsWith("..."))
        }

        @Test
        fun `test global overview with custom summaryLength truncates correctly`() = runBlocking {
            // Create feature with long summary
            val longSummary = "This is a very long feature summary that needs to be truncated to a custom length. " +
                    "It contains much more text than the custom limit we will specify in the test parameters."
            val featureWithLongSummary = mockFeature.copy(summary = longSummary)

            val params = buildJsonObject {
                put("operation", "overview")
                put("containerType", "feature")
                put("summaryLength", 50)
            }

            coEvery { mockFeatureRepository.findAll(100) } returns
                Result.Success(listOf(featureWithLongSummary))

            val result = tool.execute(params, context)

            val response = result.jsonObject
            assertTrue(response["success"]?.jsonPrimitive?.boolean == true)

            val data = response["data"]?.jsonObject
            val items = data?.get("items")?.jsonArray
            val summary = items?.get(0)?.jsonObject?.get("summary")?.jsonPrimitive?.content

            assertNotNull(summary)
            assertTrue(summary!!.length <= 50)
            assertTrue(summary.endsWith("..."))
        }

        @Test
        fun `test global overview with summaryLength=0 excludes summaries`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "overview")
                put("containerType", "task")
                put("summaryLength", 0)
            }

            coEvery { mockTaskRepository.findAll(100) } returns
                Result.Success(listOf(mockTask))

            val result = tool.execute(params, context)

            val response = result.jsonObject
            assertTrue(response["success"]?.jsonPrimitive?.boolean == true)

            val data = response["data"]?.jsonObject
            val items = data?.get("items")?.jsonArray

            assertNotNull(items)
            assertEquals(1, items!!.size)

            // Task objects should NOT contain summary field when summaryLength=0
            val taskObj = items[0].jsonObject
            assertNull(taskObj["summary"])
        }

        @Test
        fun `test global overview returns empty array when no entities exist`() = runBlocking {
            val params = buildJsonObject {
                put("operation", "overview")
                put("containerType", "project")
            }

            coEvery { mockProjectRepository.findAll(100) } returns Result.Success(emptyList())

            val result = tool.execute(params, context)

            val response = result.jsonObject
            assertTrue(response["success"]?.jsonPrimitive?.boolean == true)

            val data = response["data"]?.jsonObject
            assertNotNull(data)

            val items = data!!["items"]?.jsonArray
            assertNotNull(items)
            assertEquals(0, items!!.size)
            assertEquals(0, data["count"]?.jsonPrimitive?.int)
        }

        @Test
        fun `test global overview response format is consistent across all container types`() = runBlocking {
            // Test that all container types return the same structure for global overview
            val projectParams = buildJsonObject {
                put("operation", "overview")
                put("containerType", "project")
            }

            val featureParams = buildJsonObject {
                put("operation", "overview")
                put("containerType", "feature")
            }

            val taskParams = buildJsonObject {
                put("operation", "overview")
                put("containerType", "task")
            }

            coEvery { mockProjectRepository.findAll(100) } returns Result.Success(listOf(mockProject))
            coEvery { mockFeatureRepository.findAll(100) } returns Result.Success(listOf(mockFeature))
            coEvery { mockTaskRepository.findAll(100) } returns Result.Success(listOf(mockTask))

            val projectResult = tool.execute(projectParams, context).jsonObject
            val featureResult = tool.execute(featureParams, context).jsonObject
            val taskResult = tool.execute(taskParams, context).jsonObject

            // All should have same top-level structure
            listOf(projectResult, featureResult, taskResult).forEach { response ->
                assertTrue(response["success"]?.jsonPrimitive?.boolean == true)
                assertNotNull(response["message"])
                assertNotNull(response["data"]?.jsonObject)
                assertNotNull(response["data"]?.jsonObject?.get("items"))
                assertNotNull(response["data"]?.jsonObject?.get("count"))
            }
        }

        @Test
        fun `test global overview does not include child relationships`() = runBlocking {
            // Verify that global overview never includes nested children (features, tasks, dependencies)
            // even if they exist in the database

            val projectParams = buildJsonObject {
                put("operation", "overview")
                put("containerType", "project")
            }

            val featureParams = buildJsonObject {
                put("operation", "overview")
                put("containerType", "feature")
            }

            val taskParams = buildJsonObject {
                put("operation", "overview")
                put("containerType", "task")
            }

            coEvery { mockProjectRepository.findAll(100) } returns Result.Success(listOf(mockProject))
            coEvery { mockFeatureRepository.findAll(100) } returns Result.Success(listOf(mockFeature))
            coEvery { mockTaskRepository.findAll(100) } returns Result.Success(listOf(mockTask))

            val projectResult = tool.execute(projectParams, context).jsonObject
            val featureResult = tool.execute(featureParams, context).jsonObject
            val taskResult = tool.execute(taskParams, context).jsonObject

            // Project should not have features array
            val projectItems = projectResult["data"]?.jsonObject?.get("items")?.jsonArray
            projectItems?.forEach { item ->
                assertNull(item.jsonObject["features"])
                assertNull(item.jsonObject["taskCounts"])
            }

            // Feature should not have tasks array
            val featureItems = featureResult["data"]?.jsonObject?.get("items")?.jsonArray
            featureItems?.forEach { item ->
                assertNull(item.jsonObject["tasks"])
                assertNull(item.jsonObject["taskCounts"])
            }

            // Task should not have dependencies object
            val taskItems = taskResult["data"]?.jsonObject?.get("items")?.jsonArray
            taskItems?.forEach { item ->
                assertNull(item.jsonObject["dependencies"])
            }
        }
    }

    @Nested
    inner class IntegrationTests {
        /**
         * Measures the approximate token count of a JSON response.
         * Uses character count as a rough proxy (1 token ≈ 4 characters for JSON).
         */
        private fun measureTokens(jsonElement: JsonElement): Int {
            val jsonString = jsonElement.toString()
            return jsonString.length / 4  // Rough approximation
        }

        @Test
        fun `test integration - create feature and query scoped overview shows tasks`() = runBlocking {
            // Setup - create realistic scenario
            val project = mockProject
            val feature = mockFeature

            // Create 5 tasks with various statuses
            val task1 = mockTask.copy(id = UUID.randomUUID(), title = "Task 1", status = TaskStatus.COMPLETED)
            val task2 = mockTask.copy(id = UUID.randomUUID(), title = "Task 2", status = TaskStatus.COMPLETED)
            val task3 = mockTask.copy(id = UUID.randomUUID(), title = "Task 3", status = TaskStatus.COMPLETED)
            val task4 = mockTask.copy(id = UUID.randomUUID(), title = "Task 4", status = TaskStatus.PENDING)
            val task5 = mockTask.copy(id = UUID.randomUUID(), title = "Task 5", status = TaskStatus.PENDING)
            val allTasks = listOf(task1, task2, task3, task4, task5)

            // Mock repository responses
            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            coEvery { mockTaskRepository.findByFeature(featureId, null, null, 20) } returns Result.Success(allTasks)
            coEvery { mockTaskRepository.findByFeature(featureId, null, null, 100) } returns Result.Success(allTasks)

            // Execute - scoped overview
            val params = buildJsonObject {
                put("operation", "overview")
                put("containerType", "feature")
                put("id", featureId.toString())
            }
            val result = tool.execute(params, context)

            // Assert - comprehensive validation
            val response = result.jsonObject
            assertTrue(response["success"]?.jsonPrimitive?.boolean == true)

            val data = response["data"]?.jsonObject
            assertNotNull(data)
            assertEquals(feature.name, data!!["name"]?.jsonPrimitive?.content)
            assertEquals(feature.status.name.lowercase().replace('_', '-'), data["status"]?.jsonPrimitive?.content)

            val tasksArray = data["tasks"]?.jsonArray
            assertNotNull(tasksArray)
            assertEquals(5, tasksArray!!.size)

            val taskCounts = data["taskCounts"]?.jsonObject
            assertNotNull(taskCounts)
            assertEquals(5, taskCounts!!["total"]?.jsonPrimitive?.int)
            assertEquals(3, taskCounts["byStatus"]?.jsonObject?.get("completed")?.jsonPrimitive?.int)
            assertEquals(2, taskCounts["byStatus"]?.jsonObject?.get("pending")?.jsonPrimitive?.int)

            // Verify projectId is present
            assertEquals(projectId.toString(), data["projectId"]?.jsonPrimitive?.content)
        }

        @Test
        fun `test integration - scoped overview after bulk task updates reflects changes`() = runBlocking {
            // Setup - create feature with 10 tasks (all pending initially)
            val feature = mockFeature
            val tasks = (1..10).map { i ->
                mockTask.copy(
                    id = UUID.randomUUID(),
                    title = "Task $i",
                    status = when {
                        i <= 6 -> TaskStatus.COMPLETED
                        i <= 9 -> TaskStatus.IN_PROGRESS
                        else -> TaskStatus.PENDING
                    }
                )
            }

            // Mock repository responses
            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            coEvery { mockTaskRepository.findByFeature(featureId, null, null, 20) } returns Result.Success(tasks)
            coEvery { mockTaskRepository.findByFeature(featureId, null, null, 100) } returns Result.Success(tasks)

            // Execute - scoped feature overview
            val params = buildJsonObject {
                put("operation", "overview")
                put("containerType", "feature")
                put("id", featureId.toString())
            }
            val result = tool.execute(params, context)

            // Assert - validate counts reflect actual task statuses
            val response = result.jsonObject
            assertTrue(response["success"]?.jsonPrimitive?.boolean == true)

            val data = response["data"]?.jsonObject
            assertNotNull(data)

            val taskCounts = data!!["taskCounts"]?.jsonObject
            assertNotNull(taskCounts)
            assertEquals(10, taskCounts!!["total"]?.jsonPrimitive?.int)

            val byStatus = taskCounts["byStatus"]?.jsonObject
            assertNotNull(byStatus)
            assertEquals(6, byStatus!!["completed"]?.jsonPrimitive?.int)
            assertEquals(3, byStatus["in-progress"]?.jsonPrimitive?.int)
            assertEquals(1, byStatus["pending"]?.jsonPrimitive?.int)
        }

        @Test
        fun `test integration - scoped task overview shows bidirectional dependencies`() = runBlocking {
            // Setup - create dependency chain: A -> B -> C -> D
            val taskA = mockTask.copy(id = UUID.randomUUID(), title = "Task A")
            val taskB = mockTask.copy(id = UUID.randomUUID(), title = "Task B")
            val taskC = mockTask  // This is the task we're querying
            val taskD = mockTask.copy(id = UUID.randomUUID(), title = "Task D")

            val depBtoC = Dependency(
                fromTaskId = taskB.id,
                toTaskId = taskC.id,
                createdAt = java.time.Instant.now()
            )
            val depCtoD = Dependency(
                fromTaskId = taskC.id,
                toTaskId = taskD.id,
                createdAt = java.time.Instant.now()
            )

            // Mock repository responses
            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(taskC)
            coEvery { mockDependencyRepository.findByFromTaskId(taskId) } returns listOf(depCtoD)
            coEvery { mockDependencyRepository.findByToTaskId(taskId) } returns listOf(depBtoC)
            coEvery { mockTaskRepository.getById(taskB.id) } returns Result.Success(taskB)
            coEvery { mockTaskRepository.getById(taskD.id) } returns Result.Success(taskD)

            // Execute - scoped overview for task C
            val params = buildJsonObject {
                put("operation", "overview")
                put("containerType", "task")
                put("id", taskId.toString())
            }
            val result = tool.execute(params, context)

            // Assert - validate bidirectional dependencies
            val response = result.jsonObject
            assertTrue(response["success"]?.jsonPrimitive?.boolean == true)

            val data = response["data"]?.jsonObject
            assertNotNull(data)

            val dependencies = data!!["dependencies"]?.jsonObject
            assertNotNull(dependencies)

            // Validate blockedBy (task B blocks task C)
            val blockedBy = dependencies!!["blockedBy"]?.jsonArray
            assertNotNull(blockedBy)
            assertEquals(1, blockedBy!!.size)
            assertEquals(taskB.id.toString(), blockedBy[0].jsonObject["id"]?.jsonPrimitive?.content)
            assertEquals("Task B", blockedBy[0].jsonObject["title"]?.jsonPrimitive?.content)

            // Validate blocking (task C blocks task D)
            val blocking = dependencies["blocking"]?.jsonArray
            assertNotNull(blocking)
            assertEquals(1, blocking!!.size)
            assertEquals(taskD.id.toString(), blocking[0].jsonObject["id"]?.jsonPrimitive?.content)
            assertEquals("Task D", blocking[0].jsonObject["title"]?.jsonPrimitive?.content)
        }

        @Test
        fun `test integration - scoped project overview hierarchy complete`() = runBlocking {
            // Setup - create project with multiple features and tasks
            val project = mockProject

            val feature1 = mockFeature.copy(id = UUID.randomUUID(), name = "Feature 1")
            val feature2 = mockFeature.copy(id = UUID.randomUUID(), name = "Feature 2")
            val feature3 = mockFeature.copy(id = UUID.randomUUID(), name = "Feature 3")

            // Feature 1: 5 tasks
            val feature1Tasks = (1..5).map { i ->
                mockTask.copy(id = UUID.randomUUID(), featureId = feature1.id, title = "F1-Task $i")
            }

            // Feature 2: 3 tasks
            val feature2Tasks = (1..3).map { i ->
                mockTask.copy(id = UUID.randomUUID(), featureId = feature2.id, title = "F2-Task $i")
            }

            // Feature 3: 0 tasks
            val allTasks = feature1Tasks + feature2Tasks

            // Mock repository responses
            coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(project)
            coEvery { mockFeatureRepository.findByProject(projectId, 100) } returns
                Result.Success(listOf(feature1, feature2, feature3))
            coEvery { mockTaskRepository.findByFilters(
                projectId = projectId,
                statusFilter = null,
                priorityFilter = null,
                tags = null,
                textQuery = null,
                limit = 1000
            ) } returns Result.Success(allTasks)

            // Execute - scoped project overview
            val params = buildJsonObject {
                put("operation", "overview")
                put("containerType", "project")
                put("id", projectId.toString())
            }
            val result = tool.execute(params, context)

            // Assert - validate complete hierarchy
            val response = result.jsonObject
            assertTrue(response["success"]?.jsonPrimitive?.boolean == true)

            val data = response["data"]?.jsonObject
            assertNotNull(data)
            assertEquals(project.name, data!!["name"]?.jsonPrimitive?.content)

            // Validate features array
            val features = data["features"]?.jsonArray
            assertNotNull(features)
            assertEquals(3, features!!.size)

            // Validate task counts
            val taskCounts = data["taskCounts"]?.jsonObject
            assertNotNull(taskCounts)
            assertEquals(8, taskCounts!!["total"]?.jsonPrimitive?.int)
        }

        @Test
        fun `test integration - scoped vs global overview return different formats`() = runBlocking {
            // Setup - create feature with 5 tasks
            val feature = mockFeature
            val tasks = (1..5).map { i ->
                mockTask.copy(id = UUID.randomUUID(), title = "Task $i")
            }

            // Mock for global overview
            coEvery { mockFeatureRepository.findAll(100) } returns Result.Success(listOf(feature))

            // Mock for scoped overview
            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            coEvery { mockTaskRepository.findByFeature(featureId, null, null, 20) } returns Result.Success(tasks)
            coEvery { mockTaskRepository.findByFeature(featureId, null, null, 100) } returns Result.Success(tasks)

            // Execute - global overview (no id)
            val globalParams = buildJsonObject {
                put("operation", "overview")
                put("containerType", "feature")
            }
            val globalResult = tool.execute(globalParams, context)

            // Execute - scoped overview (with id)
            val scopedParams = buildJsonObject {
                put("operation", "overview")
                put("containerType", "feature")
                put("id", featureId.toString())
            }
            val scopedResult = tool.execute(scopedParams, context)

            // Assert - global returns items array
            val globalResponse = globalResult.jsonObject
            assertTrue(globalResponse["success"]?.jsonPrimitive?.boolean == true)
            val globalData = globalResponse["data"]?.jsonObject
            assertNotNull(globalData!!["items"])
            assertNotNull(globalData["count"])
            assertNull(globalData["tasks"])
            assertNull(globalData["taskCounts"])

            // Assert - scoped returns single object with tasks array
            val scopedResponse = scopedResult.jsonObject
            assertTrue(scopedResponse["success"]?.jsonPrimitive?.boolean == true)
            val scopedData = scopedResponse["data"]?.jsonObject
            assertNotNull(scopedData!!["tasks"])
            assertNotNull(scopedData["taskCounts"])
            assertNull(scopedData["items"])
            assertNull(scopedData["count"])
        }
    }

    @Nested
    inner class TokenMeasurementTests {
        /**
         * Measures the approximate token count of a JSON response.
         * Uses character count as a rough proxy (1 token ≈ 4 characters for JSON).
         */
        private fun measureTokens(jsonElement: JsonElement): Int {
            val jsonString = jsonElement.toString()
            return jsonString.length / 4  // Rough approximation
        }

        @Test
        fun `test token efficiency - scoped feature overview vs get with sections`() = runBlocking {
            // Setup - create feature with 10 sections and 20 tasks
            // Use realistic section content sizes (500+ chars each to simulate actual usage)
            val feature = mockFeature

            val sections = (1..10).map { i ->
                mockSection.copy(
                    id = UUID.randomUUID(),
                    entityType = EntityType.FEATURE,
                    entityId = featureId,
                    title = "Section $i - Implementation Details and Requirements",
                    content = buildString {
                        repeat(50) {
                            append("This is realistic section content for section $i with detailed implementation notes, ")
                            append("architectural decisions, code snippets, API documentation, examples, and usage patterns. ")
                        }
                    } // ~5000 chars each to simulate real sections
                )
            }

            val tasks = (1..20).map { i ->
                mockTask.copy(id = UUID.randomUUID(), title = "Task $i", featureId = featureId)
            }

            // Option A: Get with sections (old way)
            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            coEvery { mockTaskRepository.findByFeature(featureId, null, null, 20) } returns Result.Success(tasks)
            coEvery { mockTaskRepository.findByFeature(featureId, null, null, 100) } returns Result.Success(tasks)
            coEvery { mockSectionRepository.getSectionsForEntity(EntityType.FEATURE, featureId) } returns
                Result.Success(sections)

            val paramsA = buildJsonObject {
                put("operation", "get")
                put("containerType", "feature")
                put("id", featureId.toString())
                put("includeSections", true)
            }
            val responseA = tool.execute(paramsA, context)
            val tokensA = measureTokens(responseA)

            // Option B: Scoped overview (new way)
            val paramsB = buildJsonObject {
                put("operation", "overview")
                put("containerType", "feature")
                put("id", featureId.toString())
            }
            val responseB = tool.execute(paramsB, context)
            val tokensB = measureTokens(responseB)

            // Calculate reduction
            val reduction = ((tokensA - tokensB).toDouble() / tokensA) * 100

            // Log for documentation
            println("Token Efficiency Report:")
            println("  Get with sections: $tokensA tokens")
            println("  Scoped overview: $tokensB tokens")
            println("  Reduction: ${"%.1f".format(reduction)}%")

            // Assert
            assertTrue(reduction >= 85.0, "Token reduction was ${"%.1f".format(reduction)}%, expected >= 85%")
            assertTrue(tokensB < 1500, "Scoped overview used $tokensB tokens, expected < 1500")
        }

        @Test
        fun `test token efficiency - scoped overview with many tasks still efficient`() = runBlocking {
            // Setup - create feature with 50 tasks (stress test)
            val feature = mockFeature
            val tasks = (1..50).map { i ->
                mockTask.copy(
                    id = UUID.randomUUID(),
                    title = "Task $i - Implementation of feature component",
                    featureId = featureId,
                    status = when (i % 3) {
                        0 -> TaskStatus.COMPLETED
                        1 -> TaskStatus.IN_PROGRESS
                        else -> TaskStatus.PENDING
                    },
                    summary = "This is a realistic task summary describing what needs to be done for task $i"
                )
            }

            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            coEvery { mockTaskRepository.findByFeature(featureId, null, null, 20) } returns Result.Success(tasks)
            coEvery { mockTaskRepository.findByFeature(featureId, null, null, 100) } returns Result.Success(tasks)

            // Execute - scoped feature overview
            val params = buildJsonObject {
                put("operation", "overview")
                put("containerType", "feature")
                put("id", featureId.toString())
            }
            val response = tool.execute(params, context)
            val tokens = measureTokens(response)

            // Log for documentation
            println("Token Efficiency with 50 Tasks:")
            println("  Scoped overview: $tokens tokens")

            // Assert - should scale linearly with minimal task format
            // With 50 tasks and realistic data, expect ~3000-4000 tokens
            assertTrue(tokens < 5000, "Scoped overview used $tokens tokens, expected < 5000")

            // Verify response contains all 50 tasks
            val responseObj = response.jsonObject
            assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true)
            val data = responseObj["data"]?.jsonObject
            val tasksArray = data?.get("tasks")?.jsonArray
            assertEquals(50, tasksArray?.size)
        }

        @Test
        fun `test token efficiency - scoped vs global overview comparison`() = runBlocking {
            // Setup - create 10 features
            val features = (1..10).map { i ->
                mockFeature.copy(id = UUID.randomUUID(), name = "Feature $i")
            }

            // Create 10 tasks for the first feature
            val tasks = (1..10).map { i ->
                mockTask.copy(id = UUID.randomUUID(), title = "Task $i", featureId = features[0].id)
            }

            // Mock for global overview
            coEvery { mockFeatureRepository.findAll(100) } returns Result.Success(features)

            // Mock for scoped overview
            coEvery { mockFeatureRepository.getById(features[0].id) } returns Result.Success(features[0])
            coEvery { mockTaskRepository.findByFeature(features[0].id, null, null, 20) } returns Result.Success(tasks)
            coEvery { mockTaskRepository.findByFeature(features[0].id, null, null, 100) } returns Result.Success(tasks)

            // Execute - global overview (all features)
            val globalParams = buildJsonObject {
                put("operation", "overview")
                put("containerType", "feature")
            }
            val globalResponse = tool.execute(globalParams, context)
            val globalTokens = measureTokens(globalResponse)

            // Execute - scoped overview (one feature with 10 tasks)
            val scopedParams = buildJsonObject {
                put("operation", "overview")
                put("containerType", "feature")
                put("id", features[0].id.toString())
            }
            val scopedResponse = tool.execute(scopedParams, context)
            val scopedTokens = measureTokens(scopedResponse)

            // Log for documentation
            println("Token Comparison - Global vs Scoped:")
            println("  Global (10 features): $globalTokens tokens")
            println("  Scoped (1 feature + 10 tasks): $scopedTokens tokens")

            // Assert - both should be efficient (no section content)
            assertTrue(globalTokens < 2000, "Global overview used $globalTokens tokens")
            assertTrue(scopedTokens < 1500, "Scoped overview used $scopedTokens tokens")

            // Verify scoped provides more detail for similar token budget
            val globalData = globalResponse.jsonObject["data"]?.jsonObject
            val scopedData = scopedResponse.jsonObject["data"]?.jsonObject

            // Global has items array but no task details
            assertNotNull(globalData!!["items"])
            assertNull(globalData["tasks"])

            // Scoped has task details
            assertNotNull(scopedData!!["tasks"])
            assertNotNull(scopedData["taskCounts"])
        }

        @Test
        fun `test token reduction measurement report`() = runBlocking {
            // Setup - create realistic scenario (feature with 10 sections, 26 tasks)
            val feature = mockFeature

            val sections = (1..10).map { i ->
                mockSection.copy(
                    id = UUID.randomUUID(),
                    entityType = EntityType.FEATURE,
                    entityId = featureId,
                    title = "Section $i - Detailed Implementation Guide",
                    content = buildString {
                        repeat(50) {
                            append("This is realistic section content with detailed implementation notes, ")
                            append("architectural decisions, code examples, API specifications, usage patterns, ")
                            append("performance considerations, security requirements, and testing strategies. ")
                        }
                    } // ~5000 chars each to simulate real sections
                )
            }

            val tasks = (1..26).map { i ->
                mockTask.copy(
                    id = UUID.randomUUID(),
                    title = "Task $i - Implement feature component",
                    featureId = featureId,
                    summary = "Detailed task summary describing the implementation requirements for task $i",
                    status = when (i % 4) {
                        0 -> TaskStatus.COMPLETED
                        1 -> TaskStatus.IN_PROGRESS
                        2 -> TaskStatus.PENDING
                        else -> TaskStatus.DEFERRED
                    }
                )
            }

            // Set up mocks
            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)
            coEvery { mockTaskRepository.findByFeature(featureId, null, null, 20) } returns Result.Success(tasks)
            coEvery { mockTaskRepository.findByFeature(featureId, null, null, 100) } returns Result.Success(tasks)
            coEvery { mockSectionRepository.getSectionsForEntity(EntityType.FEATURE, featureId) } returns
                Result.Success(sections)

            // Method 1: Get with sections (full data)
            val params1 = buildJsonObject {
                put("operation", "get")
                put("containerType", "feature")
                put("id", featureId.toString())
                put("includeSections", true)
            }
            val response1 = tool.execute(params1, context)
            val tokens1 = measureTokens(response1)

            // Method 2: Get without sections (metadata only)
            val params2 = buildJsonObject {
                put("operation", "get")
                put("containerType", "feature")
                put("id", featureId.toString())
                put("includeSections", false)
            }
            val response2 = tool.execute(params2, context)
            val tokens2 = measureTokens(response2)

            // Method 3: Scoped overview (metadata + task list)
            val params3 = buildJsonObject {
                put("operation", "overview")
                put("containerType", "feature")
                put("id", featureId.toString())
            }
            val response3 = tool.execute(params3, context)
            val tokens3 = measureTokens(response3)

            // Calculate reductions
            val reduction1to3 = ((tokens1 - tokens3).toDouble() / tokens1) * 100
            val reduction2to3 = ((tokens2 - tokens3).toDouble() / tokens2) * 100

            // Print detailed report
            println("\n=== TOKEN REDUCTION MEASUREMENT REPORT ===")
            println("Scenario: Feature with 10 sections + 26 tasks")
            println()
            println("Method 1 - Get with sections (full data):")
            println("  Tokens: $tokens1")
            println("  Use case: When you need all section content")
            println()
            println("Method 2 - Get without sections (metadata only):")
            println("  Tokens: $tokens2")
            println("  Use case: When you only need container metadata")
            println()
            println("Method 3 - Scoped overview (metadata + task list):")
            println("  Tokens: $tokens3")
            println("  Use case: When you need container + child overview (sweet spot)")
            println()
            println("Token Reduction:")
            println("  Method 1 → Method 3: ${"%.1f".format(reduction1to3)}%")
            println("  Method 2 → Method 3: ${"%.1f".format(reduction2to3)}%")
            println()
            println("Conclusion: Scoped overview provides more context than metadata-only")
            println("            but uses ${reduction1to3.toInt()}% fewer tokens than full sections")
            println("==========================================\n")

            // Assert - verify scoped overview is the sweet spot
            assertTrue(tokens3 < tokens1, "Scoped overview should use fewer tokens than full get")
            assertTrue(tokens3 < 4000, "Scoped overview should be under 4000 tokens")
            assertTrue(reduction1to3 >= 85.0, "Should achieve >= 85% reduction vs full sections")

            // Verify scoped overview has more data than metadata-only
            val data2 = response2.jsonObject["data"]?.jsonObject
            val data3 = response3.jsonObject["data"]?.jsonObject

            assertNull(data2!!["tasks"], "Metadata-only should not have tasks")
            assertNotNull(data3!!["tasks"], "Scoped overview should have tasks")
            assertNotNull(data3["taskCounts"], "Scoped overview should have task counts")
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
