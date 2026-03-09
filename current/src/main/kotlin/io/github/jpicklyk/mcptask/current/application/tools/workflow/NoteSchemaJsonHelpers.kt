package io.github.jpicklyk.mcptask.current.application.tools.workflow

import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import kotlinx.serialization.json.*

/**
 * Gate-check helpers for note schema enforcement in [AdvanceItemTool].
 * Provides the "filled" key computation used by gate checks (start and complete triggers).
 *
 * Response-field computation (guidancePointer, noteProgress) has been consolidated
 * into the shared [io.github.jpicklyk.mcptask.current.application.service.computePhaseNoteContext] function.
 */
object NoteSchemaJsonHelpers {
    /**
     * A note is considered "filled" if it exists with a non-blank body.
     * This is the single source of truth for fill-check logic across all workflow tools.
     */
    fun buildFilledKeys(notes: List<io.github.jpicklyk.mcptask.current.domain.model.Note>): Set<String> =
        notes.filter { it.body.isNotBlank() }.map { it.key }.toSet()

    /**
     * Builds a JSON array describing which required notes are missing (unfilled).
     * Used by gate checks in [AdvanceItemTool] for start, complete, and cascade triggers.
     */
    fun buildMissingNotesArray(missingEntries: List<NoteSchemaEntry>): JsonArray =
        JsonArray(
            missingEntries.map { entry ->
                buildJsonObject {
                    put("key", JsonPrimitive(entry.key))
                    put("description", JsonPrimitive(entry.description))
                    entry.guidance?.let { put("guidance", JsonPrimitive(it)) }
                }
            }
        )
}
