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

class ListTagsToolTest {
    private lateinit var tool: ListTagsTool
    private lateinit var context: ToolExecutionContext
    private lateinit var mockTaskRepository: TaskRepository
    private lateinit var mockFeatureRepository: FeatureRepository
    private lateinit var mockProjectRepository: ProjectRepository
    private lateinit var mockTemplateRepository: TemplateRepository
    private lateinit var mockRepositoryProvider: RepositoryProvider

    @BeforeEach
    fun setup() {
        tool = ListTagsTool()

        // Create mock repositories
        mockTaskRepository = mockk<TaskRepository>()
        mockFeatureRepository = mockk<FeatureRepository>()
        mockProjectRepository = mockk<ProjectRepository>()
        mockTemplateRepository = mockk<TemplateRepository>()
        mockRepositoryProvider = mockk<RepositoryProvider>()

        // Configure the repository provider to return the mock repositories
        every { mockRepositoryProvider.taskRepository() } returns mockTaskRepository
        every { mockRepositoryProvider.featureRepository() } returns mockFeatureRepository
        every { mockRepositoryProvider.projectRepository() } returns mockProjectRepository
        every { mockRepositoryProvider.templateRepository() } returns mockTemplateRepository

        // Create the execution context with the mock repository provider
        context = ToolExecutionContext(mockRepositoryProvider)
    }

    // ========== Parameter Validation Tests ==========

    @Test
    fun `test valid parameters validation`() {
        val params = JsonObject(emptyMap())
        // Should not throw an exception
        assertDoesNotThrow { tool.validateParams(params) }
    }

    @Test
    fun `test valid entity types parameter validation`() {
        val params = JsonObject(
            mapOf(
                "entityTypes" to JsonArray(
                    listOf(
                        JsonPrimitive("TASK"),
                        JsonPrimitive("FEATURE")
                    )
                )
            )
        )
        // Should not throw an exception
        assertDoesNotThrow { tool.validateParams(params) }
    }

    @Test
    fun `test invalid entity type validation`() {
        val params = JsonObject(
            mapOf(
                "entityTypes" to JsonArray(
                    listOf(JsonPrimitive("INVALID"))
                )
            )
        )

        // Should throw an exception for invalid entity type
        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Invalid entity type"))
    }

    @Test
    fun `test invalid sortBy validation`() {
        val params = JsonObject(
            mapOf(
                "sortBy" to JsonPrimitive("invalid")
            )
        )

        // Should throw an exception for invalid sortBy
        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Invalid sortBy"))
    }

    @Test
    fun `test invalid sortDirection validation`() {
        val params = JsonObject(
            mapOf(
                "sortDirection" to JsonPrimitive("invalid")
            )
        )

        // Should throw an exception for invalid sortDirection
        val exception = assertThrows(ToolValidationException::class.java) {
            tool.validateParams(params)
        }
        assertTrue(exception.message!!.contains("Invalid sortDirection"))
    }

    // ========== Execution Tests ==========

    @Test
    fun `test list all tags with default parameters`() = runBlocking {
        // Mock task repository
        coEvery { mockTaskRepository.getAllTags() } returns Result.Success(listOf("bug", "feature", "urgent"))
        coEvery { mockTaskRepository.countByTag("bug") } returns Result.Success(5L)
        coEvery { mockTaskRepository.countByTag("feature") } returns Result.Success(10L)
        coEvery { mockTaskRepository.countByTag("urgent") } returns Result.Success(2L)

        // Mock feature repository
        coEvery { mockFeatureRepository.getAllTags() } returns Result.Success(listOf("feature", "enhancement"))
        coEvery { mockFeatureRepository.countByTag("feature") } returns Result.Success(3L)
        coEvery { mockFeatureRepository.countByTag("enhancement") } returns Result.Success(4L)

        // Mock project repository
        coEvery { mockProjectRepository.getAllTags() } returns Result.Success(listOf("2025", "urgent"))
        coEvery { mockProjectRepository.countByTag("2025") } returns Result.Success(1L)
        coEvery { mockProjectRepository.countByTag("urgent") } returns Result.Success(1L)

        // Mock template repository
        coEvery { mockTemplateRepository.getAllTemplates() } returns Result.Success(
            listOf(
                Template(name = "Test Template", description = "Test", targetEntityType = EntityType.TASK, tags = listOf("template", "feature"))
            )
        )

        val params = JsonObject(emptyMap())
        val result = tool.execute(params, context)

        assertTrue(result is JsonObject, "Response should be a JsonObject")
        val responseObj = result as JsonObject
        val success = responseObj["success"]?.jsonPrimitive?.boolean ?: false
        assertTrue(success, "Success should be true")

        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data object should not be null")

        val tags = data!!["tags"]?.jsonArray
        assertNotNull(tags, "Tags array should not be null")
        assertTrue(tags!!.size > 0, "Should have at least one tag")

        val totalTags = data["totalTags"]?.jsonPrimitive?.int
        assertEquals(tags.size, totalTags, "Total tags count should match array length")
    }

    @Test
    fun `test list tags sorted by count descending`() = runBlocking {
        // Mock task repository
        coEvery { mockTaskRepository.getAllTags() } returns Result.Success(listOf("bug", "feature", "urgent"))
        coEvery { mockTaskRepository.countByTag("bug") } returns Result.Success(5L)
        coEvery { mockTaskRepository.countByTag("feature") } returns Result.Success(10L)
        coEvery { mockTaskRepository.countByTag("urgent") } returns Result.Success(2L)

        // Mock other repositories with empty results
        coEvery { mockFeatureRepository.getAllTags() } returns Result.Success(emptyList())
        coEvery { mockProjectRepository.getAllTags() } returns Result.Success(emptyList())
        coEvery { mockTemplateRepository.getAllTemplates() } returns Result.Success(emptyList())

        val params = JsonObject(
            mapOf(
                "sortBy" to JsonPrimitive("count"),
                "sortDirection" to JsonPrimitive("desc")
            )
        )
        val result = tool.execute(params, context)

        val responseObj = result as JsonObject
        val data = responseObj["data"]?.jsonObject
        val tags = data!!["tags"]?.jsonArray!!

        // Should be sorted by count descending: feature(10), bug(5), urgent(2)
        assertEquals("feature", tags[0].jsonObject["tag"]?.jsonPrimitive?.content)
        assertEquals(10, tags[0].jsonObject["totalCount"]?.jsonPrimitive?.int)

        assertEquals("bug", tags[1].jsonObject["tag"]?.jsonPrimitive?.content)
        assertEquals(5, tags[1].jsonObject["totalCount"]?.jsonPrimitive?.int)

        assertEquals("urgent", tags[2].jsonObject["tag"]?.jsonPrimitive?.content)
        assertEquals(2, tags[2].jsonObject["totalCount"]?.jsonPrimitive?.int)
    }

    @Test
    fun `test list tags sorted by name ascending`() = runBlocking {
        // Mock task repository
        coEvery { mockTaskRepository.getAllTags() } returns Result.Success(listOf("zebra", "apple", "banana"))
        coEvery { mockTaskRepository.countByTag("zebra") } returns Result.Success(1L)
        coEvery { mockTaskRepository.countByTag("apple") } returns Result.Success(1L)
        coEvery { mockTaskRepository.countByTag("banana") } returns Result.Success(1L)

        // Mock other repositories with empty results
        coEvery { mockFeatureRepository.getAllTags() } returns Result.Success(emptyList())
        coEvery { mockProjectRepository.getAllTags() } returns Result.Success(emptyList())
        coEvery { mockTemplateRepository.getAllTemplates() } returns Result.Success(emptyList())

        val params = JsonObject(
            mapOf(
                "sortBy" to JsonPrimitive("name"),
                "sortDirection" to JsonPrimitive("asc")
            )
        )
        val result = tool.execute(params, context)

        val responseObj = result as JsonObject
        val data = responseObj["data"]?.jsonObject
        val tags = data!!["tags"]?.jsonArray!!

        // Should be sorted alphabetically: apple, banana, zebra
        assertEquals("apple", tags[0].jsonObject["tag"]?.jsonPrimitive?.content)
        assertEquals("banana", tags[1].jsonObject["tag"]?.jsonPrimitive?.content)
        assertEquals("zebra", tags[2].jsonObject["tag"]?.jsonPrimitive?.content)
    }

    @Test
    fun `test filter by single entity type`() = runBlocking {
        // Mock task repository
        coEvery { mockTaskRepository.getAllTags() } returns Result.Success(listOf("task-tag"))
        coEvery { mockTaskRepository.countByTag("task-tag") } returns Result.Success(5L)

        val params = JsonObject(
            mapOf(
                "entityTypes" to JsonArray(listOf(JsonPrimitive("TASK")))
            )
        )
        val result = tool.execute(params, context)

        val responseObj = result as JsonObject
        val data = responseObj["data"]?.jsonObject
        val tags = data!!["tags"]?.jsonArray!!

        assertEquals(1, tags.size, "Should have exactly 1 tag")
        assertEquals("task-tag", tags[0].jsonObject["tag"]?.jsonPrimitive?.content)

        // Should only have TASK in byEntityType
        val byEntityType = tags[0].jsonObject["byEntityType"]?.jsonObject!!
        assertTrue(byEntityType.containsKey("TASK"))
        assertFalse(byEntityType.containsKey("FEATURE"))
        assertFalse(byEntityType.containsKey("PROJECT"))
    }

    @Test
    fun `test filter by multiple entity types`() = runBlocking {
        // Mock task repository
        coEvery { mockTaskRepository.getAllTags() } returns Result.Success(listOf("shared-tag"))
        coEvery { mockTaskRepository.countByTag("shared-tag") } returns Result.Success(3L)

        // Mock feature repository
        coEvery { mockFeatureRepository.getAllTags() } returns Result.Success(listOf("shared-tag"))
        coEvery { mockFeatureRepository.countByTag("shared-tag") } returns Result.Success(2L)

        val params = JsonObject(
            mapOf(
                "entityTypes" to JsonArray(listOf(JsonPrimitive("TASK"), JsonPrimitive("FEATURE")))
            )
        )
        val result = tool.execute(params, context)

        val responseObj = result as JsonObject
        val data = responseObj["data"]?.jsonObject
        val tags = data!!["tags"]?.jsonArray!!

        assertEquals(1, tags.size, "Should have exactly 1 tag")
        assertEquals("shared-tag", tags[0].jsonObject["tag"]?.jsonPrimitive?.content)
        assertEquals(5, tags[0].jsonObject["totalCount"]?.jsonPrimitive?.int, "Should sum counts from both entity types")

        // Should have both TASK and FEATURE in byEntityType
        val byEntityType = tags[0].jsonObject["byEntityType"]?.jsonObject!!
        assertEquals(3, byEntityType["TASK"]?.jsonPrimitive?.int)
        assertEquals(2, byEntityType["FEATURE"]?.jsonPrimitive?.int)
    }

    @Test
    fun `test no tags found`() = runBlocking {
        // Mock all repositories with empty results
        coEvery { mockTaskRepository.getAllTags() } returns Result.Success(emptyList())
        coEvery { mockFeatureRepository.getAllTags() } returns Result.Success(emptyList())
        coEvery { mockProjectRepository.getAllTags() } returns Result.Success(emptyList())
        coEvery { mockTemplateRepository.getAllTemplates() } returns Result.Success(emptyList())

        val params = JsonObject(emptyMap())
        val result = tool.execute(params, context)

        val responseObj = result as JsonObject
        val success = responseObj["success"]?.jsonPrimitive?.boolean ?: false
        assertTrue(success, "Success should be true even with no tags")

        val data = responseObj["data"]?.jsonObject
        val tags = data!!["tags"]?.jsonArray!!
        assertEquals(0, tags.size, "Should have zero tags")
        assertEquals(0, data["totalTags"]?.jsonPrimitive?.int, "Total tags should be 0")
    }

    @Test
    fun `test repository error handling`() = runBlocking {
        // Mock task repository with error
        coEvery { mockTaskRepository.getAllTags() } returns Result.Error(
            RepositoryError.DatabaseError("Database error", null)
        )

        // Mock other repositories with empty results
        coEvery { mockFeatureRepository.getAllTags() } returns Result.Success(emptyList())
        coEvery { mockProjectRepository.getAllTags() } returns Result.Success(emptyList())
        coEvery { mockTemplateRepository.getAllTemplates() } returns Result.Success(emptyList())

        val params = JsonObject(emptyMap())
        val result = tool.execute(params, context)

        // Should still succeed, just without task tags
        val responseObj = result as JsonObject
        val success = responseObj["success"]?.jsonPrimitive?.boolean ?: false
        assertTrue(success, "Success should be true even with repository errors")
    }

    @Test
    fun `test template tags extraction`() = runBlocking {
        // Mock all entity repositories with empty results
        coEvery { mockTaskRepository.getAllTags() } returns Result.Success(emptyList())
        coEvery { mockFeatureRepository.getAllTags() } returns Result.Success(emptyList())
        coEvery { mockProjectRepository.getAllTags() } returns Result.Success(emptyList())

        // Mock template repository with templates that have tags
        coEvery { mockTemplateRepository.getAllTemplates() } returns Result.Success(
            listOf(
                Template(name = "Template1", description = "Test", targetEntityType = EntityType.TASK, tags = listOf("template-tag", "common")),
                Template(name = "Template2", description = "Test", targetEntityType = EntityType.TASK, tags = listOf("common", "another-tag"))
            )
        )

        val params = JsonObject(emptyMap())
        val result = tool.execute(params, context)

        val responseObj = result as JsonObject
        val data = responseObj["data"]?.jsonObject
        val tags = data!!["tags"]?.jsonArray!!

        assertEquals(3, tags.size, "Should have 3 unique tags from templates")

        // Find the "common" tag which should appear in 2 templates
        val commonTag = tags.find { it.jsonObject["tag"]?.jsonPrimitive?.content == "common" }
        assertNotNull(commonTag, "Should find 'common' tag")
        assertEquals(2, commonTag!!.jsonObject["totalCount"]?.jsonPrimitive?.int, "Common tag should have count of 2")
    }
}
