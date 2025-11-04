package io.github.jpicklyk.mcptask.integration

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ManageContainerTool
import io.github.jpicklyk.mcptask.application.tools.dependency.ManageDependencyTool
import io.github.jpicklyk.mcptask.application.tools.section.ManageSectionsTool
import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.github.jpicklyk.mcptask.test.mock.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*

/**
 * Integration tests for cascade deletion behavior when tasks with dependencies are deleted.
 * 
 * Tests various cascade deletion scenarios including:
 * - Task deletion with dependency cleanup
 * - Force deletion behavior and warnings
 * - Section cleanup during deletion
 * - Complex dependency chain cleanup
 * - Error handling during cascade operations
 */
class CascadeDeletionBehaviorTest {
    private lateinit var dependencyRepository: MockDependencyRepository
    private lateinit var taskRepository: MockTaskRepository
    private lateinit var sectionRepository: MockSectionRepository
    private lateinit var executionContext: ToolExecutionContext
    
    // Tools
    private lateinit var manageContainerTool: ManageContainerTool
    private lateinit var manageDependencyTool: ManageDependencyTool
    private lateinit var manageSectionsTool: ManageSectionsTool
    
    // Test tasks
    private lateinit var centralTask: Task
    private lateinit var dependentTask1: Task
    private lateinit var dependentTask2: Task
    private lateinit var blockedTask1: Task
    private lateinit var blockedTask2: Task
    private lateinit var relatedTask: Task

    @BeforeEach
    fun setUp() {
        // Set up mock repositories
        dependencyRepository = MockDependencyRepository()
        taskRepository = MockTaskRepository()
        sectionRepository = MockSectionRepository()
        val featureRepository = MockFeatureRepository()
        val projectRepository = MockProjectRepository()
        val templateRepository = MockTemplateRepository()

        val repositoryProvider = MockRepositoryProvider(
            taskRepository = taskRepository,
            dependencyRepository = dependencyRepository,
            sectionRepository = sectionRepository,
            featureRepository = featureRepository,
            projectRepository = projectRepository,
            templateRepository = templateRepository
        )

        executionContext = ToolExecutionContext(repositoryProvider)
        
        // Initialize tools
        manageContainerTool = ManageContainerTool(null, null)
        manageDependencyTool = ManageDependencyTool(null, null)
        manageSectionsTool = ManageSectionsTool(null, null)
        
        // Create test tasks with different roles in dependency chain
        centralTask = Task(id = UUID.randomUUID(), title = "Central Task", summary = "Task at center of dependency network", status = TaskStatus.IN_PROGRESS)
        dependentTask1 = Task(id = UUID.randomUUID(), title = "Dependent Task 1", summary = "Task that depends on central task", status = TaskStatus.PENDING)
        dependentTask2 = Task(id = UUID.randomUUID(), title = "Dependent Task 2", summary = "Another task that depends on central task", status = TaskStatus.PENDING)
        blockedTask1 = Task(id = UUID.randomUUID(), title = "Blocked Task 1", summary = "Task blocked by central task", status = TaskStatus.PENDING)
        blockedTask2 = Task(id = UUID.randomUUID(), title = "Blocked Task 2", summary = "Another task blocked by central task", status = TaskStatus.PENDING)
        relatedTask = Task(id = UUID.randomUUID(), title = "Related Task", summary = "Task related to central task", status = TaskStatus.PENDING)
        
        // Add tasks to repository
        taskRepository.addTask(centralTask)
        taskRepository.addTask(dependentTask1)
        taskRepository.addTask(dependentTask2)
        taskRepository.addTask(blockedTask1)
        taskRepository.addTask(blockedTask2)
        taskRepository.addTask(relatedTask)
    }

    @Test
    fun `should prevent deletion of task with dependencies when force is false`() = runBlocking {
        // Create dependencies involving the central task
        createDependency(dependentTask1.id, centralTask.id, DependencyType.IS_BLOCKED_BY)
        createDependency(centralTask.id, blockedTask1.id, DependencyType.BLOCKS)
        createDependency(centralTask.id, relatedTask.id, DependencyType.RELATES_TO)
        
        // Attempt to delete central task without force
        val deleteParams = JsonObject(mapOf(
            "operation" to JsonPrimitive("delete"),
            "containerType" to JsonPrimitive("task"),
            "id" to JsonPrimitive(centralTask.id.toString()),
            "force" to JsonPrimitive(false)
        ))
        
        val response = manageContainerTool.execute(deleteParams, executionContext)
        val responseObj = response as JsonObject
        
        assertEquals(false, responseObj["success"]?.jsonPrimitive?.boolean, "Deletion should fail without force")
        
        val error = responseObj["error"]?.jsonObject
        assertNotNull(error, "Error should be present")
        assertEquals(ErrorCodes.VALIDATION_ERROR, error!!["code"]?.jsonPrimitive?.content)
        assertTrue(
            error["details"]?.jsonPrimitive?.content?.contains("dependencies") ?: false,
            "Error should mention dependencies"
        )
        
        // Check that dependency information is provided
        val additionalData = error["additionalData"]?.jsonObject
        assertNotNull(additionalData, "Additional data should contain dependency information")
        assertEquals(3, additionalData!!["totalDependencies"]?.jsonPrimitive?.int, "Should show 3 total dependencies")
        assertEquals(1, additionalData["incomingDependencies"]?.jsonPrimitive?.int, "Should show 1 incoming dependency")
        assertEquals(2, additionalData["outgoingDependencies"]?.jsonPrimitive?.int, "Should show 2 outgoing dependencies")
        assertEquals(3, additionalData["affectedTasks"]?.jsonPrimitive?.int, "Should show 3 affected tasks")
        
        // Verify task still exists
        val taskResult = taskRepository.getById(centralTask.id)
        assertTrue(taskResult is Result.Success, "Task should still exist")
    }

    @Test
    fun `should successfully delete task with force and clean up all dependencies`() = runBlocking {
        // Create complex dependency network around central task
        createDependency(dependentTask1.id, centralTask.id, DependencyType.IS_BLOCKED_BY)
        createDependency(dependentTask2.id, centralTask.id, DependencyType.IS_BLOCKED_BY)
        createDependency(centralTask.id, blockedTask1.id, DependencyType.BLOCKS)
        createDependency(centralTask.id, blockedTask2.id, DependencyType.BLOCKS)
        createDependency(centralTask.id, relatedTask.id, DependencyType.RELATES_TO)
        
        // Verify dependencies exist before deletion
        val initialDependencies = dependencyRepository.findByTaskId(centralTask.id)
        assertEquals(5, initialDependencies.size, "Should have 5 dependencies initially")
        
        // Delete central task with force
        val deleteParams = JsonObject(mapOf(
            "operation" to JsonPrimitive("delete"),
            "containerType" to JsonPrimitive("task"),
            "id" to JsonPrimitive(centralTask.id.toString()),
            "force" to JsonPrimitive(true)
        ))
        
        val response = manageContainerTool.execute(deleteParams, executionContext)
        val responseObj = response as JsonObject
        
        assertEquals(true, responseObj["success"]?.jsonPrimitive?.boolean, "Deletion should succeed with force")
        
        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data should be present")
        assertEquals(centralTask.id.toString(), data!!["id"]?.jsonPrimitive?.content)
        assertTrue(data["deleted"]?.jsonPrimitive?.boolean == true, "Deleted flag should be true")
        assertEquals(5, data["dependenciesDeleted"]?.jsonPrimitive?.int, "Should show 5 dependencies deleted")
        
        // Verify broken dependency warnings are present
        assertTrue(data["warningsBrokenDependencies"]?.jsonPrimitive?.boolean == true, "Should warn about broken dependencies")
        val brokenChains = data["brokenDependencyChains"]?.jsonObject
        assertNotNull(brokenChains, "Broken dependency chains info should be present")
        assertEquals(2, brokenChains!!["incomingDependencies"]?.jsonPrimitive?.int, "Should show 2 incoming dependencies")
        assertEquals(3, brokenChains["outgoingDependencies"]?.jsonPrimitive?.int, "Should show 3 outgoing dependencies")
        assertEquals(5, brokenChains["affectedTasks"]?.jsonPrimitive?.int, "Should show 5 affected tasks")
        
        // Verify task is deleted
        val taskResult = taskRepository.getById(centralTask.id)
        assertTrue(taskResult is Result.Error, "Task should be deleted (not found)")
        
        // Verify all dependencies are cleaned up
        val remainingDependencies = dependencyRepository.findByTaskId(centralTask.id)
        assertEquals(0, remainingDependencies.size, "All dependencies should be cleaned up")
    }

    @Test
    fun `should delete task sections along with dependencies when deleteSections is true`() = runBlocking {
        // Create sections for the central task
        val section1 = createSection(centralTask.id, "Requirements", "Task requirements", 0)
        val section2 = createSection(centralTask.id, "Implementation", "Implementation details", 1)
        val section3 = createSection(centralTask.id, "Testing", "Testing approach", 2)
        
        // Create dependencies
        createDependency(dependentTask1.id, centralTask.id, DependencyType.IS_BLOCKED_BY)
        createDependency(centralTask.id, blockedTask1.id, DependencyType.BLOCKS)
        
        // Verify sections exist before deletion
        val initialSectionsResult = sectionRepository.getSectionsForEntity(EntityType.TASK, centralTask.id)
        assertTrue(initialSectionsResult is Result.Success, "Should successfully retrieve sections")
        assertEquals(3, (initialSectionsResult as Result.Success).data.size, "Should have 3 sections initially")
        
        // Delete task with sections and dependencies
        val deleteParams = JsonObject(mapOf(
            "operation" to JsonPrimitive("delete"),
            "containerType" to JsonPrimitive("task"),
            "id" to JsonPrimitive(centralTask.id.toString()),
            "force" to JsonPrimitive(true),
            "deleteSections" to JsonPrimitive(true)
        ))
        
        val response = manageContainerTool.execute(deleteParams, executionContext)
        val responseObj = response as JsonObject
        
        assertEquals(true, responseObj["success"]?.jsonPrimitive?.boolean, "Deletion should succeed")
        
        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data should be present")
        assertEquals(2, data!!["dependenciesDeleted"]?.jsonPrimitive?.int, "Should show 2 dependencies deleted")
        assertEquals(3, data["sectionsDeleted"]?.jsonPrimitive?.int, "Should show 3 sections deleted")
        
        // Verify sections are deleted
        val remainingSectionsResult = sectionRepository.getSectionsForEntity(EntityType.TASK, centralTask.id)
        assertTrue(remainingSectionsResult is Result.Success, "Should successfully retrieve remaining sections")
        assertEquals(0, (remainingSectionsResult as Result.Success).data.size, "All sections should be deleted")
        
        // Verify dependencies are deleted
        val remainingDependencies = dependencyRepository.findByTaskId(centralTask.id)
        assertEquals(0, remainingDependencies.size, "All dependencies should be deleted")
    }

    @Test
    fun `should preserve sections when deleteSections is false`() = runBlocking {
        // Create sections for the central task
        createSection(centralTask.id, "Requirements", "Task requirements", 0)
        createSection(centralTask.id, "Implementation", "Implementation details", 1)
        
        // Create dependency
        createDependency(centralTask.id, blockedTask1.id, DependencyType.BLOCKS)
        
        // Delete task but preserve sections
        val deleteParams = JsonObject(mapOf(
            "operation" to JsonPrimitive("delete"),
            "containerType" to JsonPrimitive("task"),
            "id" to JsonPrimitive(centralTask.id.toString()),
            "force" to JsonPrimitive(true),
            "deleteSections" to JsonPrimitive(false)
        ))
        
        val response = manageContainerTool.execute(deleteParams, executionContext)
        val responseObj = response as JsonObject
        
        assertEquals(true, responseObj["success"]?.jsonPrimitive?.boolean, "Deletion should succeed")
        
        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data should be present")
        assertEquals(1, data!!["dependenciesDeleted"]?.jsonPrimitive?.int, "Should show 1 dependency deleted")
        assertEquals(0, data["sectionsDeleted"]?.jsonPrimitive?.int, "Should show 0 sections deleted")
        
        // Verify sections are preserved (in a real system, orphaned sections might be handled differently)
        val remainingSectionsResult = sectionRepository.getSectionsForEntity(EntityType.TASK, centralTask.id)
        assertTrue(remainingSectionsResult is Result.Success, "Should successfully retrieve remaining sections")
        assertEquals(2, (remainingSectionsResult as Result.Success).data.size, "Sections should be preserved")
        
        // Verify dependencies are still deleted
        val remainingDependencies = dependencyRepository.findByTaskId(centralTask.id)
        assertEquals(0, remainingDependencies.size, "Dependencies should be deleted")
    }

    @Test
    fun `should handle cascade deletion with no dependencies gracefully`() = runBlocking {
        // Task with no dependencies
        val isolatedTask = Task(id = UUID.randomUUID(), title = "Isolated Task", summary = "Task with no dependencies", status = TaskStatus.PENDING)
        taskRepository.addTask(isolatedTask)
        
        // Create sections for the task
        createSection(isolatedTask.id, "Notes", "Task notes", 0)
        
        // Delete task
        val deleteParams = JsonObject(mapOf(
            "operation" to JsonPrimitive("delete"),
            "containerType" to JsonPrimitive("task"),
            "id" to JsonPrimitive(isolatedTask.id.toString()),
            "force" to JsonPrimitive(true)
        ))
        
        val response = manageContainerTool.execute(deleteParams, executionContext)
        val responseObj = response as JsonObject
        
        
        assertEquals(true, responseObj["success"]?.jsonPrimitive?.boolean, "Deletion should succeed")
        
        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data should be present")
        assertEquals(0, data!!["dependenciesDeleted"]?.jsonPrimitive?.int, "Should show 0 dependencies deleted")
        assertEquals(1, data["sectionsDeleted"]?.jsonPrimitive?.int, "Should show 1 section deleted")
        
        // Should not include broken dependency warnings when there are no dependencies
        assertFalse(data.containsKey("warningsBrokenDependencies"), "Should not have broken dependency warnings")
        assertFalse(data.containsKey("brokenDependencyChains"), "Should not have broken dependency chains info")
        
        // Verify task is deleted
        val taskResult = taskRepository.getById(isolatedTask.id)
        assertTrue(taskResult is Result.Error, "Task should be deleted (not found)")
    }

    @Test
    fun `should handle mixed dependency types in cascade deletion`() = runBlocking {
        // Create dependencies with different types
        createDependency(dependentTask1.id, centralTask.id, DependencyType.IS_BLOCKED_BY)
        createDependency(centralTask.id, blockedTask1.id, DependencyType.BLOCKS)
        createDependency(centralTask.id, relatedTask.id, DependencyType.RELATES_TO)
        
        // Delete central task with force
        val deleteParams = JsonObject(mapOf(
            "operation" to JsonPrimitive("delete"),
            "containerType" to JsonPrimitive("task"),
            "id" to JsonPrimitive(centralTask.id.toString()),
            "force" to JsonPrimitive(true)
        ))
        
        val response = manageContainerTool.execute(deleteParams, executionContext)
        val responseObj = response as JsonObject
        
        assertEquals(true, responseObj["success"]?.jsonPrimitive?.boolean, "Deletion should succeed")
        
        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data should be present")
        assertEquals(3, data!!["dependenciesDeleted"]?.jsonPrimitive?.int, "Should delete all 3 dependencies")
        
        // Verify broken dependency chain statistics are accurate
        val brokenChains = data["brokenDependencyChains"]?.jsonObject
        assertNotNull(brokenChains, "Broken dependency chains info should be present")
        assertEquals(1, brokenChains!!["incomingDependencies"]?.jsonPrimitive?.int, "Should show 1 incoming dependency")
        assertEquals(2, brokenChains["outgoingDependencies"]?.jsonPrimitive?.int, "Should show 2 outgoing dependencies")
        assertEquals(3, brokenChains["affectedTasks"]?.jsonPrimitive?.int, "Should show 3 affected tasks")
        
        // Verify all dependencies are cleaned up
        val remainingDependencies = dependencyRepository.findByTaskId(centralTask.id)
        assertEquals(0, remainingDependencies.size, "All dependencies should be cleaned up")
    }

    @Test
    fun `should handle large dependency networks efficiently`() = runBlocking {
        // Create a large dependency network around central task
        val manyTasks = mutableListOf<Task>()
        repeat(20) { i ->
            val task = Task(id = UUID.randomUUID(), title = "Task $i", summary = "Test task $i", status = TaskStatus.PENDING)
            taskRepository.addTask(task)
            manyTasks.add(task)
            
            // Create dependency to central task
            if (i % 2 == 0) {
                createDependency(task.id, centralTask.id, DependencyType.IS_BLOCKED_BY)
            } else {
                createDependency(centralTask.id, task.id, DependencyType.BLOCKS)
            }
        }
        
        // Verify dependencies exist
        val initialDependencies = dependencyRepository.findByTaskId(centralTask.id)
        assertEquals(20, initialDependencies.size, "Should have 20 dependencies initially")
        
        // Delete central task with force
        val deleteParams = JsonObject(mapOf(
            "operation" to JsonPrimitive("delete"),
            "containerType" to JsonPrimitive("task"),
            "id" to JsonPrimitive(centralTask.id.toString()),
            "force" to JsonPrimitive(true)
        ))
        
        val response = manageContainerTool.execute(deleteParams, executionContext)
        val responseObj = response as JsonObject
        
        assertEquals(true, responseObj["success"]?.jsonPrimitive?.boolean, "Deletion should succeed with large network")
        
        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data should be present")
        assertEquals(20, data!!["dependenciesDeleted"]?.jsonPrimitive?.int, "Should delete all 20 dependencies")
        
        // Verify broken dependency chain statistics
        val brokenChains = data["brokenDependencyChains"]?.jsonObject
        assertNotNull(brokenChains, "Broken dependency chains info should be present")
        assertEquals(10, brokenChains!!["incomingDependencies"]?.jsonPrimitive?.int, "Should show 10 incoming dependencies")
        assertEquals(10, brokenChains["outgoingDependencies"]?.jsonPrimitive?.int, "Should show 10 outgoing dependencies")
        assertEquals(20, brokenChains["affectedTasks"]?.jsonPrimitive?.int, "Should show 20 affected tasks")
        
        // Verify all dependencies are cleaned up
        val remainingDependencies = dependencyRepository.findByTaskId(centralTask.id)
        assertEquals(0, remainingDependencies.size, "All dependencies should be cleaned up")
    }

    @Test
    fun `should provide detailed message about dependencies and sections deleted`() = runBlocking {
        // Create dependencies and sections
        createDependency(dependentTask1.id, centralTask.id, DependencyType.IS_BLOCKED_BY)
        createDependency(centralTask.id, blockedTask1.id, DependencyType.BLOCKS)
        createSection(centralTask.id, "Requirements", "Task requirements", 0)
        createSection(centralTask.id, "Notes", "Task notes", 1)
        
        // Delete task
        val deleteParams = JsonObject(mapOf(
            "operation" to JsonPrimitive("delete"),
            "containerType" to JsonPrimitive("task"),
            "id" to JsonPrimitive(centralTask.id.toString()),
            "force" to JsonPrimitive(true)
        ))
        
        val response = manageContainerTool.execute(deleteParams, executionContext)
        val responseObj = response as JsonObject
        
        assertEquals(true, responseObj["success"]?.jsonPrimitive?.boolean, "Deletion should succeed")
        
        // Check message contains details about cleanup
        val message = responseObj["message"]?.jsonPrimitive?.content
        assertNotNull(message, "Message should be present")
        assertTrue(
            message!!.contains("2 dependencies") && message.contains("2 sections"),
            "Message should mention both dependencies and sections deleted: $message"
        )
    }

    // Helper methods

    private suspend fun createDependency(fromTaskId: UUID, toTaskId: UUID, type: DependencyType) {
        val params = JsonObject(mapOf(
            "operation" to JsonPrimitive("create"),
            "fromTaskId" to JsonPrimitive(fromTaskId.toString()),
            "toTaskId" to JsonPrimitive(toTaskId.toString()),
            "type" to JsonPrimitive(type.name)
        ))

        val response = manageDependencyTool.execute(params, executionContext)
        assertTrue((response as JsonObject)["success"]?.jsonPrimitive?.boolean == true, "Dependency creation should succeed")
    }

    private suspend fun createSection(taskId: UUID, title: String, content: String, ordinal: Int): Section {
        val params = JsonObject(mapOf(
            "operation" to JsonPrimitive("add"),
            "entityType" to JsonPrimitive("TASK"),
            "entityId" to JsonPrimitive(taskId.toString()),
            "title" to JsonPrimitive(title),
            "usageDescription" to JsonPrimitive("Test section usage"),
            "content" to JsonPrimitive(content),
            "ordinal" to JsonPrimitive(ordinal)
        ))

        val response = manageSectionsTool.execute(params, executionContext)
        assertTrue((response as JsonObject)["success"]?.jsonPrimitive?.boolean == true, "Section creation should succeed")

        // v2 API: section data is directly in "data", not nested under "data.section"
        val data = response["data"] as JsonObject
        val sectionId = UUID.fromString(data["id"]!!.jsonPrimitive.content)

        return Section(
            id = sectionId,
            entityType = EntityType.TASK,
            entityId = taskId,
            title = title,
            usageDescription = "Test section usage",
            content = content,
            ordinal = ordinal
        )
    }
}