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
 * Test for SetupClaudeOrchestrationTool
 *
 * NOTE: This test uses a temporary directory to avoid modifying the actual .claude/ directory.
 * Each test creates a fresh temporary directory that is cleaned up after the test completes.
 */
class SetupClaudeOrchestrationToolTest {

    private lateinit var tool: BaseToolDefinition
    private lateinit var mockContext: ToolExecutionContext
    private lateinit var tempDir: Path
    private lateinit var originalUserDir: String

    @BeforeEach
    fun setup() {
        // Create mock execution context (tool doesn't use repositories)
        val mockRepositoryProvider = mockk<RepositoryProvider>()
        mockContext = ToolExecutionContext(mockRepositoryProvider)

        tool = SetupClaudeOrchestrationTool()

        // Create temporary directory for testing
        tempDir = Files.createTempDirectory("claude-orchestration-test")

        // Save original user.dir and set to temp directory
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

        // Print error details if the response failed
        if (responseObj["success"]?.jsonPrimitive?.boolean != true) {
            println("Tool execution failed:")
            println("Message: ${responseObj["message"]?.jsonPrimitive?.content}")
            println("Error: ${responseObj["error"]}")
            println("Full response: $responseObj")
        }

        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Success should be true")
        assertTrue(
            responseObj["message"]?.jsonPrimitive?.content?.contains("setup") ?: false,
            "Message should mention setup"
        )

        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data object should not be null")
        assertNotNull(data!!["directoryCreated"], "Should have directoryCreated field")
        assertNotNull(data["agentFilesCreated"], "Should have agentFilesCreated field")
        assertNotNull(data["agentFilesSkipped"], "Should have agentFilesSkipped field")
        assertNotNull(data["directory"], "Should have directory field")
        assertNotNull(data["totalAgents"], "Should have totalAgents field")
    }

    @Test
    fun `execute should report claude directory path in response`() = runBlocking {
        val params = JsonObject(emptyMap())

        val response = tool.execute(params, mockContext)
        val responseObj = response as JsonObject
        val data = responseObj["data"]?.jsonObject

        val directory = data!!["directory"]?.jsonPrimitive?.content
        assertNotNull(directory, "Directory path should not be null")
        assertTrue(directory!!.contains(".claude"), "Directory path should contain .claude")
    }

    @Test
    fun `execute should report correct number of total agents`() = runBlocking {
        val params = JsonObject(emptyMap())

        val response = tool.execute(params, mockContext)
        val responseObj = response as JsonObject
        val data = responseObj["data"]?.jsonObject

        val totalAgents = data!!["totalAgents"]?.jsonPrimitive?.int
        assertEquals(8, totalAgents, "Should report 8 total agent files")
    }

    @Test
    fun `execute should create all expected agent files`() = runBlocking {
        val params = JsonObject(emptyMap())

        // Execute the tool
        val response = tool.execute(params, mockContext)
        val responseObj = response as JsonObject
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Should succeed")

        // Verify .claude/agents/task-orchestrator directory exists
        val workingDir = Paths.get(System.getProperty("user.dir"))
        val claudeDir = workingDir.resolve(".claude")
        val agentsDir = claudeDir.resolve("agents").resolve("task-orchestrator")

        assertTrue(Files.exists(agentsDir), ".claude/agents/task-orchestrator directory should exist after execution")

        // Verify expected agent files exist
        val expectedFiles = listOf(
            "backend-engineer.md",
            "bug-triage-specialist.md",
            "database-engineer.md",
            "feature-architect.md",
            "frontend-developer.md",
            "planning-specialist.md",
            "technical-writer.md",
            "test-engineer.md"
        )

        expectedFiles.forEach { fileName ->
            val file = agentsDir.resolve(fileName)
            assertTrue(Files.exists(file), "Agent file $fileName should exist")
            assertTrue(Files.size(file) > 0, "$fileName should not be empty")
        }
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

        // Second execution should indicate it verified rather than created new files
        val secondMessage = secondResponseObj["message"]?.jsonPrimitive?.content
        // Message should indicate verification or that files were skipped
        assertTrue(
            secondMessage?.contains("verified") == true || secondMessage?.contains("Skipped") == true,
            "Second execution message should indicate idempotent behavior"
        )
    }

    @Test
    fun `execute should verify agent files have correct Claude Code model format`() = runBlocking {
        val params = JsonObject(emptyMap())

        // Execute the tool
        tool.execute(params, mockContext)

        val workingDir = Paths.get(System.getProperty("user.dir"))
        val agentsDir = workingDir.resolve(".claude/agents/task-orchestrator")

        // Verify backend-engineer has model: sonnet (not claude-sonnet-4)
        val backendFile = agentsDir.resolve("backend-engineer.md")
        if (Files.exists(backendFile)) {
            val backendContent = Files.readString(backendFile)
            assertTrue(
                backendContent.contains("model:"),
                "backend-engineer should have model field"
            )
            // Should have simple format, not full model name
            assertFalse(
                backendContent.contains("model: claude-sonnet-4"),
                "Should not contain full Claude model name format"
            )
        }

        // Verify planning-specialist has model: opus (not claude-opus-4)
        val planningFile = agentsDir.resolve("planning-specialist.md")
        if (Files.exists(planningFile)) {
            val planningContent = Files.readString(planningFile)
            assertTrue(
                planningContent.contains("model:"),
                "planning-specialist should have model field"
            )
            assertFalse(
                planningContent.contains("model: claude-opus-4"),
                "Should not contain full Claude model name format"
            )
        }
    }

    @Test
    fun `execute should return proper response structure`() = runBlocking {
        val params = JsonObject(emptyMap())

        val response = tool.execute(params, mockContext)
        assertTrue(response is JsonObject, "Response should be a JsonObject")

        val responseObj = response as JsonObject

        // Verify top-level response structure
        assertNotNull(responseObj["success"], "Response should have success field")
        assertNotNull(responseObj["message"], "Response should have message field")
        assertNotNull(responseObj["data"], "Response should have data field")
        assertTrue(responseObj["error"] is JsonNull, "Error should be null on success")
        assertNotNull(responseObj["metadata"], "Response should have metadata field")

        // Verify data structure has all required fields
        val data = responseObj["data"]?.jsonObject
        assertNotNull(data, "Data should not be null")
        assertNotNull(data!!["directoryCreated"], "Data should have directoryCreated field")
        assertNotNull(data["agentFilesCreated"], "Data should have agentFilesCreated field")
        assertNotNull(data["agentFilesSkipped"], "Data should have agentFilesSkipped field")
        assertNotNull(data["directory"], "Data should have directory field")
        assertNotNull(data["totalAgents"], "Data should have totalAgents field")

        // Verify hooks-related fields are NOT present
        assertNull(data["hooksDirectoryCreated"], "Data should NOT have hooksDirectoryCreated field")
        assertNull(data["hooksCopied"], "Data should NOT have hooksCopied field")
        assertNull(data["hooksDirectory"], "Data should NOT have hooksDirectory field")
    }

    @Test
    fun `tool should have correct metadata`() {
        assertEquals("setup_claude_orchestration", tool.name, "Tool name should be setup_claude_orchestration")
        assertEquals("Setup Claude Code Orchestration System", tool.title, "Tool should have proper title")
        assertTrue(tool.description.contains("Claude Code"), "Description should mention Claude Code")
        assertTrue(tool.description.contains(".claude/agents/task-orchestrator/"), "Description should mention .claude/agents/task-orchestrator/")
    }

    @Test
    fun `execute should create skills directory`() = runBlocking {
        val params = JsonObject(emptyMap())

        val response = tool.execute(params, mockContext)
        val responseObj = response as JsonObject
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Should succeed")

        // Verify .claude/skills directory exists
        val workingDir = Paths.get(System.getProperty("user.dir"))
        val skillsDir = workingDir.resolve(".claude/skills")

        assertTrue(Files.exists(skillsDir), ".claude/skills directory should exist after execution")

        // Verify response includes skills directory info
        val data = responseObj["data"]?.jsonObject
        assertNotNull(data!!["skillsDirectoryCreated"], "Should have skillsDirectoryCreated field")
        assertNotNull(data["skillsCopied"], "Should have skillsCopied field")
        assertNotNull(data["skillsDirectory"], "Should have skillsDirectory field")
        assertNotNull(data["totalSkills"], "Should have totalSkills field")
    }

    @Test
    fun `execute should NOT create hooks directory by default`() = runBlocking {
        val params = JsonObject(emptyMap())

        val response = tool.execute(params, mockContext)
        val responseObj = response as JsonObject
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Should succeed")

        // Verify .claude/hooks/task-orchestrator directory does NOT exist
        val workingDir = Paths.get(System.getProperty("user.dir"))
        val hooksDir = workingDir.resolve(".claude/hooks/task-orchestrator")

        assertFalse(Files.exists(hooksDir), ".claude/hooks/task-orchestrator directory should NOT exist after execution (hooks not created by default)")

        // Verify response does NOT include hooks directory info
        val data = responseObj["data"]?.jsonObject
        assertNull(data!!["hooksDirectoryCreated"], "Should NOT have hooksDirectoryCreated field")
        assertNull(data["hooksCopied"], "Should NOT have hooksCopied field")
        assertNull(data["hooksDirectory"], "Should NOT have hooksDirectory field")
    }

    @Test
    fun `execute should copy all expected skill directories`() = runBlocking {
        val params = JsonObject(emptyMap())

        val response = tool.execute(params, mockContext)
        val responseObj = response as JsonObject
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Should succeed")

        val workingDir = Paths.get(System.getProperty("user.dir"))
        val skillsDir = workingDir.resolve(".claude/skills")

        val expectedSkills = listOf(
            "dependency-analysis",
            "dependency-orchestration",
            "feature-orchestration",
            "hook-builder",
            "status-progression",
            "task-orchestration"
        )

        expectedSkills.forEach { skillName ->
            val skillDir = skillsDir.resolve(skillName)
            assertTrue(Files.exists(skillDir), "Skill directory $skillName should exist")

            val skillFile = skillDir.resolve("SKILL.md")
            assertTrue(Files.exists(skillFile), "SKILL.md should exist in $skillName")
            assertTrue(Files.size(skillFile) > 0, "SKILL.md in $skillName should not be empty")
        }

        // Verify response indicates skills were copied
        val data = responseObj["data"]?.jsonObject
        val totalSkills = data!!["totalSkills"]?.jsonPrimitive?.int
        assertEquals(6, totalSkills, "Should report 6 total skills")
    }

    @Test
    fun `execute should NOT copy hook examples by default`() = runBlocking {
        val params = JsonObject(emptyMap())

        val response = tool.execute(params, mockContext)
        val responseObj = response as JsonObject
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Should succeed")

        val workingDir = Paths.get(System.getProperty("user.dir"))
        val hooksDir = workingDir.resolve(".claude/hooks/task-orchestrator")

        // Verify hooks directory and files do NOT exist
        assertFalse(Files.exists(hooksDir), "Hooks directory should NOT exist by default")

        // If hooks directory exists for some reason (manual creation), verify key files are NOT copied
        if (Files.exists(hooksDir)) {
            val readme = hooksDir.resolve("README.md")
            assertFalse(Files.exists(readme), "README.md should NOT be copied by setup tool")

            val quickRef = hooksDir.resolve("QUICK_REFERENCE.md")
            assertFalse(Files.exists(quickRef), "QUICK_REFERENCE.md should NOT be copied by setup tool")

            val scriptsDir = hooksDir.resolve("scripts")
            assertFalse(Files.exists(scriptsDir), "scripts/ subdirectory should NOT be created by setup tool")

            val templatesDir = hooksDir.resolve("templates")
            assertFalse(Files.exists(templatesDir), "templates/ subdirectory should NOT be created by setup tool")
        }
    }

    @Test
    fun `execute should create config yaml file`() = runBlocking {
        val params = JsonObject(emptyMap())

        val response = tool.execute(params, mockContext)
        val responseObj = response as JsonObject
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Should succeed")

        // Verify .taskorchestrator/config.yaml exists
        val workingDir = Paths.get(System.getProperty("user.dir"))
        val taskOrchestratorDir = workingDir.resolve(".taskorchestrator")
        val configFile = taskOrchestratorDir.resolve("config.yaml")

        assertTrue(Files.exists(taskOrchestratorDir), ".taskorchestrator directory should exist after execution")
        assertTrue(Files.exists(configFile), "config.yaml should exist after execution")
        assertTrue(Files.size(configFile) > 0, "config.yaml should not be empty")

        // Verify config.yaml contains expected content (v2.0 schema)
        val configContent = Files.readString(configFile)
        assertTrue(configContent.contains("version: \"2.0.0\""), "Config should have version 2.0.0")
        assertTrue(configContent.contains("status_progression:"), "Config should have status_progression section")
        assertTrue(configContent.contains("default_flow:"), "Config should have default_flow (v2.0 schema)")
        assertTrue(configContent.contains("emergency_transitions:"), "Config should have emergency_transitions")
        assertTrue(configContent.contains("terminal_statuses:"), "Config should have terminal_statuses")
        assertTrue(configContent.contains("cancelled"), "Config should include cancelled status")
        assertTrue(configContent.contains("deferred"), "Config should include deferred status")

        // Verify response includes config creation info
        val data = responseObj["data"]?.jsonObject
        assertNotNull(data!!["configCreated"], "Should have configCreated field")
        assertNotNull(data["configPath"], "Should have configPath field")
        assertNotNull(data["v2ModeEnabled"], "Should have v2ModeEnabled field")
        assertTrue(data["configCreated"]?.jsonPrimitive?.boolean == true, "configCreated should be true on first run")
        assertTrue(data["v2ModeEnabled"]?.jsonPrimitive?.boolean == true, "v2ModeEnabled should be true when config created")
    }

    @Test
    fun `execute should skip config yaml if already exists`() = runBlocking {
        val params = JsonObject(emptyMap())

        // First execution - creates config
        val firstResponse = tool.execute(params, mockContext)
        val firstResponseObj = firstResponse as JsonObject
        assertTrue(firstResponseObj["success"]?.jsonPrimitive?.boolean == true, "First execution should succeed")

        val firstData = firstResponseObj["data"]?.jsonObject
        assertTrue(firstData!!["configCreated"]?.jsonPrimitive?.boolean == true, "configCreated should be true on first run")

        // Second execution - skips config
        val secondResponse = tool.execute(params, mockContext)
        val secondResponseObj = secondResponse as JsonObject
        assertTrue(secondResponseObj["success"]?.jsonPrimitive?.boolean == true, "Second execution should succeed")

        val secondData = secondResponseObj["data"]?.jsonObject
        assertFalse(secondData!!["configCreated"]?.jsonPrimitive?.boolean == true, "configCreated should be false on second run (already exists)")

        // Message should indicate config already exists
        val secondMessage = secondResponseObj["message"]?.jsonPrimitive?.content
        assertTrue(
            secondMessage?.contains("Config.yaml already exists") == true,
            "Second execution message should indicate config already exists"
        )
    }
}
