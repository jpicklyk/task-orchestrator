package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.application.config.ConfigDocument
import io.github.jpicklyk.mcptask.current.application.service.WorkItemSchemaService
import io.github.jpicklyk.mcptask.current.domain.model.DispatchProfile
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import java.nio.file.Path

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
 * This is the server config's raw catalogue only (see [WorkItemSchemaService]); per-item schema
 * resolution goes through `EffectiveConfigResolver`. No config file means empty catalogues.
 */
class YamlWorkItemSchemaService(
    private val globalConfig: GlobalConfigFile
) : WorkItemSchemaService {
    constructor(configPath: Path) : this(GlobalConfigFile(configPath))

    /** The current document, or [ConfigDocument.EMPTY] when no global config file is present. */
    private val document: ConfigDocument get() = globalConfig.layer()?.document ?: ConfigDocument.EMPTY

    /** The declared type -> WorkItemSchema map. */
    private val workItemSchemas: Map<String, WorkItemSchema> get() = document.workItemSchemas

    /** The declared trait definitions. */
    private val traitDefs: Map<String, List<NoteSchemaEntry>> get() = document.traits

    override fun getLoadWarnings(): List<String> = document.warnings

    // -----------------------------------------------------------------------
    // Phase 4: Discovery / metadata API overrides
    // -----------------------------------------------------------------------

    override fun getAllSchemas(): Map<String, WorkItemSchema> = workItemSchemas

    override fun getAllTraits(): Map<String, List<NoteSchemaEntry>> = traitDefs

    override fun getTraitResources(traitName: String): List<ResourceRequirement> = document.traitResources[traitName] ?: emptyList()

    override fun getTraitDispatch(traitName: String): Map<Role, DispatchProfile> = document.traitDispatch[traitName] ?: emptyMap()

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
}
