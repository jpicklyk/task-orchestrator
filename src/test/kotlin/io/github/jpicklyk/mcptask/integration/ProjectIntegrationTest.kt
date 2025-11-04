package io.github.jpicklyk.mcptask.integration

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ManageContainerTool
import io.github.jpicklyk.mcptask.application.tools.QueryContainerTool
import io.github.jpicklyk.mcptask.application.tools.section.ManageSectionsTool
import io.github.jpicklyk.mcptask.application.tools.section.QuerySectionsTool
import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.test.mock.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Integration tests for Project entity support using mock repositories.
 */
class ProjectIntegrationTest {
    private lateinit var projectRepository: MockProjectRepository
    private lateinit var featureRepository: MockFeatureRepository
    private lateinit var taskRepository: MockTaskRepository
    private lateinit var sectionRepository: MockSectionRepository
    private lateinit var executionContext: ToolExecutionContext

    // Tools
    private lateinit var manageContainerTool: ManageContainerTool
    private lateinit var queryContainerTool: QueryContainerTool
    private lateinit var manageSectionsTool: ManageSectionsTool
    private lateinit var querySectionsTool: QuerySectionsTool

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
        manageContainerTool = ManageContainerTool(null, null)
        queryContainerTool = QueryContainerTool()
        manageSectionsTool = ManageSectionsTool(null, null)
        querySectionsTool = QuerySectionsTool(null, null)
    }

    /**
     * Test creating a project
     */
    @Test
    fun `should create and retrieve a project`() = runBlocking {
        // Create a project
        val createProjectParams = buildJsonObject {
            put("operation", "create")
            put("containerType", "project")
            put("name", "Test Project")
            put("summary", "Project for testing")
            put("status", ProjectStatus.PLANNING.name.lowercase())
            put("tags", "test,integration")
        }

        val createProjectResponse = manageContainerTool.execute(createProjectParams, executionContext)
        assertTrue(createProjectResponse is JsonObject, "Project creation response should be a JsonObject")

        val createProjectResult = createProjectResponse as JsonObject
        assertTrue(
            createProjectResult["success"]?.jsonPrimitive?.boolean == true,
            "Project creation should be successful"
        )

        // Extract the project ID
        val projectData = createProjectResult["data"]?.jsonObject
        assertNotNull(projectData, "Project data should not be null")

        val projectId = projectData!!["id"]?.jsonPrimitive?.content
        assertNotNull(projectId, "Project ID should not be null")

        // Retrieve the project
        val getProjectParams = buildJsonObject {
            put("operation", "get")
            put("containerType", "project")
            put("id", projectId)
        }

        val getProjectResponse = queryContainerTool.execute(getProjectParams, executionContext)
        assertTrue(getProjectResponse is JsonObject, "Get project response should be a JsonObject")

        val getProjectResult = getProjectResponse as JsonObject
        assertTrue(
            getProjectResult["success"]?.jsonPrimitive?.boolean == true,
            "Get project should be successful"
        )

        val retrievedProjectData = getProjectResult["data"]?.jsonObject
        assertNotNull(retrievedProjectData, "Retrieved project data should not be null")
        assertEquals("Test Project", retrievedProjectData!!["name"]?.jsonPrimitive?.content)
        assertEquals("Project for testing", retrievedProjectData["summary"]?.jsonPrimitive?.content)
    }

    /**
     * Test associating sections with projects
     */
    @Test
    fun `should associate sections with projects`() = runBlocking {
        // Create a project
        val createProjectParams = buildJsonObject {
            put("operation", "create")
            put("containerType", "project")
            put("name", "Project with Sections")
            put("summary", "Project for testing section associations")
            put("status", ProjectStatus.PLANNING.name.lowercase())
        }

        val createProjectResponse = manageContainerTool.execute(createProjectParams, executionContext)
        val createProjectResult = createProjectResponse as JsonObject
        assertTrue(
            createProjectResult["success"]?.jsonPrimitive?.boolean == true,
            "Project creation should be successful"
        )

        val projectData = createProjectResult["data"]?.jsonObject
        val projectId = projectData!!["id"]?.jsonPrimitive?.content!!

        // Create sections associated with the project
        val section1Params = buildJsonObject {
            put("operation", "add")
            put("entityType", EntityType.PROJECT.name)
            put("entityId", projectId)
            put("title", "Project Overview")
            put("usageDescription", "Overview of the project goals and scope")
            put("content", "This project aims to test section associations with projects")
            put("contentFormat", ContentFormat.MARKDOWN.name)
            put("ordinal", 0)
            put("tags", "overview")
        }

        val section2Params = buildJsonObject {
            put("operation", "add")
            put("entityType", EntityType.PROJECT.name)
            put("entityId", projectId)
            put("title", "Project Details")
            put("usageDescription", "Detailed information about the project")
            put("content", "This section contains detailed specifications for the project")
            put("contentFormat", ContentFormat.MARKDOWN.name)
            put("ordinal", 1)
            put("tags", "details,specifications")
        }

        val addSection1Response = manageSectionsTool.execute(section1Params, executionContext)
        val addSection1Result = addSection1Response as JsonObject
        assertTrue(
            addSection1Result["success"]?.jsonPrimitive?.boolean == true,
            "Section 1 addition should be successful"
        )

        val addSection2Response = manageSectionsTool.execute(section2Params, executionContext)
        val addSection2Result = addSection2Response as JsonObject
        assertTrue(
            addSection2Result["success"]?.jsonPrimitive?.boolean == true,
            "Section 2 addition should be successful"
        )

        // Retrieve sections for the project
        val getSectionsParams = buildJsonObject {
            put("entityType", EntityType.PROJECT.name)
            put("entityId", projectId)
        }

        val getSectionsResponse = querySectionsTool.execute(getSectionsParams, executionContext)
        val getSectionsResult = getSectionsResponse as JsonObject
        assertTrue(
            getSectionsResult["success"]?.jsonPrimitive?.boolean == true,
            "Get sections should be successful"
        )

        val sectionsData = getSectionsResult["data"]?.jsonObject
        val sections = sectionsData!!["sections"]?.jsonArray
        assertEquals(2, sections!!.size, "Project should have 2 sections")

        // Verify section details
        val sortedSections = sections.sortedBy { it.jsonObject["ordinal"]?.jsonPrimitive?.int ?: 0 }
        assertEquals("Project Overview", sortedSections[0].jsonObject["title"]?.jsonPrimitive?.content)
        assertEquals("Project Details", sortedSections[1].jsonObject["title"]?.jsonPrimitive?.content)
        assertEquals(0, sortedSections[0].jsonObject["ordinal"]?.jsonPrimitive?.int)
        assertEquals(1, sortedSections[1].jsonObject["ordinal"]?.jsonPrimitive?.int)
    }

    /**
     * Test project and feature relationships
     */
    @Test
    fun `should create project with features and maintain relationships`() = runBlocking {
        // Create a project
        val createProjectParams = buildJsonObject {
            put("operation", "create")
            put("containerType", "project")
            put("name", "Project with Features")
            put("summary", "Project for testing feature relationships")
            put("status", ProjectStatus.PLANNING.name.lowercase())
        }

        val createProjectResponse = manageContainerTool.execute(createProjectParams, executionContext)
        val createProjectResult = createProjectResponse as JsonObject
        assertTrue(
            createProjectResult["success"]?.jsonPrimitive?.boolean == true,
            "Project creation should be successful"
        )

        val projectData = createProjectResult["data"]?.jsonObject
        val projectId = projectData!!["id"]?.jsonPrimitive?.content!!

        // Create features associated with the project
        val feature1Params = buildJsonObject {
            put("operation", "create")
            put("containerType", "feature")
            put("name", "Feature 1")
            put("summary", "First test feature")
            put("projectId", projectId)
            put("status", FeatureStatus.PLANNING.name.lowercase())
            put("priority", Priority.HIGH.name.lowercase())
        }

        val feature2Params = buildJsonObject {
            put("operation", "create")
            put("containerType", "feature")
            put("name", "Feature 2")
            put("summary", "Second test feature")
            put("projectId", projectId)
            put("status", FeatureStatus.PLANNING.name.lowercase())
            put("priority", Priority.MEDIUM.name.lowercase())
        }

        val createFeature1Response = manageContainerTool.execute(feature1Params, executionContext)
        val createFeature1Result = createFeature1Response as JsonObject
        assertTrue(
            createFeature1Result["success"]?.jsonPrimitive?.boolean == true,
            "Feature 1 creation should be successful"
        )

        val createFeature2Response = manageContainerTool.execute(feature2Params, executionContext)
        val createFeature2Result = createFeature2Response as JsonObject
        assertTrue(
            createFeature2Result["success"]?.jsonPrimitive?.boolean == true,
            "Feature 2 creation should be successful"
        )

        // Retrieve project - v2 does not support includeFeatures parameter
        val getProjectParams = buildJsonObject {
            put("operation", "get")
            put("containerType", "project")
            put("id", projectId)
        }

        val getProjectResponse = queryContainerTool.execute(getProjectParams, executionContext)
        val getProjectResult = getProjectResponse as JsonObject
        assertTrue(
            getProjectResult["success"]?.jsonPrimitive?.boolean == true,
            "Get project should be successful"
        )

        val retrievedProjectData = getProjectResult["data"]?.jsonObject
        assertNotNull(retrievedProjectData, "Retrieved project data should not be null")
        assertEquals("Project with Features", retrievedProjectData!!["name"]?.jsonPrimitive?.content)

        // Retrieve features separately using search with projectId filter
        val searchFeaturesParams = buildJsonObject {
            put("operation", "search")
            put("containerType", "feature")
            put("projectId", projectId)
        }

        val searchFeaturesResponse = queryContainerTool.execute(searchFeaturesParams, executionContext)
        val searchFeaturesResult = searchFeaturesResponse as JsonObject
        assertTrue(
            searchFeaturesResult["success"]?.jsonPrimitive?.boolean == true,
            "Search features should be successful"
        )

        val featuresData = searchFeaturesResult["data"]?.jsonObject
        val features = featuresData!!["items"]?.jsonArray
        assertNotNull(features, "Features should not be null")
        assertEquals(2, features!!.size, "Project should have 2 features")

        // Verify feature details
        val featureNames = features.map { it.jsonObject["name"]?.jsonPrimitive?.content }
        assertTrue(featureNames.contains("Feature 1"), "Features should include 'Feature 1'")
        assertTrue(featureNames.contains("Feature 2"), "Features should include 'Feature 2'")
    }

    /**
     * Test validation for feature-project relationships
     */
    @Test
    fun `should enforce project consistency for features`() = runBlocking {
        // Create two projects
        val project1Params = buildJsonObject {
            put("operation", "create")
            put("containerType", "project")
            put("name", "Project 1")
            put("summary", "First test project")
        }

        val project2Params = buildJsonObject {
            put("operation", "create")
            put("containerType", "project")
            put("name", "Project 2")
            put("summary", "Second test project")
        }

        val project1Response = manageContainerTool.execute(project1Params, executionContext)
        val project1Result = project1Response as JsonObject
        assertTrue(
            project1Result["success"]?.jsonPrimitive?.boolean == true,
            "Project 1 creation should be successful"
        )
        val project1Id = project1Result["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content!!

        val project2Response = manageContainerTool.execute(project2Params, executionContext)
        val project2Result = project2Response as JsonObject
        assertTrue(
            project2Result["success"]?.jsonPrimitive?.boolean == true,
            "Project 2 creation should be successful"
        )
        val project2Id = project2Result["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content!!

        // Create a feature in project 1
        val featureParams = buildJsonObject {
            put("operation", "create")
            put("containerType", "feature")
            put("name", "Test Feature")
            put("summary", "Feature for testing consistency")
            put("projectId", project1Id)
        }

        val featureResponse = manageContainerTool.execute(featureParams, executionContext)
        val featureResult = featureResponse as JsonObject
        assertTrue(
            featureResult["success"]?.jsonPrimitive?.boolean == true,
            "Feature creation should be successful"
        )
        val featureId = featureResult["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content!!

        // Verify feature is associated with project 1
        val getFeatureParams = buildJsonObject {
            put("operation", "get")
            put("containerType", "feature")
            put("id", featureId)
        }

        val getFeatureResponse = queryContainerTool.execute(getFeatureParams, executionContext)
        val getFeatureResult = getFeatureResponse as JsonObject
        assertTrue(
            getFeatureResult["success"]?.jsonPrimitive?.boolean == true,
            "Get feature should be successful"
        )

        val retrievedFeatureData = getFeatureResult["data"]?.jsonObject
        assertEquals(
            project1Id, retrievedFeatureData!!["projectId"]?.jsonPrimitive?.content,
            "Feature should be associated with Project 1"
        )
    }
}
