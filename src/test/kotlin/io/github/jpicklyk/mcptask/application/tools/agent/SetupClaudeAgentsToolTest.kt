package io.github.jpicklyk.mcptask.application.tools.agent

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.infrastructure.repository.RepositoryProvider
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Test for SetupClaudeAgentsTool
 *
 * NOTE: This test creates files in .claude/agents/ in the project root.
 * The tool is designed to be idempotent, so running tests multiple times
 * will skip existing files rather than overwrite them.
 */
class SetupClaudeAgentsToolTest {

    private lateinit var tool: BaseToolDefinition
    private lateinit var mockContext: ToolExecutionContext

    @BeforeEach
    fun setup() {
        // Create mock execution context (tool doesn't use repositories)
        val mockRepositoryProvider = mockk<RepositoryProvider>()
        mockContext = ToolExecutionContext(mockRepositoryProvider)

        tool = SetupClaudeAgentsTool()
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
        assertEquals(10, totalAgents, "Should report 10 total agent files")
    }

    @Test
    fun `execute should create all expected agent files`() = runBlocking {
        val params = JsonObject(emptyMap())

        // Execute the tool
        val response = tool.execute(params, mockContext)
        val responseObj = response as JsonObject
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Should succeed")

        // Verify .claude/agents directory exists
        val workingDir = Paths.get(System.getProperty("user.dir"))
        val claudeDir = workingDir.resolve(".claude")
        val agentsDir = claudeDir.resolve("agents")

        assertTrue(Files.exists(agentsDir), ".claude/agents directory should exist after execution")

        // Verify expected agent files exist
        val expectedFiles = listOf(
            "backend-engineer.md",
            "bug-triage-specialist.md",
            "database-engineer.md",
            "feature-architect.md",
            "feature-manager.md",
            "frontend-developer.md",
            "planning-specialist.md",
            "task-manager.md",
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
        val agentsDir = workingDir.resolve(".claude/agents")

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
    }

    @Test
    fun `tool should have correct metadata`() {
        assertEquals("setup_claude_agents", tool.name, "Tool name should be setup_claude_agents")
        assertEquals("Setup Claude Code Agent Configuration", tool.title, "Tool should have proper title")
        assertTrue(tool.description.contains("Claude Code"), "Description should mention Claude Code")
        assertTrue(tool.description.contains(".claude/agents/"), "Description should mention .claude/agents/")
    }

    @Test
    fun `execute should create skills directory`() = runBlocking {
        val params = JsonObject(emptyMap())

        val response = tool.execute(params, mockContext)
        val responseObj = response as JsonObject
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Should succeed")

        // Verify .claude/skills/task-manager directory exists
        val workingDir = Paths.get(System.getProperty("user.dir"))
        val skillsDir = workingDir.resolve(".claude/skills/task-manager")

        assertTrue(Files.exists(skillsDir), ".claude/skills/task-manager directory should exist after execution")

        // Verify response includes skills directory info
        val data = responseObj["data"]?.jsonObject
        assertNotNull(data!!["skillsDirectoryCreated"], "Should have skillsDirectoryCreated field")
        assertNotNull(data["skillsCopied"], "Should have skillsCopied field")
        assertNotNull(data["skillsDirectory"], "Should have skillsDirectory field")
        assertNotNull(data["totalSkills"], "Should have totalSkills field")
    }

    @Test
    fun `execute should create hooks directory with subdirectories`() = runBlocking {
        val params = JsonObject(emptyMap())

        val response = tool.execute(params, mockContext)
        val responseObj = response as JsonObject
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Should succeed")

        // Verify .claude/hooks/task-manager directory exists
        val workingDir = Paths.get(System.getProperty("user.dir"))
        val hooksDir = workingDir.resolve(".claude/hooks/task-manager")
        val scriptsDir = hooksDir.resolve("scripts")
        val templatesDir = hooksDir.resolve("templates")

        assertTrue(Files.exists(hooksDir), ".claude/hooks/task-manager directory should exist after execution")
        assertTrue(Files.exists(scriptsDir), ".claude/hooks/task-manager/scripts directory should exist")
        assertTrue(Files.exists(templatesDir), ".claude/hooks/task-manager/templates directory should exist")

        // Verify response includes hooks directory info
        val data = responseObj["data"]?.jsonObject
        assertNotNull(data!!["hooksDirectoryCreated"], "Should have hooksDirectoryCreated field")
        assertNotNull(data["hooksCopied"], "Should have hooksCopied field")
        assertNotNull(data["hooksDirectory"], "Should have hooksDirectory field")
    }

    @Test
    fun `execute should copy all expected skill directories`() = runBlocking {
        val params = JsonObject(emptyMap())

        val response = tool.execute(params, mockContext)
        val responseObj = response as JsonObject
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Should succeed")

        val workingDir = Paths.get(System.getProperty("user.dir"))
        val skillsDir = workingDir.resolve(".claude/skills/task-manager")

        val expectedSkills = listOf(
            "dependency-analysis",
            "feature-management",
            "hook-builder",
            "skill-builder",
            "task-management"
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
        assertEquals(5, totalSkills, "Should report 5 total skills")
    }

    @Test
    fun `execute should copy hook examples with proper structure`() = runBlocking {
        val params = JsonObject(emptyMap())

        val response = tool.execute(params, mockContext)
        val responseObj = response as JsonObject
        assertTrue(responseObj["success"]?.jsonPrimitive?.boolean == true, "Should succeed")

        val workingDir = Paths.get(System.getProperty("user.dir"))
        val hooksDir = workingDir.resolve(".claude/hooks/task-manager")

        // Verify key hook files exist
        val readme = hooksDir.resolve("README.md")
        assertTrue(Files.exists(readme), "README.md should exist in hooks directory")

        val quickRef = hooksDir.resolve("QUICK_REFERENCE.md")
        assertTrue(Files.exists(quickRef), "QUICK_REFERENCE.md should exist in hooks directory")

        // Verify scripts subdirectory has scripts
        val scriptsDir = hooksDir.resolve("scripts")
        val taskCompleteScript = scriptsDir.resolve("task-complete-commit.sh")
        if (Files.exists(taskCompleteScript)) {
            assertTrue(Files.size(taskCompleteScript) > 0, "task-complete-commit.sh should not be empty")
        }

        // Verify templates subdirectory has templates
        val templatesDir = hooksDir.resolve("templates")
        val settingsExample = templatesDir.resolve("settings.local.json.example")
        if (Files.exists(settingsExample)) {
            assertTrue(Files.size(settingsExample) > 0, "settings.local.json.example should not be empty")
        }
    }
}
