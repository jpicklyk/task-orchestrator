package io.github.jpicklyk.mcptask.infrastructure.filesystem

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * Manages the .claude/agents/ directory structure for Claude Code agent definitions.
 *
 * Claude Code expects agent definitions in .claude/agents/ with specific format:
 * - YAML frontmatter with name, description, tools, model
 * - Markdown content with agent instructions
 * - Model field: "sonnet" or "opus" (not full model names)
 *
 * Responsibilities:
 * - Create and manage .claude/agents/ directory
 * - Copy Claude-specific agent template files from embedded resources
 * - Read/write agent definition files
 * - Handle Docker volume mounts
 * - Portable across Windows/Linux/macOS
 *
 * ## Configuration
 * The project root directory can be configured via environment variable:
 * - `AGENT_CONFIG_DIR`: Directory containing .claude/agents/
 * - Defaults to current working directory (user.dir)
 * - In Docker, mount project directory: -v /host/project:/project -e AGENT_CONFIG_DIR=/project
 */
class ClaudeAgentDirectoryManager(
    private val projectRoot: Path = Paths.get(
        System.getenv("AGENT_CONFIG_DIR") ?: System.getProperty("user.dir")
    )
) {
    private val logger = LoggerFactory.getLogger(ClaudeAgentDirectoryManager::class.java)

    companion object {
        const val CLAUDE_DIR = ".claude"
        const val AGENTS_DIR = "agents"
        const val RESOURCE_PATH_PREFIX = "/agents/claude"
        const val TASKORCHESTRATOR_DIR = ".taskorchestrator"
        const val AGENT_MAPPING_FILE = "agent-mapping.yaml"
        const val CLAUDE_MD_FILE = "CLAUDE.md"
        const val DECISION_GATES_MARKER = "## Claude Code Sub-Agent Decision Gates"

        // Default Claude Code agent template files
        val DEFAULT_AGENT_FILES = listOf(
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

        init {
            val configDir = System.getenv("AGENT_CONFIG_DIR")
            if (configDir != null) {
                LoggerFactory.getLogger(ClaudeAgentDirectoryManager::class.java)
                    .info("Using AGENT_CONFIG_DIR: $configDir")
            }
        }
    }

    /**
     * Get the .claude directory path
     */
    fun getClaudeDir(): Path {
        return projectRoot.resolve(CLAUDE_DIR)
    }

    /**
     * Get the agents directory path (.claude/agents/)
     */
    fun getAgentsDir(): Path {
        return getClaudeDir().resolve(AGENTS_DIR)
    }

    /**
     * Check if the .claude directory exists
     */
    fun claudeDirExists(): Boolean {
        return Files.exists(getClaudeDir())
    }

    /**
     * Check if the agents directory exists
     */
    fun agentsDirExists(): Boolean {
        return Files.exists(getAgentsDir())
    }

    /**
     * Create the .claude/agents/ directory structure
     * Returns true if created, false if already exists
     */
    fun createDirectoryStructure(): Boolean {
        val claudeDir = getClaudeDir()
        val agentsDir = getAgentsDir()

        var created = false

        if (!Files.exists(claudeDir)) {
            Files.createDirectories(claudeDir)
            logger.info("Created directory: $claudeDir")
            created = true
        }

        if (!Files.exists(agentsDir)) {
            Files.createDirectories(agentsDir)
            logger.info("Created directory: $agentsDir")
            created = true
        }

        return created
    }

    /**
     * Copy default Claude Code agent template files from embedded resources to the agents directory.
     * Skips files that already exist (idempotent).
     *
     * Returns list of files that were copied.
     */
    fun copyDefaultAgentTemplates(): List<String> {
        val agentsDir = getAgentsDir()
        val copiedFiles = mutableListOf<String>()

        if (!Files.exists(agentsDir)) {
            throw IllegalStateException("Agents directory does not exist. Call createDirectoryStructure() first.")
        }

        for (fileName in DEFAULT_AGENT_FILES) {
            val targetFile = agentsDir.resolve(fileName)

            // Skip if file already exists (idempotent)
            if (Files.exists(targetFile)) {
                logger.debug("Agent file already exists, skipping: $fileName")
                continue
            }

            // Read from embedded resources
            val resourcePath = "$RESOURCE_PATH_PREFIX/$fileName"
            val resourceStream = javaClass.getResourceAsStream(resourcePath)
                ?: throw IllegalStateException("Could not find embedded resource: $resourcePath")

            // Copy to target location
            resourceStream.use { input ->
                Files.copy(input, targetFile, StandardCopyOption.REPLACE_EXISTING)
            }

            logger.info("Copied Claude agent template: $fileName")
            copiedFiles.add(fileName)
        }

        return copiedFiles
    }

    /**
     * List all agent definition files in the agents directory
     * Returns list of file names (not full paths)
     */
    fun listAgentFiles(): List<String> {
        val agentsDir = getAgentsDir()

        if (!Files.exists(agentsDir)) {
            return emptyList()
        }

        return Files.list(agentsDir)
            .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".md") }
            .map { it.fileName.toString() }
            .toList()
    }

    /**
     * Read an agent definition file by name
     * Returns file content as string, or null if file doesn't exist
     */
    fun readAgentFile(fileName: String): String? {
        val filePath = getAgentsDir().resolve(fileName)

        if (!Files.exists(filePath)) {
            logger.warn("Agent file not found: $fileName")
            return null
        }

        return Files.readString(filePath)
    }

    /**
     * Write content to an agent definition file
     */
    fun writeAgentFile(fileName: String, content: String) {
        val filePath = getAgentsDir().resolve(fileName)
        Files.writeString(filePath, content)
        logger.info("Wrote agent file: $fileName")
    }

    /**
     * Get the .taskorchestrator directory path
     */
    fun getTaskOrchestratorDir(): Path {
        return projectRoot.resolve(TASKORCHESTRATOR_DIR)
    }

    /**
     * Check if the .taskorchestrator directory exists
     */
    fun taskOrchestratorDirExists(): Boolean {
        return Files.exists(getTaskOrchestratorDir())
    }

    /**
     * Create the .taskorchestrator directory
     * Returns true if created, false if already exists
     */
    fun createTaskOrchestratorDirectory(): Boolean {
        val taskOrchestratorDir = getTaskOrchestratorDir()

        if (!Files.exists(taskOrchestratorDir)) {
            Files.createDirectories(taskOrchestratorDir)
            logger.info("Created directory: $taskOrchestratorDir")
            return true
        }

        return false
    }

    /**
     * Copy the agent-mapping.yaml file from embedded resources to .taskorchestrator/
     * Skips if file already exists (idempotent).
     *
     * Returns true if the file was copied, false if it already existed.
     */
    fun copyAgentMappingFile(): Boolean {
        val taskOrchestratorDir = getTaskOrchestratorDir()
        val targetFile = taskOrchestratorDir.resolve(AGENT_MAPPING_FILE)

        // Skip if file already exists (idempotent)
        if (Files.exists(targetFile)) {
            logger.debug("Agent mapping file already exists, skipping: $AGENT_MAPPING_FILE")
            return false
        }

        // Ensure directory exists
        if (!Files.exists(taskOrchestratorDir)) {
            throw IllegalStateException(".taskorchestrator directory does not exist. Call createTaskOrchestratorDirectory() first.")
        }

        // Read from embedded resources
        val resourcePath = "/agents/$AGENT_MAPPING_FILE"
        val resourceStream = javaClass.getResourceAsStream(resourcePath)
            ?: throw IllegalStateException("Could not find embedded resource: $resourcePath")

        // Copy to target location
        resourceStream.use { input ->
            Files.copy(input, targetFile, StandardCopyOption.REPLACE_EXISTING)
        }

        logger.info("Copied agent mapping file: $AGENT_MAPPING_FILE")
        return true
    }

    /**
     * Inject decision gates section into CLAUDE.md file.
     * Skips if the section already exists (idempotent).
     *
     * Returns true if the section was injected, false if it already existed or CLAUDE.md doesn't exist.
     */
    fun injectDecisionGatesIntoClaude(): Boolean {
        val claudeMdPath = projectRoot.resolve(CLAUDE_MD_FILE)

        // Check if CLAUDE.md exists
        if (!Files.exists(claudeMdPath)) {
            logger.warn("CLAUDE.md not found at: $claudeMdPath")
            return false
        }

        // Read current content
        val currentContent = Files.readString(claudeMdPath)

        // Check if decision gates already present (idempotent)
        if (currentContent.contains(DECISION_GATES_MARKER)) {
            logger.debug("Decision gates already present in CLAUDE.md")
            return false
        }

        // Find injection point: before "## Task Orchestrator - AI Initialization"
        val injectionMarker = "## Task Orchestrator - AI Initialization"
        val injectionIndex = currentContent.indexOf(injectionMarker)

        if (injectionIndex == -1) {
            logger.warn("Could not find injection point in CLAUDE.md (looking for: $injectionMarker)")
            return false
        }

        // Build decision gates content
        val decisionGatesContent = """
## Claude Code Sub-Agent Decision Gates

**These decision gates help you route work to specialized agents proactively.**

### Before Creating a Feature

❓ **Did user say** "create/start/build a feature for..." **OR** provide rich context (3+ paragraphs)?
→ **YES?** Launch **Feature Architect** agent
→ **NO?** Proceed with direct `create_feature` tool

### Before Starting Multi-Task Feature Work

❓ **Does feature have** 4+ tasks with dependencies?
❓ **Need** specialist coordination across domains?
→ **YES?** Launch **Feature Manager** agent (START mode)
→ **NO?** Work through tasks sequentially yourself

### Before Working on a Task

❓ **Is task** part of a larger feature (has `featureId`)?
❓ **Does task** have specialist tags (backend, frontend, database, testing, docs)?
→ **YES?** Check `recommend_agent(taskId)` for specialist routing
→ **NO?** Proceed with direct implementation

### When User Reports a Bug

❓ **User says:** "broken", "error", "crash", "doesn't work", "failing"?
→ **YES?** Launch **Bug Triage Specialist** agent
→ **NO?** If it's a feature request, use Feature Architect

### After Feature Architect Creates Feature

❓ **Does the feature** need task breakdown?
→ **YES?** Launch **Planning Specialist** agent
→ **NO?** If it's a simple feature, create tasks yourself

**Remember:** These gates are for Claude Code only. If using other LLMs (Cursor, Windsurf), use templates and workflow prompts directly.

---

"""

        // Insert decision gates before the AI Initialization section
        val updatedContent = currentContent.substring(0, injectionIndex) +
                decisionGatesContent +
                currentContent.substring(injectionIndex)

        // Write updated content back to file
        Files.writeString(claudeMdPath, updatedContent)
        logger.info("Injected decision gates into CLAUDE.md")

        return true
    }
}
