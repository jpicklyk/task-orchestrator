package io.github.jpicklyk.mcptask.integration

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.feature.CreateFeatureTool
import io.github.jpicklyk.mcptask.application.tools.project.CreateProjectTool
import io.github.jpicklyk.mcptask.application.tools.project.DeleteProjectTool
import io.github.jpicklyk.mcptask.application.tools.project.GetProjectTool
import io.github.jpicklyk.mcptask.application.tools.section.AddSectionTool
import io.github.jpicklyk.mcptask.application.tools.task.CreateTaskTool
import io.github.jpicklyk.mcptask.domain.model.ContentFormat
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.ProjectStatus
import io.github.jpicklyk.mcptask.test.mock.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Integration tests focusing on project cascade deletion
 */
class ProjectCascadeDeleteTest {
    private lateinit var projectRepository: MockProjectRepository
    private lateinit var featureRepository: MockFeatureRepository
    private lateinit var taskRepository: MockTaskRepository
    private lateinit var sectionRepository: MockSectionRepository
    private lateinit var executionContext: ToolExecutionContext

    // Tools
    private lateinit var createProjectTool: CreateProjectTool
    private lateinit var getProjectTool: GetProjectTool
    private lateinit var deleteProjectTool: DeleteProjectTool
    private lateinit var createFeatureTool: CreateFeatureTool
    private lateinit var createTaskTool: CreateTaskTool
    private lateinit var addSectionTool: AddSectionTool

    @BeforeEach
    fun setUp() {
        // Set up mock repositories
        projectRepository = MockProjectRepository()
        featureRepository = MockFeatureRepository()
        taskRepository = MockTaskRepository()
        sectionRepository = MockSectionRepository()

        val repositoryProvider = MockRepositoryProvider(
            projectRepository = projectRepository,
            featureRepository = featureRepository,
            taskRepository = taskRepository,
            sectionRepository = sectionRepository
        )

        executionContext = ToolExecutionContext(repositoryProvider)

        // Initialize tools
        createProjectTool = CreateProjectTool()
        getProjectTool = GetProjectTool()
        deleteProjectTool = DeleteProjectTool()
        createFeatureTool = CreateFeatureTool()
        createTaskTool = CreateTaskTool()
        addSectionTool = AddSectionTool()
    }

    /**
     * Test cascade deletion of a project with associated features, tasks, and sections
     */
    @Test
    fun `should cascade delete project with all associated entities`() = runBlocking {
        // Create a project with features, tasks, and sections
        val createProjectParams = buildJsonObject {
            put("name", "Project to Delete")
            put("summary", "Project for testing cascade deletion")
            put("status", ProjectStatus.PLANNING.name)
        }

        val projectResult = createProjectTool.execute(createProjectParams, executionContext) as JsonObject
        assertTrue(
            projectResult["success"]?.jsonPrimitive?.boolean == true,
            "Project creation should be successful"
        )

        val projectId = projectResult["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content!!

        // Create a feature associated with the project
        val createFeatureParams = buildJsonObject {
            put("name", "Feature in Project")
            put("summary", "Feature for testing cascade deletion")
            put("projectId", projectId)
        }

        val featureResult = createFeatureTool.execute(createFeatureParams, executionContext) as JsonObject
        assertTrue(
            featureResult["success"]?.jsonPrimitive?.boolean == true,
            "Feature creation should be successful"
        )

        val featureId = featureResult["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content!!

        // Create a task associated with the feature and project
        val task1Params = buildJsonObject {
            put("title", "Task in Feature")
            put("summary", "Task for testing cascade deletion")
            put("featureId", featureId)
            put("projectId", projectId)
        }

        // Create a task directly associated with the project
        val task2Params = buildJsonObject {
            put("title", "Task in Project")
            put("summary", "Task for testing direct project association")
            put("projectId", projectId)
        }

        val task1Result = createTaskTool.execute(task1Params, executionContext) as JsonObject
        val task2Result = createTaskTool.execute(task2Params, executionContext) as JsonObject

        assertTrue(
            task1Result["success"]?.jsonPrimitive?.boolean == true,
            "Task 1 creation should be successful"
        )
        assertTrue(
            task2Result["success"]?.jsonPrimitive?.boolean == true,
            "Task 2 creation should be successful"
        )

        // Create a section associated with the project
        val sectionParams = buildJsonObject {
            put("entityType", EntityType.PROJECT.name)
            put("entityId", projectId)
            put("title", "Project Section")
            put("usageDescription", "Section for testing cascade deletion")
            put("content", "Content for testing")
            put("contentFormat", ContentFormat.MARKDOWN.name)
            put("ordinal", 0)
        }

        val sectionResult = addSectionTool.execute(sectionParams, executionContext) as JsonObject
        assertTrue(
            sectionResult["success"]?.jsonPrimitive?.boolean == true,
            "Section creation should be successful"
        )

        // Verify entities were created by checking if project exists
        val getProjectParams = buildJsonObject {
            put("id", projectId)
            put("includeFeatures", true)
            put("includeTasks", true)
            put("includeSections", true)
        }

        val projectBeforeDeleteResult = getProjectTool.execute(getProjectParams, executionContext) as JsonObject
        assertTrue(
            projectBeforeDeleteResult["success"]?.jsonPrimitive?.boolean == true,
            "Project retrieval should be successful"
        )

        // Now delete the project with cascade=true
        val deleteProjectParams = buildJsonObject {
            put("id", projectId)
            put("cascade", true)  // This will delete all associated entities
            put("hardDelete", true)
        }

        val deleteResult = deleteProjectTool.execute(deleteProjectParams, executionContext) as JsonObject
        assertTrue(
            deleteResult["success"]?.jsonPrimitive?.boolean == true,
            "Project deletion should be successful"
        )

        // Verify project was deleted
        val projectAfterDeleteResult = getProjectTool.execute(getProjectParams, executionContext) as JsonObject
        assertTrue(
            projectAfterDeleteResult["success"]?.jsonPrimitive?.boolean == false,
            "Project should no longer exist"
        )
    }

    /**
     * Test verifying that deleting a project with force=true works even if it has dependencies
     */
    @Test
    fun `should delete project with force even with dependencies`() = runBlocking {
        // Create a project with a feature
        val createProjectParams = buildJsonObject {
            put("name", "Project with Dependencies")
            put("summary", "Project with dependencies for testing force deletion")
            put("status", ProjectStatus.PLANNING.name)
        }

        val projectResult = createProjectTool.execute(createProjectParams, executionContext) as JsonObject
        assertTrue(
            projectResult["success"]?.jsonPrimitive?.boolean == true,
            "Project creation should be successful"
        )

        val projectId = projectResult["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content!!

        // Create a feature associated with the project
        val createFeatureParams = buildJsonObject {
            put("name", "Feature in Project")
            put("summary", "Feature creating a dependency")
            put("projectId", projectId)
        }

        val featureResult = createFeatureTool.execute(createFeatureParams, executionContext) as JsonObject
        assertTrue(
            featureResult["success"]?.jsonPrimitive?.boolean == true,
            "Feature creation should be successful"
        )

        // With force=true, we should be able to delete the project despite dependencies
        val deleteProjectParams = buildJsonObject {
            put("id", projectId)
            put("force", true)  // This will bypass dependency checks
        }

        val deleteResult = deleteProjectTool.execute(deleteProjectParams, executionContext) as JsonObject
        assertTrue(
            deleteResult["success"]?.jsonPrimitive?.boolean == true,
            "Project deletion with force=true should be successful"
        )

        // Verify project was deleted
        val getProjectParams = buildJsonObject {
            put("id", projectId)
        }

        val projectAfterDeleteResult = getProjectTool.execute(getProjectParams, executionContext) as JsonObject
        assertTrue(
            projectAfterDeleteResult["success"]?.jsonPrimitive?.boolean == false,
            "Project should no longer exist"
        )
    }
}
