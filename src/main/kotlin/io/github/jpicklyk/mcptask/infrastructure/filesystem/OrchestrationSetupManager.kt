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
 * ## Claude Code Specific (Subagents & Skills)
 * - Agent definitions in .claude/agents/task-orchestrator/ with YAML frontmatter and markdown content
 * - Skills in .claude/skills/ (root level, not in subdirectory) for lightweight coordination
 * - Agent format: YAML frontmatter with name, description, tools, model
 * - Model field: "sonnet" or "opus" (not full model names)
 *
 * ## Universal (Any AI Client)
 * - Output-style in .claude/output-styles/ - orchestrator behavior and decision-making patterns
 * - Configuration in .taskorchestrator/config.yaml - status progression, quality gates
 * - Agent mapping in .taskorchestrator/agent-mapping.yaml - tag-based routing
 * - Decision gates injected into CLAUDE.md
 *
 * ## Responsibilities
 * - Create and manage .claude/ directory structure (agents, skills, output-styles)
 * - Create and manage .taskorchestrator/ directory with config files
 * - Copy Claude-specific agent and skill templates from embedded resources
 * - Copy universal output-style and config files from embedded resources
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
        const val OUTPUT_STYLE_DIR = "output-styles"
        const val TASK_ORCHESTRATOR_SUBDIR = "task-orchestrator"
        const val TASKORCHESTRATOR_DIR = ".taskorchestrator"

        // Resource paths
        const val RESOURCE_PATH_PREFIX = "/claude/agents"
        const val SKILLS_RESOURCE_PATH = "/claude/skills"
        const val OUTPUT_STYLE_RESOURCE_PATH = "/claude/output-styles"

        // Configuration files
        const val AGENT_MAPPING_FILE = "agent-mapping.yaml"
        const val CONFIG_FILE = "config.yaml"
        const val WORKFLOW_CONFIG_FILE = "status-workflow-config.yaml"
        const val OUTPUT_STYLE_FILE = "task-orchestrator.md"
        const val CLAUDE_MD_FILE = "CLAUDE.md"
        const val DECISION_GATES_MARKER = "## Decision Gates (Claude Code)"

        // Configuration version
        const val CURRENT_CONFIG_VERSION = "2.0.0"

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
     * Get the output-style directory path (.claude/output-styles/)
     */
    fun getOutputStyleDir(): Path {
        return getClaudeDir().resolve(OUTPUT_STYLE_DIR)
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
     * Create the .claude/output-styles/ directory structure.
     * Returns true if created, false if already exists.
     */
    fun createOutputStyleDirectory(): Boolean {
        val outputStyleDir = getOutputStyleDir()

        if (!Files.exists(outputStyleDir)) {
            Files.createDirectories(outputStyleDir)
            logger.info("Created directory: $outputStyleDir")
            return true
        }

        return false
    }

    /**
     * Copy output-style file from embedded resources to .claude/output-styles/
     * Skips if file already exists (idempotent).
     *
     * Returns true if the file was copied, false if it already existed.
     */
    fun copyOutputStyleFile(): Boolean {
        val outputStyleDir = getOutputStyleDir()
        val targetFile = outputStyleDir.resolve(OUTPUT_STYLE_FILE)

        // Skip if file already exists (idempotent)
        if (Files.exists(targetFile)) {
            logger.debug("Output-style file already exists, skipping: $OUTPUT_STYLE_FILE")
            return false
        }

        // Ensure directory exists
        if (!Files.exists(outputStyleDir)) {
            throw IllegalStateException("Output-style directory does not exist. Call createOutputStyleDirectory() first.")
        }

        // Read from embedded resources
        val resourcePath = "$OUTPUT_STYLE_RESOURCE_PATH/$OUTPUT_STYLE_FILE"
        val resourceStream = javaClass.getResourceAsStream(resourcePath)
            ?: throw IllegalStateException("Could not find embedded resource: $resourcePath")

        // Copy to target location
        resourceStream.use { input ->
            Files.copy(input, targetFile, StandardCopyOption.REPLACE_EXISTING)
        }

        logger.info("Copied output-style file: $OUTPUT_STYLE_FILE")
        return true
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
        // Check if content starts with YAML frontmatter delimiter
        if (!content.trim().startsWith("---")) {
            return null
        }

        // Extract frontmatter section (between first and second ---)
        val lines = content.lines()
        val frontmatterEnd = lines.drop(1).indexOfFirst { it.trim() == "---" }

        if (frontmatterEnd == -1) {
            return null
        }

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
}
