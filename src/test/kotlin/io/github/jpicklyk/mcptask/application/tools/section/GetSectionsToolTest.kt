package io.github.jpicklyk.mcptask.application.tools.section

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.test.mock.*
import io.github.jpicklyk.mcptask.test.util.ResponseAssertions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*

class GetSectionsToolTest {

    private lateinit var tool: GetSectionsTool
    private lateinit var mockTaskRepository: MockTaskRepository
    private lateinit var mockFeatureRepository: MockFeatureRepository
    private lateinit var mockProjectRepository: MockProjectRepository
    private lateinit var mockSectionRepository: MockSectionRepository
    private lateinit var context: ToolExecutionContext

    @BeforeEach
    fun setup() {
        tool = GetSectionsTool()
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
    fun `should retrieve sections for a task`() = runBlocking {
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

        val section1 = Section(
            entityType = EntityType.TASK,
            entityId = task.id,
            title = "Implementation Details",
            usageDescription = "Technical notes for developers",
            content = "This task should be implemented using Kotlin coroutines",
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 0,
            tags = listOf("technical", "implementation")
        )

        val section2 = Section(
            entityType = EntityType.TASK,
            entityId = task.id,
            title = "Test Strategy",
            usageDescription = "Testing approach for this task",
            content = "Unit tests should cover all edge cases",
            contentFormat = ContentFormat.PLAIN_TEXT,
            ordinal = 1
        )

        mockSectionRepository.addSection(EntityType.TASK, task.id, section1)
        mockSectionRepository.addSection(EntityType.TASK, task.id, section2)

        val params = buildJsonObject {
            put("entityType", EntityType.TASK.name)
            put("entityId", task.id.toString())
        }

        // Act
        val result = tool.execute(params, context)

        // Assert
        val data = ResponseAssertions.assertSuccessResponse(result)

        // Verify data structure
        assertTrue(data is JsonObject, "Data should be a JSON object")
        ResponseAssertions.assertDataContainsFields(data, "sections", "entityType", "entityId", "count")

        // Verify sections array
        val sectionsArray = ResponseAssertions.assertCollectionSize(data, 2, "sections")

        // Check the first section
        val firstSection = sectionsArray!![0].jsonObject
        assertEquals("Implementation Details", firstSection["title"]?.jsonPrimitive?.content)
        assertEquals("MARKDOWN", firstSection["contentFormat"]?.jsonPrimitive?.content)
        assertEquals(0, firstSection["ordinal"]?.jsonPrimitive?.int)

        // Check the second section
        val secondSection = sectionsArray[1].jsonObject
        assertEquals("Test Strategy", secondSection["title"]?.jsonPrimitive?.content)
        assertEquals("PLAIN_TEXT", secondSection["contentFormat"]?.jsonPrimitive?.content)
        assertEquals(1, secondSection["ordinal"]?.jsonPrimitive?.int)

        // Check metadata
        assertEquals(EntityType.TASK.name, (data as JsonObject)["entityType"]?.jsonPrimitive?.content)
        assertEquals(task.id.toString(), data["entityId"]?.jsonPrimitive?.content)
        assertEquals(2, data["count"]?.jsonPrimitive?.int)
    }

    @Test
    fun `should retrieve sections for a feature`() = runBlocking {
        // Arrange
        val feature = Feature(
            name = "Test Feature",
            summary = "Feature for testing",
            status = FeatureStatus.PLANNING,
            priority = Priority.MEDIUM
        )
        mockFeatureRepository.create(feature)

        val section = Section(
            entityType = EntityType.FEATURE,
            entityId = feature.id,
            title = "Feature Overview",
            usageDescription = "High-level description of the feature",
            content = "This feature provides testing capabilities",
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 0
        )

        mockSectionRepository.addSection(EntityType.FEATURE, feature.id, section)

        val params = buildJsonObject {
            put("entityType", EntityType.FEATURE.name)
            put("entityId", feature.id.toString())
        }

        // Act
        val result = tool.execute(params, context)

        // Assert
        val data = ResponseAssertions.assertSuccessResponse(result)

        // Verify data structure
        assertTrue(data is JsonObject, "Data should be a JSON object")
        ResponseAssertions.assertDataContainsFields(data, "sections", "entityType", "entityId", "count")

        // Verify sections array
        val sectionsArray = ResponseAssertions.assertCollectionSize(data, 1, "sections")

        // Check section
        val sectionResult = sectionsArray!![0].jsonObject
        assertEquals("Feature Overview", sectionResult["title"]?.jsonPrimitive?.content)
        assertEquals("MARKDOWN", sectionResult["contentFormat"]?.jsonPrimitive?.content)
        assertEquals(0, sectionResult["ordinal"]?.jsonPrimitive?.int)

        // Check metadata
        assertEquals(EntityType.FEATURE.name, (data as JsonObject)["entityType"]?.jsonPrimitive?.content)
        assertEquals(feature.id.toString(), data["entityId"]?.jsonPrimitive?.content)
        assertEquals(1, data["count"]?.jsonPrimitive?.int)
    }

    @Test
    fun `should retrieve sections for a project`() = runBlocking {
        // Arrange
        val project = Project(
            name = "Test Project",
            summary = "Project for testing",
            status = ProjectStatus.PLANNING
        )
        mockProjectRepository.addProject(project)

        val section1 = Section(
            entityType = EntityType.PROJECT,
            entityId = project.id,
            title = "Project Overview",
            usageDescription = "High-level description of the project",
            content = "This project aims to accomplish testing functionality",
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 0,
            tags = listOf("overview", "documentation")
        )

        val section2 = Section(
            entityType = EntityType.PROJECT,
            entityId = project.id,
            title = "Project Architecture",
            usageDescription = "Technical architecture of the project",
            content = "The project uses a modular architecture with the following components...",
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 1,
            tags = listOf("architecture", "technical")
        )

        mockSectionRepository.addSection(EntityType.PROJECT, project.id, section1)
        mockSectionRepository.addSection(EntityType.PROJECT, project.id, section2)

        val params = buildJsonObject {
            put("entityType", EntityType.PROJECT.name)
            put("entityId", project.id.toString())
        }

        // Act
        val result = tool.execute(params, context)

        // Assert
        val data = ResponseAssertions.assertSuccessResponse(result)

        // Verify data structure
        assertTrue(data is JsonObject, "Data should be a JSON object")
        ResponseAssertions.assertDataContainsFields(data, "sections", "entityType", "entityId", "count")

        // Verify sections array
        val sectionsArray = ResponseAssertions.assertCollectionSize(data, 2, "sections")

        // Check the first section
        val firstSection = sectionsArray!![0].jsonObject
        assertEquals("Project Overview", firstSection["title"]?.jsonPrimitive?.content)
        assertEquals("MARKDOWN", firstSection["contentFormat"]?.jsonPrimitive?.content)
        assertEquals(0, firstSection["ordinal"]?.jsonPrimitive?.int)

        // Check the second section
        val secondSection = sectionsArray[1].jsonObject
        assertEquals("Project Architecture", secondSection["title"]?.jsonPrimitive?.content)
        assertEquals("MARKDOWN", secondSection["contentFormat"]?.jsonPrimitive?.content)
        assertEquals(1, secondSection["ordinal"]?.jsonPrimitive?.int)

        // Check metadata
        assertEquals(EntityType.PROJECT.name, (data as JsonObject)["entityType"]?.jsonPrimitive?.content)
        assertEquals(project.id.toString(), data["entityId"]?.jsonPrimitive?.content)
        assertEquals(2, data["count"]?.jsonPrimitive?.int)
    }

    @Test
    fun `should return empty sections array for entity with no sections`() = runBlocking {
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
        }

        // Act
        val result = tool.execute(params, context)

        // Assert
        val data = ResponseAssertions.assertSuccessResponse(result)

        // Verify data structure
        assertTrue(data is JsonObject, "Data should be a JSON object")
        ResponseAssertions.assertDataContainsFields(data, "sections", "entityType", "entityId", "count")

        // Verify an empty sections array
        val sectionsArray = ResponseAssertions.assertCollectionSize(data, 0, "sections")

        // Check metadata
        assertEquals(EntityType.TASK.name, (data as JsonObject)["entityType"]?.jsonPrimitive?.content)
        assertEquals(task.id.toString(), data["entityId"]?.jsonPrimitive?.content)
        assertEquals(0, data["count"]?.jsonPrimitive?.int)
    }

    @Test
    fun `should return empty sections array for project with no sections`() = runBlocking {
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
        }

        // Act
        val result = tool.execute(params, context)

        // Assert
        val data = ResponseAssertions.assertSuccessResponse(result)

        // Verify data structure
        assertTrue(data is JsonObject, "Data should be a JSON object")
        ResponseAssertions.assertDataContainsFields(data, "sections", "entityType", "entityId", "count")

        // Verify an empty sections array
        val sectionsArray = ResponseAssertions.assertCollectionSize(data, 0, "sections")

        // Check metadata
        assertEquals(EntityType.PROJECT.name, (data as JsonObject)["entityType"]?.jsonPrimitive?.content)
        assertEquals(project.id.toString(), data["entityId"]?.jsonPrimitive?.content)
        assertEquals(0, data["count"]?.jsonPrimitive?.int)
    }

    @Test
    fun `should return error for non-existent entity`() = runBlocking {
        // Arrange
        val nonExistentId = UUID.randomUUID()

        val params = buildJsonObject {
            put("entityType", EntityType.TASK.name)
            put("entityId", nonExistentId.toString())
        }

        // Act
        val result = tool.execute(params, context)

        // Assert
        val errorResponse = ResponseAssertions.assertErrorResponse(result, null, "RESOURCE_NOT_FOUND")

        // Additional verification to confirm the error structure
        assertTrue(errorResponse is JsonObject, "Error response should be a JSON object")
        assertEquals(
            "RESOURCE_NOT_FOUND", (errorResponse as JsonObject)["code"]?.jsonPrimitive?.content,
            "Error code should be RESOURCE_NOT_FOUND"
        )
        assertTrue(
            (errorResponse["details"]?.jsonPrimitive?.content ?: "").isNotEmpty(),
            "Error details should not be empty"
        )
    }

    @Test
    fun `should return error for non-existent project`() = runBlocking {
        // Arrange
        val nonExistentId = UUID.randomUUID()

        val params = buildJsonObject {
            put("entityType", EntityType.PROJECT.name)
            put("entityId", nonExistentId.toString())
        }

        // Act
        val result = tool.execute(params, context)

        // Assert
        val errorResponse = ResponseAssertions.assertErrorResponse(result, null, "RESOURCE_NOT_FOUND")

        // Additional verification to confirm the error structure
        assertTrue(errorResponse is JsonObject, "Error response should be a JSON object")
        assertEquals(
            "RESOURCE_NOT_FOUND", (errorResponse as JsonObject)["code"]?.jsonPrimitive?.content,
            "Error code should be RESOURCE_NOT_FOUND"
        )
        assertTrue(
            (errorResponse["details"]?.jsonPrimitive?.content ?: "").isNotEmpty(),
            "Error details should not be empty"
        )
    }
}