package io.github.jpicklyk.mcptask.application.tools.tag

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.repository.*
import io.github.jpicklyk.mcptask.infrastructure.repository.RepositoryProvider
import io.mockk.coEvery
import io.mockk.coVerify
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

class RenameTagToolTest {

    private lateinit var tool: RenameTagTool
    private lateinit var context: ToolExecutionContext
    private lateinit var taskRepository: TaskRepository
    private lateinit var featureRepository: FeatureRepository
    private lateinit var projectRepository: ProjectRepository
    private lateinit var templateRepository: TemplateRepository
    private lateinit var repositoryProvider: RepositoryProvider

    @BeforeEach
    fun setUp() {
        tool = RenameTagTool()
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
            every { this@mockk.repositoryProvider } returns this@RenameTagToolTest.repositoryProvider
        }
    }

    @Test
    fun `should validate oldTag is required`() {
        val params = buildJsonObject {
            put("newTag", "new")
        }

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("oldTag"))
    }

    @Test
    fun `should validate newTag is required`() {
        val params = buildJsonObject {
            put("oldTag", "old")
        }

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("newTag"))
    }

    @Test
    fun `should validate oldTag cannot be empty`() {
        val params = buildJsonObject {
            put("oldTag", "")
            put("newTag", "new")
        }

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("empty"))
    }

    @Test
    fun `should validate newTag cannot be empty`() {
        val params = buildJsonObject {
            put("oldTag", "old")
            put("newTag", "")
        }

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("empty"))
    }

    @Test
    fun `should validate tags are different`() {
        val params = buildJsonObject {
            put("oldTag", "same")
            put("newTag", "same")
        }

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("cannot be the same"))
    }

    @Test
    fun `should validate tags are different case insensitive`() {
        val params = buildJsonObject {
            put("oldTag", "TAG")
            put("newTag", "tag")
        }

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("cannot be the same"))
    }

    @Test
    fun `should accept valid parameters`() {
        val params = buildJsonObject {
            put("oldTag", "old")
            put("newTag", "new")
        }

        assertDoesNotThrow {
            tool.validateParams(params)
        }
    }

    @Test
    fun `should rename tag in tasks`() = runBlocking {
        val task = createTestTask(tags = listOf("old", "other"))
        val updatedTask = task.copy(tags = listOf("new", "other"))

        coEvery { taskRepository.findAll(any()) } returns Result.Success(listOf(task))
        coEvery { taskRepository.update(updatedTask) } returns Result.Success(updatedTask)
        coEvery { featureRepository.findAll(any()) } returns Result.Success(emptyList())
        coEvery { projectRepository.findAll(any()) } returns Result.Success(emptyList())
        coEvery { templateRepository.getAllTemplates() } returns Result.Success(emptyList())

        val params = buildJsonObject {
            put("oldTag", "old")
            put("newTag", "new")
        }

        val result = tool.execute(params, context) as JsonObject

        assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
        val data = result["data"]!!.jsonObject
        assertEquals(1, data["totalUpdated"]?.jsonPrimitive?.int)
        assertEquals(0, data["failedUpdates"]?.jsonPrimitive?.int)

        coVerify(exactly = 1) { taskRepository.update(any()) }
    }

    @Test
    fun `should rename tag in features`() = runBlocking {
        val feature = createTestFeature(tags = listOf("old", "other"))
        val updatedFeature = feature.copy(tags = listOf("new", "other"))

        coEvery { taskRepository.findAll(any()) } returns Result.Success(emptyList())
        coEvery { featureRepository.findAll(any()) } returns Result.Success(listOf(feature))
        coEvery { featureRepository.update(updatedFeature) } returns Result.Success(updatedFeature)
        coEvery { projectRepository.findAll(any()) } returns Result.Success(emptyList())
        coEvery { templateRepository.getAllTemplates() } returns Result.Success(emptyList())

        val params = buildJsonObject {
            put("oldTag", "old")
            put("newTag", "new")
        }

        val result = tool.execute(params, context) as JsonObject

        assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
        val data = result["data"]!!.jsonObject
        assertEquals(1, data["totalUpdated"]?.jsonPrimitive?.int)

        coVerify(exactly = 1) { featureRepository.update(any()) }
    }

    @Test
    fun `should rename tag in projects`() = runBlocking {
        val project = createTestProject(tags = listOf("old"))
        val updatedProject = project.copy(tags = listOf("new"))

        coEvery { taskRepository.findAll(any()) } returns Result.Success(emptyList())
        coEvery { featureRepository.findAll(any()) } returns Result.Success(emptyList())
        coEvery { projectRepository.findAll(any()) } returns Result.Success(listOf(project))
        coEvery { projectRepository.update(updatedProject) } returns Result.Success(updatedProject)
        coEvery { templateRepository.getAllTemplates() } returns Result.Success(emptyList())

        val params = buildJsonObject {
            put("oldTag", "old")
            put("newTag", "new")
        }

        val result = tool.execute(params, context) as JsonObject

        assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
        val data = result["data"]!!.jsonObject
        assertEquals(1, data["totalUpdated"]?.jsonPrimitive?.int)

        coVerify(exactly = 1) { projectRepository.update(any()) }
    }

    @Test
    fun `should rename tag in templates`() = runBlocking {
        val template = createTestTemplate(tags = listOf("old"))
        val updatedTemplate = template.copy(tags = listOf("new"))

        coEvery { taskRepository.findAll(any()) } returns Result.Success(emptyList())
        coEvery { featureRepository.findAll(any()) } returns Result.Success(emptyList())
        coEvery { projectRepository.findAll(any()) } returns Result.Success(emptyList())
        coEvery { templateRepository.getAllTemplates() } returns Result.Success(listOf(template))
        coEvery { templateRepository.updateTemplate(updatedTemplate) } returns Result.Success(updatedTemplate)

        val params = buildJsonObject {
            put("oldTag", "old")
            put("newTag", "new")
        }

        val result = tool.execute(params, context) as JsonObject

        assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
        val data = result["data"]!!.jsonObject
        assertEquals(1, data["totalUpdated"]?.jsonPrimitive?.int)

        coVerify(exactly = 1) { templateRepository.updateTemplate(any()) }
    }

    @Test
    fun `should be case insensitive when matching`() = runBlocking {
        val task = createTestTask(tags = listOf("OLD", "other"))
        val updatedTask = task.copy(tags = listOf("new", "other"))

        coEvery { taskRepository.findAll(any()) } returns Result.Success(listOf(task))
        coEvery { taskRepository.update(updatedTask) } returns Result.Success(updatedTask)
        coEvery { featureRepository.findAll(any()) } returns Result.Success(emptyList())
        coEvery { projectRepository.findAll(any()) } returns Result.Success(emptyList())
        coEvery { templateRepository.getAllTemplates() } returns Result.Success(emptyList())

        val params = buildJsonObject {
            put("oldTag", "old")
            put("newTag", "new")
        }

        val result = tool.execute(params, context) as JsonObject

        assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
        coVerify(exactly = 1) { taskRepository.update(any()) }
    }

    @Test
    fun `should handle entity already has new tag`() = runBlocking {
        val task = createTestTask(tags = listOf("old", "new", "other"))
        // Should result in ["new", "other"] - old removed, new kept
        val updatedTask = task.copy(tags = listOf("new", "other"))

        coEvery { taskRepository.findAll(any()) } returns Result.Success(listOf(task))
        coEvery { taskRepository.update(updatedTask) } returns Result.Success(updatedTask)
        coEvery { featureRepository.findAll(any()) } returns Result.Success(emptyList())
        coEvery { projectRepository.findAll(any()) } returns Result.Success(emptyList())
        coEvery { templateRepository.getAllTemplates() } returns Result.Success(emptyList())

        val params = buildJsonObject {
            put("oldTag", "old")
            put("newTag", "new")
        }

        val result = tool.execute(params, context) as JsonObject

        assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
        coVerify(exactly = 1) { taskRepository.update(any()) }
    }

    @Test
    fun `should filter by entity types`() = runBlocking {
        val task = createTestTask(tags = listOf("old"))
        val feature = createTestFeature(tags = listOf("old"))

        coEvery { taskRepository.findAll(any()) } returns Result.Success(listOf(task))
        coEvery { taskRepository.update(any()) } returns Result.Success(task)

        val params = buildJsonObject {
            put("oldTag", "old")
            put("newTag", "new")
            put("entityTypes", "TASK")
        }

        val result = tool.execute(params, context) as JsonObject

        assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
        val data = result["data"]!!.jsonObject
        assertEquals(1, data["totalUpdated"]?.jsonPrimitive?.int)

        // Only task repository should be queried
        coVerify(exactly = 1) { taskRepository.update(any()) }
        coVerify(exactly = 0) { featureRepository.findAll(any()) }
    }

    @Test
    fun `should support dry run mode`() = runBlocking {
        val task = createTestTask(tags = listOf("old"))

        coEvery { taskRepository.findAll(any()) } returns Result.Success(listOf(task))
        coEvery { featureRepository.findAll(any()) } returns Result.Success(emptyList())
        coEvery { projectRepository.findAll(any()) } returns Result.Success(emptyList())
        coEvery { templateRepository.getAllTemplates() } returns Result.Success(emptyList())

        val params = buildJsonObject {
            put("oldTag", "old")
            put("newTag", "new")
            put("dryRun", true)
        }

        val result = tool.execute(params, context) as JsonObject

        assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
        val data = result["data"]!!.jsonObject
        assertEquals(1, data["totalUpdated"]?.jsonPrimitive?.int)
        assertEquals(true, data["dryRun"]?.jsonPrimitive?.boolean)

        val message = result["message"]?.jsonPrimitive?.content
        assertTrue(message!!.contains("dry run"))

        // No updates should have been called
        coVerify(exactly = 0) { taskRepository.update(any()) }
    }

    @Test
    fun `should handle update failures`() = runBlocking {
        val task1 = createTestTask(tags = listOf("old"))
        val task2 = createTestTask(tags = listOf("old"))

        coEvery { taskRepository.findAll(any()) } returns Result.Success(listOf(task1, task2))
        coEvery { taskRepository.update(task1.copy(tags = listOf("new"))) } returns Result.Success(task1)
        coEvery { taskRepository.update(task2.copy(tags = listOf("new"))) } returns Result.Error(RepositoryError.DatabaseError("Database error"))
        coEvery { featureRepository.findAll(any()) } returns Result.Success(emptyList())
        coEvery { projectRepository.findAll(any()) } returns Result.Success(emptyList())
        coEvery { templateRepository.getAllTemplates() } returns Result.Success(emptyList())

        val params = buildJsonObject {
            put("oldTag", "old")
            put("newTag", "new")
        }

        val result = tool.execute(params, context) as JsonObject

        assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
        val data = result["data"]!!.jsonObject
        assertEquals(1, data["totalUpdated"]?.jsonPrimitive?.int)
        assertEquals(1, data["failedUpdates"]?.jsonPrimitive?.int)

        val message = result["message"]?.jsonPrimitive?.content
        assertTrue(message!!.contains("failed"))
    }

    @Test
    fun `should rename across multiple entity types`() = runBlocking {
        val task = createTestTask(tags = listOf("old"))
        val feature = createTestFeature(tags = listOf("old"))
        val project = createTestProject(tags = listOf("old"))

        coEvery { taskRepository.findAll(any()) } returns Result.Success(listOf(task))
        coEvery { taskRepository.update(any()) } returns Result.Success(task)
        coEvery { featureRepository.findAll(any()) } returns Result.Success(listOf(feature))
        coEvery { featureRepository.update(any()) } returns Result.Success(feature)
        coEvery { projectRepository.findAll(any()) } returns Result.Success(listOf(project))
        coEvery { projectRepository.update(any()) } returns Result.Success(project)
        coEvery { templateRepository.getAllTemplates() } returns Result.Success(emptyList())

        val params = buildJsonObject {
            put("oldTag", "old")
            put("newTag", "new")
        }

        val result = tool.execute(params, context) as JsonObject

        assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
        val data = result["data"]!!.jsonObject
        assertEquals(3, data["totalUpdated"]?.jsonPrimitive?.int)

        val byEntityType = data["byEntityType"]!!.jsonObject
        assertEquals(1, byEntityType["TASK"]?.jsonPrimitive?.int)
        assertEquals(1, byEntityType["FEATURE"]?.jsonPrimitive?.int)
        assertEquals(1, byEntityType["PROJECT"]?.jsonPrimitive?.int)
    }

    @Test
    fun `should handle no entities with tag`() = runBlocking {
        coEvery { taskRepository.findAll(any()) } returns Result.Success(emptyList())
        coEvery { featureRepository.findAll(any()) } returns Result.Success(emptyList())
        coEvery { projectRepository.findAll(any()) } returns Result.Success(emptyList())
        coEvery { templateRepository.getAllTemplates() } returns Result.Success(emptyList())

        val params = buildJsonObject {
            put("oldTag", "nonexistent")
            put("newTag", "new")
        }

        val result = tool.execute(params, context) as JsonObject

        assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
        val data = result["data"]!!.jsonObject
        assertEquals(0, data["totalUpdated"]?.jsonPrimitive?.int)

        val message = result["message"]?.jsonPrimitive?.content
        assertTrue(message!!.contains("No entities found"))
    }

    @Test
    fun `should preserve tag order when renaming`() = runBlocking {
        val task = createTestTask(tags = listOf("first", "old", "last"))
        val expectedTags = listOf("first", "new", "last")
        val updatedTask = task.copy(tags = expectedTags)

        coEvery { taskRepository.findAll(any()) } returns Result.Success(listOf(task))
        coEvery { taskRepository.update(updatedTask) } returns Result.Success(updatedTask)
        coEvery { featureRepository.findAll(any()) } returns Result.Success(emptyList())
        coEvery { projectRepository.findAll(any()) } returns Result.Success(emptyList())
        coEvery { templateRepository.getAllTemplates() } returns Result.Success(emptyList())

        val params = buildJsonObject {
            put("oldTag", "old")
            put("newTag", "new")
        }

        val result = tool.execute(params, context) as JsonObject

        assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
        coVerify(exactly = 1) { taskRepository.update(match { it.tags == expectedTags }) }
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
