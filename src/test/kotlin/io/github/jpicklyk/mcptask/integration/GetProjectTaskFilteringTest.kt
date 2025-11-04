package io.github.jpicklyk.mcptask.integration

import io.github.jpicklyk.mcptask.application.tools.ManageContainerTool
import io.github.jpicklyk.mcptask.application.tools.QueryContainerTool
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.domain.model.ProjectStatus
import io.github.jpicklyk.mcptask.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.infrastructure.repository.DefaultRepositoryProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Integration tests focusing on get_project tool task filtering behavior.
 * These tests verify that the get_project tool only returns tasks that have a valid
 * relationship to the project (either direct projectId or through a feature that belongs to the project).
 */
class GetProjectTaskFilteringTest {
    private lateinit var database: Database
    private lateinit var repositoryProvider: DefaultRepositoryProvider
    private lateinit var executionContext: ToolExecutionContext

    // Tools
    private lateinit var manageContainerTool: ManageContainerTool
    private lateinit var queryContainerTool: QueryContainerTool

    @BeforeEach
    fun setUp() {
        // Create in-memory database for testing with unique name to ensure isolation
        val uniqueDbName = "test_get_project_filtering_${System.currentTimeMillis()}_${Math.random()}"
        database = Database.connect("jdbc:h2:mem:$uniqueDbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")

        val dbManager = DatabaseManager(database)
        dbManager.updateSchema()

        repositoryProvider = DefaultRepositoryProvider(dbManager)
        executionContext = ToolExecutionContext(repositoryProvider)

        // Initialize tools
        manageContainerTool = ManageContainerTool()
        queryContainerTool = QueryContainerTool()
    }

    /**
     * Test that get_project only returns tasks with valid project relationships
     * and excludes orphaned tasks
     */
    @Test
    fun `get_project should only return tasks with valid project relationships`() = runBlocking {
        // 1. Create a test project
        val createProjectParams = buildJsonObject {
            put("operation", "create")
            put("containerType", "project")
            put("name", "Test Project")
            put("summary", "Project for testing task filtering")
            put("status", ProjectStatus.PLANNING.name)
        }

        val projectResult = manageContainerTool.execute(createProjectParams, executionContext) as JsonObject
        assertTrue(projectResult["success"]?.jsonPrimitive?.boolean == true, "Project creation should succeed")

        val projectId = projectResult["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content!!

        // 2. Create a feature that belongs to the project
        val createFeatureParams = buildJsonObject {
            put("operation", "create")
            put("containerType", "feature")
            put("name", "Test Feature")
            put("summary", "Feature for testing task filtering")
            put("projectId", projectId)
        }

        val featureResult = manageContainerTool.execute(createFeatureParams, executionContext) as JsonObject
        assertTrue(featureResult["success"]?.jsonPrimitive?.boolean == true, "Feature creation should succeed")

        val featureId = featureResult["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content!!

        // 3. Create a task with direct project relationship
        val directTaskParams = buildJsonObject {
            put("operation", "create")
            put("containerType", "task")
            put("title", "Direct Project Task")
            put("summary", "Task directly associated with the project")
            put("projectId", projectId)
        }

        val directTaskResult = manageContainerTool.execute(directTaskParams, executionContext) as JsonObject
        assertTrue(directTaskResult["success"]?.jsonPrimitive?.boolean == true, "Direct task creation should succeed")

        val directTaskId = directTaskResult["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content!!

        // 4. Create a task with feature relationship (which belongs to the project)
        val featureTaskParams = buildJsonObject {
            put("operation", "create")
            put("containerType", "task")
            put("title", "Feature Task")
            put("summary", "Task associated with the feature")
            put("featureId", featureId)
            put("projectId", projectId) // Consistency: task and feature should have same project
        }

        val featureTaskResult = manageContainerTool.execute(featureTaskParams, executionContext) as JsonObject
        assertTrue(featureTaskResult["success"]?.jsonPrimitive?.boolean == true, "Feature task creation should succeed")

        val featureTaskId = featureTaskResult["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content!!

        // 5. Create orphaned tasks that should NOT be associated with the project

        // Orphaned task 1: No project or feature relationship
        val orphanedTask1Params = buildJsonObject {
            put("operation", "create")
            put("containerType", "task")
            put("title", "Orphaned Task 1")
            put("summary", "Task with no project or feature relationship")
        }

        val orphanedTask1Result = manageContainerTool.execute(orphanedTask1Params, executionContext) as JsonObject
        assertTrue(
            orphanedTask1Result["success"]?.jsonPrimitive?.boolean == true,
            "Orphaned task 1 creation should succeed"
        )

        val orphanedTask1Id = orphanedTask1Result["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content!!

        // Orphaned task 2: Different project
        val otherProjectParams = buildJsonObject {
            put("operation", "create")
            put("containerType", "project")
            put("name", "Other Project")
            put("summary", "Another project for testing")
            put("status", ProjectStatus.PLANNING.name)
        }

        val otherProjectResult = manageContainerTool.execute(otherProjectParams, executionContext) as JsonObject
        val otherProjectId = otherProjectResult["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content!!

        val orphanedTask2Params = buildJsonObject {
            put("operation", "create")
            put("containerType", "task")
            put("title", "Task in Other Project")
            put("summary", "Task that belongs to a different project")
            put("projectId", otherProjectId)
        }

        val orphanedTask2Result = manageContainerTool.execute(orphanedTask2Params, executionContext) as JsonObject
        assertTrue(
            orphanedTask2Result["success"]?.jsonPrimitive?.boolean == true,
            "Orphaned task 2 creation should succeed"
        )

        val orphanedTask2Id = orphanedTask2Result["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content!!

        // 6. Now test get_project - v2 API returns taskCounts, not inline tasks
        val getProjectParams = buildJsonObject {
            put("operation", "get")
            put("containerType", "project")
            put("id", projectId)
        }

        val result = queryContainerTool.execute(getProjectParams, executionContext) as JsonObject

        // 7. Verify the response
        assertTrue(result["success"]?.jsonPrimitive?.boolean == true, "get_project should succeed")

        val data = result["data"]?.jsonObject
        assertNotNull(data, "Data should not be null")

        // v2 API returns taskCounts instead of inline tasks
        val taskCounts = data!!["taskCounts"]?.jsonObject
        assertNotNull(taskCounts, "Task counts should not be null")

        // 8. Verify the task count is correct
        assertEquals(2, taskCounts!!["total"]?.jsonPrimitive?.int, "Total task count should be 2")

        // 9. Now query actual tasks to verify filtering works correctly
        val queryTasksParams = buildJsonObject {
            put("operation", "search")
            put("containerType", "task")
            put("projectId", projectId)
            put("limit", 100)
        }

        val tasksResult = queryContainerTool.execute(queryTasksParams, executionContext) as JsonObject
        assertTrue(tasksResult["success"]?.jsonPrimitive?.boolean == true)

        val taskItems = tasksResult["data"]?.jsonObject?.get("items")?.jsonArray
        assertNotNull(taskItems, "Task items should not be null")

        // 10. Verify only the correct tasks are included
        assertEquals(2, taskItems!!.size, "Should only return tasks with valid project relationships")

        val returnedTaskIds = taskItems.map { it.jsonObject["id"]?.jsonPrimitive?.content }.toSet()

        // Should include the direct project task and feature task
        assertTrue(returnedTaskIds.contains(directTaskId), "Should include direct project task")
        assertTrue(returnedTaskIds.contains(featureTaskId), "Should include feature task")

        // Should NOT include orphaned tasks
        assertFalse(returnedTaskIds.contains(orphanedTask1Id), "Should not include orphaned task 1")
        assertFalse(returnedTaskIds.contains(orphanedTask2Id), "Should not include orphaned task 2")
    }

    /**
     * Test that get_project correctly handles tasks associated with features
     * that belong to different projects
     */
    @Test
    fun `get_project should include tasks through feature relationships correctly`() = runBlocking {
        // 1. Create two projects
        val project1Params = buildJsonObject {
            put("operation", "create")
            put("containerType", "project")
            put("name", "Project 1")
            put("summary", "First project for feature testing")
        }
        val project1Result = manageContainerTool.execute(project1Params, executionContext) as JsonObject
        val project1Id = project1Result["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content!!

        val project2Params = buildJsonObject {
            put("operation", "create")
            put("containerType", "project")
            put("name", "Project 2")
            put("summary", "Second project for feature testing")
        }
        val project2Result = manageContainerTool.execute(project2Params, executionContext) as JsonObject
        val project2Id = project2Result["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content!!

        // 2. Create features in each project
        val feature1Params = buildJsonObject {
            put("operation", "create")
            put("containerType", "feature")
            put("name", "Feature 1")
            put("summary", "Feature in project 1")
            put("projectId", project1Id)
        }
        val feature1Result = manageContainerTool.execute(feature1Params, executionContext) as JsonObject
        val feature1Id = feature1Result["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content!!

        val feature2Params = buildJsonObject {
            put("operation", "create")
            put("containerType", "feature")
            put("name", "Feature 2")
            put("summary", "Feature in project 2")
            put("projectId", project2Id)
        }
        val feature2Result = manageContainerTool.execute(feature2Params, executionContext) as JsonObject
        val feature2Id = feature2Result["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content!!

        // 3. Create tasks in each feature
        val task1Params = buildJsonObject {
            put("operation", "create")
            put("containerType", "task")
            put("title", "Task in Feature 1")
            put("summary", "Task that belongs to feature 1")
            put("featureId", feature1Id)
            put("projectId", project1Id)
        }
        val task1Result = manageContainerTool.execute(task1Params, executionContext) as JsonObject
        val task1Id = task1Result["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content!!

        val task2Params = buildJsonObject {
            put("operation", "create")
            put("containerType", "task")
            put("title", "Task in Feature 2")
            put("summary", "Task that belongs to feature 2")
            put("featureId", feature2Id)
            put("projectId", project2Id)
        }
        val task2Result = manageContainerTool.execute(task2Params, executionContext) as JsonObject
        val task2Id = task2Result["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content!!

        // 4. Test get_project for project 1 - v2 API returns taskCounts
        val getProject1Params = buildJsonObject {
            put("operation", "get")
            put("containerType", "project")
            put("id", project1Id)
        }

        val project1GetResult = queryContainerTool.execute(getProject1Params, executionContext) as JsonObject
        assertTrue(project1GetResult["success"]?.jsonPrimitive?.boolean == true)

        val project1TaskCounts = project1GetResult["data"]?.jsonObject?.get("taskCounts")?.jsonObject
        assertNotNull(project1TaskCounts)
        assertEquals(1, project1TaskCounts!!["total"]?.jsonPrimitive?.int, "Project 1 should have 1 task")

        // Query actual tasks for project 1
        val queryProject1TasksParams = buildJsonObject {
            put("operation", "search")
            put("containerType", "task")
            put("projectId", project1Id)
            put("limit", 100)
        }

        val project1TasksResult = queryContainerTool.execute(queryProject1TasksParams, executionContext) as JsonObject
        val project1Tasks = project1TasksResult["data"]?.jsonObject?.get("items")?.jsonArray
        assertNotNull(project1Tasks)
        assertEquals(1, project1Tasks!!.size, "Project 1 should only have 1 task")

        val project1TaskId = project1Tasks[0].jsonObject["id"]?.jsonPrimitive?.content
        assertEquals(task1Id, project1TaskId, "Project 1 should only include its own task")

        // 5. Test get_project for project 2 - v2 API returns taskCounts
        val getProject2Params = buildJsonObject {
            put("operation", "get")
            put("containerType", "project")
            put("id", project2Id)
        }

        val project2GetResult = queryContainerTool.execute(getProject2Params, executionContext) as JsonObject
        assertTrue(project2GetResult["success"]?.jsonPrimitive?.boolean == true)

        val project2TaskCounts = project2GetResult["data"]?.jsonObject?.get("taskCounts")?.jsonObject
        assertNotNull(project2TaskCounts)
        assertEquals(1, project2TaskCounts!!["total"]?.jsonPrimitive?.int, "Project 2 should have 1 task")

        // Query actual tasks for project 2
        val queryProject2TasksParams = buildJsonObject {
            put("operation", "search")
            put("containerType", "task")
            put("projectId", project2Id)
            put("limit", 100)
        }

        val project2TasksResult = queryContainerTool.execute(queryProject2TasksParams, executionContext) as JsonObject
        val project2Tasks = project2TasksResult["data"]?.jsonObject?.get("items")?.jsonArray
        assertNotNull(project2Tasks)
        assertEquals(1, project2Tasks!!.size, "Project 2 should only have 1 task")

        val project2TaskId = project2Tasks[0].jsonObject["id"]?.jsonPrimitive?.content
        assertEquals(task2Id, project2TaskId, "Project 2 should only include its own task")
    }

    /**
     * Test edge case where tasks exist with only featureId but no projectId,
     * and the feature belongs to a project
     */
    @Test
    fun `get_project should include tasks with only featureId when feature belongs to project`() = runBlocking {
        // 1. Create a project
        val projectParams = buildJsonObject {
            put("operation", "create")
            put("containerType", "project")
            put("name", "Edge Case Project")
            put("summary", "Project for edge case testing")
        }
        val projectResult = manageContainerTool.execute(projectParams, executionContext) as JsonObject
        val projectId = projectResult["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content!!

        // 2. Create a feature
        val featureParams = buildJsonObject {
            put("operation", "create")
            put("containerType", "feature")
            put("name", "Edge Case Feature")
            put("summary", "Feature for edge case testing")
            put("projectId", projectId)
        }
        val featureResult = manageContainerTool.execute(featureParams, executionContext) as JsonObject
        val featureId = featureResult["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content!!

        // 3. Create a task with only featureId (no explicit projectId)
        val taskParams = buildJsonObject {
            put("operation", "create")
            put("containerType", "task")
            put("title", "Task with only featureId")
            put("summary", "Task that only has feature relationship")
            put("featureId", featureId)
            // Note: No projectId specified
        }
        val taskResult = manageContainerTool.execute(taskParams, executionContext) as JsonObject
        val taskId = taskResult["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content!!

        // 4. Test get_project - v2 API returns taskCounts
        val getProjectParams = buildJsonObject {
            put("operation", "get")
            put("containerType", "project")
            put("id", projectId)
        }

        val result = queryContainerTool.execute(getProjectParams, executionContext) as JsonObject
        assertTrue(result["success"]?.jsonPrimitive?.boolean == true)

        val taskCounts = result["data"]?.jsonObject?.get("taskCounts")?.jsonObject
        assertNotNull(taskCounts)
        assertEquals(1, taskCounts!!["total"]?.jsonPrimitive?.int, "Should have 1 task")

        // Query actual tasks to verify the task is included
        val queryTasksParams = buildJsonObject {
            put("operation", "search")
            put("containerType", "task")
            put("projectId", projectId)
            put("limit", 100)
        }

        val tasksResult = queryContainerTool.execute(queryTasksParams, executionContext) as JsonObject
        val tasks = tasksResult["data"]?.jsonObject?.get("items")?.jsonArray
        assertNotNull(tasks)
        assertEquals(1, tasks!!.size, "Should include task with featureId relationship")

        val returnedTaskId = tasks[0].jsonObject["id"]?.jsonPrimitive?.content
        assertEquals(taskId, returnedTaskId, "Should return the task with featureId relationship")
    }

    /**
     * Test that get_project handles empty task results correctly
     */
    @Test
    fun `get_project should handle projects with no tasks correctly`() = runBlocking {
        // 1. Create a project with no tasks
        val projectParams = buildJsonObject {
            put("operation", "create")
            put("containerType", "project")
            put("name", "Empty Project")
            put("summary", "Project with no tasks")
        }
        val projectResult = manageContainerTool.execute(projectParams, executionContext) as JsonObject
        val projectId = projectResult["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content!!

        // 2. Create some orphaned tasks that should not be included
        val orphanedTaskParams = buildJsonObject {
            put("operation", "create")
            put("containerType", "task")
            put("title", "Orphaned Task")
            put("summary", "Task with no project relationship")
        }
        manageContainerTool.execute(orphanedTaskParams, executionContext)

        // 3. Test get_project - v2 API returns taskCounts
        val getProjectParams = buildJsonObject {
            put("operation", "get")
            put("containerType", "project")
            put("id", projectId)
        }

        val result = queryContainerTool.execute(getProjectParams, executionContext) as JsonObject
        assertTrue(result["success"]?.jsonPrimitive?.boolean == true)

        val taskCounts = result["data"]?.jsonObject?.get("taskCounts")?.jsonObject
        assertNotNull(taskCounts)
        assertEquals(0, taskCounts!!["total"]?.jsonPrimitive?.int, "Total count should be 0")

        // Query actual tasks to verify empty result
        val queryTasksParams = buildJsonObject {
            put("operation", "search")
            put("containerType", "task")
            put("projectId", projectId)
            put("limit", 100)
        }

        val tasksResult = queryContainerTool.execute(queryTasksParams, executionContext) as JsonObject
        val taskItems = tasksResult["data"]?.jsonObject?.get("items")?.jsonArray
        assertNotNull(taskItems)
        assertEquals(0, taskItems!!.size, "Should return no tasks for project with no associated tasks")
    }
}
