package io.github.jpicklyk.mcptask.application.service

import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.workflow.CleanupConfig
import io.github.jpicklyk.mcptask.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.domain.repository.SectionRepository
import io.github.jpicklyk.mcptask.domain.repository.TaskRepository
import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

/**
 * Result of a cleanup operation on feature completion.
 */
data class CleanupResult(
    val performed: Boolean,
    val tasksDeleted: Int,
    val tasksRetained: Int,
    val retainedTaskIds: List<UUID>,
    val sectionsDeleted: Int,
    val dependenciesDeleted: Int,
    val reason: String
)

/**
 * Service for automatic task cleanup when a feature reaches terminal status.
 *
 * When a feature is completed/archived, its child tasks have served their purpose.
 * This service deletes those tasks (with their sections and dependencies) while
 * retaining tasks tagged with diagnostic markers (e.g., bug reports).
 *
 * Configuration is loaded from .taskorchestrator/config.yaml (same source as
 * StatusProgressionServiceImpl). Falls back to hardcoded defaults if no config exists.
 *
 * Terminal statuses are read from status_progression.features.terminal_statuses in config,
 * ensuring custom workflow flows are respected automatically.
 */
class CompletionCleanupService(
    private val taskRepository: TaskRepository,
    private val sectionRepository: SectionRepository,
    private val dependencyRepository: DependencyRepository
) {
    private val logger = LoggerFactory.getLogger(CompletionCleanupService::class.java)

    /**
     * Cleans up child tasks of a feature that has reached terminal status.
     *
     * @param featureId The feature whose tasks should be cleaned up
     * @param featureStatus The new status of the feature (normalized, e.g., "completed")
     * @return CleanupResult with metrics about what was deleted/retained
     */
    suspend fun cleanupFeatureTasks(featureId: UUID, featureStatus: String): CleanupResult {
        val config = loadCleanupConfig()

        // Check if cleanup is enabled
        if (!config.enabled) {
            logger.debug("Completion cleanup is disabled via configuration")
            return CleanupResult(
                performed = false,
                tasksDeleted = 0,
                tasksRetained = 0,
                retainedTaskIds = emptyList(),
                sectionsDeleted = 0,
                dependenciesDeleted = 0,
                reason = "Cleanup disabled in configuration"
            )
        }

        // Check if the feature status is terminal
        val terminalStatuses = loadFeatureTerminalStatuses()
        val normalizedStatus = normalizeStatus(featureStatus)
        if (normalizedStatus !in terminalStatuses.map { normalizeStatus(it) }) {
            logger.debug("Feature status '$featureStatus' is not terminal, skipping cleanup")
            return CleanupResult(
                performed = false,
                tasksDeleted = 0,
                tasksRetained = 0,
                retainedTaskIds = emptyList(),
                sectionsDeleted = 0,
                dependenciesDeleted = 0,
                reason = "Status '$featureStatus' is not a terminal status"
            )
        }

        // Find all child tasks
        val tasks = taskRepository.findByFeatureId(featureId)
        if (tasks.isEmpty()) {
            logger.debug("Feature $featureId has no child tasks, nothing to clean up")
            return CleanupResult(
                performed = false,
                tasksDeleted = 0,
                tasksRetained = 0,
                retainedTaskIds = emptyList(),
                sectionsDeleted = 0,
                dependenciesDeleted = 0,
                reason = "No child tasks found"
            )
        }

        // Partition tasks into delete vs retain based on tags
        val retainTagsLower = config.retainTags.map { it.lowercase() }
        val (tasksToRetain, tasksToDelete) = tasks.partition { task ->
            task.tags.any { tag -> tag.lowercase() in retainTagsLower }
        }

        if (tasksToDelete.isEmpty()) {
            logger.debug("All ${tasks.size} tasks have retained tags, nothing to delete")
            return CleanupResult(
                performed = true,
                tasksDeleted = 0,
                tasksRetained = tasksToRetain.size,
                retainedTaskIds = tasksToRetain.map { it.id },
                sectionsDeleted = 0,
                dependenciesDeleted = 0,
                reason = "All tasks retained (tags: ${tasksToRetain.flatMap { it.tags }.distinct().joinToString(", ")})"
            )
        }

        // Delete tasks and their associated data
        var totalSectionsDeleted = 0
        var totalDependenciesDeleted = 0
        var totalTasksDeleted = 0

        for (task in tasksToDelete) {
            try {
                // Delete dependencies first
                val depsDeleted = dependencyRepository.deleteByTaskId(task.id)
                totalDependenciesDeleted += depsDeleted

                // Delete sections
                val sectionsResult = sectionRepository.getSectionsForEntity(EntityType.TASK, task.id)
                if (sectionsResult is Result.Success) {
                    for (section in sectionsResult.data) {
                        sectionRepository.deleteSection(section.id)
                    }
                    totalSectionsDeleted += sectionsResult.data.size
                }

                // Delete the task
                when (taskRepository.delete(task.id)) {
                    is Result.Success -> totalTasksDeleted++
                    is Result.Error -> logger.warn("Failed to delete task ${task.id} during cleanup")
                }
            } catch (e: Exception) {
                logger.error("Error cleaning up task ${task.id}: ${e.message}", e)
            }
        }

        val reason = buildString {
            append("Feature reached terminal status '$featureStatus'")
            if (tasksToRetain.isNotEmpty()) {
                val retainedTags = tasksToRetain.flatMap { it.tags }.distinct()
                    .filter { it.lowercase() in retainTagsLower }
                append("; ${tasksToRetain.size} task(s) retained (tags: ${retainedTags.joinToString(", ")})")
            }
        }

        logger.info("Cleanup completed for feature $featureId: $totalTasksDeleted tasks deleted, " +
                "${tasksToRetain.size} retained, $totalSectionsDeleted sections deleted, " +
                "$totalDependenciesDeleted dependencies deleted")

        return CleanupResult(
            performed = true,
            tasksDeleted = totalTasksDeleted,
            tasksRetained = tasksToRetain.size,
            retainedTaskIds = tasksToRetain.map { it.id },
            sectionsDeleted = totalSectionsDeleted,
            dependenciesDeleted = totalDependenciesDeleted,
            reason = reason
        )
    }

    /**
     * Loads cleanup configuration from .taskorchestrator/config.yaml.
     * Falls back to defaults if the file doesn't exist or the section is missing.
     */
    @Suppress("UNCHECKED_CAST")
    internal fun loadCleanupConfig(): CleanupConfig {
        val config = loadRawConfig() ?: return CleanupConfig()

        val cleanupSection = config["completion_cleanup"] as? Map<String, Any?> ?: return CleanupConfig()

        val enabled = cleanupSection["enabled"] as? Boolean ?: false
        val retainTags = (cleanupSection["retain_tags"] as? List<String>) ?: CleanupConfig().retainTags

        return CleanupConfig(enabled = enabled, retainTags = retainTags)
    }

    /**
     * Loads feature terminal statuses from config.
     * Falls back to ["completed", "archived"] if not configured.
     */
    @Suppress("UNCHECKED_CAST")
    internal fun loadFeatureTerminalStatuses(): List<String> {
        val config = loadRawConfig() ?: return DEFAULT_FEATURE_TERMINAL_STATUSES

        val statusProgression = config["status_progression"] as? Map<String, Any?> ?: return DEFAULT_FEATURE_TERMINAL_STATUSES
        val features = statusProgression["features"] as? Map<String, Any?> ?: return DEFAULT_FEATURE_TERMINAL_STATUSES
        val terminalStatuses = features["terminal_statuses"] as? List<String> ?: return DEFAULT_FEATURE_TERMINAL_STATUSES

        return terminalStatuses
    }

    /**
     * Loads the raw YAML config from .taskorchestrator/config.yaml.
     * Uses AGENT_CONFIG_DIR for Docker compatibility.
     */
    private fun loadRawConfig(): Map<String, Any?>? {
        val configPath = getConfigPath()
        val inputStream = if (Files.exists(configPath)) {
            Files.newInputStream(configPath)
        } else {
            // Fall back to bundled default config
            this::class.java.classLoader.getResourceAsStream("configuration/default-config.yaml")
                ?: return null
        }

        return try {
            inputStream.use { stream ->
                Yaml().load<Map<String, Any?>>(stream)
            }
        } catch (e: Exception) {
            logger.error("Failed to load config from $configPath", e)
            null
        }
    }

    private fun getConfigPath(): Path {
        val projectRoot = Paths.get(
            System.getenv("AGENT_CONFIG_DIR") ?: System.getProperty("user.dir")
        )
        return projectRoot.resolve(".taskorchestrator/config.yaml")
    }

    private fun normalizeStatus(status: String): String {
        return status.lowercase().replace('_', '-')
    }

    companion object {
        private val DEFAULT_FEATURE_TERMINAL_STATUSES = listOf("completed", "archived")
    }
}
