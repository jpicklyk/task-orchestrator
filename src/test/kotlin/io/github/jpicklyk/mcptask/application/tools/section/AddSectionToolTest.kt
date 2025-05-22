package io.github.jpicklyk.mcptask.application.tools.section

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.test.mock.*
import io.github.jpicklyk.mcptask.test.util.ResponseAssertions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*

class AddSectionToolTest {

    private lateinit var tool: AddSectionTool
    private lateinit var mockTaskRepository: MockTaskRepository
    private lateinit var mockFeatureRepository: MockFeatureRepository
    private lateinit var mockProjectRepository: MockProjectRepository
    private lateinit var mockSectionRepository: MockSectionRepository
    private lateinit var context: ToolExecutionContext

    @BeforeEach
    fun setup() {
        tool = AddSectionTool()
        mockTaskRepository = MockTaskRepository()
        mockFeatureRepository = MockFeatureRepository()
        mockProjectRepository = MockProjectRepository()
        mockSectionRepository = MockSectionRepository()

        val repositoryProvider = MockRepositoryProvider(
            taskRepository = mockTaskRepository,
            featureRepository = mockFeatureRepository,
            projectRepository = mockProjectRepository,
            sectionRepository = mockSectionRepository
        )

        context = ToolExecutionContext(repositoryProvider)
    }

    @Test
    fun `should add section to a valid task`() = runBlocking {
        // Arrange
        val task = Task.create {
            it.copy(
                title = "Test Task",
                summary = "Task for testing",
                status = TaskStatus.PENDING,
                priority = Priority.MEDIUM
            )
        }
        mockTaskRepository.create(task)

        val params = buildJsonObject {
            put("entityType", EntityType.TASK.name)
            put("entityId", task.id.toString())
            put("title", "Implementation Details")
            put("usageDescription", "Technical notes for developers")
            put("content", "This task should be implemented using Kotlin coroutines")
            put("contentFormat", ContentFormat.MARKDOWN.name)
            put("ordinal", 0)
            put("tags", "technical,implementation")
        }

        // Act
        val result = tool.execute(params, context)

        // Assert using the new response assertions
        val data = ResponseAssertions.assertSuccessResponse(
            result,
            "Section added successfully"
        )

        // Verify data structure
        assertTrue(data is JsonObject, "Data should be a JSON object")
        ResponseAssertions.assertDataContainsFields(data, "section")

        val section = (data as JsonObject)["section"]
        assertTrue(section is JsonObject, "Section should be a JSON object")
        ResponseAssertions.assertDataContainsFields(
            section,
            "id", "entityType", "entityId", "title", "contentFormat", "ordinal", "createdAt"
        )

        // Verify section was added to repository
        val sections = mockSectionRepository.getSectionsForEntity(EntityType.TASK, task.id)
        assertTrue(sections is Result.Success)
        assertEquals(1, (sections as Result.Success).data.size)
        assertEquals("Implementation Details", sections.data[0].title)
        assertEquals(task.id, sections.data[0].entityId)
    }

    @Test
    fun `should add section to a valid feature`() = runBlocking {
        // Arrange
        val feature = Feature(
            name = "Test Feature",
            summary = "Feature for testing",
            status = FeatureStatus.PLANNING,
            priority = Priority.MEDIUM
        )
        mockFeatureRepository.create(feature)

        val params = buildJsonObject {
            put("entityType", EntityType.FEATURE.name)
            put("entityId", feature.id.toString())
            put("title", "Feature Overview")
            put("usageDescription", "High-level description of the feature")
            put("content", "This feature provides testing capabilities")
            put("contentFormat", ContentFormat.MARKDOWN.name)
            put("ordinal", 0)
        }

        // Act
        val result = tool.execute(params, context)

        // Assert
        val data = ResponseAssertions.assertSuccessResponse(
            result,
            "Section added successfully"
        )

        // Verify data structure
        assertTrue(data is JsonObject, "Data should be a JSON object")
        ResponseAssertions.assertDataContainsFields(data, "section")

        val section = (data as JsonObject)["section"]
        assertTrue(section is JsonObject, "Section should be a JSON object")
        ResponseAssertions.assertDataContainsFields(
            section,
            "id", "entityType", "entityId", "title", "contentFormat", "ordinal", "createdAt"
        )

        val sectionObj = section as JsonObject
        assertEquals(EntityType.FEATURE.name, sectionObj["entityType"]?.jsonPrimitive?.content)
        assertEquals(feature.id.toString(), sectionObj["entityId"]?.jsonPrimitive?.content)
        assertEquals("Feature Overview", sectionObj["title"]?.jsonPrimitive?.content)

        // Verify section was added to repository
        val sections = mockSectionRepository.getSectionsForEntity(EntityType.FEATURE, feature.id)
        assertTrue(sections is Result.Success)
        assertEquals(1, (sections as Result.Success).data.size)
        assertEquals("Feature Overview", sections.data[0].title)
    }

    @Test
    fun `should add section to a valid project`() = runBlocking {
        // Arrange
        val project = Project(
            name = "Test Project",
            summary = "Project for testing",
            status = ProjectStatus.PLANNING
        )
        mockProjectRepository.addProject(project)

        val params = buildJsonObject {
            put("entityType", EntityType.PROJECT.name)
            put("entityId", project.id.toString())
            put("title", "Project Overview")
            put("usageDescription", "High-level description of the project")
            put("content", "This project aims to accomplish testing functionality")
            put("contentFormat", ContentFormat.MARKDOWN.name)
            put("ordinal", 0)
            put("tags", "overview,documentation")
        }

        // Act
        val result = tool.execute(params, context)

        // Assert
        val data = ResponseAssertions.assertSuccessResponse(
            result,
            "Section added successfully"
        )

        // Verify data structure
        assertTrue(data is JsonObject, "Data should be a JSON object")
        ResponseAssertions.assertDataContainsFields(data, "section")

        val section = (data as JsonObject)["section"]
        assertTrue(section is JsonObject, "Section should be a JSON object")
        ResponseAssertions.assertDataContainsFields(
            section,
            "id", "entityType", "entityId", "title", "contentFormat", "ordinal", "createdAt"
        )

        val sectionObj = section as JsonObject
        assertEquals(EntityType.PROJECT.name, sectionObj["entityType"]?.jsonPrimitive?.content)
        assertEquals(project.id.toString(), sectionObj["entityId"]?.jsonPrimitive?.content)
        assertEquals("Project Overview", sectionObj["title"]?.jsonPrimitive?.content)

        // Verify section was added to repository
        val sections = mockSectionRepository.getSectionsForEntity(EntityType.PROJECT, project.id)
        assertTrue(sections is Result.Success)
        assertEquals(1, (sections as Result.Success).data.size)
        assertEquals("Project Overview", sections.data[0].title)
        assertEquals(project.id, sections.data[0].entityId)
    }

    @Test
    fun `should return error for non-existent entity`() = runBlocking {
        // Arrange
        val nonExistentId = UUID.randomUUID()

        val params = buildJsonObject {
            put("entityType", EntityType.TASK.name)
            put("entityId", nonExistentId.toString())
            put("title", "Implementation Details")
            put("usageDescription", "Technical notes for developers")
            put("content", "This task should be implemented using Kotlin coroutines")
            put("contentFormat", ContentFormat.MARKDOWN.name)
            put("ordinal", 0)
        }

        // Act
        val result = tool.execute(params, context)

        // Assert
        ResponseAssertions.assertErrorResponse(result)

        // Verify no sections were added to repository
        val sections = mockSectionRepository.getSectionsForEntity(EntityType.TASK, nonExistentId)
        assertTrue(sections is Result.Success)
        assertEquals(0, (sections as Result.Success).data.size)
    }

    @Test
    fun `should return error for non-existent project`() = runBlocking {
        // Arrange
        val nonExistentId = UUID.randomUUID()

        val params = buildJsonObject {
            put("entityType", EntityType.PROJECT.name)
            put("entityId", nonExistentId.toString())
            put("title", "Project Overview")
            put("usageDescription", "High-level description of the project")
            put("content", "This project aims to accomplish testing functionality")
            put("contentFormat", ContentFormat.MARKDOWN.name)
            put("ordinal", 0)
        }

        // Act
        val result = tool.execute(params, context)

        // Assert
        ResponseAssertions.assertErrorResponse(result)

        // Verify no sections were added to repository
        val sections = mockSectionRepository.getSectionsForEntity(EntityType.PROJECT, nonExistentId)
        assertTrue(sections is Result.Success)
        assertEquals(0, (sections as Result.Success).data.size)
    }

    @Test
    fun `should return error for invalid parameters`() = runBlocking {
        // Arrange - Missing title
        val params = buildJsonObject {
            put("entityType", EntityType.TASK.name)
            put("entityId", UUID.randomUUID().toString())
            put("usageDescription", "Technical notes for developers")
            put("content", "This task should be implemented using Kotlin coroutines")
            put("contentFormat", ContentFormat.MARKDOWN.name)
            put("ordinal", 0)
        }

        // Act
        val result = tool.execute(params, context)

        // Assert
        val error = ResponseAssertions.assertErrorResponse(result)
        assertTrue(error is JsonObject, "Error should be a JSON object")
        assertEquals("VALIDATION_ERROR", (error as JsonObject)["code"]?.jsonPrimitive?.content)
    }
}