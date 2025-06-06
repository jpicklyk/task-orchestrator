package io.github.jpicklyk.mcptask.application.tools.task

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.repository.*
import io.github.jpicklyk.mcptask.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.test.mock.MockDependencyRepository
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

class GetTaskToolTest {

    private val tool = GetTaskTool()
    private lateinit var mockContext: ToolExecutionContext
    private lateinit var mockTaskRepository: TaskRepository
    private lateinit var mockFeatureRepository: FeatureRepository
    private lateinit var mockSectionRepository: SectionRepository
    private lateinit var mockDependencyRepository: MockDependencyRepository
    private lateinit var mockTask: Task
    private val taskId = UUID.randomUUID()
    private val featureId = UUID.randomUUID()
    private val relatedTask1Id = UUID.randomUUID()
    private val relatedTask2Id = UUID.randomUUID()
    private val relatedTask3Id = UUID.randomUUID()
    private val mockSections = listOf(
        Section(
            id = UUID.randomUUID(),
            entityType = EntityType.TASK,
            entityId = taskId,
            title = "Test Section 1",
            usageDescription = "Test Usage 1",
            content = "Test Content 1",
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 0
        ),
        Section(
            id = UUID.randomUUID(),
            entityType = EntityType.TASK,
            entityId = taskId,
            title = "Test Section 2",
            usageDescription = "Test Usage 2",
            content = "Test Content 2",
            contentFormat = ContentFormat.MARKDOWN,
            ordinal = 1
        )
    )

    @BeforeEach
    fun setup() {
        // Create a mock task
        mockTask = Task(
            id = taskId,
            title = "Test Task",
            summary = "This is a test task description that is somewhat long to test truncation",
            status = TaskStatus.IN_PROGRESS,
            priority = Priority.HIGH,
            complexity = 7,
            createdAt = Instant.now().minusSeconds(3600),
            modifiedAt = Instant.now(),
            tags = listOf("test", "important", "development"),
            featureId = featureId
        )

        // Create mock repositories
        mockTaskRepository = mockk<TaskRepository>()
        mockFeatureRepository = mockk<FeatureRepository>()
        mockSectionRepository = mockk<SectionRepository>()
        mockDependencyRepository = MockDependencyRepository()
        val mockRepositoryProvider = mockk<RepositoryProvider>()

        // Configure the repository provider to return the mock repositories
        every { mockRepositoryProvider.taskRepository() } returns mockTaskRepository
        every { mockRepositoryProvider.featureRepository() } returns mockFeatureRepository
        every { mockRepositoryProvider.sectionRepository() } returns mockSectionRepository
        every { mockRepositoryProvider.dependencyRepository() } returns mockDependencyRepository

        // Mock the task repository responses
        coEvery {
            mockTaskRepository.getById(taskId)
        } returns Result.Success(mockTask)

        coEvery {
            mockTaskRepository.getById(not(taskId))
        } returns Result.Error(RepositoryError.NotFound(UUID.randomUUID(), EntityType.TASK, "Task not found"))

        // Mock feature repository response for tests that include feature information
        coEvery {
            mockFeatureRepository.getById(featureId)
        } returns Result.Success(
            Feature(
                id = featureId,
                name = "Test Feature",
                summary = "Test Feature Description",
                status = FeatureStatus.IN_DEVELOPMENT
            )
        )

        // Default mock for section repository
        coEvery {
            mockSectionRepository.getSectionsForEntity(any(), any())
        } returns Result.Success(emptyList())

        // Specific mock for our task's sections
        coEvery {
            mockSectionRepository.getSectionsForEntity(EntityType.TASK, taskId)
        } returns Result.Success(mockSections)

        // Create the execution context with the mock repository provider
        mockContext = ToolExecutionContext(mockRepositoryProvider)
    }

    @Test
    fun `validate with valid parameters should not throw exceptions`() {
        val validParams = JsonObject(
            mapOf(
                "id" to JsonPrimitive(taskId.toString()),
                "includeSubtasks" to JsonPrimitive("true"),
                "includeDependencies" to JsonPrimitive("true"),
                "includeFeature" to JsonPrimitive("true"),
                "summaryView" to JsonPrimitive("false")
            )
        )

        // Should not throw any exceptions
        tool.validateParams(validParams)
    }

    @Test
    fun `validate with only required parameters should not throw exceptions`() {
        val minimalParams = JsonObject(
            mapOf(
                "id" to JsonPrimitive(taskId.toString())
            )
        )

        // Should not throw any exceptions
        tool.validateParams(minimalParams)
    }

    @Test
    fun `validate without required parameter should throw validation exception`() {
        val invalidParams = JsonObject(
            mapOf(
                "includeSubtasks" to JsonPrimitive(true)
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(invalidParams)
        }

        // Based on the implementation in TaskManagementTool, the error message is 
        // "Missing required parameter: id" not "Required parameter 'id' is missing"
        assertTrue(exception.message!!.contains("Missing required parameter: id"))
    }

    @Test
    fun `validate with invalid UUID should throw validation exception`() {
        val invalidParams = JsonObject(
            mapOf(
                "id" to JsonPrimitive("not-a-uuid")
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(invalidParams)
        }

        assertTrue(exception.message!!.contains("Invalid task ID format"))
    }

    @Test
    fun `execute with valid ID should return task details`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(taskId.toString()),
                "includeSections" to JsonPrimitive("true")
            )
        )

        val response = tool.execute(params, mockContext)

        // Step 1: Verify basic response structure
        assertTrue(response is JsonObject, "Response should be a JsonObject")
        val responseObj = response as JsonObject

        // Step 2: Verify success flag
        assertTrue(responseObj.containsKey("success"), "Response should contain success field")
        val success = responseObj["success"]
        assertTrue(success is JsonPrimitive, "Success should be a JsonPrimitive")
        assertTrue((success as JsonPrimitive).boolean, "Success should be true")

        // Step 3: Verify message field
        assertTrue(responseObj.containsKey("message"), "Response should contain message field")
        val message = responseObj["message"]
        assertTrue(message is JsonPrimitive, "Message should be a JsonPrimitive")
        assertEquals("Task retrieved successfully", (message as JsonPrimitive).content)

        // Step 4: Verify data field exists
        assertTrue(responseObj.containsKey("data"), "Response should contain data field")
        val data = responseObj["data"]
        assertTrue(data is JsonObject, "Data should be a JsonObject")
        val dataObj = data as JsonObject

        // Step 5: Verify basic task properties
        assertTrue(dataObj.containsKey("id"), "Data should contain id field")
        assertEquals(taskId.toString(), dataObj["id"]?.jsonPrimitive?.content)

        assertTrue(dataObj.containsKey("title"), "Data should contain title field")
        assertEquals("Test Task", dataObj["title"]?.jsonPrimitive?.content)

        assertTrue(dataObj.containsKey("summary"), "Data should contain summary field")
        assertEquals(
            "This is a test task description that is somewhat long to test truncation",
            dataObj["summary"]?.jsonPrimitive?.content
        )

        assertTrue(dataObj.containsKey("status"), "Data should contain status field")
        assertEquals(TaskStatus.IN_PROGRESS.name.lowercase(), dataObj["status"]?.jsonPrimitive?.content)

        assertTrue(dataObj.containsKey("priority"), "Data should contain priority field")
        assertEquals(Priority.HIGH.name.lowercase(), dataObj["priority"]?.jsonPrimitive?.content)

        assertTrue(dataObj.containsKey("complexity"), "Data should contain complexity field")
        assertEquals(7, dataObj["complexity"]?.jsonPrimitive?.int)

        // Step 6: Verify sections array
        assertTrue(dataObj.containsKey("sections"), "Data should contain sections field")
        val sections = dataObj["sections"]
        assertTrue(sections is JsonArray, "Sections should be a JsonArray")
        val sectionsArray = sections as JsonArray
        assertEquals(2, sectionsArray.size, "There should be 2 sections")

        // Step 7: Verify first section content
        val firstSection = sectionsArray[0].jsonObject
        assertTrue(firstSection.containsKey("title"), "Section should contain title field")
        assertEquals("Test Section 1", firstSection["title"]?.jsonPrimitive?.content)

        assertTrue(firstSection.containsKey("content"), "Section should contain content field")
        assertEquals("Test Content 1", firstSection["content"]?.jsonPrimitive?.content)

        assertTrue(firstSection.containsKey("contentFormat"), "Section should contain contentFormat field")
        assertEquals("markdown", firstSection["contentFormat"]?.jsonPrimitive?.content)
    }

    @Test
    fun `execute with summary view should return simplified task details`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(taskId.toString()),
                "summaryView" to JsonPrimitive("true"),
                "includeSections" to JsonPrimitive("true")
            )
        )

        val response = tool.execute(params, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        val success = responseObj["success"]?.jsonPrimitive?.boolean ?: false
        assertTrue(success, "Response should indicate success")

        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data object should not be null")

        // Verify basic task properties are present
        assertNotNull(data!!["id"], "ID should be present")
        assertEquals(taskId.toString(), data["id"]?.jsonPrimitive?.content)

        assertNotNull(data["title"], "Title should be present")
        assertEquals("Test Task", data["title"]?.jsonPrimitive?.content)

        assertNotNull(data["status"], "Status should be present")
        assertEquals(TaskStatus.IN_PROGRESS.name.lowercase(), data["status"]?.jsonPrimitive?.content)

        assertNotNull(data["priority"], "Priority should be present")
        assertEquals(Priority.HIGH.name.lowercase(), data["priority"]?.jsonPrimitive?.content)

        assertNotNull(data["complexity"], "Complexity should be present")
        assertEquals(7, data["complexity"]?.jsonPrimitive?.int)

        assertNotNull(data["createdAt"], "Created date should be present")
        assertNotNull(data["modifiedAt"], "Modified date should be present")

        // Verify summary is truncated if necessary
        if (data.containsKey("summary")) {
            val summary = data["summary"]?.jsonPrimitive?.content
            assertNotNull(summary, "Summary should be present")
            assertTrue(summary!!.length <= mockTask.summary.length, "Summary should be truncated or equal length")

            // If summary is truncated, it should end with "..."
            if (mockTask.summary.length > 100) {
                assertTrue(summary.endsWith("..."), "Long summary should be truncated with '...'")
                assertEquals("${mockTask.summary.take(97)}...", summary)
            } else {
                assertEquals(mockTask.summary, summary)
            }
        }

        // Verify sections are included and content is truncated if necessary
        val sections = data["sections"]?.jsonArray
        assertNotNull(sections, "Sections array should be present")
        assertEquals(2, sections!!.size, "There should be 2 sections")

        // Verify the first section
        val firstSection = sections[0].jsonObject
        assertNotNull(firstSection["title"], "Section title should be present")
        assertEquals("Test Section 1", firstSection["title"]?.jsonPrimitive?.content)

        // Content should be present
        assertNotNull(firstSection["content"], "Section content should be present")

        // Format should be present
        assertNotNull(firstSection["contentFormat"], "Content format should be present")
        assertEquals("markdown", firstSection["contentFormat"]?.jsonPrimitive?.content)
    }

    @Test
    fun `execute with includeFeature should include feature information`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(taskId.toString()),
                "includeFeature" to JsonPrimitive("true")
            )
        )

        val response = tool.execute(params, mockContext)

        // First verify basic success response
        assertTrue(response is JsonObject, "Response should be a JsonObject")
        val responseObj = response as JsonObject
        assertTrue(responseObj.containsKey("success"), "Response should contain success field")
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Success should be true")

        // Verify data field
        assertTrue(responseObj.containsKey("data"), "Response should contain data field")
        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data object should not be null")

        // Verify feature ID is included
        assertTrue(data!!.containsKey("featureId"), "Data should contain featureId field")
        assertEquals(featureId.toString(), data["featureId"]?.jsonPrimitive?.content, "Feature ID should match")

        // Verify feature information is included
        assertTrue(data.containsKey("feature"), "Response should include feature object")
        val feature = data["feature"]?.jsonObject
        assertNotNull(feature, "Feature object should not be null")

        // Verify feature properties
        assertTrue(feature!!.containsKey("id"), "Feature should contain id field")
        assertEquals(featureId.toString(), feature["id"]?.jsonPrimitive?.content, "Feature ID should match")

        assertTrue(feature.containsKey("name"), "Feature should contain name field")
        assertEquals("Test Feature", feature["name"]?.jsonPrimitive?.content, "Feature name should match")

        assertTrue(feature.containsKey("status"), "Feature should contain status field")
        assertEquals(
            FeatureStatus.IN_DEVELOPMENT.name.lowercase(),
            feature["status"]?.jsonPrimitive?.content,
            "Feature status should match"
        )

        // Verify feature summary is included (since summaryView is false by default)
        assertTrue(feature.containsKey("summary"), "Feature should contain summary field")
        assertEquals(
            "Test Feature Description",
            feature["summary"]?.jsonPrimitive?.content,
            "Feature summary should match"
        )
    }

    @Test
    fun `execute with non-existent ID should return error`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(UUID.randomUUID().toString())
            )
        )

        val response = tool.execute(params, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        val success = responseObj["success"]?.jsonPrimitive?.boolean ?: true
        assertFalse(success, "Response should indicate failure")

        // Check that the message field exists and contains expected text about the error
        val message = responseObj["message"]?.jsonPrimitive?.content
        assertNotNull(message)
        assertTrue(message!!.contains("Task not found"))
    }

    // ========================================
    // Dependency Feature Tests
    // ========================================

    @Test
    fun `execute with includeDependencies false should not include dependency information`() = runBlocking {
        // Create test dependencies (they should not be included in response)
        val dependency1 = Dependency(
            fromTaskId = relatedTask1Id,
            toTaskId = taskId,
            type = DependencyType.BLOCKS,
            createdAt = Instant.now()
        )
        mockDependencyRepository.addDependency(dependency1)

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(taskId.toString()),
                "includeDependencies" to JsonPrimitive(false)
            )
        )

        val response = tool.execute(params, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Success should be true")

        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data object should not be null")

        // Verify dependencies are NOT included
        assertFalse(data!!.containsKey("dependencies"), "Dependencies should not be included when includeDependencies=false")
    }

    @Test
    fun `execute with includeDependencies true and no dependencies should return empty dependency structure`() = runBlocking {
        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(taskId.toString()),
                "includeDependencies" to JsonPrimitive(true)
            )
        )

        val response = tool.execute(params, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Success should be true")

        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data object should not be null")

        // Verify dependencies structure is included
        assertTrue(data!!.containsKey("dependencies"), "Dependencies should be included when includeDependencies=true")
        val dependencies = data["dependencies"]?.jsonObject
        assertNotNull(dependencies, "Dependencies object should not be null")

        // Verify incoming dependencies
        assertTrue(dependencies!!.containsKey("incoming"), "Dependencies should contain incoming array")
        val incoming = dependencies["incoming"]?.jsonArray
        assertNotNull(incoming, "Incoming array should not be null")
        assertEquals(0, incoming!!.size, "Should have no incoming dependencies")

        // Verify outgoing dependencies
        assertTrue(dependencies.containsKey("outgoing"), "Dependencies should contain outgoing array")
        val outgoing = dependencies["outgoing"]?.jsonArray
        assertNotNull(outgoing, "Outgoing array should not be null")
        assertEquals(0, outgoing!!.size, "Should have no outgoing dependencies")

        // Verify counts
        assertTrue(dependencies.containsKey("counts"), "Dependencies should contain counts object")
        val counts = dependencies["counts"]?.jsonObject
        assertNotNull(counts, "Counts object should not be null")
        assertEquals(0, counts!!["total"]?.jsonPrimitive?.int, "Total count should be 0")
        assertEquals(0, counts["incoming"]?.jsonPrimitive?.int, "Incoming count should be 0")
        assertEquals(0, counts["outgoing"]?.jsonPrimitive?.int, "Outgoing count should be 0")
    }

    @Test
    fun `execute with includeDependencies true should include incoming dependencies`() = runBlocking {
        // Create incoming dependencies (other tasks depend on this task)
        val incomingDep1 = Dependency(
            fromTaskId = relatedTask1Id,
            toTaskId = taskId,
            type = DependencyType.BLOCKS,
            createdAt = Instant.now().minusSeconds(100)
        )
        val incomingDep2 = Dependency(
            fromTaskId = relatedTask2Id,
            toTaskId = taskId,
            type = DependencyType.IS_BLOCKED_BY,
            createdAt = Instant.now()
        )
        mockDependencyRepository.addDependencies(listOf(incomingDep1, incomingDep2))

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(taskId.toString()),
                "includeDependencies" to JsonPrimitive(true)
            )
        )

        val response = tool.execute(params, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Success should be true")

        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data object should not be null")

        val dependencies = data!!["dependencies"]?.jsonObject
        assertNotNull(dependencies, "Dependencies object should not be null")

        // Verify incoming dependencies
        val incoming = dependencies!!["incoming"]?.jsonArray
        assertNotNull(incoming, "Incoming array should not be null")
        assertEquals(2, incoming!!.size, "Should have 2 incoming dependencies")

        // Verify incoming dependencies - collect all and check content
        val incomingDeps = incoming.map { it.jsonObject }
        
        // All incoming dependencies should have taskId as toTaskId
        incomingDeps.forEach { dep ->
            assertEquals(taskId.toString(), dep["toTaskId"]?.jsonPrimitive?.content, "All incoming deps should have taskId as toTaskId")
            assertNotNull(dep["id"], "Dependency ID should be present")
            assertNotNull(dep["createdAt"], "Created at should be present")
        }
        
        // Check that we have the expected fromTaskIds and types
        val fromTaskIds = incomingDeps.map { it["fromTaskId"]?.jsonPrimitive?.content }.toSet()
        val types = incomingDeps.map { it["type"]?.jsonPrimitive?.content }.toSet()
        
        assertTrue(fromTaskIds.contains(relatedTask1Id.toString()), "Should contain relatedTask1Id as fromTaskId")
        assertTrue(fromTaskIds.contains(relatedTask2Id.toString()), "Should contain relatedTask2Id as fromTaskId")
        assertTrue(types.contains("BLOCKS"), "Should contain BLOCKS type")
        assertTrue(types.contains("IS_BLOCKED_BY"), "Should contain IS_BLOCKED_BY type")

        // Verify outgoing dependencies (should be empty)
        val outgoing = dependencies["outgoing"]?.jsonArray
        assertNotNull(outgoing, "Outgoing array should not be null")
        assertEquals(0, outgoing!!.size, "Should have no outgoing dependencies")

        // Verify counts
        val counts = dependencies["counts"]?.jsonObject
        assertNotNull(counts, "Counts object should not be null")
        assertEquals(2, counts!!["total"]?.jsonPrimitive?.int, "Total count should be 2")
        assertEquals(2, counts["incoming"]?.jsonPrimitive?.int, "Incoming count should be 2")
        assertEquals(0, counts["outgoing"]?.jsonPrimitive?.int, "Outgoing count should be 0")
    }

    @Test
    fun `execute with includeDependencies true should include outgoing dependencies`() = runBlocking {
        // Create outgoing dependencies (this task depends on other tasks)
        val outgoingDep1 = Dependency(
            fromTaskId = taskId,
            toTaskId = relatedTask1Id,
            type = DependencyType.BLOCKS,
            createdAt = Instant.now().minusSeconds(50)
        )
        val outgoingDep2 = Dependency(
            fromTaskId = taskId,
            toTaskId = relatedTask2Id,
            type = DependencyType.RELATES_TO,
            createdAt = Instant.now()
        )
        mockDependencyRepository.addDependencies(listOf(outgoingDep1, outgoingDep2))

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(taskId.toString()),
                "includeDependencies" to JsonPrimitive(true)
            )
        )

        val response = tool.execute(params, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Success should be true")

        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data object should not be null")

        val dependencies = data!!["dependencies"]?.jsonObject
        assertNotNull(dependencies, "Dependencies object should not be null")

        // Verify incoming dependencies (should be empty)
        val incoming = dependencies!!["incoming"]?.jsonArray
        assertNotNull(incoming, "Incoming array should not be null")
        assertEquals(0, incoming!!.size, "Should have no incoming dependencies")

        // Verify outgoing dependencies
        val outgoing = dependencies["outgoing"]?.jsonArray
        assertNotNull(outgoing, "Outgoing array should not be null")
        assertEquals(2, outgoing!!.size, "Should have 2 outgoing dependencies")

        // Verify outgoing dependencies - collect all and check content
        val outgoingDeps = outgoing.map { it.jsonObject }
        
        // All outgoing dependencies should have taskId as fromTaskId
        outgoingDeps.forEach { dep ->
            assertEquals(taskId.toString(), dep["fromTaskId"]?.jsonPrimitive?.content, "All outgoing deps should have taskId as fromTaskId")
            assertNotNull(dep["id"], "Dependency ID should be present")
            assertNotNull(dep["createdAt"], "Created at should be present")
        }
        
        // Check that we have the expected toTaskIds and types
        val toTaskIds = outgoingDeps.map { it["toTaskId"]?.jsonPrimitive?.content }.toSet()
        val types = outgoingDeps.map { it["type"]?.jsonPrimitive?.content }.toSet()
        
        assertTrue(toTaskIds.contains(relatedTask1Id.toString()), "Should contain relatedTask1Id as toTaskId")
        assertTrue(toTaskIds.contains(relatedTask2Id.toString()), "Should contain relatedTask2Id as toTaskId")
        assertTrue(types.contains("BLOCKS"), "Should contain BLOCKS type")
        assertTrue(types.contains("RELATES_TO"), "Should contain RELATES_TO type")

        // Verify counts
        val counts = dependencies["counts"]?.jsonObject
        assertNotNull(counts, "Counts object should not be null")
        assertEquals(2, counts!!["total"]?.jsonPrimitive?.int, "Total count should be 2")
        assertEquals(0, counts["incoming"]?.jsonPrimitive?.int, "Incoming count should be 0")
        assertEquals(2, counts["outgoing"]?.jsonPrimitive?.int, "Outgoing count should be 2")
    }

    @Test
    fun `execute with includeDependencies true should include mixed incoming and outgoing dependencies`() = runBlocking {
        // Create mixed dependencies
        val incomingDep1 = Dependency(
            fromTaskId = relatedTask1Id,
            toTaskId = taskId,
            type = DependencyType.BLOCKS,
            createdAt = Instant.now().minusSeconds(200)
        )
        val incomingDep2 = Dependency(
            fromTaskId = relatedTask2Id,
            toTaskId = taskId,
            type = DependencyType.IS_BLOCKED_BY,
            createdAt = Instant.now().minusSeconds(150)
        )
        val outgoingDep1 = Dependency(
            fromTaskId = taskId,
            toTaskId = relatedTask3Id,
            type = DependencyType.RELATES_TO,
            createdAt = Instant.now().minusSeconds(100)
        )
        mockDependencyRepository.addDependencies(listOf(incomingDep1, incomingDep2, outgoingDep1))

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(taskId.toString()),
                "includeDependencies" to JsonPrimitive(true)
            )
        )

        val response = tool.execute(params, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Success should be true")

        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data object should not be null")

        val dependencies = data!!["dependencies"]?.jsonObject
        assertNotNull(dependencies, "Dependencies object should not be null")

        // Verify incoming dependencies
        val incoming = dependencies!!["incoming"]?.jsonArray
        assertNotNull(incoming, "Incoming array should not be null")
        assertEquals(2, incoming!!.size, "Should have 2 incoming dependencies")

        // Verify outgoing dependencies
        val outgoing = dependencies["outgoing"]?.jsonArray
        assertNotNull(outgoing, "Outgoing array should not be null")
        assertEquals(1, outgoing!!.size, "Should have 1 outgoing dependency")

        // Verify counts
        val counts = dependencies["counts"]?.jsonObject
        assertNotNull(counts, "Counts object should not be null")
        assertEquals(3, counts!!["total"]?.jsonPrimitive?.int, "Total count should be 3")
        assertEquals(2, counts["incoming"]?.jsonPrimitive?.int, "Incoming count should be 2")
        assertEquals(1, counts["outgoing"]?.jsonPrimitive?.int, "Outgoing count should be 1")

        // Verify dependency structure consistency
        incoming.forEach { dep ->
            val depObj = dep.jsonObject
            assertEquals(taskId.toString(), depObj["toTaskId"]?.jsonPrimitive?.content, "All incoming deps should have taskId as toTaskId")
            assertNotEquals(taskId.toString(), depObj["fromTaskId"]?.jsonPrimitive?.content, "Incoming deps should not have taskId as fromTaskId")
        }

        outgoing.forEach { dep ->
            val depObj = dep.jsonObject
            assertEquals(taskId.toString(), depObj["fromTaskId"]?.jsonPrimitive?.content, "All outgoing deps should have taskId as fromTaskId")
            assertNotEquals(taskId.toString(), depObj["toTaskId"]?.jsonPrimitive?.content, "Outgoing deps should not have taskId as toTaskId")
        }
    }

    @Test
    fun `execute with includeDependencies true should handle different dependency types correctly`() = runBlocking {
        // Create dependencies with all possible types
        val blocksDep = Dependency(
            fromTaskId = relatedTask1Id,
            toTaskId = taskId,
            type = DependencyType.BLOCKS,
            createdAt = Instant.now().minusSeconds(300)
        )
        val isBlockedByDep = Dependency(
            fromTaskId = taskId,
            toTaskId = relatedTask2Id,
            type = DependencyType.IS_BLOCKED_BY,
            createdAt = Instant.now().minusSeconds(200)
        )
        val relatesToDep = Dependency(
            fromTaskId = relatedTask3Id,
            toTaskId = taskId,
            type = DependencyType.RELATES_TO,
            createdAt = Instant.now().minusSeconds(100)
        )
        mockDependencyRepository.addDependencies(listOf(blocksDep, isBlockedByDep, relatesToDep))

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(taskId.toString()),
                "includeDependencies" to JsonPrimitive(true)
            )
        )

        val response = tool.execute(params, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Success should be true")

        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data object should not be null")

        val dependencies = data!!["dependencies"]?.jsonObject
        assertNotNull(dependencies, "Dependencies object should not be null")

        // Collect all dependency types from the response
        val allDependencies = mutableListOf<JsonObject>()
        dependencies!!["incoming"]?.jsonArray?.forEach { allDependencies.add(it.jsonObject) }
        dependencies["outgoing"]?.jsonArray?.forEach { allDependencies.add(it.jsonObject) }

        val dependencyTypes = allDependencies.map { it["type"]?.jsonPrimitive?.content }.toSet()
        
        // Verify all dependency types are present
        assertEquals(3, dependencyTypes.size, "Should have 3 different dependency types")
        assertTrue(dependencyTypes.contains("BLOCKS"), "Should include BLOCKS dependency")
        assertTrue(dependencyTypes.contains("IS_BLOCKED_BY"), "Should include IS_BLOCKED_BY dependency")
        assertTrue(dependencyTypes.contains("RELATES_TO"), "Should include RELATES_TO dependency")

        // Verify structure and count
        assertEquals(3, allDependencies.size, "Should have 3 total dependencies")
        assertEquals(2, dependencies["incoming"]?.jsonArray?.size, "Should have 2 incoming dependencies")
        assertEquals(1, dependencies["outgoing"]?.jsonArray?.size, "Should have 1 outgoing dependency")

        val counts = dependencies["counts"]?.jsonObject
        assertNotNull(counts, "Counts object should not be null")
        assertEquals(3, counts!!["total"]?.jsonPrimitive?.int, "Total count should be 3")
    }

    @Test
    fun `execute with includeDependencies true should handle dependency repository errors gracefully`() = runBlocking {
        // Configure mock dependency repository to throw an exception
        mockDependencyRepository.nextFindByIdResult = { throw RuntimeException("Database connection failed") }

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(taskId.toString()),
                "includeDependencies" to JsonPrimitive(true)
            )
        )

        val response = tool.execute(params, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Success should still be true")

        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data object should not be null")

        // Verify dependencies structure is included with empty data
        assertTrue(data!!.containsKey("dependencies"), "Dependencies should be included even with error")
        val dependencies = data["dependencies"]?.jsonObject
        assertNotNull(dependencies, "Dependencies object should not be null")

        // Verify empty arrays and zero counts
        val incoming = dependencies!!["incoming"]?.jsonArray
        val outgoing = dependencies["outgoing"]?.jsonArray
        val counts = dependencies["counts"]?.jsonObject
        
        assertNotNull(incoming, "Incoming array should not be null")
        assertNotNull(outgoing, "Outgoing array should not be null")
        assertNotNull(counts, "Counts object should not be null")
        
        assertEquals(0, incoming!!.size, "Incoming should be empty on error")
        assertEquals(0, outgoing!!.size, "Outgoing should be empty on error")
        assertEquals(0, counts!!["total"]?.jsonPrimitive?.int, "Total count should be 0 on error")
        assertEquals(0, counts["incoming"]?.jsonPrimitive?.int, "Incoming count should be 0 on error")
        assertEquals(0, counts["outgoing"]?.jsonPrimitive?.int, "Outgoing count should be 0 on error")
    }

    @Test
    fun `execute with includeDependencies and includeSections should include both`() = runBlocking {
        // Create test dependencies
        val incomingDep = Dependency(
            fromTaskId = relatedTask1Id,
            toTaskId = taskId,
            type = DependencyType.BLOCKS,
            createdAt = Instant.now()
        )
        mockDependencyRepository.addDependency(incomingDep)

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(taskId.toString()),
                "includeDependencies" to JsonPrimitive(true),
                "includeSections" to JsonPrimitive(true)
            )
        )

        val response = tool.execute(params, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Success should be true")

        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data object should not be null")

        // Verify both dependencies and sections are included
        assertTrue(data!!.containsKey("dependencies"), "Dependencies should be included")
        assertTrue(data.containsKey("sections"), "Sections should be included")

        // Verify dependencies structure
        val dependencies = data["dependencies"]?.jsonObject
        assertNotNull(dependencies, "Dependencies object should not be null")
        assertEquals(1, dependencies!!["incoming"]?.jsonArray?.size, "Should have 1 incoming dependency")
        assertEquals(0, dependencies["outgoing"]?.jsonArray?.size, "Should have 0 outgoing dependencies")

        // Verify sections structure
        val sections = data["sections"]?.jsonArray
        assertNotNull(sections, "Sections array should not be null")
        assertEquals(2, sections!!.size, "Should have 2 sections")
    }

    @Test
    fun `execute with includeDependencies and includeFeature should include both`() = runBlocking {
        // Create test dependencies
        val outgoingDep = Dependency(
            fromTaskId = taskId,
            toTaskId = relatedTask1Id,
            type = DependencyType.RELATES_TO,
            createdAt = Instant.now()
        )
        mockDependencyRepository.addDependency(outgoingDep)

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(taskId.toString()),
                "includeDependencies" to JsonPrimitive(true),
                "includeFeature" to JsonPrimitive(true)
            )
        )

        val response = tool.execute(params, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Success should be true")

        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data object should not be null")

        // Verify both dependencies and feature are included
        assertTrue(data!!.containsKey("dependencies"), "Dependencies should be included")
        assertTrue(data.containsKey("feature"), "Feature should be included")

        // Verify dependencies structure
        val dependencies = data["dependencies"]?.jsonObject
        assertNotNull(dependencies, "Dependencies object should not be null")
        assertEquals(0, dependencies!!["incoming"]?.jsonArray?.size, "Should have 0 incoming dependencies")
        assertEquals(1, dependencies["outgoing"]?.jsonArray?.size, "Should have 1 outgoing dependency")

        // Verify feature structure
        val feature = data["feature"]?.jsonObject
        assertNotNull(feature, "Feature object should not be null")
        assertEquals(featureId.toString(), feature!!["id"]?.jsonPrimitive?.content, "Feature ID should match")
        assertEquals("Test Feature", feature["name"]?.jsonPrimitive?.content, "Feature name should match")
    }

    @Test
    fun `execute with all include parameters should include all information`() = runBlocking {
        // Create comprehensive test data
        val incomingDep = Dependency(
            fromTaskId = relatedTask1Id,
            toTaskId = taskId,
            type = DependencyType.BLOCKS,
            createdAt = Instant.now().minusSeconds(100)
        )
        val outgoingDep = Dependency(
            fromTaskId = taskId,
            toTaskId = relatedTask2Id,
            type = DependencyType.IS_BLOCKED_BY,
            createdAt = Instant.now()
        )
        mockDependencyRepository.addDependencies(listOf(incomingDep, outgoingDep))

        val params = JsonObject(
            mapOf(
                "id" to JsonPrimitive(taskId.toString()),
                "includeDependencies" to JsonPrimitive(true),
                "includeSections" to JsonPrimitive(true),
                "includeFeature" to JsonPrimitive(true),
                "summaryView" to JsonPrimitive(false)
            )
        )

        val response = tool.execute(params, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Success should be true")

        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data object should not be null")

        // Verify all information is included
        assertTrue(data!!.containsKey("dependencies"), "Dependencies should be included")
        assertTrue(data.containsKey("sections"), "Sections should be included")
        assertTrue(data.containsKey("feature"), "Feature should be included")

        // Verify basic task information is still present
        assertEquals(taskId.toString(), data["id"]?.jsonPrimitive?.content, "Task ID should match")
        assertEquals("Test Task", data["title"]?.jsonPrimitive?.content, "Task title should match")
        assertEquals(TaskStatus.IN_PROGRESS.name.lowercase(), data["status"]?.jsonPrimitive?.content, "Task status should match")

        // Verify dependencies
        val dependencies = data["dependencies"]?.jsonObject
        assertNotNull(dependencies, "Dependencies object should not be null")
        assertEquals(1, dependencies!!["incoming"]?.jsonArray?.size, "Should have 1 incoming dependency")
        assertEquals(1, dependencies["outgoing"]?.jsonArray?.size, "Should have 1 outgoing dependency")
        assertEquals(2, dependencies["counts"]?.jsonObject?.get("total")?.jsonPrimitive?.int, "Total dependencies should be 2")

        // Verify sections
        val sections = data["sections"]?.jsonArray
        assertNotNull(sections, "Sections array should not be null")
        assertEquals(2, sections!!.size, "Should have 2 sections")

        // Verify feature
        val feature = data["feature"]?.jsonObject
        assertNotNull(feature, "Feature object should not be null")
        assertEquals(featureId.toString(), feature!!["id"]?.jsonPrimitive?.content, "Feature ID should match")
    }
}