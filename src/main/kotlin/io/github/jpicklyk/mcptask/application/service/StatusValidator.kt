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
    }

    /**
     * Validates a status value for the given container type.
     * @param status The status string to validate (e.g., "in-progress", "testing")
     * @param containerType The container type ("project", "feature", or "task")
     * @return ValidationResult indicating validity with optional error message and suggestions
     */
    fun validateStatus(status: String, containerType: String): ValidationResult {
        val config = loadConfig()

        return if (config != null) {
            validateStatusV2(status, containerType, config)
        } else {
            validateStatusV1(status, containerType)
        }
    }

    /**
     * Validates a status transition from current status to new status.
     * @param currentStatus The current status
     * @param newStatus The target status
     * @param containerType The container type ("project", "feature", or "task")
     * @return ValidationResult indicating validity with optional error message and suggestions
     */
    fun validateTransition(currentStatus: String, newStatus: String, containerType: String): ValidationResult {
        // Status validation first
        val statusValidation = validateStatus(newStatus, containerType)
        if (statusValidation is ValidationResult.Invalid) {
            return statusValidation
        }

        val config = loadConfig()

        return if (config != null) {
            validateTransitionV2(currentStatus, newStatus, containerType, config)
        } else {
            // v1.0 mode: all transitions allowed (no config-based rules)
            ValidationResult.Valid
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
        config: Map<String, Any?>
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

        val defaultFlow = getDefaultFlow(containerType, statusProgression)
        val currentIndex = defaultFlow.indexOf(normalizedCurrent)
        val newIndex = defaultFlow.indexOf(normalizedNew)

        // If either status not in default flow, allow transition (might be in alternative flow)
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
            val skippedStatuses = defaultFlow.subList(currentIndex + 1, newIndex)
            return ValidationResult.Invalid(
                "Cannot skip statuses. Must transition through: ${skippedStatuses.joinToString(" → ")}",
                listOf(defaultFlow[currentIndex + 1])
            )
        }

        return ValidationResult.Valid
    }

    private fun getAllowedStatusesV2(containerType: String, config: Map<String, Any?>): List<String> {
        val statusProgression = getStatusProgressionConfig(containerType, config)
        return statusProgression["allowed_statuses"] as? List<String> ?: emptyList()
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

    // ========== UTILITY METHODS ==========

    /**
     * Normalizes status string to match config format (lowercase with hyphens).
     * Examples: "IN_PROGRESS" → "in-progress", "InProgress" → "in-progress"
     */
    private fun normalizeStatus(status: String): String {
        return status.lowercase().replace('_', '-')
    }
}
