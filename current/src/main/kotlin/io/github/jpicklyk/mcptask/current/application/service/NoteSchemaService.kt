package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.Role

/**
 * Provides note schemas derived from `.taskorchestrator/config.yaml`.
 *
 * Schemas are keyed by tag name. When a WorkItem has tags, the service returns
 * the first schema whose key matches one of the item's tags.
 *
 * Schema-free mode: When no config file is present or no tags match, all methods
 * return null / false, and transitions proceed without gate enforcement.
 */
interface NoteSchemaService {
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
     * Returns the list of note schema entries for the given trait name,
     * or null if the trait is not defined in the config.
     *
     * Traits are declared under the top-level `traits:` key in config.yaml.
     * Each trait has a `notes:` list using the same format as schema notes.
     */
    fun getTraitNotes(traitName: String): List<NoteSchemaEntry>? = null

    /**
     * Returns the list of default trait names for the given work item type (schema tag),
     * or an empty list if the type has no default traits or is not defined.
     *
     * Default traits are declared as `default_traits:` within a schema in config.yaml.
     */
    fun getDefaultTraits(type: String?): List<String> = emptyList()
}

/**
 * No-op implementation used when no config file is present.
 * All methods indicate schema-free mode: no schemas, no gates, no review phase detection.
 */
object NoOpNoteSchemaService : NoteSchemaService {
    override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? = null
    override fun getTraitNotes(traitName: String): List<NoteSchemaEntry>? = null
    override fun getDefaultTraits(type: String?): List<String> = emptyList()
}
