package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.DispatchProfile
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema

/**
 * The server-wide config's raw catalogue, as the GLOBAL-only `/api/v1/config*` routes and the
 * status-graph builder read it, plus the load warnings collected while parsing it.
 *
 * Not a resolution surface: schema, trait and dispatch RESOLUTION for an item goes through
 * `EffectiveConfigResolver` (per-root over server, one precedence rule). Nothing here selects a
 * schema for a work item.
 */
interface WorkItemSchemaService {
    /**
     * Returns any warnings collected during schema loading (e.g., malformed entries,
     * missing required fields). Returns an empty list if no warnings were generated or
     * if this implementation does not support warning collection.
     */
    fun getLoadWarnings(): List<String> = emptyList()

    /** Returns every declared [WorkItemSchema] keyed by type name; empty in schema-free mode. */
    fun getAllSchemas(): Map<String, WorkItemSchema> = emptyMap()

    /** Returns every trait's note entries keyed by trait name; empty in schema-free mode. */
    fun getAllTraits(): Map<String, List<NoteSchemaEntry>> = emptyMap()

    /**
     * Returns a stable fingerprint string for the current config state, used to derive an ETag for
     * the `/api/v1/config*` endpoints. Returns `null` when no config file is present (schema-free mode).
     */
    fun getConfigFingerprint(): String? = null

    /**
     * Returns the resource requirements declared for trait [traitName] (its `resources:` list), or an
     * empty list when the trait declares none or is undefined.
     */
    fun getTraitResources(traitName: String): List<ResourceRequirement> = emptyList()

    /**
     * Returns the per-phase dispatch routing profiles declared for trait [traitName] (its `dispatch:`
     * map), or an empty map when the trait declares none or is undefined.
     */
    fun getTraitDispatch(traitName: String): Map<Role, DispatchProfile> = emptyMap()
}

/**
 * Backward-compatibility alias. All existing code referencing [NoteSchemaService] continues
 * to compile without modification.
 */
typealias NoteSchemaService = WorkItemSchemaService

/** No-op implementation used when no config file is present: every catalogue is empty. */
object NoOpNoteSchemaService : WorkItemSchemaService
