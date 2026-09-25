package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.application.service.WorkItemSchemaService
import io.github.jpicklyk.mcptask.current.domain.model.DispatchProfile
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.ResourceDefinition
import io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import io.github.jpicklyk.mcptask.current.infrastructure.security.configFingerprint
import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.nio.file.Paths

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

    /** Lazily loaded type→WorkItemSchema cache. Note lists per tag are read via `[tag]?.notes`. */
    private val workItemSchemas: Map<String, WorkItemSchema> get() = loadResult.workItemSchemas

    /** Lazily loaded trait definitions. */
    private val traitDefs: Map<String, List<NoteSchemaEntry>> get() = loadResult.traits

    override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? {
        // First matching tag wins; fall back to the "default" schema only after every tag misses.
        for (tag in tags) {
            val schema = workItemSchemas[tag]
            if (schema != null) return schema.notes
        }
        return workItemSchemas["default"]?.notes
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

    override fun getTraitResources(traitName: String): List<ResourceRequirement> = loadResult.traitResources[traitName] ?: emptyList()

    override fun getResourceRegistry(): Map<String, ResourceDefinition> = loadResult.resourceRegistry

    override fun getTraitDispatch(traitName: String): Map<Role, DispatchProfile> = loadResult.traitDispatch[traitName] ?: emptyMap()

    /**
     * Returns the configured `note_limits.mode` ("warn" or "reject"), defaulting to "warn"
     * when the config file is absent, the `note_limits` block is absent, or the value is
     * invalid (a load warning is recorded in the latter case — see [YamlSchemaParser]).
     */
    override fun getNoteLimitsMode(): String = loadResult.noteLimitsMode

    /**
     * Returns the SHA-256 fingerprint computed once, at parse time, over the exact bytes
     * [loadSchemas] parsed (see [YamlSchemaParser.ParsedConfig.fingerprint]) — not a fresh re-read
     * of the file. Because [loadResult] is cached (`by lazy`), the fingerprint a running process
     * reports is stable for that process's lifetime even if the file on disk changes underneath it;
     * restart to pick up new bytes (consistent with every other global-config value, which is also
     * read once at startup — see the class kdoc). Returns `null` when no config file was present at
     * load time.
     */
    override fun getConfigFingerprint(): String? = loadResult.fingerprint

    /**
     * Reads and parses the config file, delegating the "root map -> schemas/traits/warnings"
     * step to [YamlSchemaParser.parseRoot] (shared with [PerRootConfigService]). This method
     * retains only the file-specific concerns: existence check, IO, YAML syntax-error handling,
     * fingerprinting, and the summary log line.
     *
     * Parses via [SafeConstructor] rather than SnakeYAML's default `Constructor` — matching
     * [PerRootConfigService]'s parse of the same shared format, so a `!!`-tagged arbitrary-Java-type
     * payload (CWE-502) is rejected the same way regardless of whether it arrived via a locally
     * edited `.taskorchestrator/config.yaml` or a pushed per-root document.
     *
     * **Fails closed**: a file that exists but cannot be read, is not valid YAML, or whose parsed
     * root is not a mapping throws [IllegalArgumentException] naming [configPath] rather than
     * silently falling back to an empty (schema-free) [YamlSchemaParser.ParsedConfig] — see
     * [ServerComposition.build], which forces this lazy load at startup so the failure surfaces
     * before the readiness marker is written. An absent, empty, or comment-only file is not an
     * error: it keeps the coded "schema-free mode" defaults.
     */
    private fun loadSchemas(): YamlSchemaParser.ParsedConfig {
        if (!configPath.toFile().exists()) {
            logger.debug("No config file found at {}; running in schema-free mode", configPath)
            return YamlSchemaParser.ParsedConfig(emptyMap(), emptyMap(), emptyList())
        }

        // Read the bytes once: the fingerprint is computed over, and the YAML is parsed from,
        // this exact same byte array (D4) — no separate re-read of a possibly-changed file.
        val bytes =
            try {
                configPath.toFile().readBytes()
            } catch (e: Exception) {
                throw IllegalArgumentException(
                    "Failed to read note schemas config from '$configPath': ${e.message}",
                    e
                )
            }
        // JVM String decoding of UTF-8 bytes keeps a leading U+FEFF as a real character, so
        // configFingerprint's BOM-stripping normalization applies here exactly as it does for the
        // raw byte path elsewhere — YAML parsing below still uses the raw bytes.
        val fingerprint = configFingerprint(String(bytes, Charsets.UTF_8))

        val root =
            try {
                val yaml = Yaml(SafeConstructor(LoaderOptions()))
                yaml.load<Any?>(String(bytes, Charsets.UTF_8))
            } catch (e: Exception) {
                throw IllegalArgumentException(
                    "Failed to load note schemas from '$configPath': ${e.message}",
                    e
                )
            }

        val parsed =
            if (root == null) {
                YamlSchemaParser.ParsedConfig(emptyMap(), emptyMap(), emptyList())
            } else {
                @Suppress("UNCHECKED_CAST")
                val rootMap =
                    root as? Map<String, Any>
                        ?: throw IllegalArgumentException(
                            "Config file '$configPath' root must be a mapping; got '$root'"
                        )
                try {
                    YamlSchemaParser.parseRoot(rootMap)
                } catch (e: IllegalArgumentException) {
                    throw e
                } catch (e: Exception) {
                    // An unexpected section shape (e.g. a ClassCastException from an unchecked cast)
                    // must still fail startup naming the file, like every other global-config error.
                    throw IllegalArgumentException("Failed to parse note schemas in '$configPath': ${e.message}", e)
                }
            }

        return parsed.copy(fingerprint = fingerprint).also { result ->
            result.warnings.forEach { w -> logger.warn(w) }
            val totalEntries = result.workItemSchemas.values.sumOf { it.notes.size }
            logger.info(
                "Loaded {} schemas ({} entries, {} warnings)",
                result.workItemSchemas.size,
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
