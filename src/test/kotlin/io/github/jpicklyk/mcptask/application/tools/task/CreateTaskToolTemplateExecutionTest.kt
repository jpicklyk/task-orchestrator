package io.github.jpicklyk.mcptask.application.tools.task

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.domain.repository.TaskRepository
import io.github.jpicklyk.mcptask.domain.repository.TemplateRepository
import io.github.jpicklyk.mcptask.infrastructure.repository.RepositoryProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*

/**
 * Tests for template execution-related functionality in the CreateTaskTool
 */
class CreateTaskToolTemplateExecutionTest {
    
    private lateinit var tool: CreateTaskTool
    private lateinit var context: ToolExecutionContext
    private lateinit var mockTaskRepository: TaskRepository
    private lateinit var mockTemplateRepository: TemplateRepository
    private lateinit var mockRepositoryProvider: RepositoryProvider
    private lateinit var mockTask: Task
    private val taskId = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        // Create a mock repository provider and repositories
        mockTaskRepository = mockk<TaskRepository>()
        mockTemplateRepository = mockk<TemplateRepository>()
        mockRepositoryProvider = mockk<RepositoryProvider>()

        // Configure the repository provider to return the mock repositories
        every { mockRepositoryProvider.taskRepository() } returns mockTaskRepository
        every { mockRepositoryProvider.featureRepository() } returns mockk(relaxed = true)
        every { mockRepositoryProvider.templateRepository() } returns mockTemplateRepository

        // Create a sample task for the mock response
        mockTask = Task(
            id = taskId,
            title = "Test Task",
            summary = "This is a test task",
            status = TaskStatus.PENDING,
            priority = Priority.HIGH,
            complexity = 7,
            tags = listOf("test", "kotlin", "mcp")
        )

        // Define behavior for create method
        coEvery {
            mockTaskRepository.create(any())
        } returns Result.Success(mockTask)
        
        // Default behavior for template repository (no templates applied)
        coEvery {
            mockTemplateRepository.applyMultipleTemplates(any(), any(), any())
        } returns Result.Success(emptyMap<UUID, List<TemplateSection>>())

        // Create the execution context with the mock repository provider
        context = ToolExecutionContext(mockRepositoryProvider)

        tool = CreateTaskTool()
    }
    
    @Test
    fun `test task creation with single template application`() = runBlocking {
        val templateId = UUID.randomUUID()
        
        // Mock template application result
        val templateSections = mapOf(templateId to listOf<TemplateSection>())
        
        coEvery {
            mockTemplateRepository.applyMultipleTemplates(listOf(templateId), EntityType.TASK, taskId)
        } returns Result.Success(templateSections)
        
        val params = JsonObject(
            mapOf(
                "title" to JsonPrimitive("Test Task"),
                "summary" to JsonPrimitive("This is a test task"),
                "templateIds" to JsonArray(listOf(JsonPrimitive(templateId.toString())))
            )
        )

        val result = tool.execute(params, context)

        // Check that the result is a success response
        val resultObj = result as JsonObject
        assertEquals(true, (resultObj["success"] as JsonPrimitive).content.toBoolean())
        assertTrue((resultObj["message"] as JsonPrimitive).content.contains("template(s) applied"))
        
        // Check that the data contains appliedTemplates
        val data = resultObj["data"] as JsonObject
        assertNotNull(data["appliedTemplates"])
        
        val appliedTemplates = data["appliedTemplates"] as JsonArray
        assertEquals(1, appliedTemplates.size)
        
        val appliedTemplate = appliedTemplates[0] as JsonObject
        assertEquals(templateId.toString(), (appliedTemplate["templateId"] as JsonPrimitive).content)
        assertEquals(0, (appliedTemplate["sectionsCreated"] as JsonPrimitive).content.toInt())
    }
}
