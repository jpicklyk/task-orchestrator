package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.application.service.WorkItemSchemaService
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.Yaml
import java.io.FileReader
import java.nio.file.Paths
import java.security.MessageDigest

/**
 * YAML-backed implementation of [WorkItemSchemaService].
 *
 * Reads note schemas from `.taskorchestrator/config.yaml` in the project root.
 * The project root is resolved from the `AGENT_CONFIG_DIR` environment variable,
 * falling back to `user.dir` if not set.
 *
 * Supports two YAML formats:
 *
 * **New format** (`work_item_schemas:`) — supports lifecycle mode per schema:
 * ```yaml
 * work_item_schemas:
 *   feature-implementation:
 *     lifecycle: auto          # parsed via LifecycleMode.fromString()
 *     notes:
 *       - key: specification
 *         role: queue
 *         required: true
 *         description: "..."
 *         guidance: "..."
 * ```
 *
 * **Legacy format** (`note_schemas:`) — backward compatible, lifecycle defaults to AUTO:
 * ```yaml
 * note_schemas:
 *   schema-tag-name:
 *     - key: note-key
 *       role: queue        # or work, review
 *       required: true
 *       description: "..."
 *       guidance: "..."    # optional
 * ```
 *
 * **Precedence**: if both `work_item_schemas:` and `note_schemas:` keys are present,
 * `work_item_schemas:` wins entirely (legacy key is ignored).
 *
 * Schema matching: The first tag in the provided list that matches a schema key wins.
 * If no config file is present, or no tags match, returns null (schema-free mode).
 */
class YamlWorkItemSchemaService(
    private val configPath: java.nio.file.Path = resolveDefaultConfigPath()
) : WorkItemSchemaService {
    private val logger = LoggerFactory.getLogger(YamlWorkItemSchemaService::class.java)

    /** Lazily loaded schema cache and warnings. Initialized once on first access. */
    private val loadResult: YamlSchemaParser.ParsedConfig by lazy { loadSchemas() }

    /** Lazily loaded tag→entries schema cache. */
    private val schemas: Map<String, List<NoteSchemaEntry>> get() = loadResult.schemas

    /** Lazily loaded type→WorkItemSchema cache. */
    private val workItemSchemas: Map<String, WorkItemSchema> get() = loadResult.workItemSchemas

    /** Lazily loaded trait definitions. */
    private val traitDefs: Map<String, List<NoteSchemaEntry>> get() = loadResult.traits

    override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? {
        for (tag in tags) {
            val schema = schemas[tag]
            if (schema != null) return schema
        }
        return schemas["default"]
    }

    override fun getSchemaForType(type: String?): WorkItemSchema? {
        if (type == null) return null
        return workItemSchemas[type] ?: workItemSchemas["default"]
    }

    override fun getLoadWarnings(): List<String> = loadResult.warnings

    override fun getTraitNotes(traitName: String): List<NoteSchemaEntry>? = traitDefs[traitName]

    override fun getAvailableTraits(): List<String> = traitDefs.keys.toList()

    override fun getDefaultTraits(type: String?): List<String> =
        if (type != null) workItemSchemas[type]?.defaultTraits ?: emptyList() else emptyList()

    // -----------------------------------------------------------------------
    // Phase 4: Discovery / metadata API overrides
    // -----------------------------------------------------------------------

    override fun getAllSchemas(): Map<String, WorkItemSchema> = workItemSchemas

    override fun getAllTraits(): Map<String, List<NoteSchemaEntry>> = traitDefs

    /**
     * Returns the configured `note_limits.mode` ("warn" or "reject"), defaulting to "warn"
     * when the config file is absent, the `note_limits` block is absent, or the value is
     * invalid (a load warning is recorded in the latter case — see [YamlSchemaParser]).
     */
    override fun getNoteLimitsMode(): String = loadResult.noteLimitsMode

    /**
     * Returns a SHA-256 fingerprint of the config file bytes, or
     * `"${lastModified}-${size}"` if reading the file fails.
     * Returns `null` when no config file is present.
     */
    override fun getConfigFingerprint(): String? {
        val file = configPath.toFile()
        if (!file.exists()) return null
        return try {
            val bytes = file.readBytes()
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            digest.joinToString("") { "%02x".format(it) }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception
        ) {
            logger.warn("Failed to compute config fingerprint: ${e.message}")
            "${file.lastModified()}-${file.length()}"
        }
    }

    /**
     * Reads and parses the config file, delegating the "root map -> schemas/traits/warnings"
     * step to [YamlSchemaParser.parseRoot] (shared with [PerRootConfigService]). This method
     * retains only the file-specific concerns: existence check, IO, YAML syntax-error handling,
     * and the summary log line.
     */
    private fun loadSchemas(): YamlSchemaParser.ParsedConfig {
        if (!configPath.toFile().exists()) {
            logger.debug("No config file found at {}; running in schema-free mode", configPath)
            return YamlSchemaParser.ParsedConfig(emptyMap(), emptyMap(), emptyMap(), emptyList())
        }

        return try {
            val yaml = Yaml()
            FileReader(configPath.toFile()).use { reader ->
                @Suppress("UNCHECKED_CAST")
                val root = yaml.load<Map<String, Any>>(reader)
                if (root == null) {
                    YamlSchemaParser.ParsedConfig(emptyMap(), emptyMap(), emptyMap(), emptyList())
                } else {
                    YamlSchemaParser.parseRoot(root)
                }
            }
        } catch (e: Exception) {
            val msg = "Failed to load note schemas from '$configPath': ${e.message}"
            logger.warn(msg)
            YamlSchemaParser.ParsedConfig(emptyMap(), emptyMap(), emptyMap(), listOf(msg))
        }.also { result ->
            result.warnings.forEach { w -> logger.warn(w) }
            val totalEntries = result.schemas.values.sumOf { it.size }
            logger.info(
                "Loaded {} schemas ({} entries, {} warnings)",
                result.schemas.size,
                totalEntries,
                result.warnings.size
            )
        }
    }

    companion object {
        fun resolveDefaultConfigPath(): java.nio.file.Path {
            val projectRoot =
                Paths.get(
                    AppConfig.resolveConfigBaseDir(System.getenv("AGENT_CONFIG_DIR"))
                )
            return projectRoot.resolve(".taskorchestrator/config.yaml")
        }
    }
}

/**
 * Backward-compatibility alias. All existing code importing [YamlNoteSchemaService] continues
 * to compile without modification.
 */
typealias YamlNoteSchemaService = YamlWorkItemSchemaService
