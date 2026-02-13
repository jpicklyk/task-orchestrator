package io.github.jpicklyk.mcptask.application.service.cascade

import io.github.jpicklyk.mcptask.application.service.CompletionCleanupService
import io.github.jpicklyk.mcptask.application.service.CleanupResult
import io.github.jpicklyk.mcptask.application.service.StatusValidator
import io.github.jpicklyk.mcptask.application.service.progression.StatusProgressionService
import io.github.jpicklyk.mcptask.application.service.progression.NextStatusRecommendation
import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.model.workflow.*
import io.github.jpicklyk.mcptask.domain.repository.*
import org.slf4j.LoggerFactory
import java.util.UUID
import io.github.jpicklyk.mcptask.domain.model.workflow.AppliedCascade as DomainAppliedCascade
import io.github.jpicklyk.mcptask.domain.model.workflow.UnblockedTask as DomainUnblockedTask
import io.github.jpicklyk.mcptask.domain.model.workflow.CascadeCleanupResult as DomainCascadeCleanupResult

/**
 * Implementation of CascadeService providing unified cascade detection and application.
 *
 * This service consolidates cascade logic previously split across:
 * - WorkflowServiceImpl (detection using WorkflowConfigLoader)
 * - RequestTransitionTool (application with auto-cascade)
 * - ManageContainerTool (detection-only)
 *
 * Key differences from WorkflowServiceImpl:
 * - Uses StatusProgressionService instead of WorkflowConfigLoader for flow/status resolution
 * - Provides both detection AND application (WorkflowServiceImpl was detection-only)
 * - Supports auto-cascade configuration from .taskorchestrator/config.yaml
 */
class CascadeServiceImpl(
    private val statusProgressionService: StatusProgressionService,
    private val statusValidator: StatusValidator,
    private val taskRepository: TaskRepository,
    private val featureRepository: FeatureRepository,
    private val projectRepository: ProjectRepository,
    private val dependencyRepository: DependencyRepository,
    private val sectionRepository: SectionRepository
) : CascadeService {

    private val logger = LoggerFactory.getLogger(CascadeServiceImpl::class.java)

    override suspend fun detectCascadeEvents(
        containerId: UUID,
        containerType: ContainerType
    ): List<CascadeEvent> {
        return when (containerType) {
            ContainerType.TASK -> detectTaskCascades(containerId)
            ContainerType.FEATURE -> detectFeatureCascades(containerId)
            ContainerType.PROJECT -> detectProjectCascades(containerId)
        }
    }

    /**
     * Detects cascade events for task status changes.
     *
     * Events detected:
     * - first_task_started: First task moves to in-progress AND feature at first status in flow
     * - all_tasks_complete: All tasks completed/cancelled
     *
     * Uses StatusProgressionService to determine next status instead of hardcoded values.
     */
    private suspend fun detectTaskCascades(taskId: UUID): List<CascadeEvent> {
        val task = taskRepository.getById(taskId).getOrNull() ?: return emptyList()
        val featureId = task.featureId ?: return emptyList()
        val feature = featureRepository.getById(featureId).getOrNull() ?: return emptyList()

        val events = mutableListOf<CascadeEvent>()

        // Normalize statuses for comparison
        val taskStatusNormalized = normalizeStatus(task.status.name)
        val featureStatusNormalized = normalizeStatus(feature.status.name)

        // Get flow path for the feature
        val flowPath = statusProgressionService.getFlowPath("feature", feature.tags, featureStatusNormalized)

        // Event: first_task_started
        // Trigger: First task moves to in-progress AND feature is at first status in flow
        if (taskStatusNormalized == "in-progress") {
            val inProgressCount = countTasksByStatus(featureId, "in-progress")

            // Check if this is the first in-progress task AND feature is at the first flow status
            val firstFeatureStatus = flowPath.flowSequence.firstOrNull() ?: "planning"
            if (inProgressCount == 1 && featureStatusNormalized == firstFeatureStatus) {
                val recommendation = statusProgressionService.getNextStatus(
                    currentStatus = featureStatusNormalized,
                    containerType = "feature",
                    tags = feature.tags,
                    containerId = feature.id
                )

                if (recommendation is NextStatusRecommendation.Ready &&
                    recommendation.recommendedStatus != featureStatusNormalized) {
                    events.add(CascadeEvent(
                        event = "first_task_started",
                        targetType = ContainerType.FEATURE,
                        targetId = feature.id,
                        targetName = feature.name,
                        currentStatus = featureStatusNormalized,
                        suggestedStatus = recommendation.recommendedStatus,
                        flow = recommendation.activeFlow,
                        automatic = true,
                        reason = "First task started in feature"
                    ))
                }
            }
        }

        // Event: all_tasks_complete
        // Trigger: Task completed AND all tasks in feature are done
        if (taskStatusNormalized in listOf("completed", "cancelled")) {
            val taskCounts = featureRepository.getTaskCountsByFeatureId(featureId)
            val allDone = (taskCounts.completed + taskCounts.cancelled) == taskCounts.total

            if (allDone && taskCounts.total > 0) {
                val recommendation = statusProgressionService.getNextStatus(
                    currentStatus = featureStatusNormalized,
                    containerType = "feature",
                    tags = feature.tags,
                    containerId = feature.id
                )

                if (recommendation is NextStatusRecommendation.Ready &&
                    recommendation.recommendedStatus != featureStatusNormalized) {
                    events.add(CascadeEvent(
                        event = "all_tasks_complete",
                        targetType = ContainerType.FEATURE,
                        targetId = feature.id,
                        targetName = feature.name,
                        currentStatus = featureStatusNormalized,
                        suggestedStatus = recommendation.recommendedStatus,
                        flow = recommendation.activeFlow,
                        automatic = true,
                        reason = "All ${taskCounts.total} tasks completed/cancelled"
                    ))
                }
            }
        }

        return events
    }

    /**
     * Detects cascade events for feature status changes.
     *
     * Events detected:
     * - all_features_complete: Feature reaches terminal status AND all features in project terminal
     */
    private suspend fun detectFeatureCascades(featureId: UUID): List<CascadeEvent> {
        val feature = featureRepository.getById(featureId).getOrNull() ?: return emptyList()
        val projectId = feature.projectId ?: return emptyList()
        val project = projectRepository.getById(projectId).getOrNull() ?: return emptyList()

        val events = mutableListOf<CascadeEvent>()

        // Normalize statuses
        val featureStatusNormalized = normalizeStatus(feature.status.name)
        val projectStatusNormalized = normalizeStatus(project.status.name)

        // Check if feature is at terminal status
        val flowPath = statusProgressionService.getFlowPath("feature", feature.tags, featureStatusNormalized)
        val isFeatureTerminal = flowPath.terminalStatuses.contains(featureStatusNormalized)

        // Event: all_features_complete
        // Trigger: Feature reaches terminal status AND all features in project are terminal
        if (isFeatureTerminal) {
            val featureCounts = projectRepository.getFeatureCountsByProjectId(projectId)
            val allDone = featureCounts.completed == featureCounts.total

            if (allDone && featureCounts.total > 0 && projectStatusNormalized != "completed") {
                val recommendation = statusProgressionService.getNextStatus(
                    currentStatus = projectStatusNormalized,
                    containerType = "project",
                    tags = project.tags,
                    containerId = project.id
                )

                if (recommendation is NextStatusRecommendation.Ready) {
                    events.add(CascadeEvent(
                        event = "all_features_complete",
                        targetType = ContainerType.PROJECT,
                        targetId = project.id,
                        targetName = project.name,
                        currentStatus = projectStatusNormalized,
                        suggestedStatus = recommendation.recommendedStatus,
                        flow = recommendation.activeFlow,
                        automatic = true,
                        reason = "All ${featureCounts.total} features completed"
                    ))
                }
            }
        }

        return events
    }

    /**
     * Detects cascade events for project status changes.
     * Projects are top-level, so no cascades upward.
     */
    private fun detectProjectCascades(projectId: UUID): List<CascadeEvent> {
        // Projects are top-level, no cascades upward
        return emptyList()
    }

    override suspend fun applyCascades(
        containerId: UUID,
        containerType: String,
        depth: Int,
        maxDepth: Int
    ): List<DomainAppliedCascade> {
        if (depth >= maxDepth) {
            logger.warn("Auto-cascade depth limit ($maxDepth) reached for $containerType $containerId")
            return emptyList()
        }

        val events = try {
            val ct = ContainerType.valueOf(containerType.uppercase())
            detectCascadeEvents(containerId, ct)
        } catch (e: Exception) {
            logger.warn("Failed to detect cascade events: ${e.message}")
            return emptyList()
        }

        if (events.isEmpty()) return emptyList()

        val results = mutableListOf<DomainAppliedCascade>()

        for (event in events) {
            try {
                // Re-fetch current state (may have changed from earlier cascades)
                val entityDetails = fetchEntityDetails(event.targetId, event.targetType.name.lowercase())
                if (entityDetails == null) {
                    results.add(DomainAppliedCascade(
                        event = event.event,
                        targetType = event.targetType.name.lowercase(),
                        targetId = event.targetId,
                        targetName = event.targetName,
                        previousStatus = event.currentStatus,
                        newStatus = event.suggestedStatus,
                        applied = false,
                        reason = event.reason,
                        error = "Target entity not found"
                    ))
                    continue
                }

                val (currentStatus, tags) = entityDetails

                // Skip if already at suggested status (another cascade may have handled this)
                if (currentStatus == normalizeStatus(event.suggestedStatus)) {
                    continue
                }

                // Validate the cascade transition
                val prerequisiteContext = StatusValidator.PrerequisiteContext(
                    taskRepository = taskRepository,
                    featureRepository = featureRepository,
                    projectRepository = projectRepository,
                    dependencyRepository = dependencyRepository
                )
                val validationResult = statusValidator.validateTransition(
                    currentStatus = currentStatus,
                    newStatus = event.suggestedStatus,
                    containerType = event.targetType.name.lowercase(),
                    containerId = event.targetId,
                    context = prerequisiteContext,
                    tags = tags
                )

                if (validationResult is StatusValidator.ValidationResult.Invalid) {
                    results.add(DomainAppliedCascade(
                        event = event.event,
                        targetType = event.targetType.name.lowercase(),
                        targetId = event.targetId,
                        targetName = event.targetName,
                        previousStatus = currentStatus,
                        newStatus = event.suggestedStatus,
                        applied = false,
                        reason = event.reason,
                        error = "Transition blocked: ${validationResult.reason}"
                    ))
                    continue
                }

                // Apply the cascade transition
                val applyError = applyStatusChange(event.targetId, event.targetType.name.lowercase(), event.suggestedStatus)
                if (applyError != null) {
                    results.add(DomainAppliedCascade(
                        event = event.event,
                        targetType = event.targetType.name.lowercase(),
                        targetId = event.targetId,
                        targetName = event.targetName,
                        previousStatus = currentStatus,
                        newStatus = event.suggestedStatus,
                        applied = false,
                        reason = event.reason,
                        error = applyError
                    ))
                    continue
                }

                // Find newly unblocked tasks if this was a task-type cascade target
                val unblockedTasks = if (event.targetType.name.lowercase() == "task" &&
                    normalizeStatus(event.suggestedStatus) in listOf("completed", "cancelled")) {
                    findNewlyUnblockedTasks(event.targetId)
                } else emptyList()

                // Run completion cleanup if feature reached terminal status
                val cleanup = if (event.targetType == ContainerType.FEATURE) {
                    runCompletionCleanup(event.targetId, event.suggestedStatus)
                } else null

                // Recurse to detect further cascades
                val childCascades = applyCascades(
                    event.targetId,
                    event.targetType.name.lowercase(),
                    depth + 1,
                    maxDepth
                )

                results.add(DomainAppliedCascade(
                    event = event.event,
                    targetType = event.targetType.name.lowercase(),
                    targetId = event.targetId,
                    targetName = event.targetName,
                    previousStatus = currentStatus,
                    newStatus = event.suggestedStatus,
                    applied = true,
                    reason = event.reason,
                    cleanup = cleanup,
                    unblockedTasks = unblockedTasks,
                    childCascades = childCascades
                ))
            } catch (e: Exception) {
                logger.error("Error applying cascade for ${event.event} on ${event.targetType} ${event.targetId}: ${e.message}")
                results.add(DomainAppliedCascade(
                    event = event.event,
                    targetType = event.targetType.name.lowercase(),
                    targetId = event.targetId,
                    targetName = event.targetName,
                    previousStatus = event.currentStatus,
                    newStatus = event.suggestedStatus,
                    applied = false,
                    reason = event.reason,
                    error = "Internal error: ${e.message}"
                ))
            }
        }

        return results
    }

    override suspend fun findNewlyUnblockedTasks(completedTaskId: UUID): List<DomainUnblockedTask> {
        return try {
            // Get outgoing dependencies from the completed/cancelled task
            val outgoingDeps = dependencyRepository.findByFromTaskId(completedTaskId)
            val blocksDeps = outgoingDeps.filter { it.type == DependencyType.BLOCKS }

            if (blocksDeps.isEmpty()) return emptyList()

            val unblockedTasks = mutableListOf<DomainUnblockedTask>()

            for (dep in blocksDeps) {
                val downstreamTaskId = dep.toTaskId

                // Check if the downstream task itself is still active (not completed/cancelled)
                val downstreamTask = taskRepository.getById(downstreamTaskId).getOrNull()
                    ?: continue
                val downstreamStatus = normalizeStatus(downstreamTask.status.name)
                if (downstreamStatus in listOf("completed", "cancelled")) continue

                // Get ALL incoming blockers for the downstream task
                val incomingDeps = dependencyRepository.findByToTaskId(downstreamTaskId)
                val incomingBlockers = incomingDeps.filter { it.type == DependencyType.BLOCKS }

                // Check if ALL blockers are now completed or cancelled
                val allBlockersResolved = incomingBlockers.all { blocker ->
                    val blockerTask = taskRepository.getById(blocker.fromTaskId).getOrNull()
                    if (blockerTask != null) {
                        val blockerStatus = normalizeStatus(blockerTask.status.name)
                        blockerStatus in listOf("completed", "cancelled")
                    } else {
                        // If blocker task doesn't exist, treat as resolved
                        true
                    }
                }

                if (allBlockersResolved) {
                    unblockedTasks.add(DomainUnblockedTask(
                        taskId = downstreamTaskId,
                        title = downstreamTask.title
                    ))
                }
            }

            unblockedTasks
        } catch (e: Exception) {
            logger.warn("Failed to find newly unblocked tasks: ${e.message}")
            emptyList()
        }
    }

    override fun loadAutoCascadeConfig(): AutoCascadeConfig {
        return try {
            val configPath = java.nio.file.Paths.get(
                System.getenv("AGENT_CONFIG_DIR") ?: System.getProperty("user.dir")
            ).resolve(".taskorchestrator/config.yaml")

            val inputStream = if (java.nio.file.Files.exists(configPath)) {
                java.nio.file.Files.newInputStream(configPath)
            } else {
                // Fall back to bundled default config
                this::class.java.classLoader.getResourceAsStream("configuration/default-config.yaml")
                    ?: return AutoCascadeConfig()
            }

            inputStream.use { stream ->
                @Suppress("UNCHECKED_CAST")
                val config = org.yaml.snakeyaml.Yaml().load<Map<String, Any?>>(stream) ?: return AutoCascadeConfig()
                val section = config["auto_cascade"] as? Map<String, Any?> ?: return AutoCascadeConfig()
                val enabled = section["enabled"] as? Boolean ?: true
                val maxDepth = (section["max_depth"] as? Number)?.toInt() ?: 3
                AutoCascadeConfig(enabled = enabled, maxDepth = maxDepth)
            }
        } catch (e: Exception) {
            logger.warn("Failed to load auto_cascade config: ${e.message}")
            AutoCascadeConfig()
        }
    }

    // Private helper methods

    /**
     * Applies the status change to the entity via the appropriate repository.
     * Returns an error message String if the update fails, or null on success.
     */
    private suspend fun applyStatusChange(
        containerId: UUID,
        containerType: String,
        targetStatus: String
    ): String? {
        return when (containerType) {
            "task" -> {
                val existing = taskRepository.getById(containerId).getOrNull()
                    ?: return "Task not found"
                val status = parseTaskStatus(targetStatus)
                    ?: return "Invalid task status: $targetStatus"
                val updated = existing.copy(status = status, modifiedAt = java.time.Instant.now())
                when (val result = taskRepository.update(updated)) {
                    is Result.Success -> null // success
                    is Result.Error -> "Failed to update task: ${result.error}"
                }
            }
            "feature" -> {
                val existing = featureRepository.getById(containerId).getOrNull()
                    ?: return "Feature not found"
                val status = parseFeatureStatus(targetStatus)
                    ?: return "Invalid feature status: $targetStatus"
                val updated = existing.update(status = status)
                when (val result = featureRepository.update(updated)) {
                    is Result.Success -> null
                    is Result.Error -> "Failed to update feature: ${result.error}"
                }
            }
            "project" -> {
                val existing = projectRepository.getById(containerId).getOrNull()
                    ?: return "Project not found"
                val status = parseProjectStatus(targetStatus)
                    ?: return "Invalid project status: $targetStatus"
                val updated = existing.update(status = status)
                when (val result = projectRepository.update(updated)) {
                    is Result.Success -> null
                    is Result.Error -> "Failed to update project: ${result.error}"
                }
            }
            else -> "Unsupported container type: $containerType"
        }
    }

    private suspend fun fetchEntityDetails(
        containerId: UUID,
        containerType: String
    ): Pair<String, List<String>>? {
        return when (containerType) {
            "task" -> {
                val task = taskRepository.getById(containerId).getOrNull() ?: return null
                normalizeStatus(task.status.name) to task.tags
            }
            "feature" -> {
                val feature = featureRepository.getById(containerId).getOrNull() ?: return null
                normalizeStatus(feature.status.name) to feature.tags
            }
            "project" -> {
                val project = projectRepository.getById(containerId).getOrNull() ?: return null
                normalizeStatus(project.status.name) to project.tags
            }
            else -> null
        }
    }

    private fun normalizeStatus(status: String): String {
        return status.lowercase().replace('_', '-')
    }

    private fun parseTaskStatus(status: String): TaskStatus? {
        val normalized = status.uppercase().replace('-', '_')
        return try {
            TaskStatus.valueOf(normalized)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun parseFeatureStatus(status: String): FeatureStatus? {
        val normalized = status.uppercase().replace('-', '_')
        return try {
            FeatureStatus.valueOf(normalized)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun parseProjectStatus(status: String): ProjectStatus? {
        val normalized = status.uppercase().replace('-', '_')
        return try {
            ProjectStatus.valueOf(normalized)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * Runs completion cleanup for a feature transition.
     * The cleanup service internally checks if the status is terminal and if cleanup is enabled.
     */
    private suspend fun runCompletionCleanup(
        featureId: UUID,
        targetStatus: String
    ): DomainCascadeCleanupResult? {
        return try {
            val cleanupService = CompletionCleanupService(
                taskRepository = taskRepository,
                sectionRepository = sectionRepository,
                dependencyRepository = dependencyRepository
            )
            val result = cleanupService.cleanupFeatureTasks(featureId, targetStatus)
            result?.let { mapCleanupResultToDomain(it) }
        } catch (e: Exception) {
            logger.warn("Failed to run completion cleanup for feature $featureId: ${e.message}")
            null
        }
    }

    /**
     * Maps application-layer CleanupResult to domain-layer CascadeCleanupResult.
     * Keeps the domain layer free from application-layer dependencies.
     */
    private fun mapCleanupResultToDomain(result: CleanupResult): DomainCascadeCleanupResult {
        return DomainCascadeCleanupResult(
            performed = result.performed,
            tasksDeleted = result.tasksDeleted,
            tasksRetained = result.tasksRetained,
            retainedTaskIds = result.retainedTaskIds,
            sectionsDeleted = result.sectionsDeleted,
            dependenciesDeleted = result.dependenciesDeleted,
            reason = result.reason
        )
    }

    /**
     * Counts tasks in a feature with normalized status.
     *
     * @param featureId Feature UUID
     * @param normalizedStatus Status in normalized format
     * @return Count of tasks with matching status
     */
    private suspend fun countTasksByStatus(featureId: UUID, normalizedStatus: String): Int {
        val tasks = taskRepository.findByFeatureId(featureId)
        return tasks.count { normalizeStatus(it.status.name) == normalizedStatus }
    }
}
