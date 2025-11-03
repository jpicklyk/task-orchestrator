package io.github.jpicklyk.mcptask.application.service.progression

import io.github.jpicklyk.mcptask.application.service.StatusValidator
import io.github.jpicklyk.mcptask.application.service.StatusValidator.PrerequisiteContext
import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

/**
 * Implementation of StatusProgressionService that provides intelligent status progression recommendations.
 *
 * This service integrates with StatusValidator for prerequisite validation and config loading.
 * Implements config caching with 60-second timeout following AgentRecommendationServiceImpl pattern.
 *
 * Key responsibilities:
 * - Load and cache workflow configuration from .taskorchestrator/config.yaml
 * - Determine active flow based on entity tags
 * - Calculate next status in workflow sequence
 * - Validate readiness via StatusValidator integration
 * - Provide rich context for AI decision-making
 *
 * ## Configuration Directory
 * The config directory can be configured via environment variable:
 * - `AGENT_CONFIG_DIR`: Directory containing .taskorchestrator/config.yaml
 * - Defaults to current working directory (user.dir)
 * - In Docker, mount project directory: -v /host/project:/project -e AGENT_CONFIG_DIR=/project
 */
class StatusProgressionServiceImpl(
    private val statusValidator: StatusValidator
) : StatusProgressionService {

    private val logger = LoggerFactory.getLogger(StatusProgressionServiceImpl::class.java)
    private val yaml = Yaml()

    // Cached config to avoid repeated file reads (following AgentRecommendationServiceImpl pattern)
    @Volatile
    private var cachedConfig: Map<String, Any?>? = null
    @Volatile
    private var lastConfigCheck: Long = 0L
    @Volatile
    private var cachedConfigDir: String? = null
    private val configCacheTimeout = 60_000L // 60 seconds

    private fun getConfigPath(): Path {
        val projectRoot = Paths.get(
            System.getenv("AGENT_CONFIG_DIR") ?: System.getProperty("user.dir")
        )
        return projectRoot.resolve(".taskorchestrator/config.yaml")
    }

    override suspend fun getNextStatus(
        currentStatus: String,
        containerType: String,
        tags: List<String>,
        containerId: UUID?
    ): NextStatusRecommendation {
        logger.debug("Getting next status for $containerType with status=$currentStatus, tags=$tags")

        val config = loadConfig()
        if (config == null) {
            logger.warn("No config found, cannot determine next status")
            return NextStatusRecommendation.Terminal(
                terminalStatus = currentStatus,
                activeFlow = "unknown",
                reason = "Configuration not found. Run setup_project to initialize workflows."
            )
        }

        val statusProgression = getStatusProgressionConfig(containerType, config)
        if (statusProgression.isEmpty()) {
            logger.warn("No status progression config for containerType=$containerType")
            return NextStatusRecommendation.Terminal(
                terminalStatus = currentStatus,
                activeFlow = "unknown",
                reason = "No workflow configuration found for $containerType"
            )
        }

        // Determine active flow based on tags
        val activeFlowName = getActiveFlowName(containerType, statusProgression, tags)
        val flowSequence = getActiveFlow(containerType, statusProgression, tags)
        val matchedTags = findMatchedTags(containerType, statusProgression, tags)

        if (flowSequence.isEmpty()) {
            logger.warn("Active flow is empty for $containerType")
            return NextStatusRecommendation.Terminal(
                terminalStatus = currentStatus,
                activeFlow = activeFlowName,
                reason = "Workflow sequence is empty"
            )
        }

        // Normalize current status for comparison
        val normalizedCurrentStatus = normalizeStatus(currentStatus)

        // Find current position in flow
        val currentPosition = flowSequence.indexOfFirst { normalizeStatus(it) == normalizedCurrentStatus }
        if (currentPosition == -1) {
            logger.warn("Current status '$currentStatus' not found in flow: $flowSequence")
            return NextStatusRecommendation.Terminal(
                terminalStatus = currentStatus,
                activeFlow = activeFlowName,
                reason = "Current status '$currentStatus' not found in workflow sequence. Available statuses: ${flowSequence.joinToString(", ")}"
            )
        }

        // Check if at terminal status
        val terminalStatuses = getTerminalStatusesFromConfig(containerType, statusProgression)
        if (terminalStatuses.map { normalizeStatus(it) }.contains(normalizedCurrentStatus)) {
            logger.debug("Current status '$currentStatus' is terminal")
            return NextStatusRecommendation.Terminal(
                terminalStatus = currentStatus,
                activeFlow = activeFlowName,
                reason = "Status '$currentStatus' is terminal. No further progression available."
            )
        }

        // Check if at end of flow
        if (currentPosition >= flowSequence.size - 1) {
            logger.debug("At end of flow sequence")
            return NextStatusRecommendation.Terminal(
                terminalStatus = currentStatus,
                activeFlow = activeFlowName,
                reason = "At end of workflow sequence. No further statuses available."
            )
        }

        // Get next status in sequence
        val nextStatus = flowSequence[currentPosition + 1]
        logger.debug("Next status in sequence: $nextStatus")

        // Validate readiness if containerId provided
        if (containerId != null) {
            // Use StatusValidator to check prerequisites
            // Note: We need PrerequisiteContext for full validation
            // For now, we'll just do basic transition validation
            val validationResult = statusValidator.validateTransition(
                currentStatus = currentStatus,
                newStatus = nextStatus,
                containerType = containerType,
                containerId = null, // Skip prerequisite validation here (would need context)
                context = null,
                tags = tags
            )

            when (validationResult) {
                is StatusValidator.ValidationResult.Invalid -> {
                    // Transition is not valid
                    return NextStatusRecommendation.Blocked(
                        currentStatus = currentStatus,
                        blockers = listOf(validationResult.reason),
                        activeFlow = activeFlowName,
                        flowSequence = flowSequence,
                        currentPosition = currentPosition
                    )
                }
                else -> {
                    // Valid or ValidWithAdvisory - proceed
                    // Note: For full prerequisite validation, caller should use checkReadiness with PrerequisiteContext
                }
            }
        }

        // Ready to progress
        return NextStatusRecommendation.Ready(
            recommendedStatus = nextStatus,
            activeFlow = activeFlowName,
            flowSequence = flowSequence,
            currentPosition = currentPosition,
            matchedTags = matchedTags,
            reason = "Next status in $activeFlowName workflow. Transition from '$currentStatus' → '$nextStatus'."
        )
    }

    override fun getFlowPath(
        containerType: String,
        tags: List<String>,
        currentStatus: String?
    ): FlowPath {
        logger.debug("Getting flow path for $containerType with tags=$tags, currentStatus=$currentStatus")

        val config = loadConfig()
        if (config == null) {
            logger.warn("No config found, returning empty flow path")
            return FlowPath(
                activeFlow = "unknown",
                flowSequence = emptyList(),
                currentPosition = null,
                matchedTags = emptyList(),
                terminalStatuses = emptyList(),
                emergencyTransitions = emptyList()
            )
        }

        val statusProgression = getStatusProgressionConfig(containerType, config)
        if (statusProgression.isEmpty()) {
            logger.warn("No status progression config for containerType=$containerType")
            return FlowPath(
                activeFlow = "unknown",
                flowSequence = emptyList(),
                currentPosition = null,
                matchedTags = emptyList(),
                terminalStatuses = emptyList(),
                emergencyTransitions = emptyList()
            )
        }

        val activeFlowName = getActiveFlowName(containerType, statusProgression, tags)
        val flowSequence = getActiveFlow(containerType, statusProgression, tags)
        val matchedTags = findMatchedTags(containerType, statusProgression, tags)
        val terminalStatuses = getTerminalStatusesFromConfig(containerType, statusProgression)
        val emergencyTransitions = getEmergencyTransitions(statusProgression)

        val currentPosition = if (currentStatus != null) {
            val normalizedStatus = normalizeStatus(currentStatus)
            flowSequence.indexOfFirst { normalizeStatus(it) == normalizedStatus }.takeIf { it >= 0 }
        } else {
            null
        }

        return FlowPath(
            activeFlow = activeFlowName,
            flowSequence = flowSequence,
            currentPosition = currentPosition,
            matchedTags = matchedTags,
            terminalStatuses = terminalStatuses,
            emergencyTransitions = emergencyTransitions
        )
    }

    override suspend fun checkReadiness(
        currentStatus: String,
        targetStatus: String,
        containerType: String,
        tags: List<String>,
        containerId: UUID
    ): ReadinessResult {
        logger.debug("Checking readiness for $containerType $containerId to transition $currentStatus → $targetStatus")

        val config = loadConfig()
        if (config == null) {
            logger.warn("No config found, cannot check readiness")
            return ReadinessResult.Invalid(
                reason = "Configuration not found. Run setup_project to initialize workflows.",
                allowedStatuses = emptyList()
            )
        }

        val statusProgression = getStatusProgressionConfig(containerType, config)
        if (statusProgression.isEmpty()) {
            logger.warn("No status progression config for containerType=$containerType")
            return ReadinessResult.Invalid(
                reason = "No workflow configuration found for $containerType",
                allowedStatuses = emptyList()
            )
        }

        val flowSequence = getActiveFlow(containerType, statusProgression, tags)
        val activeFlowName = getActiveFlowName(containerType, statusProgression, tags)
        val normalizedTargetStatus = normalizeStatus(targetStatus)

        // Check if target status exists in flow
        if (flowSequence.none { normalizeStatus(it) == normalizedTargetStatus }) {
            logger.debug("Target status '$targetStatus' not in active flow '$activeFlowName'")
            return ReadinessResult.Invalid(
                reason = "Status '$targetStatus' is not valid in active workflow '$activeFlowName'",
                allowedStatuses = flowSequence
            )
        }

        // Check if current status is terminal
        val terminalStatuses = getTerminalStatusesFromConfig(containerType, statusProgression)
        val normalizedCurrentStatus = normalizeStatus(currentStatus)
        if (terminalStatuses.map { normalizeStatus(it) }.contains(normalizedCurrentStatus)) {
            return ReadinessResult.Invalid(
                reason = "Cannot transition from terminal status '$currentStatus'",
                allowedStatuses = emptyList()
            )
        }

        // Use StatusValidator for transition validation
        // Note: This doesn't include prerequisite validation (would need PrerequisiteContext)
        val validationResult = statusValidator.validateTransition(
            currentStatus = currentStatus,
            newStatus = targetStatus,
            containerType = containerType,
            containerId = null, // Would need PrerequisiteContext for full validation
            context = null,
            tags = tags
        )

        return when (validationResult) {
            is StatusValidator.ValidationResult.Valid -> {
                ReadinessResult.Ready(
                    isValid = true,
                    reason = "Transition from '$currentStatus' to '$targetStatus' is valid in workflow '$activeFlowName'"
                )
            }
            is StatusValidator.ValidationResult.ValidWithAdvisory -> {
                ReadinessResult.Ready(
                    isValid = true,
                    reason = "Transition is valid. Advisory: ${validationResult.advisory}"
                )
            }
            is StatusValidator.ValidationResult.Invalid -> {
                ReadinessResult.NotReady(
                    blockers = listOf(validationResult.reason),
                    suggestions = validationResult.suggestions
                )
            }
        }
    }

    // ========== PRIVATE HELPER METHODS (duplicated from StatusValidator for independence) ==========

    /**
     * Load and cache config following AgentRecommendationServiceImpl pattern.
     * Returns null if file doesn't exist or can't be parsed.
     */
    private fun loadConfig(): Map<String, Any?>? {
        val now = System.currentTimeMillis()
        val currentConfigDir = System.getenv("AGENT_CONFIG_DIR") ?: System.getProperty("user.dir")

        // Invalidate cache if config directory changed (important for testing)
        if (cachedConfigDir != null && cachedConfigDir != currentConfigDir) {
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
            logger.debug("Config file not found at $configPath")
            lastConfigCheck = now
            cachedConfig = null
            cachedConfigDir = currentConfigDir
            return null
        }

        // Load config from file
        return try {
            Files.newInputStream(configPath).use { inputStream ->
                val config = yaml.load<Map<String, Any?>>(inputStream)
                logger.info("Loaded config from $configPath")
                cachedConfig = config
                lastConfigCheck = now
                cachedConfigDir = currentConfigDir
                config
            }
        } catch (e: Exception) {
            logger.error("Failed to load config from $configPath", e)
            lastConfigCheck = now
            cachedConfig = null
            cachedConfigDir = currentConfigDir
            null
        }
    }

    /**
     * Get status progression config for container type.
     * Maps containerType to plural form (task → tasks, feature → features, project → projects).
     */
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

    /**
     * Determines the active flow name based on entity tags and flow_mappings configuration.
     * Returns flow name (e.g., "bug_fix_flow", "default_flow").
     */
    @Suppress("UNCHECKED_CAST")
    private fun getActiveFlowName(
        containerType: String,
        statusProgression: Map<String, Any?>,
        tags: List<String>
    ): String {
        // If no tags provided, use default flow
        if (tags.isEmpty()) {
            return "default_flow"
        }

        // Get flow_mappings configuration
        val flowMappings = statusProgression["flow_mappings"] as? List<Map<String, Any?>> ?: return "default_flow"

        // Normalize tags for case-insensitive matching
        val normalizedTags = tags.map { it.lowercase() }

        // Iterate through mappings in priority order (first match wins)
        for (mapping in flowMappings) {
            val mappingTags = (mapping["tags"] as? List<String>)?.map { it.lowercase() } ?: continue
            val flowName = mapping["flow"] as? String ?: continue

            // Check if any entity tag matches any mapping tag
            if (normalizedTags.any { entityTag -> mappingTags.contains(entityTag) }) {
                logger.debug("Active flow for $containerType with tags $tags: $flowName")
                return flowName
            }
        }

        // No match found - use default flow
        logger.debug("No flow mapping matched for $containerType with tags $tags, using default_flow")
        return "default_flow"
    }

    /**
     * Determines the active flow sequence based on entity tags and flow_mappings configuration.
     * Returns flow sequence (e.g., ["backlog", "pending", "in-progress", "completed"]).
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
     * Gets default flow for container type.
     */
    @Suppress("UNCHECKED_CAST")
    private fun getDefaultFlow(containerType: String, statusProgression: Map<String, Any?>): List<String> {
        return statusProgression["default_flow"] as? List<String> ?: emptyList()
    }

    /**
     * Finds which tags from the entity matched flow mappings.
     * Used for diagnostic and context information.
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

            // Collect entity tags that match this mapping (return original case)
            val matches = tags.filter { tag -> mappingTags.contains(tag.lowercase()) }
            matchedTags.addAll(matches)
        }

        return matchedTags.distinct()
    }

    /**
     * Gets terminal statuses for a container type from status progression config.
     * Terminal statuses are states from which no further transitions are allowed.
     */
    @Suppress("UNCHECKED_CAST")
    private fun getTerminalStatusesFromConfig(
        containerType: String,
        statusProgression: Map<String, Any?>
    ): List<String> {
        return statusProgression["terminal_statuses"] as? List<String> ?: emptyList()
    }

    /**
     * Gets emergency transitions that are available from any status.
     * These are special transitions that bypass normal flow rules.
     */
    @Suppress("UNCHECKED_CAST")
    private fun getEmergencyTransitions(statusProgression: Map<String, Any?>): List<String> {
        return statusProgression["emergency_transitions"] as? List<String> ?: emptyList()
    }

    /**
     * Normalizes status string to match config format (lowercase with hyphens).
     * Examples: "IN_PROGRESS" → "in-progress", "InProgress" → "in-progress"
     */
    private fun normalizeStatus(status: String): String {
        return status.lowercase().replace('_', '-')
    }
}
