package io.github.jpicklyk.mcptask.application.service

import io.github.jpicklyk.mcptask.domain.model.FeatureStatus
import io.github.jpicklyk.mcptask.domain.model.ProjectStatus
import io.github.jpicklyk.mcptask.domain.model.TaskStatus
import io.github.jpicklyk.mcptask.domain.validation.ValidationException
import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.Yaml
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Service for validating status values and transitions using hybrid approach:
 * - v2.0 mode: Uses .taskorchestrator/config.yaml when it exists
 * - v1.0 mode: Falls back to enum-based validation when config doesn't exist
 *
 * Implements status transition rules including:
 * - Backward transitions (e.g., testing → in-development for rework)
 * - Sequential enforcement (can't skip statuses in flow)
 * - Emergency transitions (any status → blocked/archived/cancelled)
 * - Terminal status blocking (no transitions from completed/archived/etc)
 */
class StatusValidator {
    private val logger = LoggerFactory.getLogger(StatusValidator::class.java)

    // Cached config to avoid repeated file reads
    @Volatile
    private var cachedConfig: Map<String, Any?>? = null
    @Volatile
    private var lastConfigCheck: Long = 0L
    @Volatile
    private var cachedUserDir: String? = null
    private val configCacheTimeout = 60_000L // 60 seconds

    private fun getConfigPath(): Path {
        return Paths.get(System.getProperty("user.dir"), ".taskorchestrator", "config.yaml")
    }

    /**
     * Result of status validation
     */
    sealed class ValidationResult {
        object Valid : ValidationResult()
        data class Invalid(val reason: String, val suggestions: List<String> = emptyList()) : ValidationResult()
        data class ValidWithAdvisory(val advisory: String) : ValidationResult()
    }

    /**
     * Validates a status value for the given container type.
     * @param status The status string to validate (e.g., "in-progress", "testing")
     * @param containerType The container type ("project", "feature", or "task")
     * @param tags Optional tags list for deployment environment advisory
     * @return ValidationResult indicating validity with optional error message and suggestions
     */
    fun validateStatus(status: String, containerType: String, tags: List<String> = emptyList()): ValidationResult {
        val config = loadConfig()

        val result = if (config != null) {
            validateStatusV2(status, containerType, config)
        } else {
            validateStatusV1(status, containerType)
        }

        // If status is valid and it's DEPLOYED, check for environment tags
        if (result is ValidationResult.Valid) {
            val normalizedStatus = normalizeStatus(status)
            if (normalizedStatus == "deployed") {
                return checkDeploymentTagAdvisory(tags)
            }
        }

        return result
    }

    /**
     * Validates a status transition from current status to new status.
     * @param currentStatus The current status
     * @param newStatus The target status
     * @param containerType The container type ("project", "feature", or "task")
     * @param containerId Optional container ID for prerequisite validation (if null, skips prerequisite checks)
     * @param context Optional execution context for prerequisite validation (required if containerId provided)
     * @param tags Entity tags for determining active flow in v2.0 mode
     * @return ValidationResult indicating validity with optional error message and suggestions
     */
    suspend fun validateTransition(
        currentStatus: String,
        newStatus: String,
        containerType: String,
        containerId: java.util.UUID? = null,
        context: PrerequisiteContext? = null,
        tags: List<String> = emptyList()
    ): ValidationResult {
        // Status validation first (including deployment tag advisory)
        val statusValidation = validateStatus(newStatus, containerType, tags)
        if (statusValidation is ValidationResult.Invalid) {
            return statusValidation
        }

        val config = loadConfig()

        // Basic transition validation (config-based or v1.0 mode)
        val transitionResult = if (config != null) {
            validateTransitionV2(currentStatus, newStatus, containerType, config, tags)
        } else {
            // v1.0 mode: all transitions allowed (no config-based rules)
            ValidationResult.Valid
        }

        if (transitionResult is ValidationResult.Invalid) {
            return transitionResult
        }

        // Prerequisite validation if containerId and context provided
        if (containerId != null && context != null) {
            val prerequisiteResult = validatePrerequisites(containerId, newStatus, containerType, context)
            if (prerequisiteResult is ValidationResult.Invalid) {
                return prerequisiteResult
            }
        }

        // Return advisory if status validation had one, otherwise Valid
        return statusValidation
    }

    /**
     * Validates prerequisites before a status change.
     * This method should be called by tools AFTER status/transition validation.
     *
     * Prerequisites checked:
     * - Features: Specific statuses require tasks (e.g., IN_DEVELOPMENT needs 1+ tasks, TESTING needs all tasks done)
     * - Tasks: COMPLETED requires 300-500 char summary, IN_PROGRESS checks blocking dependencies
     * - Projects: COMPLETED requires all features completed
     *
     * @param entityId The entity ID being updated
     * @param newStatus The target status
     * @param containerType The container type ("project", "feature", or "task")
     * @param prerequisiteContext Context object containing repositories and entity data
     * @return ValidationResult indicating validity with detailed error messages
     */
    suspend fun validatePrerequisites(
        entityId: java.util.UUID,
        newStatus: String,
        containerType: String,
        prerequisiteContext: PrerequisiteContext
    ): ValidationResult {
        val config = loadConfig()

        // Check if prerequisite validation is enabled (default: true)
        val validationConfig = if (config != null) getValidationConfig(config) else emptyMap()
        val validatePrerequisites = validationConfig["validate_prerequisites"] as? Boolean ?: true

        if (!validatePrerequisites) {
            return ValidationResult.Valid
        }

        val normalizedStatus = normalizeStatus(newStatus)

        return when (containerType) {
            "feature" -> validateFeaturePrerequisites(entityId, normalizedStatus, prerequisiteContext)
            "task" -> validateTaskPrerequisites(entityId, normalizedStatus, prerequisiteContext)
            "project" -> validateProjectPrerequisites(entityId, normalizedStatus, prerequisiteContext)
            else -> ValidationResult.Valid
        }
    }

    /**
     * Gets all allowed statuses for the given container type.
     * @param containerType The container type ("project", "feature", or "task")
     * @return List of allowed status strings
     */
    fun getAllowedStatuses(containerType: String): List<String> {
        val config = loadConfig()

        return if (config != null) {
            getAllowedStatusesV2(containerType, config)
        } else {
            getAllowedStatusesV1(containerType)
        }
    }

    // ========== PREREQUISITE VALIDATION ==========

    /**
     * Validates feature prerequisites.
     * - IN_DEVELOPMENT: Needs at least 1 task
     * - TESTING: Needs all tasks completed
     * - COMPLETED: Needs all tasks completed
     */
    private suspend fun validateFeaturePrerequisites(
        featureId: java.util.UUID,
        newStatus: String,
        context: PrerequisiteContext
    ): ValidationResult {
        return when (newStatus) {
            "in-development" -> {
                // Need at least 1 task
                val taskCountResult = context.featureRepository.getTaskCount(featureId)
                if (taskCountResult.isError()) {
                    val error = (taskCountResult as io.github.jpicklyk.mcptask.domain.repository.Result.Error).error
                    return ValidationResult.Invalid("Failed to check task count: ${error.message}")
                }

                val taskCount = taskCountResult.getOrNull() ?: 0
                if (taskCount == 0) {
                    ValidationResult.Invalid(
                        "Feature must have at least 1 task before transitioning to IN_DEVELOPMENT",
                        listOf("Create tasks for this feature first")
                    )
                } else {
                    ValidationResult.Valid
                }
            }

            "testing" -> {
                // Need all tasks completed
                validateAllTasksCompleted(featureId, context, "TESTING")
            }

            "completed" -> {
                // Must have all tasks completed
                validateAllTasksCompleted(featureId, context, "COMPLETED")
            }

            else -> ValidationResult.Valid
        }
    }

    /**
     * Helper to validate all tasks are completed for a feature.
     */
    private suspend fun validateAllTasksCompleted(
        featureId: java.util.UUID,
        context: PrerequisiteContext,
        targetStatus: String
    ): ValidationResult {
        val tasksResult = context.taskRepository.findByFeature(featureId, statusFilter = null, priorityFilter = null, limit = 1000)
        if (tasksResult.isError()) {
            val error = (tasksResult as io.github.jpicklyk.mcptask.domain.repository.Result.Error).error
            return ValidationResult.Invalid("Failed to check tasks: ${error.message}")
        }

        val tasks = tasksResult.getOrNull() ?: emptyList()
        if (tasks.isEmpty()) {
            return ValidationResult.Invalid(
                "Feature must have tasks before transitioning to $targetStatus",
                listOf("Create and complete tasks for this feature")
            )
        }

        val incompleteTasks = tasks.filter { task ->
            val statusStr = task.status.name.lowercase().replace('_', '-')
            statusStr != "completed"
        }

        if (incompleteTasks.isNotEmpty()) {
            val incompleteCount = incompleteTasks.size
            val taskTitles = incompleteTasks.take(3).joinToString(", ") { "\"${it.title}\"" }
            val suffix = if (incompleteTasks.size > 3) " and ${incompleteTasks.size - 3} more" else ""

            return ValidationResult.Invalid(
                "Cannot transition to $targetStatus: $incompleteCount task(s) not completed. Incomplete tasks: $taskTitles$suffix",
                listOf("Complete all tasks first")
            )
        }

        return ValidationResult.Valid
    }

    /**
     * Validates task prerequisites.
     * - IN_PROGRESS: No blocking dependencies
     * - COMPLETED: Summary must be 300-500 characters
     */
    private suspend fun validateTaskPrerequisites(
        taskId: java.util.UUID,
        newStatus: String,
        context: PrerequisiteContext
    ): ValidationResult {
        return when (newStatus) {
            "in-progress" -> {
                // Check for blocking dependencies
                val dependencies = context.dependencyRepository.findByToTaskId(taskId)
                val blockingDeps = dependencies.filter {
                    it.type == io.github.jpicklyk.mcptask.domain.model.DependencyType.BLOCKS
                }

                if (blockingDeps.isEmpty()) {
                    return ValidationResult.Valid
                }

                // Check if any blocking tasks are incomplete
                val blockingTaskIds = blockingDeps.map { it.fromTaskId }
                val incompleteBlockers = mutableListOf<String>()

                for (blockerId in blockingTaskIds) {
                    val blockerResult = context.taskRepository.getById(blockerId)
                    if (blockerResult.isSuccess()) {
                        val blocker = blockerResult.getOrNull()
                        if (blocker != null) {
                            val statusStr = blocker.status.name.lowercase().replace('_', '-')
                            if (statusStr != "completed") {
                                incompleteBlockers.add("\"${blocker.title}\" (${blocker.status.name})")
                            }
                        }
                    }
                }

                if (incompleteBlockers.isNotEmpty()) {
                    ValidationResult.Invalid(
                        "Cannot transition to IN_PROGRESS: Task is blocked by ${incompleteBlockers.size} incomplete task(s): ${incompleteBlockers.take(3).joinToString(", ")}",
                        listOf("Complete blocking tasks first", "Remove blocking dependencies")
                    )
                } else {
                    ValidationResult.Valid
                }
            }

            "completed" -> {
                // Check summary length (must be 300-500 characters)
                val taskResult = context.taskRepository.getById(taskId)
                if (taskResult.isError()) {
                    val error = (taskResult as io.github.jpicklyk.mcptask.domain.repository.Result.Error).error
                    return ValidationResult.Invalid("Failed to check task: ${error.message}")
                }

                val task = taskResult.getOrNull()
                if (task == null) {
                    return ValidationResult.Invalid("Task not found")
                }

                val summaryLength = task.summary.trim().length
                if (summaryLength < 300 || summaryLength > 500) {
                    ValidationResult.Invalid(
                        "Cannot transition to COMPLETED: Task summary must be 300-500 characters (current: $summaryLength characters)",
                        listOf("Update task summary to meet length requirement")
                    )
                } else {
                    ValidationResult.Valid
                }
            }

            else -> ValidationResult.Valid
        }
    }

    /**
     * Validates project prerequisites.
     * - COMPLETED: All features must be completed
     */
    private suspend fun validateProjectPrerequisites(
        projectId: java.util.UUID,
        newStatus: String,
        context: PrerequisiteContext
    ): ValidationResult {
        return when (newStatus) {
            "completed" -> {
                // Check all features are completed
                val featuresResult = context.featureRepository.findByProject(projectId, limit = 1000)
                if (featuresResult.isError()) {
                    val error = (featuresResult as io.github.jpicklyk.mcptask.domain.repository.Result.Error).error
                    return ValidationResult.Invalid("Failed to check features: ${error.message}")
                }

                val features = featuresResult.getOrNull() ?: emptyList()
                if (features.isEmpty()) {
                    return ValidationResult.Invalid(
                        "Project must have features before transitioning to COMPLETED",
                        listOf("Create and complete features for this project")
                    )
                }

                val incompleteFeatures = features.filter { feature ->
                    val statusStr = feature.status.name.lowercase().replace('_', '-')
                    statusStr != "completed"
                }

                if (incompleteFeatures.isNotEmpty()) {
                    val incompleteCount = incompleteFeatures.size
                    val featureNames = incompleteFeatures.take(3).joinToString(", ") { "\"${it.name}\"" }
                    val suffix = if (incompleteFeatures.size > 3) " and ${incompleteFeatures.size - 3} more" else ""

                    return ValidationResult.Invalid(
                        "Cannot transition to COMPLETED: $incompleteCount feature(s) not completed. Incomplete features: $featureNames$suffix",
                        listOf("Complete all features first")
                    )
                }

                ValidationResult.Valid
            }

            else -> ValidationResult.Valid
        }
    }

    /**
     * Context object containing repositories needed for prerequisite validation.
     * Tools should construct this from their ToolExecutionContext.
     */
    data class PrerequisiteContext(
        val taskRepository: io.github.jpicklyk.mcptask.domain.repository.TaskRepository,
        val featureRepository: io.github.jpicklyk.mcptask.domain.repository.FeatureRepository,
        val projectRepository: io.github.jpicklyk.mcptask.domain.repository.ProjectRepository,
        val dependencyRepository: io.github.jpicklyk.mcptask.domain.repository.DependencyRepository
    )

    // ========== V2.0 CONFIG-BASED VALIDATION ==========

    private fun validateStatusV2(status: String, containerType: String, config: Map<String, Any?>): ValidationResult {
        val allowedStatuses = getAllowedStatusesV2(containerType, config)
        val normalizedStatus = normalizeStatus(status)

        return if (allowedStatuses.contains(normalizedStatus)) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(
                "Invalid status '$status' for $containerType. Allowed statuses: ${allowedStatuses.joinToString(", ")}",
                allowedStatuses
            )
        }
    }

    private fun validateTransitionV2(
        currentStatus: String,
        newStatus: String,
        containerType: String,
        config: Map<String, Any?>,
        tags: List<String> = emptyList()
    ): ValidationResult {
        val normalizedCurrent = normalizeStatus(currentStatus)
        val normalizedNew = normalizeStatus(newStatus)

        // Get validation settings
        val validationConfig = getValidationConfig(config)
        val statusProgression = getStatusProgressionConfig(containerType, config)

        // Check if current status is terminal
        val terminalStatuses = getTerminalStatuses(containerType, statusProgression)
        if (terminalStatuses.contains(normalizedCurrent)) {
            return ValidationResult.Invalid(
                "Cannot transition from terminal status '$currentStatus'",
                emptyList()
            )
        }

        // Check emergency transitions (blocked, archived, cancelled, deferred)
        val emergencyStatuses = getEmergencyStatuses(containerType, statusProgression)
        if (emergencyStatuses.contains(normalizedNew)) {
            // Emergency transitions allowed from any non-terminal status if enabled
            val allowEmergency = validationConfig["allow_emergency"] as? Boolean ?: true
            if (allowEmergency) {
                return ValidationResult.Valid
            }
        }

        // Check backward transitions
        val allowBackward = validationConfig["allow_backward"] as? Boolean ?: true
        val enforceSequential = validationConfig["enforce_sequential"] as? Boolean ?: true

        // Get active flow based on entity tags
        val activeFlow = getActiveFlow(containerType, statusProgression, tags)
        val currentIndex = activeFlow.indexOf(normalizedCurrent)
        val newIndex = activeFlow.indexOf(normalizedNew)

        // If either status not in active flow, allow transition (might be in another flow or emergency)
        if (currentIndex == -1 || newIndex == -1) {
            return ValidationResult.Valid
        }

        // Check if moving backward
        if (newIndex < currentIndex) {
            return if (allowBackward) {
                ValidationResult.Valid
            } else {
                ValidationResult.Invalid(
                    "Backward transition from '$currentStatus' to '$newStatus' not allowed",
                    emptyList()
                )
            }
        }

        // Check if skipping statuses (sequential enforcement)
        if (enforceSequential && newIndex > currentIndex + 1) {
            val skippedStatuses = activeFlow.subList(currentIndex + 1, newIndex)
            return ValidationResult.Invalid(
                "Cannot skip statuses. Must transition through: ${skippedStatuses.joinToString(" → ")}",
                listOf(activeFlow[currentIndex + 1])
            )
        }

        return ValidationResult.Valid
    }

    /**
     * Derives allowed statuses from configured flows, emergency transitions, and terminal statuses.
     * This eliminates redundancy - flows naturally define reachable statuses.
     */
    private fun getAllowedStatusesV2(containerType: String, config: Map<String, Any?>): List<String> {
        val statusProgression = getStatusProgressionConfig(containerType, config)
        val allowedStatuses = mutableSetOf<String>()

        // Add all statuses from all defined flows
        @Suppress("UNCHECKED_CAST")
        val allFlows = statusProgression.filterKeys { it.endsWith("_flow") }
        allFlows.values.forEach { flowValue ->
            if (flowValue is List<*>) {
                flowValue.filterIsInstance<String>().forEach { allowedStatuses.add(it) }
            }
        }

        // Add emergency transitions
        val emergencyStatuses = getEmergencyStatuses(containerType, statusProgression)
        allowedStatuses.addAll(emergencyStatuses)

        // Add terminal statuses
        val terminalStatuses = getTerminalStatuses(containerType, statusProgression)
        allowedStatuses.addAll(terminalStatuses)

        return allowedStatuses.toList()
    }

    // ========== V1.0 ENUM-BASED VALIDATION (FALLBACK) ==========

    private fun validateStatusV1(status: String, containerType: String): ValidationResult {
        val allowedStatuses = getAllowedStatusesV1(containerType)
        val normalizedStatus = normalizeStatus(status)

        return if (allowedStatuses.contains(normalizedStatus)) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(
                "Invalid status '$status' for $containerType. Allowed statuses: ${allowedStatuses.joinToString(", ")}",
                allowedStatuses
            )
        }
    }

    private fun getAllowedStatusesV1(containerType: String): List<String> {
        return when (containerType) {
            "project" -> ProjectStatus.entries.map { it.name.lowercase().replace('_', '-') }
            "feature" -> FeatureStatus.entries.map { it.name.lowercase().replace('_', '-') }
            "task" -> TaskStatus.entries.map { it.name.lowercase().replace('_', '-') }
            else -> emptyList()
        }
    }

    // ========== CONFIG LOADING ==========

    private fun loadConfig(): Map<String, Any?>? {
        val now = System.currentTimeMillis()
        val currentUserDir = System.getProperty("user.dir")

        // Invalidate cache if user.dir changed (important for testing)
        if (cachedUserDir != null && cachedUserDir != currentUserDir) {
            cachedConfig = null
            lastConfigCheck = 0L
        }

        // Check cache
        if (cachedConfig != null && (now - lastConfigCheck) < configCacheTimeout) {
            return cachedConfig
        }

        val configPath = getConfigPath()

        // Check if config exists
        if (!Files.exists(configPath)) {
            logger.debug("Config file not found at $configPath, using v1.0 enum-based validation")
            lastConfigCheck = now
            cachedConfig = null
            cachedUserDir = currentUserDir
            return null
        }

        // Load config from file
        return try {
            FileInputStream(configPath.toFile()).use { inputStream ->
                val yaml = Yaml()
                val config = yaml.load<Map<String, Any?>>(inputStream)

                logger.info("Loaded v2.0 config from $configPath")
                cachedConfig = config
                lastConfigCheck = now
                cachedUserDir = currentUserDir
                config
            }
        } catch (e: Exception) {
            logger.error("Failed to load config from $configPath, falling back to v1.0 enum validation", e)
            lastConfigCheck = now
            cachedConfig = null
            cachedUserDir = currentUserDir
            null
        }
    }

    // ========== CONFIG PARSING HELPERS ==========

    @Suppress("UNCHECKED_CAST")
    private fun getStatusProgressionConfig(containerType: String, config: Map<String, Any?>): Map<String, Any?> {
        val statusProgression = config["status_progression"] as? Map<String, Any?> ?: return emptyMap()
        val pluralType = when (containerType) {
            "project" -> "projects"
            "feature" -> "features"
            "task" -> "tasks"
            else -> return emptyMap()
        }
        return statusProgression[pluralType] as? Map<String, Any?> ?: emptyMap()
    }

    @Suppress("UNCHECKED_CAST")
    private fun getValidationConfig(config: Map<String, Any?>): Map<String, Any?> {
        return config["status_validation"] as? Map<String, Any?> ?: emptyMap()
    }

    @Suppress("UNCHECKED_CAST")
    private fun getDefaultFlow(containerType: String, statusProgression: Map<String, Any?>): List<String> {
        return statusProgression["default_flow"] as? List<String> ?: emptyList()
    }

    @Suppress("UNCHECKED_CAST")
    private fun getTerminalStatuses(containerType: String, statusProgression: Map<String, Any?>): List<String> {
        return statusProgression["terminal_statuses"] as? List<String> ?: emptyList()
    }

    @Suppress("UNCHECKED_CAST")
    private fun getEmergencyStatuses(containerType: String, statusProgression: Map<String, Any?>): List<String> {
        return statusProgression["emergency_transitions"] as? List<String> ?: emptyList()
    }

    /**
     * Determines the active flow based on entity tags and flow_mappings configuration.
     *
     * Flow selection algorithm:
     * 1. Check flow_mappings in priority order (first match wins)
     * 2. For each mapping, check if ANY entity tag matches ANY mapping tag
     * 3. If match found, return the mapped flow
     * 4. If no match, return default_flow
     *
     * @param containerType Entity type ("project", "feature", "task")
     * @param statusProgression Configuration map for the container type
     * @param tags Entity tags to match against flow_mappings
     * @return The active flow list (e.g., ["backlog", "pending", "in-progress", "completed"])
     */
    @Suppress("UNCHECKED_CAST")
    private fun getActiveFlow(
        containerType: String,
        statusProgression: Map<String, Any?>,
        tags: List<String>
    ): List<String> {
        // Get default flow as fallback
        val defaultFlow = getDefaultFlow(containerType, statusProgression)

        // If no tags provided, use default flow
        if (tags.isEmpty()) {
            return defaultFlow
        }

        // Get flow_mappings configuration
        val flowMappings = statusProgression["flow_mappings"] as? List<Map<String, Any?>> ?: return defaultFlow

        // Normalize tags for case-insensitive matching
        val normalizedTags = tags.map { it.lowercase() }

        // Iterate through mappings in priority order (first match wins)
        for (mapping in flowMappings) {
            val mappingTags = (mapping["tags"] as? List<String>)?.map { it.lowercase() } ?: continue
            val flowName = mapping["flow"] as? String ?: continue

            // Check if any entity tag matches any mapping tag
            if (normalizedTags.any { entityTag -> mappingTags.contains(entityTag) }) {
                // Found match - retrieve the flow by name
                val flow = statusProgression[flowName] as? List<String>
                if (flow != null) {
                    logger.debug("Active flow for $containerType with tags $tags: $flowName")
                    return flow
                }
            }
        }

        // No match found - use default flow
        logger.debug("No flow mapping matched for $containerType with tags $tags, using default_flow")
        return defaultFlow
    }

    /**
     * Finds which tags from the entity matched flow mappings.
     * Used for diagnostic and debugging purposes.
     *
     * @param containerType Entity type ("project", "feature", "task")
     * @param statusProgression Configuration map for the container type
     * @param tags Entity tags to check
     * @return List of tags that matched a flow mapping
     */
    @Suppress("UNCHECKED_CAST")
    private fun findMatchedTags(
        containerType: String,
        statusProgression: Map<String, Any?>,
        tags: List<String>
    ): List<String> {
        if (tags.isEmpty()) {
            return emptyList()
        }

        val flowMappings = statusProgression["flow_mappings"] as? List<Map<String, Any?>> ?: return emptyList()
        val normalizedTags = tags.map { it.lowercase() }
        val matchedTags = mutableListOf<String>()

        for (mapping in flowMappings) {
            val mappingTags = (mapping["tags"] as? List<String>)?.map { it.lowercase() } ?: continue

            // Collect entity tags that match this mapping
            val matches = normalizedTags.filter { entityTag -> mappingTags.contains(entityTag) }
            matchedTags.addAll(matches)
        }

        return matchedTags.distinct()
    }

    /**
     * Gets terminal statuses for a container type from status progression config.
     * Terminal statuses are states from which no further transitions are allowed.
     *
     * @param containerType Entity type ("project", "feature", "task")
     * @param statusProgression Configuration map for the container type
     * @return List of terminal status strings (e.g., ["completed", "cancelled", "deferred"])
     */
    @Suppress("UNCHECKED_CAST")
    private fun getTerminalStatusesFromConfig(
        containerType: String,
        statusProgression: Map<String, Any?>
    ): List<String> {
        return statusProgression["terminal_statuses"] as? List<String> ?: emptyList()
    }

    // ========== UTILITY METHODS ==========

    /**
     * Normalizes status string to match config format (lowercase with hyphens).
     * Examples: "IN_PROGRESS" → "in-progress", "InProgress" → "in-progress"
     */
    private fun normalizeStatus(status: String): String {
        return status.lowercase().replace('_', '-')
    }

    /**
     * Checks if deployment environment tags are present when status is DEPLOYED.
     * Returns advisory if environment tags are missing (not an error).
     *
     * @param tags List of tags to check for environment markers
     * @return ValidationResult.Valid if environment tag found, ValidWithAdvisory if missing
     */
    private fun checkDeploymentTagAdvisory(tags: List<String>): ValidationResult {
        val environmentTags = setOf("staging", "production", "canary", "dev", "development", "prod")
        val hasEnvironmentTag = tags.any { tag ->
            environmentTags.contains(tag.lowercase())
        }

        return if (hasEnvironmentTag) {
            ValidationResult.Valid
        } else {
            ValidationResult.ValidWithAdvisory(
                "Consider adding an environment tag (staging, production, canary, dev) to indicate deployment target"
            )
        }
    }
}
