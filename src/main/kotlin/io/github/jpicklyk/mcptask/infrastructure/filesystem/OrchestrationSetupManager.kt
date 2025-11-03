package io.github.jpicklyk.mcptask.infrastructure.filesystem

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * Manages orchestration setup for Task Orchestrator, including both Claude Code specific
 * files and universal orchestration configuration.
 *
 * ## Claude Code Specific (Subagents, Skills & Plugins)
 * - Agent definitions in .claude/agents/task-orchestrator/ with YAML frontmatter and markdown content
 * - Skills in .claude/skills/ (root level, not in subdirectory) for lightweight coordination
 * - Plugin in .claude/plugins/task-orchestrator/ with SessionStart hooks for communication style
 * - Agent format: YAML frontmatter with name, description, tools, model
 * - Model field: "sonnet" or "opus" (not full model names)
 *
 * ## Universal (Any AI Client)
 * - Configuration in .taskorchestrator/config.yaml - status progression, quality gates
 * - Agent mapping in .taskorchestrator/agent-mapping.yaml - tag-based routing
 * - Decision gates injected into CLAUDE.md
 *
 * ## Responsibilities
 * - Create and manage .claude/ directory structure (agents, skills, plugins)
 * - Create and manage .taskorchestrator/ directory with config files
 * - Copy Claude-specific agent, skill, and plugin templates from embedded resources
 * - Copy universal config files from embedded resources
 * - Read/write orchestration files
 * - Handle Docker volume mounts
 * - Portable across Windows/Linux/macOS
 *
 * ## Configuration
 * The project root directory can be configured via environment variable:
 * - `AGENT_CONFIG_DIR`: Directory containing .claude/
 * - Defaults to current working directory (user.dir)
 * - In Docker, mount project directory: -v /host/project:/project -e AGENT_CONFIG_DIR=/project
 */
class OrchestrationSetupManager(
    private val projectRoot: Path = Paths.get(
        System.getenv("AGENT_CONFIG_DIR") ?: System.getProperty("user.dir")
    )
) {
    private val logger = LoggerFactory.getLogger(OrchestrationSetupManager::class.java)

    companion object {
        // Directory structure
        const val CLAUDE_DIR = ".claude"
        const val AGENTS_DIR = "agents"
        const val SKILLS_DIR = "skills"
        const val TASK_ORCHESTRATOR_SUBDIR = "task-orchestrator"
        const val TASKORCHESTRATOR_DIR = ".taskorchestrator"
        const val ORCHESTRATION_DIR = "orchestration"

        // Resource paths
        const val RESOURCE_PATH_PREFIX = "/claude/agents"
        const val SKILLS_RESOURCE_PATH = "/claude/skills"
        const val ORCHESTRATION_RESOURCE_PATH = "/orchestration"

        // Configuration files
        const val AGENT_MAPPING_FILE = "agent-mapping.yaml"
        const val CONFIG_FILE = "config.yaml"
        const val WORKFLOW_CONFIG_FILE = "status-workflow-config.yaml"
        const val CLAUDE_MD_FILE = "CLAUDE.md"
        const val DECISION_GATES_MARKER = "## Decision Gates (Claude Code)"

        // Plugin paths
        const val PLUGINS_DIR = "plugins"
        const val PLUGIN_RESOURCE_PATH = "/claude-plugin/task-orchestrator"

        // Plugin files structure
        val PLUGIN_FILES = listOf(
            ".claude-plugin/plugin.json",
            "hooks/hooks.json",
            "hooks-handlers/session-start.sh",
            "README.md"
        )

        // Configuration version
        const val CURRENT_CONFIG_VERSION = "2.0.0"

        // Orchestration files to copy (v2.0 progressive disclosure architecture)
        val ORCHESTRATION_FILES = listOf(
            "decision-trees.md",
            "workflows.md",
            "examples.md",
            "optimizations.md",
            "error-handling.md",
            "activation-prompt.md",
            "README.md"
        )

        // Default Claude Code agent template files (Claude Code specific)
        // v2.0 Architecture: Implementation Specialist (Haiku) + Skills, Senior Engineer (Sonnet),
        // Feature Architect (Opus), Planning Specialist (Sonnet)
        val DEFAULT_AGENT_FILES = listOf(
            "implementation-specialist.md",  // v2.0: Haiku for standard work with Skills
            "senior-engineer.md",            // v2.0: Sonnet for complex problems/bugs
            "feature-architect.md",          // v2.0: Opus for feature design
            "planning-specialist.md"         // v2.0: Sonnet for task breakdown
        )

        // Skill directories to copy (Claude Code specific)
        val SKILL_DIRECTORIES = listOf(
            "dependency-analysis",
            "dependency-orchestration",
            "feature-orchestration",
            "task-orchestrator-hooks-builder",
            "status-progression",
            "task-orchestration"
        )

        init {
            val configDir = System.getenv("AGENT_CONFIG_DIR")
            if (configDir != null) {
                LoggerFactory.getLogger(OrchestrationSetupManager::class.java)
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
     * Get the agents directory path (.claude/agents/task-orchestrator/)
     */
    fun getAgentsDir(): Path {
        return getClaudeDir().resolve(AGENTS_DIR).resolve(TASK_ORCHESTRATOR_SUBDIR)
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
     * Create the .claude/agents/task-orchestrator/ directory structure
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
        val resourcePath = "/claude/configuration/$AGENT_MAPPING_FILE"
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
     * Copy the config.yaml file from embedded resources to .taskorchestrator/
     * This file enables v2.0 config-driven status validation.
     * Skips if file already exists (idempotent).
     *
     * Returns true if the file was copied, false if it already existed.
     */
    fun copyConfigFile(): Boolean {
        val taskOrchestratorDir = getTaskOrchestratorDir()
        val targetFile = taskOrchestratorDir.resolve(CONFIG_FILE)

        // Skip if file already exists (idempotent)
        if (Files.exists(targetFile)) {
            logger.debug("Config file already exists, skipping: $CONFIG_FILE")
            return false
        }

        // Ensure directory exists
        if (!Files.exists(taskOrchestratorDir)) {
            throw IllegalStateException(".taskorchestrator directory does not exist. Call createTaskOrchestratorDirectory() first.")
        }

        // Read from embedded resources
        val resourcePath = "/claude/configuration/default-config.yaml"
        val resourceStream = javaClass.getResourceAsStream(resourcePath)
            ?: throw IllegalStateException("Could not find embedded resource: $resourcePath")

        // Copy to target location
        resourceStream.use { input ->
            Files.copy(input, targetFile, StandardCopyOption.REPLACE_EXISTING)
        }

        logger.info("Copied config file: $CONFIG_FILE (v2.0 mode enabled)")
        return true
    }

    /**
     * Copy the status_workflow_config.yaml file from embedded resources to .taskorchestrator/
     * This file defines status progressions, workflow flows, and event handlers.
     * Skips if file already exists (idempotent).
     *
     * Returns true if the file was copied, false if it already existed.
     */
    fun copyWorkflowConfigFile(): Boolean {
        val taskOrchestratorDir = getTaskOrchestratorDir()
        val targetFile = taskOrchestratorDir.resolve(WORKFLOW_CONFIG_FILE)

        // Skip if file already exists (idempotent)
        if (Files.exists(targetFile)) {
            logger.debug("Workflow config file already exists, skipping: $WORKFLOW_CONFIG_FILE")
            return false
        }

        // Ensure directory exists
        if (!Files.exists(taskOrchestratorDir)) {
            throw IllegalStateException(".taskorchestrator directory does not exist. Call createTaskOrchestratorDirectory() first.")
        }

        // Read from embedded resources
        val resourcePath = "/orchestration/default-$WORKFLOW_CONFIG_FILE"
        val resourceStream = javaClass.getResourceAsStream(resourcePath)
            ?: throw IllegalStateException("Could not find embedded resource: $resourcePath")

        // Copy to target location
        resourceStream.use { input ->
            Files.copy(input, targetFile, StandardCopyOption.REPLACE_EXISTING)
        }

        logger.info("Copied workflow config file: $WORKFLOW_CONFIG_FILE")
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

        // Build decision gates content (optimized format)
        val decisionGatesContent = """
## Decision Gates (Claude Code)

**Quick routing for Skills vs Subagents:**

### When User Asks About Progress/Status/Coordination
→ **Use Skills** (60-82% token savings):
- "What's next?" → Feature Management Skill
- "Complete feature/task" → Feature/Task Management Skill
- "What's blocking?" → Dependency Analysis Skill

### When User Requests Implementation Work
→ **Use Subagents** (complex reasoning + code):
- "Create feature for X" / rich context (3+ paragraphs) → Feature Architect
- "Implement X" / task with code → Use `recommend_agent(taskId)` for specialist routing
- "Fix bug X" / "broken"/"error" → Bug Triage Specialist
- "Break down X" → Planning Specialist

### Critical Patterns
- **Always** run `list_templates` before creating tasks/features
- Feature Architect creates feature → Planning Specialist breaks into tasks → Specialists implement
- Use `recommend_agent(taskId)` for automatic specialist routing based on task tags

**Complete guide**: See [hybrid-architecture.md](docs/hybrid-architecture.md) for detailed decision matrices and examples.

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

    /**
     * Get the skills directory path (.claude/skills/)
     */
    fun getSkillsDir(): Path {
        return getClaudeDir().resolve(SKILLS_DIR)
    }

    /**
     * Get the hooks directory path (.claude/hooks/task-orchestrator/)
     * Note: Hooks are no longer automatically created, but this path is used for discovery of user-created hooks
     */
    fun getHooksDir(): Path {
        return getClaudeDir().resolve("hooks").resolve(TASK_ORCHESTRATOR_SUBDIR)
    }

    /**
     * Check if the skills directory exists
     */
    fun skillsDirExists(): Boolean {
        return Files.exists(getSkillsDir())
    }

    /**
     * Create the .claude/skills/ directory structure
     * Returns true if created, false if already exists
     */
    fun createSkillsDirectory(): Boolean {
        val skillsDir = getSkillsDir()

        if (!Files.exists(skillsDir)) {
            Files.createDirectories(skillsDir)
            logger.info("Created directory: $skillsDir")
            return true
        }

        return false
    }

    /**
     * Copy skill templates from embedded resources to .claude/skills/
     * Skips files that already exist (idempotent).
     *
     * Returns list of skills that were copied (skill directory names).
     */
    fun copySkillTemplates(): List<String> {
        val skillsDir = getSkillsDir()
        val copiedSkills = mutableListOf<String>()

        if (!Files.exists(skillsDir)) {
            throw IllegalStateException("Skills directory does not exist. Call createSkillsDirectory() first.")
        }

        for (skillName in SKILL_DIRECTORIES) {
            val targetSkillDir = skillsDir.resolve(skillName)

            // Create skill directory if it doesn't exist
            if (!Files.exists(targetSkillDir)) {
                Files.createDirectories(targetSkillDir)
                logger.debug("Created skill directory: $skillName")
            }

            // Copy all files from the skill resource directory
            val resourcePath = "$SKILLS_RESOURCE_PATH/$skillName"
            val filesCopied = copyResourceDirectory(resourcePath, targetSkillDir, skillName)

            if (filesCopied > 0) {
                copiedSkills.add(skillName)
            }
        }

        return copiedSkills
    }

    /**
     * Get the plugins directory path (.claude/plugins/task-orchestrator/)
     */
    fun getPluginDir(): Path {
        return getClaudeDir().resolve(PLUGINS_DIR).resolve(TASK_ORCHESTRATOR_SUBDIR)
    }

    /**
     * Check if the plugin directory exists
     */
    fun pluginDirExists(): Boolean {
        return Files.exists(getPluginDir())
    }

    /**
     * Create the .claude/plugins/task-orchestrator/ directory structure
     * Returns true if created, false if already exists
     */
    fun createPluginDirectory(): Boolean {
        val pluginDir = getPluginDir()

        if (!Files.exists(pluginDir)) {
            Files.createDirectories(pluginDir)
            logger.info("Created directory: $pluginDir")
            return true
        }

        return false
    }

    /**
     * Copy plugin files from embedded resources to .claude/plugins/task-orchestrator/
     * Preserves directory structure. Skips files that already exist (idempotent).
     *
     * Returns list of files that were copied.
     */
    fun copyPluginFiles(): List<String> {
        val pluginDir = getPluginDir()
        val copiedFiles = mutableListOf<String>()

        if (!Files.exists(pluginDir)) {
            throw IllegalStateException("Plugin directory does not exist. Call createPluginDirectory() first.")
        }

        // Copy entire plugin directory structure
        val filesCopied = copyResourceDirectory(PLUGIN_RESOURCE_PATH, pluginDir, "Plugin")

        if (filesCopied > 0) {
            logger.info("Copied $filesCopied plugin file(s)")
            copiedFiles.addAll(PLUGIN_FILES.filter {
                Files.exists(pluginDir.resolve(it))
            })
        }

        return copiedFiles
    }

    /**
     * Helper function to recursively copy a directory from resources to a target path.
     * Preserves directory structure and skips existing files.
     *
     * Returns the number of files copied.
     */
    private fun copyResourceDirectory(resourcePath: String, targetDir: Path, logPrefix: String): Int {
        var filesCopied = 0

        // Get the resource URL to check if it's a JAR or file system resource
        val resourceUrl = javaClass.getResource(resourcePath)
            ?: throw IllegalStateException("Could not find embedded resource: $resourcePath")

        when {
            resourceUrl.protocol == "jar" -> {
                // Running from JAR - use different approach
                filesCopied = copyFromJar(resourcePath, targetDir, logPrefix)
            }
            else -> {
                // Running from file system (development)
                filesCopied = copyFromFileSystem(resourcePath, targetDir, logPrefix)
            }
        }

        return filesCopied
    }

    /**
     * Copy resources from JAR file
     */
    private fun copyFromJar(resourcePath: String, targetDir: Path, logPrefix: String): Int {
        var filesCopied = 0
        val normalizedPath = resourcePath.removePrefix("/")

        // Get JAR file system
        val jarUri = javaClass.protectionDomain.codeSource.location.toURI()
        val jarPath = Paths.get(jarUri)

        if (Files.exists(jarPath) && jarPath.toString().endsWith(".jar")) {
            java.util.jar.JarFile(jarPath.toFile()).use { jar ->
                val entries = jar.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val entryName = entry.name

                    // Check if entry is within our resource path
                    if (entryName.startsWith(normalizedPath) && !entry.isDirectory) {
                        val relativePath = entryName.removePrefix(normalizedPath).removePrefix("/")
                        if (relativePath.isEmpty()) continue

                        val targetFile = targetDir.resolve(relativePath)

                        // Skip if file already exists
                        if (Files.exists(targetFile)) {
                            logger.debug("$logPrefix file already exists, skipping: $relativePath")
                            continue
                        }

                        // Create parent directories if needed
                        val parentDir = targetFile.parent
                        if (parentDir != null && !Files.exists(parentDir)) {
                            Files.createDirectories(parentDir)
                        }

                        // Copy file from JAR
                        jar.getInputStream(entry).use { input ->
                            Files.copy(input, targetFile, StandardCopyOption.REPLACE_EXISTING)
                        }

                        logger.info("Copied $logPrefix file: $relativePath")
                        filesCopied++
                    }
                }
            }
        } else {
            // Fallback to stream-based approach
            filesCopied = copyFromFileSystem(resourcePath, targetDir, logPrefix)
        }

        return filesCopied
    }

    /**
     * Copy resources from file system (development mode)
     */
    private fun copyFromFileSystem(resourcePath: String, targetDir: Path, logPrefix: String): Int {
        var filesCopied = 0
        val resourceUrl = javaClass.getResource(resourcePath) ?: return 0

        try {
            val sourcePath = Paths.get(resourceUrl.toURI())

            Files.walk(sourcePath).use { paths ->
                paths.forEach { sourcePath ->
                    if (Files.isRegularFile(sourcePath)) {
                        val relativePath = Paths.get(resourceUrl.toURI()).relativize(sourcePath)
                        val targetFile = targetDir.resolve(relativePath.toString())

                        // Skip if file already exists
                        if (Files.exists(targetFile)) {
                            logger.debug("$logPrefix file already exists, skipping: $relativePath")
                            return@forEach
                        }

                        // Create parent directories if needed
                        val parentDir = targetFile.parent
                        if (parentDir != null && !Files.exists(parentDir)) {
                            Files.createDirectories(parentDir)
                        }

                        // Copy file
                        Files.copy(sourcePath, targetFile, StandardCopyOption.REPLACE_EXISTING)
                        logger.info("Copied $logPrefix file: $relativePath")
                        filesCopied++
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("Could not copy from file system for $resourcePath, attempting alternative approach", e)
            // Try alternative approach for resources that might not be accessible via URI
        }

        return filesCopied
    }

    // ============================================================================
    // VERSION DETECTION AND UPGRADE SUPPORT
    // ============================================================================

    /**
     * Extract version from YAML frontmatter in a config file.
     *
     * Expected format:
     * ```yaml
     * ---
     * name: config
     * description: ...
     * version: "2.0.0"
     * ---
     * ```
     *
     * Returns version string or null if frontmatter not found or invalid.
     */
    fun extractVersionFromFile(filePath: Path): String? {
        if (!Files.exists(filePath)) {
            return null
        }

        return try {
            val content = Files.readString(filePath)
            extractVersionFromYamlFrontmatter(content)
        } catch (e: Exception) {
            logger.warn("Could not read version from file: $filePath", e)
            null
        }
    }

    /**
     * Extract version from YAML frontmatter in content string.
     *
     * @param content YAML content with frontmatter
     * @return version string or null if not found
     */
    private fun extractVersionFromYamlFrontmatter(content: String): String? {
        // Try YAML frontmatter first (between --- delimiters)
        if (content.trim().startsWith("---")) {
            val lines = content.lines()
            val frontmatterEnd = lines.drop(1).indexOfFirst { it.trim() == "---" }

            if (frontmatterEnd != -1) {
                // Parse frontmatter lines looking for version field
                val frontmatterLines = lines.subList(1, frontmatterEnd + 1)
                for (line in frontmatterLines) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("version:")) {
                        // Extract version value (remove quotes if present)
                        val versionValue = trimmed.substringAfter("version:").trim()
                        return versionValue.removeSurrounding("\"").removeSurrounding("'")
                    }
                }
            }
        }

        // Fallback: Try comment-based version (# Version: X.Y.Z)
        val versionCommentRegex = Regex("""#\s*Version:\s*([0-9]+\.[0-9]+\.[0-9]+)""", RegexOption.IGNORE_CASE)
        val match = versionCommentRegex.find(content)
        if (match != null) {
            return match.groupValues[1]
        }

        return null
    }

    /**
     * Check if a config file needs upgrading.
     *
     * @param filePath Path to config file
     * @return Triple of (needsUpgrade, currentVersion, latestVersion)
     */
    fun checkConfigNeedsUpgrade(filePath: Path): Triple<Boolean, String?, String> {
        val currentVersion = extractVersionFromFile(filePath)
        val latestVersion = CURRENT_CONFIG_VERSION

        if (currentVersion == null) {
            // No version found - likely old format or missing file
            return Triple(true, null, latestVersion)
        }

        val needsUpgrade = compareVersions(currentVersion, latestVersion) < 0
        return Triple(needsUpgrade, currentVersion, latestVersion)
    }

    /**
     * Compare two semantic version strings.
     *
     * @return negative if v1 < v2, zero if equal, positive if v1 > v2
     */
    private fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }

        val maxLength = maxOf(parts1.size, parts2.size)

        for (i in 0 until maxLength) {
            val p1 = parts1.getOrNull(i) ?: 0
            val p2 = parts2.getOrNull(i) ?: 0

            if (p1 != p2) {
                return p1.compareTo(p2)
            }
        }

        return 0
    }

    /**
     * Get version status for all config files in .taskorchestrator directory.
     *
     * @return Map of filename to version info (needsUpgrade, currentVersion, latestVersion)
     */
    fun getConfigVersionStatus(): Map<String, Triple<Boolean, String?, String>> {
        val taskOrchestratorDir = getTaskOrchestratorDir()

        return mapOf(
            CONFIG_FILE to checkConfigNeedsUpgrade(taskOrchestratorDir.resolve(CONFIG_FILE)),
            WORKFLOW_CONFIG_FILE to checkConfigNeedsUpgrade(taskOrchestratorDir.resolve(WORKFLOW_CONFIG_FILE)),
            AGENT_MAPPING_FILE to checkConfigNeedsUpgrade(taskOrchestratorDir.resolve(AGENT_MAPPING_FILE))
        )
    }

    // ============================================================================
    // ORCHESTRATION FILES SETUP (v2.0 Progressive Disclosure)
    // ============================================================================

    /**
     * Get the orchestration directory path (.taskorchestrator/orchestration/)
     */
    fun getOrchestrationDir(): Path {
        return getTaskOrchestratorDir().resolve(ORCHESTRATION_DIR)
    }

    /**
     * Check if the orchestration directory exists
     */
    fun orchestrationDirExists(): Boolean {
        return Files.exists(getOrchestrationDir())
    }

    /**
     * Create the .taskorchestrator/orchestration/ directory structure
     * Returns true if created, false if already exists
     */
    fun createOrchestrationDirectory(): Boolean {
        val orchestrationDir = getOrchestrationDir()

        if (!Files.exists(orchestrationDir)) {
            Files.createDirectories(orchestrationDir)
            logger.info("Created directory: $orchestrationDir")
            return true
        }

        return false
    }

    /**
     * Copy orchestration files from embedded resources to .taskorchestrator/orchestration/
     * Implements version checking - reports outdated files but does NOT auto-update them.
     *
     * Returns a map of orchestration file results:
     * - key: filename
     * - value: Triple of (status, currentVersion, latestVersion)
     *   - status: "created", "skipped", or "outdated"
     *
     * Idempotent: Safe to run multiple times
     */
    fun setupOrchestrationFiles(): Map<String, Pair<String, Pair<String?, String?>>> {
        val orchestrationDir = getOrchestrationDir()
        val results = mutableMapOf<String, Pair<String, Pair<String?, String?>>>()

        // Create directory if needed
        if (!Files.exists(orchestrationDir)) {
            Files.createDirectories(orchestrationDir)
            logger.info("Created orchestration directory: $orchestrationDir")
        }

        // Process each orchestration file
        for (filename in ORCHESTRATION_FILES) {
            val targetFile = orchestrationDir.resolve(filename)

            if (!Files.exists(targetFile)) {
                // File doesn't exist - copy it
                try {
                    val sourceContent = loadResourceFile("$ORCHESTRATION_RESOURCE_PATH/$filename")
                    targetFile.toFile().writeText(sourceContent)
                    logger.info("Copied orchestration file: $filename")
                    results[filename] = Pair("created", Pair(null, extractVersionFromYamlFrontmatter(sourceContent)))
                } catch (e: Exception) {
                    logger.warn("Could not copy orchestration file: $filename", e)
                    results[filename] = Pair("error", Pair(null, null))
                }
            } else {
                // File exists - check version
                val currentVersion = extractVersionFromFile(targetFile)
                val latestContent = try {
                    loadResourceFile("$ORCHESTRATION_RESOURCE_PATH/$filename")
                } catch (e: Exception) {
                    logger.warn("Could not load source version for $filename", e)
                    ""
                }
                val latestVersion = extractVersionFromYamlFrontmatter(latestContent)

                // Compare versions
                if (currentVersion == null || (latestVersion != null && compareVersions(currentVersion, latestVersion) < 0)) {
                    logger.info("Orchestration file outdated: $filename (v$currentVersion → v$latestVersion)")
                    results[filename] = Pair("outdated", Pair(currentVersion, latestVersion))
                } else {
                    logger.debug("Orchestration file up-to-date: $filename")
                    results[filename] = Pair("skipped", Pair(currentVersion, latestVersion))
                }
            }
        }

        return results
    }

    /**
     * Load a resource file as string
     * Throws IllegalStateException if resource not found
     */
    private fun loadResourceFile(resourcePath: String): String {
        val resourceStream = javaClass.getResourceAsStream(resourcePath)
            ?: throw IllegalStateException("Could not find embedded resource: $resourcePath")

        return resourceStream.bufferedReader().use { it.readText() }
    }
}
