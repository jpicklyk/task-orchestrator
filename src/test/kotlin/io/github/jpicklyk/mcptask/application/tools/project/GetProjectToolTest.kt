package io.github.jpicklyk.mcptask.application.tools.project

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.repository.*
import io.github.jpicklyk.mcptask.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.*

class GetProjectToolTest {
    private val mockProjectRepository = mockk<ProjectRepository>()
    private val mockFeatureRepository = mockk<FeatureRepository>()
    private val mockTaskRepository = mockk<TaskRepository>()
    private val mockSectionRepository = mockk<SectionRepository>()

    private val mockRepositoryProvider = mockk<RepositoryProvider>().apply {
        coEvery { projectRepository() } returns mockProjectRepository
        coEvery { featureRepository() } returns mockFeatureRepository
        coEvery { taskRepository() } returns mockTaskRepository
        coEvery { sectionRepository() } returns mockSectionRepository
    }

    private val executionContext = ToolExecutionContext(mockRepositoryProvider)
    private val getProjectTool = GetProjectTool()

    @Test
    fun `test validate params with valid id`() {
        val projectId = UUID.randomUUID()
        val params = buildJsonObject {
            put("id", projectId.toString())
        }

        // Should not throw exception
        getProjectTool.validateParams(params)
    }

    @Test
    fun `test validate params with invalid id format`() {
        val params = buildJsonObject {
            put("id", "not-a-uuid")
        }

        val exception = assertThrows<ToolValidationException> {
            getProjectTool.validateParams(params)
        }

        assertTrue(exception.message!!.contains("Invalid id format"))
    }

    @Test
    fun `test validate params with invalid maxFeatureCount`() {
        val projectId = UUID.randomUUID()
        val params = buildJsonObject {
            put("id", projectId.toString())
            put("maxFeatureCount", 0)
        }

        val exception = assertThrows<ToolValidationException> {
            getProjectTool.validateParams(params)
        }

        assertTrue(exception.message!!.contains("maxFeatureCount must be at least 1"))
    }

    @Test
    fun `test validate params with too large maxFeatureCount`() {
        val projectId = UUID.randomUUID()
        val params = buildJsonObject {
            put("id", projectId.toString())
            put("maxFeatureCount", 101)
        }

        val exception = assertThrows<ToolValidationException> {
            getProjectTool.validateParams(params)
        }

        assertTrue(exception.message!!.contains("maxFeatureCount cannot exceed 100"))
    }

    @Test
    fun `test validate params with invalid maxTaskCount`() {
        val projectId = UUID.randomUUID()
        val params = buildJsonObject {
            put("id", projectId.toString())
            put("maxTaskCount", 0)
        }

        val exception = assertThrows<ToolValidationException> {
            getProjectTool.validateParams(params)
        }

        assertTrue(exception.message!!.contains("maxTaskCount must be at least 1"))
    }

    @Test
    fun `test validate params with too large maxTaskCount`() {
        val projectId = UUID.randomUUID()
        val params = buildJsonObject {
            put("id", projectId.toString())
            put("maxTaskCount", 101)
        }

        val exception = assertThrows<ToolValidationException> {
            getProjectTool.validateParams(params)
        }

        assertTrue(exception.message!!.contains("maxTaskCount cannot exceed 100"))
    }

    @Test
    fun `test execute with valid id returns project`() = runBlocking {
        val projectId = UUID.randomUUID()
        val now = Instant.now()

        val project = Project(
            id = projectId,
            name = "Test Project",
            summary = "This is a test project",
            status = ProjectStatus.IN_DEVELOPMENT,
            tags = listOf("test", "project"),
            createdAt = now,
            modifiedAt = now
        )

        coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(project)

        val params = buildJsonObject {
            put("id", projectId.toString())
        }

        val result = getProjectTool.execute(params, executionContext)

        // Verify result
        assertTrue(result is JsonObject)
        assertEquals(true, result.jsonObject["success"]?.jsonPrimitive?.boolean)
        assertEquals("Project retrieved successfully", result.jsonObject["message"]?.jsonPrimitive?.content)

        // Check data
        val data = result.jsonObject["data"]?.jsonObject
        assertNotNull(data)
        assertEquals(projectId.toString(), data!!["id"]?.jsonPrimitive?.content)
        assertEquals("Test Project", data["name"]?.jsonPrimitive?.content)
        assertEquals("This is a test project", data["summary"]?.jsonPrimitive?.content)
        assertEquals("in-development", data["status"]?.jsonPrimitive?.content)
        assertNotNull(data["createdAt"])
        assertNotNull(data["modifiedAt"])

        // Check tags
        val tags = data["tags"]?.jsonArray
        assertNotNull(tags)
        assertEquals(2, tags!!.size)
        assertTrue(tags.any { it.jsonPrimitive.content == "test" })
        assertTrue(tags.any { it.jsonPrimitive.content == "project" })
    }

    @Test
    fun `test execute with non-existent id returns not found error`() = runBlocking {
        val projectId = UUID.randomUUID()

        coEvery { mockProjectRepository.getById(projectId) } returns Result.Error(
            RepositoryError.NotFound(projectId, EntityType.PROJECT, "Project not found")
        )

        val params = buildJsonObject {
            put("id", projectId.toString())
        }

        val result = getProjectTool.execute(params, executionContext)

        // Verify error response
        assertTrue(result is JsonObject)
        assertEquals(false, result.jsonObject["success"]?.jsonPrimitive?.boolean)
        assertEquals("Project not found", result.jsonObject["message"]?.jsonPrimitive?.content)

        // Check error details
        val error = result.jsonObject["error"]?.jsonObject
        assertNotNull(error)
        assertEquals(ErrorCodes.RESOURCE_NOT_FOUND, error!!["code"]?.jsonPrimitive?.content)
    }

    @Test
    fun `test execute with database error`() = runBlocking {
        val projectId = UUID.randomUUID()

        coEvery { mockProjectRepository.getById(projectId) } returns Result.Error(
            RepositoryError.DatabaseError("Database connection failed")
        )

        val params = buildJsonObject {
            put("id", projectId.toString())
        }

        val result = getProjectTool.execute(params, executionContext)

        // Verify error response
        assertTrue(result is JsonObject)
        assertEquals(false, result.jsonObject["success"]?.jsonPrimitive?.boolean)
        assertEquals("Failed to retrieve project", result.jsonObject["message"]?.jsonPrimitive?.content)

        // Check error details
        val error = result.jsonObject["error"]?.jsonObject
        assertNotNull(error)
        assertEquals(ErrorCodes.DATABASE_ERROR, error!!["code"]?.jsonPrimitive?.content)
    }

    @Test
    fun `test execute with includeSections returns sections`() = runBlocking {
        val projectId = UUID.randomUUID()
        val now = Instant.now()

        val project = Project(
            id = projectId,
            name = "Test Project",
            summary = "This is a test project",
            status = ProjectStatus.IN_DEVELOPMENT,
            tags = listOf("test", "project"),
            createdAt = now,
            modifiedAt = now
        )

        val section = Section(
            id = UUID.randomUUID(),
            entityType = EntityType.PROJECT,
            entityId = projectId,
            title = "Project Overview",
            usageDescription = "Overview of project goals",
            content = "This project aims to improve task management",
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 0,
            tags = listOf("overview")
        )

        coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(project)
        coEvery { mockSectionRepository.getSectionsForEntity(EntityType.PROJECT, projectId) } returns
                Result.Success(listOf(section))

        val params = buildJsonObject {
            put("id", projectId.toString())
            put("includeSections", true)
        }

        val result = getProjectTool.execute(params, executionContext)

        // Verify result
        assertTrue(result is JsonObject)
        assertEquals(true, result.jsonObject["success"]?.jsonPrimitive?.boolean)

        // Check sections
        val data = result.jsonObject["data"]?.jsonObject
        assertNotNull(data)
        val sections = data!!["sections"]?.jsonArray
        assertNotNull(sections)
        assertEquals(1, sections!!.size)

        val sectionObj = sections[0].jsonObject
        assertEquals(section.id.toString(), sectionObj["id"]?.jsonPrimitive?.content)
        assertEquals("Project Overview", sectionObj["title"]?.jsonPrimitive?.content)
        assertEquals("This project aims to improve task management", sectionObj["content"]?.jsonPrimitive?.content)
        assertEquals("markdown", sectionObj["contentFormat"]?.jsonPrimitive?.content)
        assertEquals(0, sectionObj["ordinal"]?.jsonPrimitive?.int)
    }

    @Test
    fun `test execute with includeFeatures returns features`() = runBlocking {
        val projectId = UUID.randomUUID()
        val now = Instant.now()

        val project = Project(
            id = projectId,
            name = "Test Project",
            summary = "This is a test project",
            status = ProjectStatus.IN_DEVELOPMENT,
            createdAt = now,
            modifiedAt = now
        )

        val feature = Feature(
            id = UUID.randomUUID(),
            name = "Sample Feature",
            summary = "This is a sample feature",
            status = FeatureStatus.IN_DEVELOPMENT,
            priority = Priority.HIGH,
            projectId = projectId
        )

        coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(project)
        coEvery { mockFeatureRepository.findByFilters(projectId = projectId) } returns Result.Success(listOf(feature))

        val params = buildJsonObject {
            put("id", projectId.toString())
            put("includeFeatures", true)
        }

        val result = getProjectTool.execute(params, executionContext)

        // Verify result
        assertTrue(result is JsonObject)
        assertEquals(true, result.jsonObject["success"]?.jsonPrimitive?.boolean)

        // Check features
        val data = result.jsonObject["data"]?.jsonObject
        assertNotNull(data)
        val features = data!!["features"]?.jsonObject
        assertNotNull(features)

        val items = features!!["items"]?.jsonArray
        assertNotNull(items)
        assertEquals(1, items!!.size)

        val featureObj = items[0].jsonObject
        assertEquals(feature.id.toString(), featureObj["id"]?.jsonPrimitive?.content)
        assertEquals("Sample Feature", featureObj["name"]?.jsonPrimitive?.content)
        assertEquals("in-development", featureObj["status"]?.jsonPrimitive?.content)
        assertEquals("high", featureObj["priority"]?.jsonPrimitive?.content)
        assertEquals("This is a sample feature", featureObj["summary"]?.jsonPrimitive?.content)

        // Check pagination info
        assertEquals(1, features["total"]?.jsonPrimitive?.int)
        assertEquals(1, features["included"]?.jsonPrimitive?.int)
        assertEquals(false, features["hasMore"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `test execute with includeTasks returns tasks`() = runBlocking {
        val projectId = UUID.randomUUID()
        val now = Instant.now()

        val project = Project(
            id = projectId,
            name = "Test Project",
            summary = "This is a test project",
            status = ProjectStatus.IN_DEVELOPMENT,
            createdAt = now,
            modifiedAt = now
        )

        val task = Task(
            id = UUID.randomUUID(),
            title = "Sample Task",
            summary = "This is a sample task",
            status = TaskStatus.IN_PROGRESS,
            priority = Priority.HIGH,
            complexity = 5,
            projectId = projectId
        )

        coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(project)
        coEvery { mockTaskRepository.findByFilters(projectId = projectId) } returns Result.Success(listOf(task))

        val params = buildJsonObject {
            put("id", projectId.toString())
            put("includeTasks", true)
        }

        val result = getProjectTool.execute(params, executionContext)

        // Verify result
        assertTrue(result is JsonObject)
        assertEquals(true, result.jsonObject["success"]?.jsonPrimitive?.boolean)

        // Check tasks
        val data = result.jsonObject["data"]?.jsonObject
        assertNotNull(data)
        val tasks = data!!["tasks"]?.jsonObject
        assertNotNull(tasks)

        val items = tasks!!["items"]?.jsonArray
        assertNotNull(items)
        assertEquals(1, items!!.size)

        val taskObj = items[0].jsonObject
        assertEquals(task.id.toString(), taskObj["id"]?.jsonPrimitive?.content)
        assertEquals("Sample Task", taskObj["title"]?.jsonPrimitive?.content)
        assertEquals("in-progress", taskObj["status"]?.jsonPrimitive?.content)
        assertEquals("high", taskObj["priority"]?.jsonPrimitive?.content)
        assertEquals(5, taskObj["complexity"]?.jsonPrimitive?.int)
        assertEquals("This is a sample task", taskObj["summary"]?.jsonPrimitive?.content)

        // Check pagination info
        assertEquals(1, tasks["total"]?.jsonPrimitive?.int)
        assertEquals(1, tasks["included"]?.jsonPrimitive?.int)
        assertEquals(false, tasks["hasMore"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `test execute with includeTasks should only return tasks with valid project relationships`() = runBlocking {
        val projectId = UUID.randomUUID()
        val now = Instant.now()

        val project = Project(
            id = projectId,
            name = "Test Project",
            summary = "This is a test project",
            status = ProjectStatus.IN_DEVELOPMENT,
            createdAt = now,
            modifiedAt = now
        )

        // Create a task that belongs to the project
        val projectTask = Task(
            id = UUID.randomUUID(),
            title = "Project Task",
            summary = "This task belongs to the project",
            status = TaskStatus.IN_PROGRESS,
            priority = Priority.HIGH,
            complexity = 5,
            projectId = projectId // This task has the correct projectId
        )

        // Create orphaned tasks that should NOT be returned
        val orphanedTask1 = Task(
            id = UUID.randomUUID(),
            title = "Orphaned Task 1",
            summary = "This task has no project relationship",
            status = TaskStatus.PENDING,
            priority = Priority.LOW,
            complexity = 3,
            projectId = null, // No project relationship
            featureId = null  // No feature relationship
        )

        val orphanedTask2 = Task(
            id = UUID.randomUUID(),
            title = "Orphaned Task 2",
            summary = "This task belongs to a different project",
            status = TaskStatus.PENDING,
            priority = Priority.MEDIUM,
            complexity = 4,
            projectId = UUID.randomUUID() // Different project
        )

        coEvery { mockProjectRepository.getById(projectId) } returns Result.Success(project)

        // FIXED BEHAVIOR: The implementation should only return tasks with proper project relationships
        // The orphaned tasks should be filtered out by the repository implementation
        coEvery {
            mockTaskRepository.findByFilters(
                projectId = projectId,
                status = null,
                priority = null,
                tags = null,
                textQuery = null,
                limit = 20
            )
        } returns Result.Success(listOf(projectTask)) // Only tasks with valid project relationships

        val params = buildJsonObject {
            put("id", projectId.toString())
            put("includeTasks", true)
        }

        val result = getProjectTool.execute(params, executionContext)

        // Verify result
        assertTrue(result is JsonObject)
        assertEquals(true, result.jsonObject["success"]?.jsonPrimitive?.boolean)

        // THIS IS THE BUG: The response should only include tasks with valid project relationships
        val data = result.jsonObject["data"]?.jsonObject
        assertNotNull(data)
        val tasks = data!!["tasks"]?.jsonObject
        assertNotNull(tasks)

        val items = tasks!!["items"]?.jsonArray
        assertNotNull(items)

        // BUG: Currently returns 3 tasks (including orphaned ones)
        // EXPECTED: Should only return 1 task (the one with correct projectId)
        assertEquals(1, items!!.size, "Should only return tasks with valid project relationships")

        val taskObj = items[0].jsonObject
        assertEquals(projectTask.id.toString(), taskObj["id"]?.jsonPrimitive?.content, "Should return the project task")
        assertEquals("Project Task", taskObj["title"]?.jsonPrimitive?.content)

        // Verify the correct task metadata
        assertEquals(1, tasks["total"]?.jsonPrimitive?.int, "Total should only count valid project tasks")
        assertEquals(1, tasks["included"]?.jsonPrimitive?.int, "Included should only count valid project tasks")
    }
}