package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema

/**
 * Provides note schemas and work item schemas derived from `.taskorchestrator/config.yaml`.
 *
 * Schemas are keyed by tag name. When a WorkItem has tags, the service returns
 * the first schema whose key matches one of the item's tags.
 *
 * Schema-free mode: When no config file is present or no tags match, all methods
 * return null / false, and transitions proceed without gate enforcement.
 */
interface WorkItemSchemaService {
    /**
     * Returns the schema entries for the first tag in [tags] that matches a
     * declared schema, or null if no schema matches (schema-free mode).
     */
    fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>?

    /**
     * Returns true if the matched schema contains any entry with role = "review".
     * Used to determine whether `start` from WORK should advance to REVIEW or jump to TERMINAL.
     * Returns false when no schema matches (schema-free mode — skip REVIEW).
     */
    fun hasReviewPhase(tags: List<String>): Boolean = getSchemaForTags(tags)?.any { it.role == Role.REVIEW } ?: false

    /**
     * Returns any warnings collected during schema loading (e.g., malformed entries,
     * missing required fields). Returns an empty list if no warnings were generated or
     * if this implementation does not support warning collection.
     */
    fun getLoadWarnings(): List<String> = emptyList()

    /**
     * Returns the [WorkItemSchema] for the given [type], or null if no schema is configured
     * for that type (schema-free mode for type-based lookup).
     *
     * Default implementation returns null (schema-free mode). Override in concrete implementations
     * that support type-based schema lookup (e.g., Task 3: YamlNoteSchemaService).
     *
     * @param type The work item type string (e.g., "feature-task"), or null to return null
     */
    fun getSchemaForType(type: String?): WorkItemSchema? = null

    /**
     * Returns true if the schema for the given [type] contains any note entry assigned
     * to the REVIEW phase. Delegates to [getSchemaForType] and [WorkItemSchema.hasReviewPhase].
     *
     * Returns false when [type] is null or no schema matches.
     *
     * @param type The work item type string, or null
     */
    fun hasReviewPhaseForType(type: String?): Boolean {
        val schema = getSchemaForType(type) ?: return false
        return schema.hasReviewPhase()
    }

    /**
     * Returns the list of note schema entries for the given trait name,
     * or null if the trait is not defined in the config.
     *
     * Traits are declared under the top-level `traits:` key in config.yaml.
     */
    fun getTraitNotes(traitName: String): List<NoteSchemaEntry>? = null

    /**
     * Returns the list of default trait names for the given work item type,
     * or an empty list if the type has no default traits or is not defined.
     */
    fun getDefaultTraits(type: String?): List<String> = emptyList()
}

/**
 * Backward-compatibility alias. All existing code referencing [NoteSchemaService] continues
 * to compile without modification.
 */
typealias NoteSchemaService = WorkItemSchemaService

/**
 * No-op implementation used when no config file is present.
 * All methods indicate schema-free mode: no schemas, no gates, no review phase detection.
 */
object NoOpNoteSchemaService : WorkItemSchemaService {
    override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? = null

    override fun getSchemaForType(type: String?): WorkItemSchema? = null

    override fun getTraitNotes(traitName: String): List<NoteSchemaEntry>? = null

    override fun getDefaultTraits(type: String?): List<String> = emptyList()
}
