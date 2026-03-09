package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.application.service.NoOpStatusLabelService
import io.github.jpicklyk.mcptask.current.application.service.StatusLabelService
import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.Yaml
import java.io.FileReader
import java.nio.file.Path

/**
 * YAML-backed implementation of [StatusLabelService].
 *
 * Reads status label mappings from `.taskorchestrator/config.yaml` under the `status_labels` section.
 * The config path is resolved using the same `AGENT_CONFIG_DIR` pattern as [YamlNoteSchemaService].
 *
 * Expected YAML structure:
 * ```yaml
 * status_labels:
 *   start: "in-progress"
 *   complete: "done"
 *   block: "blocked"
 *   cancel: "cancelled"
 *   cascade: "done"
 *   resume: null
 *   reopen: null
 * ```
 *
 * If no `status_labels` section is present (or the config file is missing),
 * falls back to [NoOpStatusLabelService] defaults.
 */
class YamlStatusLabelService(
    private val configPath: Path = YamlNoteSchemaService.resolveDefaultConfigPath()
) : StatusLabelService {

    private val logger = LoggerFactory.getLogger(YamlStatusLabelService::class.java)

    /** Lazily loaded label mappings. Falls back to NoOp defaults if config missing. */
    private val labels: Map<String, String?> by lazy { loadLabels() }

    /** Whether custom labels were loaded from config (vs. using defaults). */
    private val hasCustomConfig: Boolean by lazy { loadHasCustomConfig() }

    private var _hasCustomConfig: Boolean? = null

    override fun resolveLabel(trigger: String): String? {
        return if (hasCustomConfig) {
            // Config explicitly maps this trigger — use it (even if null)
            if (labels.containsKey(trigger)) labels[trigger]
            // Trigger not in config — no label override
            else null
        } else {
            // No custom config — delegate to hardcoded defaults
            NoOpStatusLabelService.resolveLabel(trigger)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadLabels(): Map<String, String?> {
        if (!configPath.toFile().exists()) {
            logger.debug("No config file found at {}; using default status labels", configPath)
            _hasCustomConfig = false
            return emptyMap()
        }

        return try {
            val yaml = Yaml()
            FileReader(configPath.toFile()).use { reader ->
                val root = yaml.load<Map<String, Any>>(reader) ?: run {
                    _hasCustomConfig = false
                    return emptyMap()
                }
                val statusLabels = root["status_labels"] as? Map<String, Any?> ?: run {
                    logger.debug("No status_labels section in config; using defaults")
                    _hasCustomConfig = false
                    return emptyMap()
                }

                _hasCustomConfig = true
                logger.info("Loaded custom status labels from config: {}", statusLabels.keys)

                // Convert to String? map — YAML nulls become Kotlin nulls
                statusLabels.entries.associate { (trigger, label) ->
                    trigger to (label?.toString())
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to load status labels from config: {}", e.message)
            _hasCustomConfig = false
            emptyMap()
        }
    }

    private fun loadHasCustomConfig(): Boolean {
        // Force lazy loading of labels first
        labels
        return _hasCustomConfig ?: false
    }
}
