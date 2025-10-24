package io.github.jpicklyk.mcptask.infrastructure.filesystem

import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * Manages the .taskorchestrator/agents/ directory structure and agent definition files.
 *
 * Responsibilities:
 * - Create and manage .taskorchestrator/agents/ directory
 * - Copy template agent files from embedded resources
 * - Read/write agent definition files
 * - Handle Docker volume mounts
 * - Portable across Windows/Linux/macOS
 *
 * ## Configuration
 * The project root directory can be configured via environment variable:
 * - `AGENT_CONFIG_DIR`: Directory containing .taskorchestrator/agents/
 * - Defaults to current working directory (user.dir)
 * - In Docker, mount project directory: -v /host/project:/project -e AGENT_CONFIG_DIR=/project
 */
class AgentDirectoryManager(
    private val projectRoot: Path = Paths.get(
        System.getenv("AGENT_CONFIG_DIR") ?: System.getProperty("user.dir")
    )
) {
    private val logger = LoggerFactory.getLogger(AgentDirectoryManager::class.java)

    companion object {
        const val TASKORCHESTRATOR_DIR = ".taskorchestrator"
        const val AGENTS_DIR = "agents"
        const val AGENT_MAPPING_FILE = "agent-mapping.yaml"
        const val RESOURCE_PATH_PREFIX = "/claude/configuration"

        // Default agent template files
        val DEFAULT_AGENT_FILES = listOf(
            "backend-engineer.md",
            "frontend-developer.md",
            "database-engineer.md",
            "test-engineer.md",
            "planning-specialist.md",
            "technical-writer.md"
        )

        init {
            val configDir = System.getenv("AGENT_CONFIG_DIR")
            if (configDir != null) {
                LoggerFactory.getLogger(AgentDirectoryManager::class.java)
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
     * Get the agents directory path
     */
    fun getAgentsDir(): Path {
        return getTaskOrchestratorDir().resolve(AGENTS_DIR)
    }

    /**
     * Get the agent-mapping.yaml file path
     */
    fun getAgentMappingFile(): Path {
        return getTaskOrchestratorDir().resolve(AGENT_MAPPING_FILE)
    }

    /**
     * Check if the .taskorchestrator directory exists
     */
    fun taskOrchestratorDirExists(): Boolean {
        return Files.exists(getTaskOrchestratorDir())
    }

    /**
     * Check if the agents directory exists
     */
    fun agentsDirExists(): Boolean {
        return Files.exists(getAgentsDir())
    }

    /**
     * Create the .taskorchestrator/agents/ directory structure
     * Returns true if created, false if already exists
     */
    fun createDirectoryStructure(): Boolean {
        val taskOrchestratorDir = getTaskOrchestratorDir()
        val agentsDir = getAgentsDir()

        var created = false

        if (!Files.exists(taskOrchestratorDir)) {
            Files.createDirectories(taskOrchestratorDir)
            logger.info("Created directory: $taskOrchestratorDir")
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
     * Copy default agent template files from embedded resources to the agents directory.
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

            logger.info("Copied agent template: $fileName")
            copiedFiles.add(fileName)
        }

        return copiedFiles
    }

    /**
     * Copy default agent-mapping.yaml file from embedded resources.
     * Skips if file already exists (idempotent).
     *
     * Returns true if copied, false if already exists.
     */
    fun copyDefaultAgentMapping(): Boolean {
        val targetFile = getAgentMappingFile()

        // Skip if file already exists (idempotent)
        if (Files.exists(targetFile)) {
            logger.debug("Agent mapping file already exists, skipping")
            return false
        }

        // Read from embedded resources
        val resourcePath = "$RESOURCE_PATH_PREFIX/$AGENT_MAPPING_FILE"
        val resourceStream = javaClass.getResourceAsStream(resourcePath)
            ?: throw IllegalStateException("Could not find embedded resource: $resourcePath")

        // Copy to target location
        resourceStream.use { input ->
            Files.copy(input, targetFile, StandardCopyOption.REPLACE_EXISTING)
        }

        logger.info("Copied agent mapping configuration")
        return true
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
     * Read the agent-mapping.yaml file
     * Returns file content as string, or null if file doesn't exist
     */
    fun readAgentMappingFile(): String? {
        val filePath = getAgentMappingFile()

        if (!Files.exists(filePath)) {
            logger.warn("Agent mapping file not found")
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
}
