package io.github.jpicklyk.mcptask.infrastructure.filesystem

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * Manages Task Orchestrator configuration files in the .taskorchestrator/ directory.
 *
 * ## Responsibilities
 * - Create and manage .taskorchestrator/ directory structure
 * - Copy universal config files from embedded resources
 * - Handle Docker volume mounts via AGENT_CONFIG_DIR
 * - Version management and upgrade detection
 * - Portable across Windows/Linux/macOS
 *
 * ## Configuration Files
 * - config.yaml - Core orchestrator configuration (status progression, validation rules)
 * - status-workflow-config.yaml - Workflow definitions and event handlers
 * - agent-mapping.yaml - Agent routing configuration (used with Claude Code if present)
 *
 * ## Configuration
 * The project root directory can be configured via environment variable:
 * - `AGENT_CONFIG_DIR`: Directory containing .taskorchestrator/
 * - Defaults to current working directory (user.dir)
 * - In Docker, mount project directory: -v /host/project:/project -e AGENT_CONFIG_DIR=/project
 */
class TaskOrchestratorConfigManager(
    private val projectRoot: Path = Paths.get(
        System.getenv("AGENT_CONFIG_DIR") ?: System.getProperty("user.dir")
    )
) {
    private val logger = LoggerFactory.getLogger(TaskOrchestratorConfigManager::class.java)

    companion object {
        // Directory structure
        const val TASKORCHESTRATOR_DIR = ".taskorchestrator"

        // Configuration files
        const val AGENT_MAPPING_FILE = "agent-mapping.yaml"
        const val CONFIG_FILE = "config.yaml"

        // Configuration version
        const val CURRENT_CONFIG_VERSION = "2.0.0"

        init {
            val configDir = System.getenv("AGENT_CONFIG_DIR")
            if (configDir != null) {
                LoggerFactory.getLogger(TaskOrchestratorConfigManager::class.java)
                    .info("Using AGENT_CONFIG_DIR: $configDir")
            }
        }
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
            AGENT_MAPPING_FILE to checkConfigNeedsUpgrade(taskOrchestratorDir.resolve(AGENT_MAPPING_FILE))
        )
    }
}
