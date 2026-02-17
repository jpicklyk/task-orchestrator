package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry

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
    fun hasReviewPhase(tags: List<String>): Boolean =
        getSchemaForTags(tags)?.any { it.role == "review" } ?: false
}

/**
 * No-op implementation used when no config file is present.
 * All methods indicate schema-free mode: no schemas, no gates, no review phase detection.
 */
object NoOpNoteSchemaService : NoteSchemaService {
    override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? = null
}
