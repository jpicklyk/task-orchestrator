package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.Role

/**
 * Snapshot of an item's required-note status for its current workflow phase.
 *
 * Computed from the item's role, its note schema, and the current state of its notes.
 * Used by ManageNotesTool (itemContext), GetContextTool (gateStatus + guidancePointer),
 * and GetContextTool.findStalledItems.
 *
 * @property guidancePointer Guidance text for the first unfilled required note, or null if all filled
 * @property missingKeys Keys of required notes that are missing or have blank bodies
 * @property filled Count of required notes in this phase that have non-blank bodies
 * @property remaining Count of required notes in this phase that are missing or blank
 * @property total Total count of required notes in this phase
 */
data class PhaseNoteContext(
    val guidancePointer: String?,
    val missingKeys: List<String>,
    val filled: Int,
    val remaining: Int,
    val total: Int
)

/**
 * Computes the [PhaseNoteContext] for an item given its current role, schema, and notes.
 *
 * Returns null when no meaningful phase context exists:
 * - Terminal items (cannot advance)
 * - Items with no matching schema (schema-free mode)
 *
 * @param role The item's current role
 * @param schema The note schema entries for the item's tags, or null if no schema matches
 * @param notesByKey Map of existing notes keyed by their note key
 */
fun computePhaseNoteContext(
    role: Role,
    schema: List<NoteSchemaEntry>?,
    notesByKey: Map<String, Note>
): PhaseNoteContext? {
    if (role == Role.TERMINAL || schema == null) return null

    val roleStr = role.name.lowercase()
    val required = schema.filter { it.role == roleStr && it.required }
    val missing =
        required.filter { entry ->
            val note = notesByKey[entry.key]
            note == null || note.body.isBlank()
        }

    return PhaseNoteContext(
        guidancePointer = missing.firstOrNull()?.guidance,
        missingKeys = missing.map { it.key },
        filled = required.size - missing.size,
        remaining = missing.size,
        total = required.size
    )
}
