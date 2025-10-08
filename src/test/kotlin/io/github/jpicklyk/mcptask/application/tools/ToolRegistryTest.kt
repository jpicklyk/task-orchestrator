package io.github.jpicklyk.mcptask.application.tools

import io.github.jpicklyk.mcptask.domain.repository.TaskRepository
import io.github.jpicklyk.mcptask.infrastructure.repository.RepositoryProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ToolRegistryTest {
    private lateinit var registry: ToolRegistry

    @BeforeEach
    fun setup() {
        // Create a mock repository provider and repositories
        val mockTaskRepository = mockk<TaskRepository>()
        val mockRepositoryProvider = mockk<RepositoryProvider>()

        // Configure the repository provider to return the mock task repository
        every { mockRepositoryProvider.taskRepository() } returns mockTaskRepository

        // Create the execution context with the mock repository provider
        val mockExecutionContext = ToolExecutionContext(mockRepositoryProvider)

        // Create a registry with mock execution context
        registry = ToolRegistry(executionContext = mockExecutionContext)
    }

    @Test
    fun `test register and get tool`() {
        val tool = createMockTool("test-tool", "Test Tool")
        registry.registerTool(tool)

        val retrievedTool = registry.getTool("test-tool")
        Assertions.assertNotNull(retrievedTool)
        Assertions.assertEquals("test-tool", retrievedTool?.name)
    }

    @Test
    fun `test register duplicate tool throws exception`() {
        val tool1 = createMockTool("test-tool", "Test Tool 1")
        val tool2 = createMockTool("test-tool", "Test Tool 2")

        registry.registerTool(tool1)

        Assertions.assertThrows(IllegalArgumentException::class.java) {
            registry.registerTool(tool2)
        }
    }

    @Test
    fun `test register multiple tools`() {
        val tools = listOf(
            createMockTool("tool1", "Tool 1"),
            createMockTool("tool2", "Tool 2"),
            createMockTool("tool3", "Tool 3")
        )

        registry.registerTools(tools)

        Assertions.assertEquals(3, registry.getAllTools().size)
        Assertions.assertNotNull(registry.getTool("tool1"))
        Assertions.assertNotNull(registry.getTool("tool2"))
        Assertions.assertNotNull(registry.getTool("tool3"))
    }

    @Test
    fun `test unregister tool`() {
        val tool = createMockTool("test-tool", "Test Tool")
        registry.registerTool(tool)

        Assertions.assertTrue(registry.unregisterTool("test-tool"))
        Assertions.assertNull(registry.getTool("test-tool"))
    }

    @Test
    fun `test unregister non-existent tool`() {
        Assertions.assertFalse(registry.unregisterTool("non-existent-tool"))
    }

    @Test
    fun `test get tools by category`() {
        val tool1 = createMockTool("tool1", "Tool 1", ToolCategory.TASK_MANAGEMENT)
        val tool2 = createMockTool("tool2", "Tool 2", ToolCategory.FEATURE_MANAGEMENT)
        val tool3 = createMockTool("tool3", "Tool 3", ToolCategory.TASK_MANAGEMENT)

        registry.registerTools(listOf(tool1, tool2, tool3))

        val taskTools = registry.getToolsByCategory(ToolCategory.TASK_MANAGEMENT)
        Assertions.assertEquals(2, taskTools.size)

        val featureTools = registry.getToolsByCategory(ToolCategory.FEATURE_MANAGEMENT)
        Assertions.assertEquals(1, featureTools.size)
    }

    @Test
    fun `test register with server`() {
        val tool1 = createMockTool("tool1", "Tool 1")
        val tool2 = createMockTool("tool2", "Tool 2")
        registry.registerTools(listOf(tool1, tool2))

        val mockServer = mockk<Server>(relaxed = true)
        registry.registerWithServer(mockServer)

        // Verify that the server.addTool method was called for each tool
        verify(exactly = 1) {
            mockServer.addTool(
                name = eq("tool1"),
                description = eq("Tool 1"),
                inputSchema = any(),
                title = any(),
                outputSchema = any(),
                toolAnnotations = any(),
                handler = any()
            )
        }
        verify(exactly = 1) {
            mockServer.addTool(
                name = eq("tool2"),
                description = eq("Tool 2"),
                inputSchema = any(),
                title = any(),
                outputSchema = any(),
                toolAnnotations = any(),
                handler = any()
            )
        }
    }

    private fun createMockTool(
        name: String,
        description: String,
        category: ToolCategory = ToolCategory.TASK_MANAGEMENT
    ): ToolDefinition {
        return object : ToolDefinition {
            override val name: String = name
            override val description: String = description
            override val parameterSchema: Tool.Input = Tool.Input(
                properties = JsonObject(mapOf("type" to JsonPrimitive("object")))
            )
            override val category: ToolCategory = category

            override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
                return JsonObject(mapOf("success" to JsonPrimitive(true)))
            }
        }
    }
}