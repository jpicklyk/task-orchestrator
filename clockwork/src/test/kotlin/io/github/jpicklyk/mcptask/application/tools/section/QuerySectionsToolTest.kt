package io.github.jpicklyk.mcptask.application.tools.section

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.repository.*
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuerySectionsToolTest {
    private lateinit var tool: QuerySectionsTool
    private lateinit var context: ToolExecutionContext
    private lateinit var sectionRepository: SectionRepository
    private lateinit var taskRepository: TaskRepository
    private lateinit var featureRepository: FeatureRepository
    private lateinit var projectRepository: ProjectRepository

    private val testTaskId = UUID.randomUUID()
    private val testFeatureId = UUID.randomUUID()
    private val testProjectId = UUID.randomUUID()
    private val section1Id = UUID.randomUUID()
    private val section2Id = UUID.randomUUID()
    private val section3Id = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        tool = QuerySectionsTool()

        sectionRepository = mockk()
        taskRepository = mockk()
        featureRepository = mockk()
        projectRepository = mockk()

        context = mockk {
            every { sectionRepository() } returns sectionRepository
            every { taskRepository() } returns taskRepository
            every { featureRepository() } returns featureRepository
            every { projectRepository() } returns projectRepository
        }
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    // ========== VALIDATION TESTS ==========

    @Test
    fun `test validateParams with valid parameters`() = runBlocking {
        val params = buildJsonObject {
            put("entityType", "TASK")
            put("entityId", testTaskId.toString())
        }

        // Should not throw
        tool.validateParams(params)
    }

    @Test
    fun `test validateParams with invalid entity type`() = runBlocking {
        val params = buildJsonObject {
            put("entityType", "INVALID")
            put("entityId", testTaskId.toString())
        }

        try {
            tool.validateParams(params)
            assert(false) { "Should have thrown ToolValidationException" }
        } catch (e: Exception) {
            assertTrue(e.message?.contains("Invalid entityType") == true)
        }
    }

    @Test
    fun `test validateParams with invalid UUID format`() = runBlocking {
        val params = buildJsonObject {
            put("entityType", "TASK")
            put("entityId", "not-a-uuid")
        }

        try {
            tool.validateParams(params)
            assert(false) { "Should have thrown ToolValidationException" }
        } catch (e: Exception) {
            assertTrue(e.message?.contains("Invalid entityId format") == true)
        }
    }

    @Test
    fun `test validateParams with invalid sectionIds format`() = runBlocking {
        val params = buildJsonObject {
            put("entityType", "TASK")
            put("entityId", testTaskId.toString())
            put("sectionIds", buildJsonArray {
                add("not-a-uuid")
            })
        }

        try {
            tool.validateParams(params)
            assert(false) { "Should have thrown ToolValidationException" }
        } catch (e: Exception) {
            assertTrue(e.message?.contains("Invalid section ID format") == true)
        }
    }

    // ========== BASIC QUERY TESTS ==========

    @Test
    fun `test query sections for task with content`() = runBlocking {
        val task = createTestTask()
        val sections = listOf(
            createTestSection(section1Id, "Requirements", "requirements"),
            createTestSection(section2Id, "Implementation", "implementation")
        )

        coEvery { taskRepository.getById(testTaskId) } returns Result.Success(task)
        coEvery { sectionRepository.getSectionsForEntity(EntityType.TASK, testTaskId) } returns Result.Success(sections)

        val params = buildJsonObject {
            put("entityType", "TASK")
            put("entityId", testTaskId.toString())
            put("includeContent", true)
        }

        val result = tool.execute(params, context)
        val resultObj = result.jsonObject

        assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
        assertEquals("Retrieved 2 sections", resultObj["message"]?.jsonPrimitive?.content)

        val data = resultObj["data"]?.jsonObject
        val sectionsArray = data?.get("sections")?.jsonArray

        assertEquals(2, sectionsArray?.size)
        assertEquals(2, data?.get("count")?.jsonPrimitive?.int)
        assertEquals("TASK", data?.get("entityType")?.jsonPrimitive?.content)

        // Verify content is included
        val firstSection = sectionsArray?.get(0)?.jsonObject
        assertTrue(firstSection?.containsKey("content") == true)
        assertEquals("Test content for Requirements", firstSection?.get("content")?.jsonPrimitive?.content)

        coVerify { taskRepository.getById(testTaskId) }
        coVerify { sectionRepository.getSectionsForEntity(EntityType.TASK, testTaskId) }
    }

    @Test
    fun `test query sections without content`() = runBlocking {
        val task = createTestTask()
        val sections = listOf(
            createTestSection(section1Id, "Requirements", "requirements")
        )

        coEvery { taskRepository.getById(testTaskId) } returns Result.Success(task)
        coEvery { sectionRepository.getSectionsForEntity(EntityType.TASK, testTaskId) } returns Result.Success(sections)

        val params = buildJsonObject {
            put("entityType", "TASK")
            put("entityId", testTaskId.toString())
            put("includeContent", false)
        }

        val result = tool.execute(params, context)
        val resultObj = result.jsonObject

        assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)

        val data = resultObj["data"]?.jsonObject
        val sectionsArray = data?.get("sections")?.jsonArray
        val firstSection = sectionsArray?.get(0)?.jsonObject

        // Verify content is NOT included
        assertFalse(firstSection?.containsKey("content") == true)

        // Verify metadata is still present
        assertTrue(firstSection?.containsKey("id") == true)
        assertTrue(firstSection?.containsKey("title") == true)
        assertTrue(firstSection?.containsKey("usageDescription") == true)
        assertTrue(firstSection?.containsKey("ordinal") == true)
        assertTrue(firstSection?.containsKey("tags") == true)
    }

    // ========== FILTERING TESTS ==========

    @Test
    fun `test query sections with sectionIds filter`() = runBlocking {
        val task = createTestTask()
        val sections = listOf(
            createTestSection(section1Id, "Requirements", "requirements"),
            createTestSection(section2Id, "Implementation", "implementation"),
            createTestSection(section3Id, "Testing", "testing")
        )

        coEvery { taskRepository.getById(testTaskId) } returns Result.Success(task)
        coEvery { sectionRepository.getSectionsForEntity(EntityType.TASK, testTaskId) } returns Result.Success(sections)

        val params = buildJsonObject {
            put("entityType", "TASK")
            put("entityId", testTaskId.toString())
            put("sectionIds", buildJsonArray {
                add(section1Id.toString())
                add(section3Id.toString())
            })
        }

        val result = tool.execute(params, context)
        val resultObj = result.jsonObject

        assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
        assertEquals("Retrieved 2 sections", resultObj["message"]?.jsonPrimitive?.content)

        val data = resultObj["data"]?.jsonObject
        val sectionsArray = data?.get("sections")?.jsonArray

        assertEquals(2, sectionsArray?.size)

        val returnedIds = sectionsArray?.map {
            it.jsonObject["id"]?.jsonPrimitive?.content
        }

        assertTrue(returnedIds?.contains(section1Id.toString()) == true)
        assertTrue(returnedIds?.contains(section3Id.toString()) == true)
        assertFalse(returnedIds?.contains(section2Id.toString()) == true)
    }

    @Test
    fun `test query sections with tags filter`() = runBlocking {
        val task = createTestTask()
        val sections = listOf(
            createTestSection(section1Id, "Requirements", "requirements", listOf("backend", "api")),
            createTestSection(section2Id, "Frontend", "frontend", listOf("frontend", "ui")),
            createTestSection(section3Id, "Database", "database", listOf("backend", "database"))
        )

        coEvery { taskRepository.getById(testTaskId) } returns Result.Success(task)
        coEvery { sectionRepository.getSectionsForEntity(EntityType.TASK, testTaskId) } returns Result.Success(sections)

        val params = buildJsonObject {
            put("entityType", "TASK")
            put("entityId", testTaskId.toString())
            put("tags", "backend,database")
        }

        val result = tool.execute(params, context)
        val resultObj = result.jsonObject

        assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)

        val data = resultObj["data"]?.jsonObject
        val sectionsArray = data?.get("sections")?.jsonArray

        // Should return sections 1 and 3 (both have "backend" or "database" tags)
        assertEquals(2, sectionsArray?.size)

        val returnedTitles = sectionsArray?.map {
            it.jsonObject["title"]?.jsonPrimitive?.content
        }

        assertTrue(returnedTitles?.contains("Requirements") == true)
        assertTrue(returnedTitles?.contains("Database") == true)
        assertFalse(returnedTitles?.contains("Frontend") == true)
    }

    @Test
    fun `test query sections with combined filters`() = runBlocking {
        val task = createTestTask()
        val sections = listOf(
            createTestSection(section1Id, "Requirements", "requirements", listOf("backend")),
            createTestSection(section2Id, "Implementation", "implementation", listOf("backend")),
            createTestSection(section3Id, "Testing", "testing", listOf("frontend"))
        )

        coEvery { taskRepository.getById(testTaskId) } returns Result.Success(task)
        coEvery { sectionRepository.getSectionsForEntity(EntityType.TASK, testTaskId) } returns Result.Success(sections)

        val params = buildJsonObject {
            put("entityType", "TASK")
            put("entityId", testTaskId.toString())
            put("sectionIds", buildJsonArray {
                add(section1Id.toString())
                add(section2Id.toString())
            })
            put("tags", "backend")
            put("includeContent", false)
        }

        val result = tool.execute(params, context)
        val resultObj = result.jsonObject

        assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)

        val data = resultObj["data"]?.jsonObject
        val sectionsArray = data?.get("sections")?.jsonArray

        // Should only return sections 1 and 2 (filtered by sectionIds AND tags)
        assertEquals(2, sectionsArray?.size)
    }

    // ========== ENTITY TYPE TESTS ==========

    @Test
    fun `test query sections for feature`() = runBlocking {
        val feature = createTestFeature()
        val sections = listOf(
            createTestSection(section1Id, "Overview", "overview")
        )

        coEvery { featureRepository.getById(testFeatureId) } returns Result.Success(feature)
        coEvery { sectionRepository.getSectionsForEntity(EntityType.FEATURE, testFeatureId) } returns Result.Success(sections)

        val params = buildJsonObject {
            put("entityType", "FEATURE")
            put("entityId", testFeatureId.toString())
        }

        val result = tool.execute(params, context)
        val resultObj = result.jsonObject

        assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
        assertEquals("FEATURE", resultObj["data"]?.jsonObject?.get("entityType")?.jsonPrimitive?.content)

        coVerify { featureRepository.getById(testFeatureId) }
        coVerify { sectionRepository.getSectionsForEntity(EntityType.FEATURE, testFeatureId) }
    }

    @Test
    fun `test query sections for project`() = runBlocking {
        val project = createTestProject()
        val sections = listOf(
            createTestSection(section1Id, "Charter", "charter")
        )

        coEvery { projectRepository.getById(testProjectId) } returns Result.Success(project)
        coEvery { sectionRepository.getSectionsForEntity(EntityType.PROJECT, testProjectId) } returns Result.Success(sections)

        val params = buildJsonObject {
            put("entityType", "PROJECT")
            put("entityId", testProjectId.toString())
        }

        val result = tool.execute(params, context)
        val resultObj = result.jsonObject

        assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
        assertEquals("PROJECT", resultObj["data"]?.jsonObject?.get("entityType")?.jsonPrimitive?.content)

        coVerify { projectRepository.getById(testProjectId) }
        coVerify { sectionRepository.getSectionsForEntity(EntityType.PROJECT, testProjectId) }
    }

    // ========== ERROR HANDLING TESTS ==========

    @Test
    fun `test query sections with entity not found`() = runBlocking {
        coEvery { taskRepository.getById(testTaskId) } returns Result.Error(
            RepositoryError.NotFound(testTaskId, EntityType.TASK, "Task not found")
        )

        val params = buildJsonObject {
            put("entityType", "TASK")
            put("entityId", testTaskId.toString())
        }

        val result = tool.execute(params, context)
        val resultObj = result.jsonObject

        assertFalse(resultObj["success"]?.jsonPrimitive?.boolean == true)
        assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("not found") == true)

        coVerify { taskRepository.getById(testTaskId) }
        coVerify(exactly = 0) { sectionRepository.getSectionsForEntity(any(), any()) }
    }

    @Test
    fun `test query sections with empty result`() = runBlocking {
        val task = createTestTask()

        coEvery { taskRepository.getById(testTaskId) } returns Result.Success(task)
        coEvery { sectionRepository.getSectionsForEntity(EntityType.TASK, testTaskId) } returns Result.Success(emptyList())

        val params = buildJsonObject {
            put("entityType", "TASK")
            put("entityId", testTaskId.toString())
        }

        val result = tool.execute(params, context)
        val resultObj = result.jsonObject

        assertTrue(resultObj["success"]?.jsonPrimitive?.boolean == true)
        assertEquals("No sections found for task", resultObj["message"]?.jsonPrimitive?.content)

        val data = resultObj["data"]?.jsonObject
        assertEquals(0, data?.get("count")?.jsonPrimitive?.int)
    }

    @Test
    fun `test query sections with repository error`() = runBlocking {
        val task = createTestTask()

        coEvery { taskRepository.getById(testTaskId) } returns Result.Success(task)
        coEvery { sectionRepository.getSectionsForEntity(EntityType.TASK, testTaskId) } returns
            Result.Error(RepositoryError.DatabaseError("Database connection failed"))

        val params = buildJsonObject {
            put("entityType", "TASK")
            put("entityId", testTaskId.toString())
        }

        val result = tool.execute(params, context)
        val resultObj = result.jsonObject

        assertFalse(resultObj["success"]?.jsonPrimitive?.boolean == true)
        assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("Database error") == true)
    }

    // ========== HELPER METHODS ==========

    private fun createTestTask(): Task {
        return Task(
            id = testTaskId,
            title = "Test Task",
            summary = "Test summary",
            description = "Test description",
            status = TaskStatus.PENDING,
            priority = Priority.MEDIUM,
            complexity = 5,
            projectId = testProjectId,
            featureId = testFeatureId,
            tags = emptyList(),
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )
    }

    private fun createTestFeature(): Feature {
        return Feature(
            id = testFeatureId,
            name = "Test Feature",
            summary = "Test summary",
            description = "Test description",
            status = FeatureStatus.PLANNING,
            priority = Priority.HIGH,
            projectId = testProjectId,
            tags = emptyList(),
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )
    }

    private fun createTestProject(): Project {
        return Project(
            id = testProjectId,
            name = "Test Project",
            summary = "Test summary",
            description = "Test description",
            status = ProjectStatus.PLANNING,
            tags = emptyList(),
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )
    }

    private fun createTestSection(
        id: UUID,
        title: String,
        usageDescription: String,
        tags: List<String> = emptyList()
    ): Section {
        return Section(
            id = id,
            entityType = EntityType.TASK,
            entityId = testTaskId,
            title = title,
            usageDescription = usageDescription,
            content = "Test content for $title",
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 0,
            tags = tags,
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )
    }
}
