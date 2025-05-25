package io.github.jpicklyk.mcptask.integration

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.feature.CreateFeatureTool
import io.github.jpicklyk.mcptask.application.tools.project.CreateProjectTool
import io.github.jpicklyk.mcptask.application.tools.project.GetProjectTool
import io.github.jpicklyk.mcptask.application.tools.task.CreateTaskTool
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
    private lateinit var createProjectTool: CreateProjectTool
    private lateinit var getProjectTool: GetProjectTool
    private lateinit var createFeatureTool: CreateFeatureTool
    private lateinit var createTaskTool: CreateTaskTool

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
        createProjectTool = CreateProjectTool()
        getProjectTool = GetProjectTool()
        createFeatureTool = CreateFeatureTool()
        createTaskTool = CreateTaskTool()
    }

    /**
     * Test that get_project only returns tasks with valid project relationships
     * and excludes orphaned tasks
     */
    @Test
    fun `get_project should only return tasks with valid project relationships`() = runBlocking {
        // 1. Create a test project
        val createProjectParams = buildJsonObject {
            put("name", "Test Project")
            put("summary", "Project for testing task filtering")
            put("status", ProjectStatus.PLANNING.name)
        }

        val projectResult = createProjectTool.execute(createProjectParams, executionContext) as JsonObject
        assertTrue(projectResult["success"]?.jsonPrimitive?.boolean == true, "Project creation should succeed")

        val projectId = projectResult["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content!!

        // 2. Create a feature that belongs to the project
        val createFeatureParams = buildJsonObject {
            put("name", "Test Feature")
            put("summary", "Feature for testing task filtering")
            put("projectId", projectId)
        }

        val featureResult = createFeatureTool.execute(createFeatureParams, executionContext) as JsonObject
        assertTrue(featureResult["success"]?.jsonPrimitive?.boolean == true, "Feature creation should succeed")

        val featureId = featureResult["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content!!

        // 3. Create a task with direct project relationship
        val directTaskParams = buildJsonObject {
            put("title", "Direct Project Task")
            put("summary", "Task directly associated with the project")
            put("projectId", projectId)
        }

        val directTaskResult = createTaskTool.execute(directTaskParams, executionContext) as JsonObject
        assertTrue(directTaskResult["success"]?.jsonPrimitive?.boolean == true, "Direct task creation should succeed")

        val directTaskId = directTaskResult["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content!!

        // 4. Create a task with feature relationship (which belongs to the project)
        val featureTaskParams = buildJsonObject {
            put("title", "Feature Task")
            put("summary", "Task associated with the feature")
            put("featureId", featureId)
            put("projectId", projectId) // Consistency: task and feature should have same project
        }

        val featureTaskResult = createTaskTool.execute(featureTaskParams, executionContext) as JsonObject
        assertTrue(featureTaskResult["success"]?.jsonPrimitive?.boolean == true, "Feature task creation should succeed")

        val featureTaskId = featureTaskResult["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content!!

        // 5. Create orphaned tasks that should NOT be associated with the project

        // Orphaned task 1: No project or feature relationship
        val orphanedTask1Params = buildJsonObject {
            put("title", "Orphaned Task 1")
            put("summary", "Task with no project or feature relationship")
        }

        val orphanedTask1Result = createTaskTool.execute(orphanedTask1Params, executionContext) as JsonObject
        assertTrue(
            orphanedTask1Result["success"]?.jsonPrimitive?.boolean == true,
            "Orphaned task 1 creation should succeed"
        )

        val orphanedTask1Id = orphanedTask1Result["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content!!

        // Orphaned task 2: Different project
        val otherProjectParams = buildJsonObject {
            put("name", "Other Project")
            put("summary", "Another project for testing")
            put("status", ProjectStatus.PLANNING.name)
        }

        val otherProjectResult = createProjectTool.execute(otherProjectParams, executionContext) as JsonObject
        val otherProjectId = otherProjectResult["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content!!

        val orphanedTask2Params = buildJsonObject {
            put("title", "Task in Other Project")
            put("summary", "Task that belongs to a different project")
            put("projectId", otherProjectId)
        }

        val orphanedTask2Result = createTaskTool.execute(orphanedTask2Params, executionContext) as JsonObject
        assertTrue(
            orphanedTask2Result["success"]?.jsonPrimitive?.boolean == true,
            "Orphaned task 2 creation should succeed"
        )

        val orphanedTask2Id = orphanedTask2Result["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content!!

        // 6. Now test get_project with includeTasks
        val getProjectParams = buildJsonObject {
            put("id", projectId)
            put("includeTasks", true)
        }

        val result = getProjectTool.execute(getProjectParams, executionContext) as JsonObject

        // 7. Verify the response
        assertTrue(result["success"]?.jsonPrimitive?.boolean == true, "get_project should succeed")

        val data = result["data"]?.jsonObject
        assertNotNull(data, "Data should not be null")

        val tasks = data!!["tasks"]?.jsonObject
        assertNotNull(tasks, "Tasks should not be null")

        val taskItems = tasks!!["items"]?.jsonArray
        assertNotNull(taskItems, "Task items should not be null")

        // 8. Verify only the correct tasks are included
        assertEquals(2, taskItems!!.size, "Should only return tasks with valid project relationships")

        val returnedTaskIds = taskItems.map { it.jsonObject["id"]?.jsonPrimitive?.content }.toSet()

        // Should include the direct project task and feature task
        assertTrue(returnedTaskIds.contains(directTaskId), "Should include direct project task")
        assertTrue(returnedTaskIds.contains(featureTaskId), "Should include feature task")

        // Should NOT include orphaned tasks
        assertFalse(returnedTaskIds.contains(orphanedTask1Id), "Should not include orphaned task 1")
        assertFalse(returnedTaskIds.contains(orphanedTask2Id), "Should not include orphaned task 2")

        // Verify the counts are correct
        assertEquals(2, tasks["total"]?.jsonPrimitive?.int, "Total count should be 2")
        assertEquals(2, tasks["included"]?.jsonPrimitive?.int, "Included count should be 2")
        assertFalse(tasks["hasMore"]?.jsonPrimitive?.boolean ?: true, "Should not have more tasks")
    }

    /**
     * Test that get_project correctly handles tasks associated with features
     * that belong to different projects
     */
    @Test
    fun `get_project should include tasks through feature relationships correctly`() = runBlocking {
        // 1. Create two projects
        val project1Params = buildJsonObject {
            put("name", "Project 1")
            put("summary", "First project for feature testing")
        }
        val project1Result = createProjectTool.execute(project1Params, executionContext) as JsonObject
        val project1Id = project1Result["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content!!

        val project2Params = buildJsonObject {
            put("name", "Project 2")
            put("summary", "Second project for feature testing")
        }
        val project2Result = createProjectTool.execute(project2Params, executionContext) as JsonObject
        val project2Id = project2Result["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content!!

        // 2. Create features in each project
        val feature1Params = buildJsonObject {
            put("name", "Feature 1")
            put("summary", "Feature in project 1")
            put("projectId", project1Id)
        }
        val feature1Result = createFeatureTool.execute(feature1Params, executionContext) as JsonObject
        val feature1Id = feature1Result["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content!!

        val feature2Params = buildJsonObject {
            put("name", "Feature 2")
            put("summary", "Feature in project 2")
            put("projectId", project2Id)
        }
        val feature2Result = createFeatureTool.execute(feature2Params, executionContext) as JsonObject
        val feature2Id = feature2Result["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content!!

        // 3. Create tasks in each feature
        val task1Params = buildJsonObject {
            put("title", "Task in Feature 1")
            put("summary", "Task that belongs to feature 1")
            put("featureId", feature1Id)
            put("projectId", project1Id)
        }
        val task1Result = createTaskTool.execute(task1Params, executionContext) as JsonObject
        val task1Id = task1Result["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content!!

        val task2Params = buildJsonObject {
            put("title", "Task in Feature 2")
            put("summary", "Task that belongs to feature 2")
            put("featureId", feature2Id)
            put("projectId", project2Id)
        }
        val task2Result = createTaskTool.execute(task2Params, executionContext) as JsonObject
        val task2Id = task2Result["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content!!

        // 4. Test get_project for project 1 - should only return task 1
        val getProject1Params = buildJsonObject {
            put("id", project1Id)
            put("includeTasks", true)
        }

        val project1GetResult = getProjectTool.execute(getProject1Params, executionContext) as JsonObject
        assertTrue(project1GetResult["success"]?.jsonPrimitive?.boolean == true)

        val project1Tasks = project1GetResult["data"]?.jsonObject?.get("tasks")?.jsonObject?.get("items")?.jsonArray
        assertNotNull(project1Tasks)
        assertEquals(1, project1Tasks!!.size, "Project 1 should only have 1 task")

        val project1TaskId = project1Tasks[0].jsonObject["id"]?.jsonPrimitive?.content
        assertEquals(task1Id, project1TaskId, "Project 1 should only include its own task")

        // 5. Test get_project for project 2 - should only return task 2
        val getProject2Params = buildJsonObject {
            put("id", project2Id)
            put("includeTasks", true)
        }

        val project2GetResult = getProjectTool.execute(getProject2Params, executionContext) as JsonObject
        assertTrue(project2GetResult["success"]?.jsonPrimitive?.boolean == true)

        val project2Tasks = project2GetResult["data"]?.jsonObject?.get("tasks")?.jsonObject?.get("items")?.jsonArray
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
            put("name", "Edge Case Project")
            put("summary", "Project for edge case testing")
        }
        val projectResult = createProjectTool.execute(projectParams, executionContext) as JsonObject
        val projectId = projectResult["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content!!

        // 2. Create a feature
        val featureParams = buildJsonObject {
            put("name", "Edge Case Feature")
            put("summary", "Feature for edge case testing")
            put("projectId", projectId)
        }
        val featureResult = createFeatureTool.execute(featureParams, executionContext) as JsonObject
        val featureId = featureResult["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content!!

        // 3. Create a task with only featureId (no explicit projectId)
        val taskParams = buildJsonObject {
            put("title", "Task with only featureId")
            put("summary", "Task that only has feature relationship")
            put("featureId", featureId)
            // Note: No projectId specified
        }
        val taskResult = createTaskTool.execute(taskParams, executionContext) as JsonObject
        val taskId = taskResult["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content!!

        // 4. Test get_project should include this task
        val getProjectParams = buildJsonObject {
            put("id", projectId)
            put("includeTasks", true)
        }

        val result = getProjectTool.execute(getProjectParams, executionContext) as JsonObject
        assertTrue(result["success"]?.jsonPrimitive?.boolean == true)

        val tasks = result["data"]?.jsonObject?.get("tasks")?.jsonObject?.get("items")?.jsonArray
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
            put("name", "Empty Project")
            put("summary", "Project with no tasks")
        }
        val projectResult = createProjectTool.execute(projectParams, executionContext) as JsonObject
        val projectId = projectResult["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content!!

        // 2. Create some orphaned tasks that should not be included
        val orphanedTaskParams = buildJsonObject {
            put("title", "Orphaned Task")
            put("summary", "Task with no project relationship")
        }
        createTaskTool.execute(orphanedTaskParams, executionContext)

        // 3. Test get_project should return empty task list
        val getProjectParams = buildJsonObject {
            put("id", projectId)
            put("includeTasks", true)
        }

        val result = getProjectTool.execute(getProjectParams, executionContext) as JsonObject
        assertTrue(result["success"]?.jsonPrimitive?.boolean == true)

        val tasks = result["data"]?.jsonObject?.get("tasks")?.jsonObject
        assertNotNull(tasks)

        val taskItems = tasks!!["items"]?.jsonArray
        assertNotNull(taskItems)
        assertEquals(0, taskItems!!.size, "Should return no tasks for project with no associated tasks")

        assertEquals(0, tasks["total"]?.jsonPrimitive?.int, "Total count should be 0")
        assertEquals(0, tasks["included"]?.jsonPrimitive?.int, "Included count should be 0")
        assertFalse(tasks["hasMore"]?.jsonPrimitive?.boolean ?: true, "Should not have more tasks")
    }
}
