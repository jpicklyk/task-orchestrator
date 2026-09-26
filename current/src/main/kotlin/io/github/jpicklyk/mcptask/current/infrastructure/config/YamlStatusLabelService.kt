package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.application.service.NoOpStatusLabelService
import io.github.jpicklyk.mcptask.current.application.service.StatusLabelService
import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.FileReader
import java.nio.file.Path

/**
 * YAML-backed implementation of [StatusLabelService].
 *
 * Reads status label mappings from `.taskorchestrator/config.yaml` under the `status_labels` section.
 *
 * Two constructors:
 *  - `YamlStatusLabelService(configPath)` (or its default): the ORIGINAL lenient, independent
 *    loader — its own `Yaml()`/`FileReader` read, swallowing any error into NoOp defaults. Kept
 *    unchanged for backward compatibility (existing tests, and the `ItemWriteRoutes.kt` default
 *    parameter) — a typealias could not preserve this constructor, which is why this class was NOT
 *    folded into a shared-document-only shape (see the C1 task-scope note's "Alternatives
 *    rejected").
 *  - `YamlStatusLabelService(globalConfig: GlobalConfigFile)`: reads `status_labels` from the ONE
 *    shared, already-parsed [GlobalConfigFile] document instead of re-reading and re-parsing the
 *    file independently. This is the constructor `ServerComposition` uses.
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
class YamlStatusLabelService private constructor(
    private val configPath: Path?,
    private val globalConfig: GlobalConfigFile?,
) : StatusLabelService {
    constructor(configPath: Path = YamlNoteSchemaService.resolveDefaultConfigPath()) : this(configPath, null)

    constructor(globalConfig: GlobalConfigFile) : this(null, globalConfig)

    private val logger = LoggerFactory.getLogger(YamlStatusLabelService::class.java)

    /** Lazily loaded label mappings. Falls back to NoOp defaults if config missing. */
    private val labels: Map<String, String?> by lazy { loadLabels() }

    /** Whether custom labels were loaded from config (vs. using defaults). */
    private val hasCustomConfig: Boolean by lazy { loadHasCustomConfig() }

    @Suppress("ktlint:standard:backing-property-naming")
    private var _hasCustomConfig: Boolean? = null

    override fun resolveLabel(trigger: String): String? =
        if (hasCustomConfig) {
            // Config explicitly maps this trigger — use it (even if null)
            if (labels.containsKey(trigger)) {
                labels[trigger]
            } else {
                // Trigger not in config — no label override
                null
            }
        } else {
            // No custom config — delegate to hardcoded defaults
            NoOpStatusLabelService.resolveLabel(trigger)
        }

    private fun loadLabels(): Map<String, String?> {
        val sharedGlobalConfig = globalConfig
        return if (sharedGlobalConfig != null) {
            loadLabelsFromDocument(sharedGlobalConfig)
        } else {
            loadLabelsFromPath()
        }
    }

    /**
     * Reads `status_labels` from [sharedGlobalConfig]'s already-parsed document. A `null` layer
     * (no global config file) or a `null` `statusLabels` (document has no top-level
     * `status_labels` key) both mean "use [NoOpStatusLabelService] defaults" — same semantics as
     * the path-based [loadLabelsFromPath], just reading from the shared document instead of
     * re-parsing the file.
     */
    private fun loadLabelsFromDocument(sharedGlobalConfig: GlobalConfigFile): Map<String, String?> {
        val statusLabels = sharedGlobalConfig.layer()?.document?.statusLabels
        if (statusLabels == null) {
            _hasCustomConfig = false
            return emptyMap()
        }
        _hasCustomConfig = true
        logger.info("Loaded custom status labels from config: {}", statusLabels.keys)
        return statusLabels
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadLabelsFromPath(): Map<String, String?> {
        val path = configPath ?: return emptyMap()
        if (!path.toFile().exists()) {
            logger.debug("No config file found at {}; using default status labels", path)
            _hasCustomConfig = false
            return emptyMap()
        }

        return try {
            val yaml = Yaml(SafeConstructor(LoaderOptions()))
            FileReader(path.toFile()).use { reader ->
                val root =
                    yaml.load<Map<String, Any>>(reader) ?: run {
                        _hasCustomConfig = false
                        return emptyMap()
                    }
                val statusLabels =
                    root["status_labels"] as? Map<String, Any?> ?: run {
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
