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
 * Configuration for start cascade — auto-advance parent when first child starts.
 * When a child transitions from queue to work role, the parent auto-advances
 * from queue to work if it is still in queue role.
 */
data class StartCascadeConfig(val enabled: Boolean = true)

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
    private val sectionRepository: SectionRepository,
    private val roleTransitionRepository: RoleTransitionRepository? = null,
    private val startCascadeConfig: StartCascadeConfig = StartCascadeConfig()
) : CascadeService {

    private val logger = LoggerFactory.getLogger(CascadeServiceImpl::class.java)

    companion object {
        /**
         * Loads start cascade configuration from the auto_cascade.start_cascade config section.
         *
         * Reads from .taskorchestrator/config.yaml (or bundled default-config.yaml as fallback).
         * Returns StartCascadeConfig(enabled=true) by default.
         *
         * Example config:
         * ```yaml
         * auto_cascade:
         *   start_cascade:
         *     enabled: true
         * ```
         */
        fun loadStartCascadeConfig(): StartCascadeConfig {
            val logger = LoggerFactory.getLogger(CascadeServiceImpl::class.java)
            return try {
                val configPath = java.nio.file.Paths.get(
                    System.getenv("AGENT_CONFIG_DIR") ?: System.getProperty("user.dir")
                ).resolve(".taskorchestrator/config.yaml")

                val inputStream = if (java.nio.file.Files.exists(configPath)) {
                    java.nio.file.Files.newInputStream(configPath)
                } else {
                    CascadeServiceImpl::class.java.classLoader.getResourceAsStream("configuration/default-config.yaml")
                        ?: return StartCascadeConfig()
                }

                inputStream.use { stream ->
                    @Suppress("UNCHECKED_CAST")
                    val config = org.yaml.snakeyaml.Yaml().load<Map<String, Any?>>(stream) ?: return StartCascadeConfig()
                    val autoCascade = config["auto_cascade"] as? Map<String, Any?> ?: return StartCascadeConfig()
                    val startCascade = autoCascade["start_cascade"] as? Map<String, Any?> ?: return StartCascadeConfig()
                    val enabled = startCascade["enabled"] as? Boolean ?: true
                    StartCascadeConfig(enabled = enabled)
                }
            } catch (e: Exception) {
                logger.warn("Failed to load start_cascade config: ${e.message}")
                StartCascadeConfig()
            }
        }
    }


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
     * - all_tasks_complete: All tasks in feature reached terminal role
     * - first_child_started: First task enters work role AND parent feature is in queue role (Rule 1)
     * - all_children_in_review: All siblings are in review or terminal role AND parent feature is in work role (Rule 2)
     *
     * Uses role comparisons exclusively — never status string comparisons.
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
        statusProgressionService.getFlowPath("feature", feature.tags, featureStatusNormalized)

        // Rule 3 — Completion cascade:
        // Trigger: Task reached terminal role AND all tasks in feature are terminal
        val taskRole = statusProgressionService.getRoleForStatus(taskStatusNormalized, "task", task.tags)
        if (statusProgressionService.isRoleAtOrBeyond(taskRole, "terminal")) {
            val taskCounts = featureRepository.getTaskCountsByFeatureId(featureId)
            val allDone = (taskCounts.completed + taskCounts.cancelled) == taskCounts.total

            if (allDone && taskCounts.total > 0) {
                // Use getNextStatus to advance one step at a time through the workflow
                val recommendation = statusProgressionService.getNextStatus(
                    currentStatus = featureStatusNormalized,
                    containerType = "feature",
                    tags = feature.tags,
                    containerId = feature.id
                )

                if (recommendation is NextStatusRecommendation.Ready) {
                    // Check verification gate: don't cascade to terminal if verification required
                    val targetRole = statusProgressionService.getRoleForStatus(
                        recommendation.recommendedStatus, "feature", feature.tags
                    )
                    if (targetRole == "terminal" && feature.requiresVerification) {
                        // Stop cascade — manual complete trigger will enforce verification gate
                        // Don't emit the event, verification must be provided first
                    } else {
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
        }

        // Rule 1 — Start cascade (first_child_started):
        // Trigger: Task moves to work role AND parent feature is still in queue role
        if (startCascadeConfig.enabled) {
            if (taskRole == "work") {
                // Task just entered work role — check if parent feature is in queue role
                val featureRole = statusProgressionService.getRoleForStatus(featureStatusNormalized, "feature", feature.tags)
                if (featureRole == "queue") {
                    val recommendation = statusProgressionService.getNextStatus(
                        currentStatus = featureStatusNormalized,
                        containerType = "feature",
                        tags = feature.tags,
                        containerId = feature.id
                    )
                    if (recommendation is NextStatusRecommendation.Ready &&
                        recommendation.recommendedStatus != featureStatusNormalized) {
                        events.add(CascadeEvent(
                            event = "first_child_started",
                            targetType = ContainerType.FEATURE,
                            targetId = feature.id,
                            targetName = feature.name,
                            currentStatus = featureStatusNormalized,
                            suggestedStatus = recommendation.recommendedStatus,
                            flow = recommendation.activeFlow,
                            automatic = true,
                            reason = "First child task started — advancing parent feature from queue to work"
                        ))
                    }
                }
            }
        }

        // Rule 2 — Review cascade (all_children_in_review):
        // Trigger: Task enters review role AND all siblings are in review or terminal role
        //          AND parent feature is in work role
        if (statusProgressionService.isRoleAtOrBeyond(taskRole, "review") &&
            !statusProgressionService.isRoleAtOrBeyond(taskRole, "terminal")) {
            val featureRole = statusProgressionService.getRoleForStatus(featureStatusNormalized, "feature", feature.tags)
            if (featureRole == "work") {
                val allSiblings = taskRepository.findByFeatureId(featureId)
                val allInReviewOrBeyond = allSiblings.isNotEmpty() && allSiblings.all { sibling ->
                    val siblingStatus = normalizeStatus(sibling.status.name)
                    val siblingRole = statusProgressionService.getRoleForStatus(siblingStatus, "task", sibling.tags)
                    statusProgressionService.isRoleAtOrBeyond(siblingRole, "review")
                }
                if (allInReviewOrBeyond) {
                    val recommendation = statusProgressionService.getNextStatus(
                        currentStatus = featureStatusNormalized,
                        containerType = "feature",
                        tags = feature.tags,
                        containerId = feature.id
                    )
                    if (recommendation is NextStatusRecommendation.Ready &&
                        recommendation.recommendedStatus != featureStatusNormalized) {
                        // Only emit if recommended target is review role (not terminal — that's Rule 3's job)
                        val targetRole = statusProgressionService.getRoleForStatus(
                            recommendation.recommendedStatus, "feature", feature.tags
                        )
                        if (targetRole == "review") {
                            events.add(CascadeEvent(
                                event = "all_children_in_review",
                                targetType = ContainerType.FEATURE,
                                targetId = feature.id,
                                targetName = feature.name,
                                currentStatus = featureStatusNormalized,
                                suggestedStatus = recommendation.recommendedStatus,
                                flow = recommendation.activeFlow,
                                automatic = true,
                                reason = "All ${allSiblings.size} child tasks reached review or beyond — advancing parent feature to review"
                            ))
                        }
                    }
                }
            }
        }

        return events
    }

    /**
     * Detects cascade events for feature status changes.
     *
     * Events detected:
     * - feature_self_advancement: Feature not terminal AND all tasks terminal -> advance one step
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

        // Event: feature_self_advancement
        // Trigger: Feature NOT terminal AND all tasks are terminal -> advance feature one step
        if (!isFeatureTerminal) {
            val taskCounts = featureRepository.getTaskCountsByFeatureId(featureId)
            val allTasksDone = (taskCounts.completed + taskCounts.cancelled) == taskCounts.total && taskCounts.total > 0

            if (allTasksDone) {
                val recommendation = statusProgressionService.getNextStatus(
                    currentStatus = featureStatusNormalized,
                    containerType = "feature",
                    tags = feature.tags,
                    containerId = feature.id
                )

                if (recommendation is NextStatusRecommendation.Ready) {
                    // Check verification gate: don't cascade to terminal if verification required
                    val targetRole = statusProgressionService.getRoleForStatus(
                        recommendation.recommendedStatus, "feature", feature.tags
                    )
                    if (targetRole == "terminal" && feature.requiresVerification) {
                        // Feature stays at current status — requires manual completion with verification
                    } else {
                        events.add(CascadeEvent(
                            event = "feature_self_advancement",
                            targetType = ContainerType.FEATURE,
                            targetId = feature.id,
                            targetName = feature.name,
                            currentStatus = featureStatusNormalized,
                            suggestedStatus = recommendation.recommendedStatus,
                            flow = recommendation.activeFlow,
                            automatic = true,
                            reason = "All tasks terminal, advancing feature to next workflow step"
                        ))
                    }
                }
            }
        }

        // Event: all_features_complete
        // Trigger: Feature reaches terminal status AND all features in project are terminal
        if (isFeatureTerminal) {
            val featureCounts = projectRepository.getFeatureCountsByProjectId(projectId)
            val allDone = featureCounts.completed == featureCounts.total

            if (allDone && featureCounts.total > 0) {
                // Use getNextStatus to advance project one step at a time
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

        // Event: first_child_started (start cascade, feature → project)
        // Trigger: Feature moves to work role AND parent project is still in queue role
        if (startCascadeConfig.enabled) {
            val featureRole = statusProgressionService.getRoleForStatus(featureStatusNormalized, "feature", feature.tags)
            if (featureRole == "work") {
                // Feature just entered work role — check if parent project is in queue role
                val projectRole = statusProgressionService.getRoleForStatus(projectStatusNormalized, "project", project.tags)
                if (projectRole == "queue") {
                    val recommendation = statusProgressionService.getNextStatus(
                        currentStatus = projectStatusNormalized,
                        containerType = "project",
                        tags = project.tags,
                        containerId = project.id
                    )
                    if (recommendation is NextStatusRecommendation.Ready &&
                        recommendation.recommendedStatus != projectStatusNormalized) {
                        events.add(CascadeEvent(
                            event = "first_child_started",
                            targetType = ContainerType.PROJECT,
                            targetId = project.id,
                            targetName = project.name,
                            currentStatus = projectStatusNormalized,
                            suggestedStatus = recommendation.recommendedStatus,
                            flow = recommendation.activeFlow,
                            automatic = true,
                            reason = "First child feature started — advancing parent project from queue to work"
                        ))
                    }
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

                // Record role transition for the cascade status change
                recordRoleTransition(
                    entityId = event.targetId,
                    entityType = event.targetType.name.lowercase(),
                    currentStatus = currentStatus,
                    targetStatus = event.suggestedStatus,
                    tags = tags,
                    trigger = "cascade:${event.event}"
                )

                // Find newly unblocked tasks if this was a task-type cascade target at terminal role
                val unblockedTasks = if (event.targetType.name.lowercase() == "task") {
                    val suggestedRole = statusProgressionService.getRoleForStatus(
                        normalizeStatus(event.suggestedStatus), "task", tags
                    )
                    if (statusProgressionService.isRoleAtOrBeyond(suggestedRole, "terminal")) {
                        findNewlyUnblockedTasks(event.targetId)
                    } else emptyList()
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

    /**
     * Records a role transition for a cascade status change.
     * Silently skips if roleTransitionRepository is null or if roles are the same.
     */
    private suspend fun recordRoleTransition(
        entityId: UUID,
        entityType: String,
        currentStatus: String,
        targetStatus: String,
        tags: List<String>,
        trigger: String
    ) {
        if (roleTransitionRepository == null) return
        try {
            val previousRole = statusProgressionService.getRoleForStatus(currentStatus, entityType, tags)
            val newRole = statusProgressionService.getRoleForStatus(targetStatus, entityType, tags)
            if (previousRole != null && newRole != null && previousRole != newRole) {
                val roleTransition = RoleTransition(
                    entityId = entityId,
                    entityType = entityType,
                    fromRole = previousRole,
                    toRole = newRole,
                    fromStatus = currentStatus,
                    toStatus = targetStatus,
                    trigger = trigger,
                    summary = "Auto-cascade status change"
                )
                roleTransitionRepository.create(roleTransition)
            }
        } catch (e: Exception) {
            logger.warn("Failed to record role transition for cascade on $entityType $entityId: ${e.message}")
        }
    }

    override suspend fun findNewlyUnblockedTasks(completedTaskId: UUID): List<DomainUnblockedTask> {
        return try {
            // Get downstream candidates from BLOCKS deps (completedTask is fromTaskId)
            val outgoingBlocksDeps = dependencyRepository.findByFromTaskId(completedTaskId)
                .filter { it.type == DependencyType.BLOCKS }

            // Get downstream candidates from IS_BLOCKED_BY deps (completedTask is toTaskId/blocker)
            val incomingIsBlockedByDeps = dependencyRepository.findByToTaskId(completedTaskId)
                .filter { it.type == DependencyType.IS_BLOCKED_BY }

            val allBlockingDeps = outgoingBlocksDeps + incomingIsBlockedByDeps
            if (allBlockingDeps.isEmpty()) return emptyList()

            val unblockedTasks = mutableListOf<DomainUnblockedTask>()
            val downstreamTaskIds = allBlockingDeps.map { it.getBlockedTaskId() }.distinct()

            for (downstreamTaskId in downstreamTaskIds) {

                // Check if the downstream task itself is still active (not completed/cancelled)
                val downstreamTask = taskRepository.getById(downstreamTaskId).getOrNull()
                    ?: continue
                val downstreamStatus = normalizeStatus(downstreamTask.status.name)
                val downstreamRole = statusProgressionService.getRoleForStatus(downstreamStatus, "task", downstreamTask.tags)
                if (statusProgressionService.isRoleAtOrBeyond(downstreamRole, "terminal")) continue

                // Get ALL blocking dependencies for the downstream task (both directions):
                // 1. BLOCKS deps where downstream is toTaskId
                // 2. IS_BLOCKED_BY deps where downstream is fromTaskId
                val downstreamBlocksDeps = dependencyRepository.findByToTaskId(downstreamTaskId)
                    .filter { it.type != DependencyType.RELATES_TO }
                val downstreamIsBlockedByDeps = dependencyRepository.findByFromTaskId(downstreamTaskId)
                    .filter { it.type == DependencyType.IS_BLOCKED_BY }
                val allDownstreamBlockingDeps = downstreamBlocksDeps + downstreamIsBlockedByDeps

                // Check if ALL blocking dependencies are resolved using role-aware + unblockAt-aware logic
                val allBlockersResolved = allDownstreamBlockingDeps.all { incomingDep ->

                    val blockerTaskId = incomingDep.getBlockerTaskId()
                    val blockerTask = taskRepository.getById(blockerTaskId).getOrNull()
                    if (blockerTask != null) {
                        val threshold = incomingDep.effectiveUnblockRole() ?: "terminal"
                        val blockerStatus = normalizeStatus(blockerTask.status.name)
                        val blockerRole = statusProgressionService.getRoleForStatus(blockerStatus, "task", blockerTask.tags)
                        statusProgressionService.isRoleAtOrBeyond(blockerRole, threshold)
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
                val maxDepth = (section["max_depth"] as? Number)?.toInt() ?: 10
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

}
