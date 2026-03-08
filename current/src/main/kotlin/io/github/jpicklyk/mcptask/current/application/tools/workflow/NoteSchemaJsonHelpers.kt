package io.github.jpicklyk.mcptask.current.application.tools.workflow

import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import kotlinx.serialization.json.*

/**
 * Shared JSON builders for note schema data used by both [AdvanceItemTool] and [GetContextTool].
 * Centralizes the "filled" definition and progress computation to keep both tools consistent.
 */
object NoteSchemaJsonHelpers {

    /**
     * A note is considered "filled" if it exists with a non-blank body.
     * This is the single source of truth for fill-check logic across all workflow tools.
     */
    fun buildFilledKeys(notes: List<io.github.jpicklyk.mcptask.current.domain.model.Note>): Set<String> =
        notes.filter { it.body.isNotBlank() }.map { it.key }.toSet()

    /**
     * Returns the guidance text for the first unfilled required note in the given role,
     * or null if all required notes are filled (or none exist).
     */
    fun findGuidancePointer(
        schema: List<NoteSchemaEntry>,
        roleStr: String,
        filledKeys: Set<String>
    ): String? {
        return schema
            .filter { it.role == roleStr && it.required && it.key !in filledKeys }
            .firstOrNull()?.guidance
    }

    /**
     * Builds a JSON array describing which required notes are missing (unfilled).
     * Used by gate checks in [AdvanceItemTool] for start, complete, and cascade triggers.
     */
    fun buildMissingNotesArray(missingEntries: List<NoteSchemaEntry>): JsonArray =
        JsonArray(missingEntries.map { entry ->
            buildJsonObject {
                put("key", JsonPrimitive(entry.key))
                put("description", JsonPrimitive(entry.description))
                entry.guidance?.let { put("guidance", JsonPrimitive(it)) }
            }
        })

    /**
     * Builds a `noteProgress` JSON object with `{filled, remaining, total}` counts
     * for required notes in the given role. Callers should guard for null schema
     * before calling — this function always returns a non-null object.
     */
    fun buildNoteProgress(
        schema: List<NoteSchemaEntry>,
        roleStr: String,
        filledKeys: Set<String>
    ): JsonObject {
        val requiredForRole = schema.filter { it.role == roleStr && it.required }
        val filled = requiredForRole.count { it.key in filledKeys }
        return buildJsonObject {
            put("filled", JsonPrimitive(filled))
            put("remaining", JsonPrimitive(requiredForRole.size - filled))
            put("total", JsonPrimitive(requiredForRole.size))
        }
    }
}
