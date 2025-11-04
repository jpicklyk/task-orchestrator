package io.github.jpicklyk.mcptask.application.tools.tag

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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.*

class GetTagUsageToolTest {

    private lateinit var tool: GetTagUsageTool
    private lateinit var context: ToolExecutionContext
    private lateinit var taskRepository: TaskRepository
    private lateinit var featureRepository: FeatureRepository
    private lateinit var projectRepository: ProjectRepository
    private lateinit var templateRepository: TemplateRepository
    private lateinit var repositoryProvider: RepositoryProvider

    @BeforeEach
    fun setUp() {
        tool = GetTagUsageTool()
        taskRepository = mockk()
        featureRepository = mockk()
        projectRepository = mockk()
        templateRepository = mockk()
        repositoryProvider = mockk()

        every { repositoryProvider.featureRepository() } returns featureRepository
        every { repositoryProvider.projectRepository() } returns projectRepository
        every { repositoryProvider.templateRepository() } returns templateRepository

        context = mockk {
            every { taskRepository() } returns taskRepository
            every { this@mockk.repositoryProvider } returns this@GetTagUsageToolTest.repositoryProvider
        }
    }

    @Test
    fun `should validate tag parameter is required`() {
        val params = buildJsonObject { }

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("tag"))
    }

    @Test
    fun `should validate tag cannot be empty`() {
        val params = buildJsonObject {
            put("tag", "")
        }

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("empty"))
    }

    @Test
    fun `should validate entity types`() {
        val params = buildJsonObject {
            put("tag", "test")
            put("entityTypes", "INVALID,TASK")
        }

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Invalid entity types"))
    }

    @Test
    fun `should accept valid parameters`() {
        val params = buildJsonObject {
            put("tag", "test")
            put("entityTypes", "TASK,FEATURE")
        }

        assertDoesNotThrow {
            tool.validateParams(params)
        }
    }

    @Test
    fun `should find tasks with tag`() = runBlocking {
        val testTag = "authentication"
        val task1 = createTestTask(tags = listOf("authentication", "security"))
        val task2 = createTestTask(tags = listOf("api", "authentication"))
        val task3 = createTestTask(tags = listOf("ui", "frontend"))

        coEvery { taskRepository.findAll(any()) } returns Result.Success(listOf(task1, task2, task3))
        coEvery { featureRepository.findAll(any()) } returns Result.Success(emptyList())
        coEvery { projectRepository.findAll(any()) } returns Result.Success(emptyList())
        coEvery { templateRepository.getAllTemplates() } returns Result.Success(emptyList())

        val params = buildJsonObject {
            put("tag", testTag)
        }

        val result = tool.execute(params, context) as JsonObject

        assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
        val data = result["data"]!!.jsonObject
        assertEquals(testTag, data["tag"]?.jsonPrimitive?.content)
        assertEquals(2, data["totalCount"]?.jsonPrimitive?.int)

        val entities = data["entities"]!!.jsonObject
        val tasks = entities["TASK"]!!.jsonArray
        assertEquals(2, tasks.size)
    }

    @Test
    fun `should find features with tag`() = runBlocking {
        val testTag = "api"
        val feature1 = createTestFeature(tags = listOf("api", "rest"))
        val feature2 = createTestFeature(tags = listOf("frontend"))

        coEvery { taskRepository.findAll(any()) } returns Result.Success(emptyList())
        coEvery { featureRepository.findAll(any()) } returns Result.Success(listOf(feature1, feature2))
        coEvery { projectRepository.findAll(any()) } returns Result.Success(emptyList())
        coEvery { templateRepository.getAllTemplates() } returns Result.Success(emptyList())

        val params = buildJsonObject {
            put("tag", testTag)
        }

        val result = tool.execute(params, context) as JsonObject

        assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
        val data = result["data"]!!.jsonObject
        assertEquals(1, data["totalCount"]?.jsonPrimitive?.int)

        val entities = data["entities"]!!.jsonObject
        val features = entities["FEATURE"]!!.jsonArray
        assertEquals(1, features.size)
    }

    @Test
    fun `should find projects with tag`() = runBlocking {
        val testTag = "infrastructure"
        val project1 = createTestProject(tags = listOf("infrastructure", "core"))

        coEvery { taskRepository.findAll(any()) } returns Result.Success(emptyList())
        coEvery { featureRepository.findAll(any()) } returns Result.Success(emptyList())
        coEvery { projectRepository.findAll(any()) } returns Result.Success(listOf(project1))
        coEvery { templateRepository.getAllTemplates() } returns Result.Success(emptyList())

        val params = buildJsonObject {
            put("tag", testTag)
        }

        val result = tool.execute(params, context) as JsonObject

        assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
        val data = result["data"]!!.jsonObject
        assertEquals(1, data["totalCount"]?.jsonPrimitive?.int)

        val entities = data["entities"]!!.jsonObject
        val projects = entities["PROJECT"]!!.jsonArray
        assertEquals(1, projects.size)
    }

    @Test
    fun `should find templates with tag`() = runBlocking {
        val testTag = "workflow"
        val template1 = createTestTemplate(tags = listOf("workflow", "git"))

        coEvery { taskRepository.findAll(any()) } returns Result.Success(emptyList())
        coEvery { featureRepository.findAll(any()) } returns Result.Success(emptyList())
        coEvery { projectRepository.findAll(any()) } returns Result.Success(emptyList())
        coEvery { templateRepository.getAllTemplates() } returns Result.Success(listOf(template1))

        val params = buildJsonObject {
            put("tag", testTag)
        }

        val result = tool.execute(params, context) as JsonObject

        assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
        val data = result["data"]!!.jsonObject
        assertEquals(1, data["totalCount"]?.jsonPrimitive?.int)

        val entities = data["entities"]!!.jsonObject
        val templates = entities["TEMPLATE"]!!.jsonArray
        assertEquals(1, templates.size)
    }

    @Test
    fun `should be case insensitive`() = runBlocking {
        val task1 = createTestTask(tags = listOf("API"))
        val task2 = createTestTask(tags = listOf("api"))
        val task3 = createTestTask(tags = listOf("Api"))

        coEvery { taskRepository.findAll(any()) } returns Result.Success(listOf(task1, task2, task3))
        coEvery { featureRepository.findAll(any()) } returns Result.Success(emptyList())
        coEvery { projectRepository.findAll(any()) } returns Result.Success(emptyList())
        coEvery { templateRepository.getAllTemplates() } returns Result.Success(emptyList())

        val params = buildJsonObject {
            put("tag", "api")
        }

        val result = tool.execute(params, context) as JsonObject
        val data = result["data"]!!.jsonObject
        assertEquals(3, data["totalCount"]?.jsonPrimitive?.int)
    }

    @Test
    fun `should filter by entity types`() = runBlocking {
        val task1 = createTestTask(tags = listOf("api"))
        val feature1 = createTestFeature(tags = listOf("api"))

        coEvery { taskRepository.findAll(any()) } returns Result.Success(listOf(task1))
        coEvery { featureRepository.findAll(any()) } returns Result.Success(listOf(feature1))

        val params = buildJsonObject {
            put("tag", "api")
            put("entityTypes", "TASK")
        }

        val result = tool.execute(params, context) as JsonObject

        val data = result["data"]!!.jsonObject
        assertEquals(1, data["totalCount"]?.jsonPrimitive?.int)

        val entities = data["entities"]!!.jsonObject
        assertTrue(entities.containsKey("TASK"))
        assertFalse(entities.containsKey("FEATURE"))
    }

    @Test
    fun `should handle no entities found`() = runBlocking {
        coEvery { taskRepository.findAll(any()) } returns Result.Success(emptyList())
        coEvery { featureRepository.findAll(any()) } returns Result.Success(emptyList())
        coEvery { projectRepository.findAll(any()) } returns Result.Success(emptyList())
        coEvery { templateRepository.getAllTemplates() } returns Result.Success(emptyList())

        val params = buildJsonObject {
            put("tag", "nonexistent")
        }

        val result = tool.execute(params, context) as JsonObject

        assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
        val message = result["message"]?.jsonPrimitive?.content
        assertTrue(message!!.contains("No entities found"))

        val data = result["data"]!!.jsonObject
        assertEquals(0, data["totalCount"]?.jsonPrimitive?.int)
    }

    @Test
    fun `should handle repository errors gracefully`() = runBlocking {
        coEvery { taskRepository.findAll(any()) } returns Result.Error(RepositoryError.DatabaseError("Database error"))
        coEvery { featureRepository.findAll(any()) } returns Result.Success(emptyList())
        coEvery { projectRepository.findAll(any()) } returns Result.Success(emptyList())
        coEvery { templateRepository.getAllTemplates() } returns Result.Success(emptyList())

        val params = buildJsonObject {
            put("tag", "test")
        }

        val result = tool.execute(params, context) as JsonObject

        // Should succeed but with zero tasks (error logged, not thrown)
        assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
        val data = result["data"]!!.jsonObject
        assertEquals(0, data["totalCount"]?.jsonPrimitive?.int)
    }

    @Test
    fun `should find entities across all types`() = runBlocking {
        val task = createTestTask(tags = listOf("common"))
        val feature = createTestFeature(tags = listOf("common"))
        val project = createTestProject(tags = listOf("common"))
        val template = createTestTemplate(tags = listOf("common"))

        coEvery { taskRepository.findAll(any()) } returns Result.Success(listOf(task))
        coEvery { featureRepository.findAll(any()) } returns Result.Success(listOf(feature))
        coEvery { projectRepository.findAll(any()) } returns Result.Success(listOf(project))
        coEvery { templateRepository.getAllTemplates() } returns Result.Success(listOf(template))

        val params = buildJsonObject {
            put("tag", "common")
        }

        val result = tool.execute(params, context) as JsonObject

        assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
        val data = result["data"]!!.jsonObject
        assertEquals(4, data["totalCount"]?.jsonPrimitive?.int)

        val entities = data["entities"]!!.jsonObject
        assertEquals(1, entities["TASK"]!!.jsonArray.size)
        assertEquals(1, entities["FEATURE"]!!.jsonArray.size)
        assertEquals(1, entities["PROJECT"]!!.jsonArray.size)
        assertEquals(1, entities["TEMPLATE"]!!.jsonArray.size)
    }

    // Helper functions
    private fun createTestTask(
        id: UUID = UUID.randomUUID(),
        title: String = "Test Task",
        tags: List<String> = emptyList()
    ): Task {
        return Task(
            id = id,
            title = title,
            summary = "Test summary",
            status = TaskStatus.PENDING,
            priority = Priority.MEDIUM,
            complexity = 5,
            tags = tags,
            featureId = null,
            projectId = null,
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )
    }

    private fun createTestFeature(
        id: UUID = UUID.randomUUID(),
        name: String = "Test Feature",
        tags: List<String> = emptyList()
    ): Feature {
        return Feature(
            id = id,
            name = name,
            summary = "Test summary",
            status = FeatureStatus.PLANNING,
            priority = Priority.MEDIUM,
            tags = tags,
            projectId = null,
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )
    }

    private fun createTestProject(
        id: UUID = UUID.randomUUID(),
        name: String = "Test Project",
        tags: List<String> = emptyList()
    ): Project {
        return Project(
            id = id,
            name = name,
            summary = "Test summary",
            status = ProjectStatus.PLANNING,
            tags = tags,
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )
    }

    private fun createTestTemplate(
        id: UUID = UUID.randomUUID(),
        name: String = "Test Template",
        tags: List<String> = emptyList()
    ): Template {
        return Template(
            id = id,
            name = name,
            description = "Test description",
            targetEntityType = EntityType.TASK,
            isBuiltIn = false,
            isProtected = false,
            isEnabled = true,
            createdBy = null,
            tags = tags,
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )
    }
}
