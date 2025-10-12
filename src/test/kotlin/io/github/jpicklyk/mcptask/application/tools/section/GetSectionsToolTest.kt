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

    @Test
    fun `should retrieve sections without content when includeContent is false`() = runBlocking {
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
            content = "This is a very long content that would consume many tokens in the response",
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 0,
            tags = listOf("technical", "implementation")
        )

        val section2 = Section(
            entityType = EntityType.TASK,
            entityId = task.id,
            title = "Test Strategy",
            usageDescription = "Testing approach for this task",
            content = "Another long content that should be excluded when includeContent=false",
            contentFormat = ContentFormat.PLAIN_TEXT,
            ordinal = 1
        )

        mockSectionRepository.addSection(EntityType.TASK, task.id, section1)
        mockSectionRepository.addSection(EntityType.TASK, task.id, section2)

        val params = buildJsonObject {
            put("entityType", EntityType.TASK.name)
            put("entityId", task.id.toString())
            put("includeContent", false)
        }

        // Act
        val result = tool.execute(params, context)

        // Assert
        val data = ResponseAssertions.assertSuccessResponse(result)
        val sectionsArray = ResponseAssertions.assertCollectionSize(data, 2, "sections")

        // Verify that content field is NOT present
        val firstSection = sectionsArray!![0].jsonObject
        assertEquals("Implementation Details", firstSection["title"]?.jsonPrimitive?.content)
        assertEquals("MARKDOWN", firstSection["contentFormat"]?.jsonPrimitive?.content)
        assertEquals(null, firstSection["content"], "Content should not be included when includeContent=false")

        val secondSection = sectionsArray[1].jsonObject
        assertEquals("Test Strategy", secondSection["title"]?.jsonPrimitive?.content)
        assertEquals("PLAIN_TEXT", secondSection["contentFormat"]?.jsonPrimitive?.content)
        assertEquals(null, secondSection["content"], "Content should not be included when includeContent=false")

        // Verify other fields are still present
        assertEquals("Technical notes for developers", firstSection["usageDescription"]?.jsonPrimitive?.content)
        assertEquals(0, firstSection["ordinal"]?.jsonPrimitive?.int)
    }

    @Test
    fun `should retrieve only specified sections when sectionIds provided`() = runBlocking {
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
            title = "Section 1",
            usageDescription = "First section",
            content = "Content 1",
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 0
        )

        val section2 = Section(
            entityType = EntityType.TASK,
            entityId = task.id,
            title = "Section 2",
            usageDescription = "Second section",
            content = "Content 2",
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 1
        )

        val section3 = Section(
            entityType = EntityType.TASK,
            entityId = task.id,
            title = "Section 3",
            usageDescription = "Third section",
            content = "Content 3",
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 2
        )

        mockSectionRepository.addSection(EntityType.TASK, task.id, section1)
        mockSectionRepository.addSection(EntityType.TASK, task.id, section2)
        mockSectionRepository.addSection(EntityType.TASK, task.id, section3)

        val params = buildJsonObject {
            put("entityType", EntityType.TASK.name)
            put("entityId", task.id.toString())
            put("sectionIds", JsonArray(listOf(
                JsonPrimitive(section1.id.toString()),
                JsonPrimitive(section3.id.toString())
            )))
        }

        // Act
        val result = tool.execute(params, context)

        // Assert
        val data = ResponseAssertions.assertSuccessResponse(result)
        val sectionsArray = ResponseAssertions.assertCollectionSize(data, 2, "sections")

        // Verify only specified sections are returned
        val returnedTitles = sectionsArray!!.map { it.jsonObject["title"]?.jsonPrimitive?.content }
        assertTrue(returnedTitles.contains("Section 1"), "Section 1 should be included")
        assertTrue(returnedTitles.contains("Section 3"), "Section 3 should be included")
        assertTrue(!returnedTitles.contains("Section 2"), "Section 2 should NOT be included")

        // Verify count is correct
        assertEquals(2, (data as JsonObject)["count"]?.jsonPrimitive?.int)
    }

    @Test
    fun `should combine includeContent false with sectionIds filtering`() = runBlocking {
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
            title = "Section 1",
            usageDescription = "First section",
            content = "Large content 1 that should be excluded",
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 0
        )

        val section2 = Section(
            entityType = EntityType.TASK,
            entityId = task.id,
            title = "Section 2",
            usageDescription = "Second section",
            content = "Large content 2 that should be excluded",
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 1
        )

        mockSectionRepository.addSection(EntityType.TASK, task.id, section1)
        mockSectionRepository.addSection(EntityType.TASK, task.id, section2)

        val params = buildJsonObject {
            put("entityType", EntityType.TASK.name)
            put("entityId", task.id.toString())
            put("includeContent", false)
            put("sectionIds", JsonArray(listOf(JsonPrimitive(section1.id.toString()))))
        }

        // Act
        val result = tool.execute(params, context)

        // Assert
        val data = ResponseAssertions.assertSuccessResponse(result)
        val sectionsArray = ResponseAssertions.assertCollectionSize(data, 1, "sections")

        // Verify only specified section is returned and content is excluded
        val firstSection = sectionsArray!![0].jsonObject
        assertEquals("Section 1", firstSection["title"]?.jsonPrimitive?.content)
        assertEquals(null, firstSection["content"], "Content should not be included")
        assertEquals("First section", firstSection["usageDescription"]?.jsonPrimitive?.content)
    }

    @Test
    fun `should return error for invalid sectionIds format`() = runBlocking {
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
            put("sectionIds", JsonArray(listOf(JsonPrimitive("not-a-uuid"))))
        }

        // Act
        val result = tool.execute(params, context)

        // Assert
        val errorResponse = ResponseAssertions.assertErrorResponse(result, null, "VALIDATION_ERROR")
        assertTrue(
            (errorResponse as JsonObject)["details"]?.jsonPrimitive?.content?.contains("UUID") ?: false,
            "Error should mention UUID format issue"
        )
    }
}