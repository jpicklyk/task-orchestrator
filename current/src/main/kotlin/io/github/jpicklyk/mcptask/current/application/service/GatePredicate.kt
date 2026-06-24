package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema

/**
 * Pure, JSON-free gate-check predicate shared by the MCP advance tool and the REST advance route.
 *
 * The gate predicate answers a single question: given a [WorkItemSchema], the item's current
 * [Role], and the set of note keys that are currently "filled" (non-blank body), which REQUIRED
 * note schema entries are still missing for the relevant trigger?
 *
 * Two trigger semantics mirror the historical inline logic in `AdvanceItemTool`:
 * - **start**: only required notes for the item's CURRENT role must be filled.
 * - **complete** (and cascade-to-terminal): ALL required notes across ALL phases must be filled.
 *
 * The function returns the structured list of missing [NoteSchemaEntry] objects (preserving key,
 * description, guidance, and skill), so each caller can build its own response shape
 * (MCP JSON `missingNotes` array, or REST `gateMissingNotes` DTO). No JSON is produced here.
 *
 * A note is "filled" when it exists with a non-blank body — see [filledNoteKeys].
 */
object GatePredicate {
    /**
     * Compute the set of note keys that count as "filled" — i.e. notes that exist with a
     * non-blank body. This is the single source of truth for fill-check logic, matching
     * `NoteSchemaJsonHelpers.buildFilledKeys`.
     */
    fun filledNoteKeys(notes: List<Note>): Set<String> = notes.filter { it.body.isNotBlank() }.map { it.key }.toSet()

    /**
     * Required notes missing for a **start** trigger: only entries whose role matches
     * [currentRole] are considered.
     *
     * @return missing required entries for the current phase, in schema order. Empty when the
     *   gate passes (or when the schema declares no required notes for the current phase).
     */
    fun missingForStart(
        schema: WorkItemSchema,
        currentRole: Role,
        filledKeys: Set<String>
    ): List<NoteSchemaEntry> {
        val requiredForCurrentPhase = schema.notes.filter { it.role == currentRole && it.required }
        return requiredForCurrentPhase.filter { it.key !in filledKeys }
    }

    /**
     * Required notes missing for a **complete** trigger (or a cascade-to-terminal): ALL required
     * entries across every phase are considered.
     *
     * @return missing required entries across all phases, in schema order. Empty when the gate
     *   passes (or when the schema declares no required notes at all).
     */
    fun missingForComplete(
        schema: WorkItemSchema,
        filledKeys: Set<String>
    ): List<NoteSchemaEntry> {
        val allRequired = schema.notes.filter { it.required }
        return allRequired.filter { it.key !in filledKeys }
    }
}
