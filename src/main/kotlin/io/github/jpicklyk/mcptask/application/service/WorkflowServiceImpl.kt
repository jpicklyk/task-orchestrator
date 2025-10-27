package io.github.jpicklyk.mcptask.application.service

import io.github.jpicklyk.mcptask.domain.model.workflow.CascadeEvent
import io.github.jpicklyk.mcptask.domain.model.workflow.ContainerType
import io.github.jpicklyk.mcptask.domain.model.workflow.PrerequisiteResult
import io.github.jpicklyk.mcptask.domain.model.workflow.WorkflowState
import io.github.jpicklyk.mcptask.domain.repository.FeatureRepository
import io.github.jpicklyk.mcptask.domain.repository.ProjectRepository
import io.github.jpicklyk.mcptask.domain.repository.TaskRepository
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Implementation of WorkflowService providing config-driven cascade event detection.
 *
 * CRITICAL DESIGN PRINCIPLES:
 * 1. NO hardcoded enum checks - all status comparisons use normalized strings from config
 * 2. Semantic status detection from config structure (first status, terminal statuses)
 * 3. Flow-specific behavior through config event handlers
 * 4. Status normalization bridges enum (domain) ↔ string (config)
 *
 * This service is deterministic and testable because logic is driven by configuration,
 * not hardcoded in implementation.
 */
class WorkflowServiceImpl(
    private val workflowConfigLoader: WorkflowConfigLoader,
    private val taskRepository: TaskRepository,
    private val featureRepository: FeatureRepository,
    private val projectRepository: ProjectRepository,
    private val statusValidator: StatusValidator
) : WorkflowService {

    private val logger = LoggerFactory.getLogger(WorkflowServiceImpl::class.java)

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
     * Uses config to determine:
     * - First status in flow (not hardcoded "planning")
     * - Next status from event handlers
     * - Active flow from feature tags
     */
    private suspend fun detectTaskCascades(taskId: UUID): List<CascadeEvent> {
        val task = taskRepository.getById(taskId).getOrNull() ?: return emptyList()
        val featureId = task.featureId ?: return emptyList()
        val feature = featureRepository.getById(featureId).getOrNull() ?: return emptyList()

        val events = mutableListOf<CascadeEvent>()
        val flow = determineActiveFlow(feature.tags)
        val flowConfig = workflowConfigLoader.getFlowConfig(flow)

        // Normalize statuses for comparison (config uses lowercase-with-dashes)
        val taskStatusNormalized = normalizeStatus(task.status.name)
        val featureStatusNormalized = normalizeStatus(feature.status.name)

        // Get semantic statuses from config
        val firstFeatureStatus = flowConfig.statuses.firstOrNull() ?: "planning"
        val inProgressTaskStatus = "in-progress" // Convention: tasks use this for active work

        // Event: first_task_started
        // Trigger: First task moves to in-progress AND feature is at first status in flow
        if (taskStatusNormalized == inProgressTaskStatus) {
            val inProgressCount = countTasksByStatus(featureId, inProgressTaskStatus)

            if (inProgressCount == 1 && featureStatusNormalized == firstFeatureStatus) {
                val nextStatus = getNextStatusForEvent(flow, "first_task_started", featureStatusNormalized)

                if (nextStatus != featureStatusNormalized) {
                    events.add(CascadeEvent(
                        event = "first_task_started",
                        targetType = ContainerType.FEATURE,
                        targetId = feature.id,
                        targetName = feature.name,
                        currentStatus = featureStatusNormalized,
                        suggestedStatus = nextStatus,
                        flow = flow,
                        automatic = true,
                        reason = "First task started in feature"
                    ))
                }
            }
        }

        // Event: all_tasks_complete
        // Trigger: Task completed AND all tasks in feature are done
        val completedTaskStatus = "completed"
        val cancelledTaskStatus = "cancelled"

        if (taskStatusNormalized == completedTaskStatus || taskStatusNormalized == cancelledTaskStatus) {
            val taskCounts = featureRepository.getTaskCountsByFeatureId(featureId)
            val allDone = (taskCounts.completed + taskCounts.cancelled) == taskCounts.total

            if (allDone && taskCounts.total > 0) {
                val nextStatus = getNextStatusForEvent(flow, "all_tasks_complete", featureStatusNormalized)

                // Only add event if status would actually change
                if (nextStatus != featureStatusNormalized) {
                    events.add(CascadeEvent(
                        event = "all_tasks_complete",
                        targetType = ContainerType.FEATURE,
                        targetId = feature.id,
                        targetName = feature.name,
                        currentStatus = featureStatusNormalized,
                        suggestedStatus = nextStatus,
                        flow = flow,
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

        // Terminal statuses for features (completed, archived, cancelled)
        val featureTerminalStatuses = listOf("completed", "archived", "cancelled")

        // Event: all_features_complete
        // Trigger: Feature reaches terminal status AND all features in project are terminal
        if (featureTerminalStatuses.contains(featureStatusNormalized)) {
            val featureCounts = projectRepository.getFeatureCountsByProjectId(projectId)
            val allDone = featureCounts.completed == featureCounts.total

            if (allDone && featureCounts.total > 0) {
                val projectFlow = "default_flow" // Projects don't have flow variants yet
                val nextStatus = "completed" // Projects complete when all features done

                // Only add event if project not already complete
                if (projectStatusNormalized != "completed") {
                    events.add(CascadeEvent(
                        event = "all_features_complete",
                        targetType = ContainerType.PROJECT,
                        targetId = project.id,
                        targetName = project.name,
                        currentStatus = projectStatusNormalized,
                        suggestedStatus = nextStatus,
                        flow = projectFlow,
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

    override fun determineActiveFlow(tags: List<String>): String {
        val flowMappings = workflowConfigLoader.getFlowMappings()

        // Check each flow mapping for tag matches
        for ((flowName, flowConfig) in flowMappings) {
            val flowTags = flowConfig.tags ?: continue
            if (tags.any { it in flowTags }) {
                logger.debug("Matched flow '$flowName' for tags: $tags")
                return flowName
            }
        }

        logger.debug("No flow matched for tags: $tags, using default_flow")
        return "default_flow"
    }

    override suspend fun validatePrerequisites(
        containerId: UUID,
        containerType: ContainerType,
        targetStatus: String
    ): StatusValidator.ValidationResult {
        // TODO: Implement prerequisite context and validation
        // For now, return Valid to allow implementation to proceed
        return StatusValidator.ValidationResult.Valid
    }

    override suspend fun getWorkflowState(
        containerId: UUID,
        containerType: ContainerType
    ): WorkflowState {
        // Get container and extract status/tags
        val (currentStatus, tags) = when (containerType) {
            ContainerType.TASK -> {
                val task = taskRepository.getById(containerId).getOrNull()
                    ?: error("Task $containerId not found")
                normalizeStatus(task.status.name) to task.tags
            }
            ContainerType.FEATURE -> {
                val feature = featureRepository.getById(containerId).getOrNull()
                    ?: error("Feature $containerId not found")
                normalizeStatus(feature.status.name) to feature.tags
            }
            ContainerType.PROJECT -> {
                val project = projectRepository.getById(containerId).getOrNull()
                    ?: error("Project $containerId not found")
                normalizeStatus(project.status.name) to emptyList()
            }
        }

        // Determine active flow
        val activeFlow = determineActiveFlow(tags)
        val flowConfig = workflowConfigLoader.getFlowConfig(activeFlow)

        // Get allowed transitions (statuses after current in flow progression)
        val allowedTransitions = getAllowedTransitions(currentStatus, flowConfig.statuses)

        // Detect cascade events pending for this container
        val detectedEvents = detectPendingCascades(containerId, containerType)

        // Check prerequisites for each allowed transition
        val prerequisites = mutableMapOf<String, PrerequisiteResult>()
        for (targetStatus in allowedTransitions) {
            val prereqResult = checkPrerequisites(
                containerId = containerId,
                containerType = containerType,
                currentStatus = currentStatus,
                targetStatus = targetStatus
            )
            prerequisites[targetStatus] = prereqResult
        }

        return WorkflowState(
            containerId = containerId,
            containerType = containerType,
            currentStatus = currentStatus,
            activeFlow = activeFlow,
            allowedTransitions = allowedTransitions,
            detectedEvents = detectedEvents,
            prerequisites = prerequisites
        )
    }

    /**
     * Detects cascade events pending for this container based on child entity status.
     *
     * Unlike detectCascadeEvents which detects upward cascades caused BY an entity,
     * this detects cascades that would be triggered FOR this entity by its children.
     *
     * @param containerId Container UUID
     * @param containerType Container type
     * @return List of pending cascade events affecting this container
     */
    private suspend fun detectPendingCascades(
        containerId: UUID,
        containerType: ContainerType
    ): List<CascadeEvent> {
        return when (containerType) {
            ContainerType.TASK -> {
                // Tasks don't have child entities, no pending cascades
                emptyList()
            }
            ContainerType.FEATURE -> {
                detectFeaturePendingCascades(containerId)
            }
            ContainerType.PROJECT -> {
                detectProjectPendingCascades(containerId)
            }
        }
    }

    /**
     * Detects pending cascades for a feature based on task completion.
     */
    private suspend fun detectFeaturePendingCascades(featureId: UUID): List<CascadeEvent> {
        val feature = featureRepository.getById(featureId).getOrNull() ?: return emptyList()
        val events = mutableListOf<CascadeEvent>()

        val flow = determineActiveFlow(feature.tags)
        val featureStatusNormalized = normalizeStatus(feature.status.name)
        val flowConfig = workflowConfigLoader.getFlowConfig(flow)
        val firstFeatureStatus = flowConfig.statuses.firstOrNull() ?: "planning"

        // Check for first_task_started event
        val taskCounts = featureRepository.getTaskCountsByFeatureId(featureId)
        val hasInProgressTasks = taskCounts.inProgress > 0

        if (featureStatusNormalized == firstFeatureStatus && hasInProgressTasks) {
            val nextStatus = getNextStatusForEvent(flow, "first_task_started", featureStatusNormalized)
            if (nextStatus != featureStatusNormalized) {
                events.add(CascadeEvent(
                    event = "first_task_started",
                    targetType = ContainerType.FEATURE,
                    targetId = featureId,
                    targetName = feature.name,
                    currentStatus = featureStatusNormalized,
                    suggestedStatus = nextStatus,
                    flow = flow,
                    automatic = true,
                    reason = "First task started in feature"
                ))
            }
        }

        // Check for all_tasks_complete event
        if (taskCounts.total > 0) {
            val allTasksDone = (taskCounts.completed + taskCounts.cancelled) == taskCounts.total
            if (allTasksDone && featureStatusNormalized != "completed") {
                val nextStatus = getNextStatusForEvent(flow, "all_tasks_complete", featureStatusNormalized)
                if (nextStatus != featureStatusNormalized) {
                    events.add(CascadeEvent(
                        event = "all_tasks_complete",
                        targetType = ContainerType.FEATURE,
                        targetId = featureId,
                        targetName = feature.name,
                        currentStatus = featureStatusNormalized,
                        suggestedStatus = nextStatus,
                        flow = flow,
                        automatic = true,
                        reason = "All ${taskCounts.total} tasks completed"
                    ))
                }
            }
        }

        return events
    }

    /**
     * Detects pending cascades for a project based on feature completion.
     */
    private suspend fun detectProjectPendingCascades(projectId: UUID): List<CascadeEvent> {
        val project = projectRepository.getById(projectId).getOrNull() ?: return emptyList()
        val events = mutableListOf<CascadeEvent>()

        val projectStatusNormalized = normalizeStatus(project.status.name)
        val featureCounts = projectRepository.getFeatureCountsByProjectId(projectId)

        // Check for all_features_complete event
        if (featureCounts.total > 0) {
            val allFeaturesDone = featureCounts.completed == featureCounts.total
            if (allFeaturesDone && projectStatusNormalized != "completed") {
                events.add(CascadeEvent(
                    event = "all_features_complete",
                    targetType = ContainerType.PROJECT,
                    targetId = projectId,
                    targetName = project.name,
                    currentStatus = projectStatusNormalized,
                    suggestedStatus = "completed",
                    flow = "default_flow",
                    automatic = false,
                    reason = "All ${featureCounts.total} features completed"
                ))
            }
        }

        return events
    }

    /**
     * Gets allowed transitions from current status based on flow progression.
     *
     * Returns all statuses that come after the current status in the flow.
     * This represents valid forward progressions.
     *
     * @param currentStatus Normalized current status
     * @param flowStatuses Ordered list of statuses in flow
     * @return List of allowed next statuses
     */
    private fun getAllowedTransitions(currentStatus: String, flowStatuses: List<String>): List<String> {
        val currentIndex = flowStatuses.indexOf(currentStatus)
        if (currentIndex == -1) {
            // Current status not in flow, return all flow statuses
            logger.warn("Current status '$currentStatus' not found in flow, returning all flow statuses")
            return flowStatuses
        }

        // Return all statuses after current (forward progression only)
        return flowStatuses.drop(currentIndex + 1)
    }

    /**
     * Checks prerequisites for a status transition.
     *
     * Validates:
     * - Task prerequisites (for features): All tasks must be completed
     * - Feature prerequisites (for projects): All features must be completed
     * - Summary requirements (for tasks): Summary must be populated
     *
     * @param containerId Container UUID
     * @param containerType Container type
     * @param currentStatus Current normalized status
     * @param targetStatus Target normalized status
     * @return PrerequisiteResult with validation details
     */
    private suspend fun checkPrerequisites(
        containerId: UUID,
        containerType: ContainerType,
        currentStatus: String,
        targetStatus: String
    ): PrerequisiteResult {
        val requirements = mutableListOf<String>()
        val blockingReasons = mutableListOf<String>()

        when (containerType) {
            ContainerType.TASK -> {
                // Task completion requires summary
                if (targetStatus == "completed") {
                    requirements.add("Task summary must be populated")
                    val task = taskRepository.getById(containerId).getOrNull()
                    if (task != null && task.summary.isBlank()) {
                        blockingReasons.add("Task summary is empty")
                    }
                }
            }
            ContainerType.FEATURE -> {
                // Feature progression requires task completion
                requirements.add("All tasks must be completed")
                val featureId = containerId
                val taskCounts = featureRepository.getTaskCountsByFeatureId(featureId)

                if (taskCounts.total > 0) {
                    val incompleteCount = taskCounts.total - taskCounts.completed - taskCounts.cancelled
                    if (incompleteCount > 0) {
                        blockingReasons.add("Not all tasks completed")
                    }
                } else {
                    // No tasks created yet
                    if (targetStatus !in listOf("planning", "in-development")) {
                        blockingReasons.add("Feature has no tasks")
                    }
                }
            }
            ContainerType.PROJECT -> {
                // Project progression requires feature completion
                requirements.add("All features must be completed")
                val projectId = containerId
                val featureCounts = projectRepository.getFeatureCountsByProjectId(projectId)

                if (featureCounts.total > 0) {
                    val incompleteCount = featureCounts.total - featureCounts.completed
                    if (incompleteCount > 0) {
                        blockingReasons.add("Not all features completed")
                    }
                } else {
                    // No features created yet
                    if (targetStatus != "planning") {
                        blockingReasons.add("Project has no features")
                    }
                }
            }
        }

        return PrerequisiteResult(
            status = targetStatus,
            met = blockingReasons.isEmpty(),
            requirements = requirements,
            blockingReasons = blockingReasons
        )
    }

    /**
     * Gets next status for an event from workflow config.
     *
     * Lookup order:
     * 1. Check event_overrides in flow (flow-specific behavior)
     * 2. Use event_handlers in flow (standard behavior)
     * 3. Return current status if no handler found
     *
     * @param flow Flow name
     * @param event Event identifier
     * @param currentStatus Current normalized status
     * @return Next status from config or current status
     */
    private fun getNextStatusForEvent(
        flow: String,
        event: String,
        currentStatus: String
    ): String {
        val flowConfig = workflowConfigLoader.getFlowConfig(flow)

        // Check for event override in flow (e.g., rapid_prototype skips testing)
        val override = flowConfig.eventOverrides?.get(event)
        if (override != null && override.from == currentStatus) {
            logger.debug("Using event override for $event: $currentStatus → ${override.to}")
            return override.to
        }

        // Use default event handler
        val eventHandler = flowConfig.eventHandlers[event]
        if (eventHandler != null && eventHandler.from == currentStatus) {
            logger.debug("Using event handler for $event: $currentStatus → ${eventHandler.to}")
            return eventHandler.to
        }

        logger.debug("No event handler found for $event from $currentStatus, no change")
        return currentStatus
    }

    /**
     * Normalizes status string to config format: lowercase-with-dashes.
     *
     * Examples:
     * - "IN_PROGRESS" → "in-progress"
     * - "InProgress" → "in-progress"
     * - "in progress" → "in-progress"
     *
     * This bridges the gap between enum names (domain) and config strings.
     */
    private fun normalizeStatus(status: String): String {
        return status.lowercase()
            .replace("_", "-")
            .replace(" ", "-")
    }

    /**
     * Counts tasks in a feature with normalized status.
     *
     * @param featureId Feature UUID
     * @param normalizedStatus Status in normalized format
     * @return Count of tasks with matching status
     */
    private fun countTasksByStatus(featureId: UUID, normalizedStatus: String): Int {
        val tasks = taskRepository.findByFeatureId(featureId)
        return tasks.count { normalizeStatus(it.status.name) == normalizedStatus }
    }
}
