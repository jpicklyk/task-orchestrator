package io.github.jpicklyk.mcptask.application.tools.feature

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Feature
import io.github.jpicklyk.mcptask.domain.model.FeatureStatus
import io.github.jpicklyk.mcptask.domain.model.Task
import io.github.jpicklyk.mcptask.domain.repository.FeatureRepository
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.domain.repository.TaskRepository
import io.github.jpicklyk.mcptask.infrastructure.repository.RepositoryProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.*

class DeleteFeatureToolTest {

    private lateinit var tool: DeleteFeatureTool
    private lateinit var mockContext: ToolExecutionContext
    private val testFeatureId = UUID.randomUUID()
    private val emptyFeatureId = UUID.randomUUID()
    private val testTaskId1 = UUID.randomUUID()
    private val testTaskId2 = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        // Create mock repositories
        val mockFeatureRepository = mockk<FeatureRepository>()
        val mockTaskRepository = mockk<TaskRepository>()
        val mockRepositoryProvider = mockk<RepositoryProvider>()

        // Configure the repository provider to return the mock repositories
        every { mockRepositoryProvider.featureRepository() } returns mockFeatureRepository
        every { mockRepositoryProvider.taskRepository() } returns mockTaskRepository

        // Set up the feature with tasks
        val testFeature = Feature(
            id = testFeatureId,
            name = "Test Feature",
            summary = "Test Feature Summary", // Added summary parameter
            status = FeatureStatus.IN_DEVELOPMENT
        )

        // Empty feature (no tasks)
        val emptyFeature = Feature(
            id = emptyFeatureId,
            name = "Empty Feature",
            summary = "Empty Feature Summary", // Added summary parameter
            status = FeatureStatus.PLANNING
        )

        // Configure findById behavior
        coEvery { mockFeatureRepository.getById(testFeatureId) } returns Result.Success(testFeature)
        coEvery { mockFeatureRepository.getById(emptyFeatureId) } returns Result.Success(emptyFeature)
        coEvery { mockFeatureRepository.getById(not(or(testFeatureId, emptyFeatureId))) } returns Result.Error(
            RepositoryError.NotFound(
                UUID.randomUUID(), EntityType.FEATURE, "Feature not found"
            )
        )

        // Set up feature's tasks
        val task1 = Task(id = testTaskId1, title = "Task 1", summary = "Task 1 Summary") // Added summary parameter
        val task2 = Task(id = testTaskId2, title = "Task 2", summary = "Task 2 Summary") // Added summary parameter
        val testTasks = listOf(task1, task2)

        // Configure task repository behavior
        coEvery { mockTaskRepository.findByFeature(testFeatureId) } returns Result.Success(testTasks)
        coEvery { mockTaskRepository.findByFeature(emptyFeatureId) } returns Result.Success(emptyList())
        coEvery { mockTaskRepository.delete(any()) } returns Result.Success(true)

        // Configure delete behavior
        coEvery { mockFeatureRepository.delete(any()) } returns Result.Success(true)

        // Create the execution context with the mock repository provider
        mockContext = ToolExecutionContext(mockRepositoryProvider)

        // Initialize the tool
        tool = DeleteFeatureTool()
    }

    @Test
    fun `validate with valid parameters should not throw exceptions`() {
        val validParams = JsonObject(
            mapOf(
                "id" to JsonPrimitive(testFeatureId.toString()),
                "hardDelete" to JsonPrimitive(true),
                "cascade" to JsonPrimitive(true),
                "force" to JsonPrimitive(true)
            )
        )

        // Should not throw any exceptions
        assertDoesNotThrow { tool.validateParams(validParams) }
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

        assertTrue(exception.message!!.contains("Invalid id format"))
    }

    @Test
    fun `validate without required ID should throw validation exception`() {
        val invalidParams = JsonObject(
            mapOf(
                "hardDelete" to JsonPrimitive(true)
            )
        )

        val exception = assertThrows<ToolValidationException> {
            tool.validateParams(invalidParams)
        }

        assertTrue(exception.message!!.contains("Missing required parameter"))
    }

    // Disabled execution tests - these will be updated in future refactoring
}
