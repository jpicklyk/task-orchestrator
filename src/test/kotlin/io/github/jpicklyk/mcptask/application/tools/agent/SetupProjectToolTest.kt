package io.github.jpicklyk.mcptask.application.tools.agent

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.infrastructure.repository.RepositoryProvider
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Test for SetupProjectTool
 *
 * NOTE: This test uses a temporary directory to avoid modifying the actual .taskorchestrator/ directory.
 * Each test creates a fresh temporary directory that is cleaned up after the test completes.
 */
class SetupProjectToolTest {

    private lateinit var tool: BaseToolDefinition
    private lateinit var mockContext: ToolExecutionContext
    private lateinit var tempDir: Path
    private lateinit var originalUserDir: String

    @BeforeEach
    fun setup() {
        // Create mock execution context (tool doesn't use repositories)
        val mockRepositoryProvider = mockk<RepositoryProvider>()
        mockContext = ToolExecutionContext(mockRepositoryProvider)

        tool = SetupProjectTool()

        // Create temporary directory for testing (isolated from real project)
        tempDir = Files.createTempDirectory("project-setup-test")

        // Save original user.dir and set to temp directory
        // This ensures the tool operates in the temp directory, not the real project directory
        originalUserDir = System.getProperty("user.dir")
        System.setProperty("user.dir", tempDir.toString())
    }

    @AfterEach
    fun tearDown() {
        // Restore original user.dir
        System.setProperty("user.dir", originalUserDir)

        // Clean up temporary directory
        tempDir.toFile().deleteRecursively()
    }

    // Validation Tests

    @Test
    fun `validate with empty parameters should not throw exceptions`() {
        val emptyParams = JsonObject(emptyMap())
        assertDoesNotThrow { tool.validateParams(emptyParams) }
    }

    @Test
    fun `validate with any parameters should not throw exceptions since none are required`() {
        val params = JsonObject(
            mapOf(
                "unexpected" to JsonPrimitive("value")
            )
        )
        assertDoesNotThrow { tool.validateParams(params) }
    }

    // Execution Tests

    @Test
    fun `execute should return success response`() = runBlocking {
        val params = JsonObject(emptyMap())

        val response = tool.execute(params, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject

        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Success should be true")
        assertTrue(
            responseObj["message"]?.jsonPrimitive?.content?.contains("setup") ?: false,
            "Message should mention setup"
        )

        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data object should not be null")
        assertNotNull(data!!["directoryCreated"], "Should have directoryCreated field")
        assertNotNull(data["configCreated"], "Should have configCreated field")
        assertNotNull(data["workflowConfigCreated"], "Should have workflowConfigCreated field")
        assertNotNull(data["agentMappingCreated"], "Should have agentMappingCreated field")
        assertNotNull(data["directory"], "Should have directory field")
        assertNotNull(data["orchestrationFilesCreated"], "Should have orchestrationFilesCreated field")
        assertNotNull(data["orchestrationPath"], "Should have orchestrationPath field")
    }

    @Test
    fun `execute should create taskorchestrator directory`() = runBlocking {
        val params = JsonObject(emptyMap())

        val response = tool.execute(params, mockContext)
        val responseObj = response as JsonObject
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Should succeed")

        // Verify .taskorchestrator directory exists
        val workingDir = Paths.get(System.getProperty("user.dir"))
        val taskOrchestratorDir = workingDir.resolve(".taskorchestrator")

        assertTrue(Files.exists(taskOrchestratorDir), ".taskorchestrator directory should exist after execution")
    }

    @Test
    fun `execute should create config yaml file`() = runBlocking {
        val params = JsonObject(emptyMap())

        val response = tool.execute(params, mockContext)
        val responseObj = response as JsonObject
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Should succeed")

        // Verify config.yaml exists
        val workingDir = Paths.get(System.getProperty("user.dir"))
        val configFile = workingDir.resolve(".taskorchestrator/config.yaml")

        assertTrue(Files.exists(configFile), "config.yaml should exist after execution")
        assertTrue(Files.size(configFile) > 0, "config.yaml should not be empty")

        // Verify config.yaml contains expected content
        val configContent = Files.readString(configFile)
        assertTrue(configContent.contains("version:"), "Config should have version")
        assertTrue(configContent.contains("status_progression:"), "Config should have status_progression section")
    }

    @Test
    fun `execute should create workflow config yaml file`() = runBlocking {
        val params = JsonObject(emptyMap())

        val response = tool.execute(params, mockContext)
        val responseObj = response as JsonObject
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Should succeed")

        // Verify status-workflow-config.yaml exists
        val workingDir = Paths.get(System.getProperty("user.dir"))
        val workflowConfigFile = workingDir.resolve(".taskorchestrator/status-workflow-config.yaml")

        assertTrue(Files.exists(workflowConfigFile), "status-workflow-config.yaml should exist after execution")
        assertTrue(Files.size(workflowConfigFile) > 0, "status-workflow-config.yaml should not be empty")

        // Verify workflow config contains expected content
        val configContent = Files.readString(workflowConfigFile)
        assertTrue(configContent.contains("status_progressions:"), "Workflow config should have status_progressions")
        assertTrue(configContent.contains("flow_mappings:"), "Workflow config should have flow_mappings")
    }

    @Test
    fun `execute should create agent mapping yaml file`() = runBlocking {
        val params = JsonObject(emptyMap())

        val response = tool.execute(params, mockContext)
        val responseObj = response as JsonObject
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Should succeed")

        // Verify agent-mapping.yaml exists
        val workingDir = Paths.get(System.getProperty("user.dir"))
        val agentMappingFile = workingDir.resolve(".taskorchestrator/agent-mapping.yaml")

        assertTrue(Files.exists(agentMappingFile), "agent-mapping.yaml should exist after execution")
        assertTrue(Files.size(agentMappingFile) > 0, "agent-mapping.yaml should not be empty")
    }

    @Test
    fun `execute should be idempotent`() = runBlocking {
        val params = JsonObject(emptyMap())

        // First execution
        val firstResponse = tool.execute(params, mockContext)
        val firstResponseObj = firstResponse as JsonObject
        assertTrue(firstResponseObj["success"]?.jsonPrimitive?.boolean == true, "First execution should succeed")

        // Second execution
        val secondResponse = tool.execute(params, mockContext)
        val secondResponseObj = secondResponse as JsonObject
        assertTrue(secondResponseObj["success"]?.jsonPrimitive?.boolean == true, "Second execution should succeed")

        // Second execution should indicate files were skipped
        val secondMessage = secondResponseObj["message"]?.jsonPrimitive?.content
        assertTrue(
            secondMessage?.contains("already exists") == true || secondMessage?.contains("already present") == true,
            "Second execution message should indicate idempotent behavior"
        )
    }

    @Test
    fun `execute should skip existing files`() = runBlocking {
        val params = JsonObject(emptyMap())

        // First execution - creates files
        val firstResponse = tool.execute(params, mockContext)
        val firstResponseObj = firstResponse as JsonObject
        assertTrue(firstResponseObj["success"]?.jsonPrimitive?.boolean == true, "First execution should succeed")

        val firstData = firstResponseObj["data"]?.jsonObject
        assertTrue(firstData!!["configCreated"]?.jsonPrimitive?.boolean == true, "configCreated should be true on first run")
        assertTrue(firstData["workflowConfigCreated"]?.jsonPrimitive?.boolean == true, "workflowConfigCreated should be true on first run")
        assertTrue(firstData["agentMappingCreated"]?.jsonPrimitive?.boolean == true, "agentMappingCreated should be true on first run")

        // Second execution - skips existing files
        val secondResponse = tool.execute(params, mockContext)
        val secondResponseObj = secondResponse as JsonObject
        assertTrue(secondResponseObj["success"]?.jsonPrimitive?.boolean == true, "Second execution should succeed")

        val secondData = secondResponseObj["data"]?.jsonObject
        assertFalse(secondData!!["configCreated"]?.jsonPrimitive?.boolean == true, "configCreated should be false on second run")
        assertFalse(secondData["workflowConfigCreated"]?.jsonPrimitive?.boolean == true, "workflowConfigCreated should be false on second run")
        assertFalse(secondData["agentMappingCreated"]?.jsonPrimitive?.boolean == true, "agentMappingCreated should be false on second run")
    }

    @Test
    fun `tool should have correct metadata`() {
        assertEquals("setup_project", tool.name, "Tool name should be setup_project")
        assertEquals("Setup Task Orchestrator Project", tool.title, "Tool should have proper title")
        assertTrue(tool.description.contains("Task Orchestrator"), "Description should mention Task Orchestrator")
        assertTrue(tool.description.contains(".taskorchestrator"), "Description should mention .taskorchestrator directory")
        assertTrue(tool.description.contains("setup_claude_orchestration"), "Description should mention setup_claude_orchestration as related tool")
    }

    @Test
    fun `execute should create orchestration workflow files`() = runBlocking {
        val params = JsonObject(emptyMap())

        val response = tool.execute(params, mockContext)
        val responseObj = response as JsonObject
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Should succeed")

        // Verify orchestration directory exists
        val workingDir = Paths.get(System.getProperty("user.dir"))
        val orchestrationDir = workingDir.resolve(".taskorchestrator/orchestration")

        assertTrue(Files.exists(orchestrationDir), "orchestration directory should exist after execution")

        // Verify at least one orchestration file exists
        val orchFiles = listOf(
            "decision-trees.md",
            "workflows.md",
            "examples.md",
            "optimizations.md",
            "error-handling.md",
            "activation-prompt.md",
            "README.md"
        )

        var filesFound = 0
        for (filename in orchFiles) {
            val file = orchestrationDir.resolve(filename)
            if (Files.exists(file)) {
                filesFound++
                assertTrue(Files.size(file) > 0, "$filename should not be empty")
            }
        }

        assertTrue(filesFound > 0, "Should have created at least one orchestration file")

        // Verify response includes orchestration file count
        val data = responseObj["data"]?.jsonObject
        assertNotNull(data!!["orchestrationFilesCreated"], "Should have orchestrationFilesCreated field")
        assertTrue(data["orchestrationFilesCreated"]?.jsonPrimitive?.int!! >= 1, "Should have created at least 1 orchestration file")
    }

    @Test
    fun `execute should NOT create Claude-specific files`() = runBlocking {
        val params = JsonObject(emptyMap())

        val response = tool.execute(params, mockContext)
        val responseObj = response as JsonObject
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Should succeed")

        val workingDir = Paths.get(System.getProperty("user.dir"))

        // Verify .claude directory does NOT exist (that's setup_claude_orchestration's job)
        val claudeDir = workingDir.resolve(".claude")
        assertFalse(Files.exists(claudeDir), ".claude directory should NOT exist (handled by setup_claude_orchestration)")

        // Verify response does NOT include Claude-specific fields
        val data = responseObj["data"]?.jsonObject
        assertNull(data!!["claudeDirectoryCreated"], "Should NOT have claudeDirectoryCreated field")
        assertNull(data["agentFilesCreated"], "Should NOT have agentFilesCreated field")
        assertNull(data["skillsCopied"], "Should NOT have skillsCopied field")
    }
}
