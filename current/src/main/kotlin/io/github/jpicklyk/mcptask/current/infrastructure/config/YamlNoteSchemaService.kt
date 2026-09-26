package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.application.config.ConfigDocument
import io.github.jpicklyk.mcptask.current.application.service.WorkItemSchemaService
import io.github.jpicklyk.mcptask.current.domain.model.DispatchProfile
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.ResourceDefinition
import io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import java.nio.file.Path
import java.nio.file.Paths

/**
 * YAML-backed implementation of [WorkItemSchemaService].
 *
 * Reads note schemas from `.taskorchestrator/config.yaml` in the project root, via a single
 * [GlobalConfigFile] instance ([globalConfig]) shared with [YamlStatusLabelService] and
 * [YamlActorAuthenticationConfigService] — the file is read and parsed once, by
 * [GlobalConfigFile], not independently by each of these three services.
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
    private val globalConfig: GlobalConfigFile
) : WorkItemSchemaService {
    constructor(configPath: Path = resolveDefaultConfigPath()) : this(GlobalConfigFile(configPath))

    /** The current document, or [ConfigDocument.EMPTY] when no global config file is present. */
    private val document: ConfigDocument get() = globalConfig.layer()?.document ?: ConfigDocument.EMPTY

    /** Lazily loaded type→WorkItemSchema cache. Note lists per tag are read via `[tag]?.notes`. */
    private val workItemSchemas: Map<String, WorkItemSchema> get() = document.workItemSchemas

    /** Lazily loaded trait definitions. */
    private val traitDefs: Map<String, List<NoteSchemaEntry>> get() = document.traits

    /**
     * EXACT match only — first tag in [tags] with a schema wins; no `"default"` fold. The
     * `default` fallback step now belongs to [io.github.jpicklyk.mcptask.current.application.config.LayeredConfig],
     * per the root's effective `schema_resolution` mode (AR-39, C4) — [ServiceBackedGlobalLookup]
     * applies that fold on top of this exact lookup for its own callers.
     */
    override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? {
        for (tag in tags) {
            val schema = workItemSchemas[tag]
            if (schema != null) return schema.notes
        }
        return null
    }

    /**
     * EXACT match only — no `"default"` fold. See [getSchemaForTags]'s kdoc for where the fold now
     * lives.
     */
    override fun getSchemaForType(type: String?): WorkItemSchema? {
        if (type == null) return null
        return workItemSchemas[type]
    }

    override fun getLoadWarnings(): List<String> = document.warnings

    override fun getTraitNotes(traitName: String): List<NoteSchemaEntry>? = traitDefs[traitName]

    override fun getAvailableTraits(): List<String> = traitDefs.keys.toList()

    override fun getDefaultTraits(type: String?): List<String> =
        if (type != null) workItemSchemas[type]?.defaultTraits ?: emptyList() else emptyList()

    // -----------------------------------------------------------------------
    // Phase 4: Discovery / metadata API overrides
    // -----------------------------------------------------------------------

    override fun getAllSchemas(): Map<String, WorkItemSchema> = workItemSchemas

    override fun getAllTraits(): Map<String, List<NoteSchemaEntry>> = traitDefs

    override fun getTraitResources(traitName: String): List<ResourceRequirement> = document.traitResources[traitName] ?: emptyList()

    override fun getResourceRegistry(): Map<String, ResourceDefinition> = document.resourceRegistry

    override fun getTraitDispatch(traitName: String): Map<Role, DispatchProfile> = document.traitDispatch[traitName] ?: emptyMap()

    /**
     * Returns the configured `note_limits.mode` ("warn" or "reject"), defaulting to "warn"
     * when the config file is absent, the `note_limits` block is absent, or the value is
     * invalid (a load warning is recorded in the latter case — see [YamlSchemaParser]).
     */
    override fun getNoteLimitsMode(): String = document.noteLimitsMode ?: YamlSchemaParser.DEFAULT_NOTE_LIMITS_MODE

    /**
     * Returns the SHA-256 fingerprint computed once, at parse time, by [GlobalConfigFile] over the
     * exact bytes it read (see [GlobalConfigFile]'s class kdoc) — not a fresh re-read of the file.
     * Because [GlobalConfigFile]'s layer is cached (`by lazy`), the fingerprint a running process
     * reports is stable for that process's lifetime even if the file on disk changes underneath it;
     * restart to pick up new bytes (consistent with every other global-config value, which is also
     * read once at startup — see the class kdoc). Returns `null` when no config file was present at
     * load time.
     */
    override fun getConfigFingerprint(): String? = globalConfig.layer()?.fingerprint

    companion object {
        fun resolveDefaultConfigPath(): Path {
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
