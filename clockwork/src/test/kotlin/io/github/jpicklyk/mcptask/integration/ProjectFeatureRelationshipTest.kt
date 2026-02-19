package io.github.jpicklyk.mcptask.integration

import io.github.jpicklyk.mcptask.domain.model.Feature
import io.github.jpicklyk.mcptask.domain.model.Project
import io.github.jpicklyk.mcptask.domain.model.Task
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.infrastructure.database.repository.SQLiteFeatureRepository
import io.github.jpicklyk.mcptask.infrastructure.database.repository.SQLiteProjectRepository
import io.github.jpicklyk.mcptask.infrastructure.database.repository.SQLiteTaskRepository
import io.github.jpicklyk.mcptask.infrastructure.database.schema.EntityTagsTable
import io.github.jpicklyk.mcptask.infrastructure.database.schema.FeaturesTable
import io.github.jpicklyk.mcptask.infrastructure.database.schema.ProjectsTable
import io.github.jpicklyk.mcptask.infrastructure.database.schema.TaskTable
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Integration tests focusing on relationships between Projects and Features
 */
class ProjectFeatureRelationshipTest {
    private lateinit var database: Database
    private lateinit var projectRepository: SQLiteProjectRepository
    private lateinit var featureRepository: SQLiteFeatureRepository
    private lateinit var taskRepository: SQLiteTaskRepository

    @BeforeEach
    fun setUp() {
        // Create an in-memory database for testing
        database = Database.connect("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")

        // Create tables
        transaction(database) {
            SchemaUtils.create(ProjectsTable, FeaturesTable, TaskTable, EntityTagsTable)
        }

        val dbManager = DatabaseManager(database)

        // Initialize repositories
        projectRepository = SQLiteProjectRepository(dbManager)
        featureRepository = SQLiteFeatureRepository(dbManager)
        taskRepository = SQLiteTaskRepository(dbManager)
    }

    /**
     * Test moving a feature between projects
     */
    @Test
    fun `should move feature between projects`() = runBlocking {
        // Create two projects
        val project1 = Project(
            name = "Source Project",
            summary = "Project from which feature will be moved"
        )
        val project2 = Project(
            name = "Target Project",
            summary = "Project to which feature will be moved"
        )

        val project1Result = projectRepository.create(project1)
        val project2Result = projectRepository.create(project2)
        assertTrue(project1Result is Result.Success)
        assertTrue(project2Result is Result.Success)

        val sourceProject = (project1Result as Result.Success<Project>).data
        val targetProject = (project2Result as Result.Success<Project>).data

        // Create a feature in the source project
        val feature = Feature(
            name = "Movable Feature",
            summary = "Feature that will be moved between projects",
            projectId = sourceProject.id
        )
        val featureResult = featureRepository.create(feature)
        assertTrue(featureResult is Result.Success)
        val createdFeature = (featureResult as Result.Success<Feature>).data

        // Create a task associated with the feature and source project
        val task = Task(
            title = "Task in Feature",
            summary = "Task that should move with its feature",
            featureId = createdFeature.id,
            projectId = sourceProject.id
        )
        val taskResult = taskRepository.create(task)
        assertTrue(taskResult is Result.Success)
        val createdTask = (taskResult as Result.Success<Task>).data

        // Verify initial associations
        val sourceFeaturesResult = featureRepository.findByProject(sourceProject.id, limit = 100)
        assertTrue(sourceFeaturesResult is Result.Success)
        assertEquals(1, (sourceFeaturesResult as Result.Success<List<Feature>>).data.size)
        assertEquals(createdFeature.id, sourceFeaturesResult.data[0].id)

        val targetFeaturesResult = featureRepository.findByProject(targetProject.id, limit = 100)
        assertTrue(targetFeaturesResult is Result.Success)
        assertEquals(0, (targetFeaturesResult as Result.Success<List<Feature>>).data.size)

        // Move the feature to the target project
        // The Feature class has an update method with a direct projectId parameter
        val updatedFeature = createdFeature.update(
            name = createdFeature.name,
            projectId = targetProject.id,
            summary = createdFeature.summary,
            status = createdFeature.status,
            priority = createdFeature.priority,
            tags = createdFeature.tags
        )
        val updateFeatureResult = featureRepository.update(updatedFeature)
        assertTrue(updateFeatureResult is Result.Success)

        // The task should still reference the old project, so update it
        val updatedTask = createdTask.update { task ->
            task.copy(projectId = targetProject.id)
        }
        val updateTaskResult = taskRepository.update(updatedTask)
        assertTrue(updateTaskResult is Result.Success)

        // Verify new associations
        val newSourceFeaturesResult = featureRepository.findByProject(sourceProject.id, limit = 100)
        assertTrue(newSourceFeaturesResult is Result.Success)
        assertEquals(0, (newSourceFeaturesResult as Result.Success<List<Feature>>).data.size)

        val newTargetFeaturesResult = featureRepository.findByProject(targetProject.id, limit = 100)
        assertTrue(newTargetFeaturesResult is Result.Success)
        assertEquals(1, (newTargetFeaturesResult as Result.Success<List<Feature>>).data.size)
        assertEquals(createdFeature.id, newTargetFeaturesResult.data[0].id)

        // Verify the task has moved with feature
        val taskAfterMoveResult = taskRepository.getById(createdTask.id)
        assertTrue(taskAfterMoveResult is Result.Success)
        val taskAfterMove = (taskAfterMoveResult as Result.Success<Task>).data
        assertEquals(targetProject.id, taskAfterMove.projectId)
        assertEquals(createdFeature.id, taskAfterMove.featureId)
    }
}
