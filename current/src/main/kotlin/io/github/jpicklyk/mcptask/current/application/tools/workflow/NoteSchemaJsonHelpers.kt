package io.github.jpicklyk.mcptask.current.application.tools.workflow

import io.github.jpicklyk.mcptask.current.domain.model.IndependenceViolation
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
                    entry.skill?.let { put("skill", JsonPrimitive(it)) }
                }
            }
        )

    /**
     * Builds the JSON array for A2 independence-attestation `violations` — actor-free by
     * construction (never an actor id, proof, or claim). `seat`/`conflictingSeat` are omitted
     * when null; `waived` is omitted unless true. Returns null when [violations] is null (meaning
     * the `violations` key itself is omitted from the response, per the A2 contract); an empty
     * list still serializes as `[]`.
     */
    fun buildViolationsArray(violations: List<IndependenceViolation>?): JsonArray? {
        if (violations == null) return null
        return JsonArray(violations.map { it.toViolationJson() })
    }

    /**
     * Builds the `violations` array for every A2 surface OTHER than `gateStatus`
     * (advance success, cascade events, 422 details, and advance_item/complete_tree failure AND
     * applied entries): the key is emitted only when the list is non-null AND non-empty. Unlike
     * [buildViolationsArray] (used solely by `gateStatus` / get_context / REST `/gate`, which
     * emits the key for a non-null EMPTY list too), an empty list here returns null so the caller's
     * `?.let { put(...) }` omits the key entirely.
     */
    fun buildViolationsArrayNonEmpty(violations: List<IndependenceViolation>?): JsonArray? {
        if (violations.isNullOrEmpty()) return null
        return JsonArray(violations.map { it.toViolationJson() })
    }

    private fun IndependenceViolation.toViolationJson(): JsonObject =
        buildJsonObject {
            put("key", JsonPrimitive(key))
            seat?.let { put("seat", JsonPrimitive(it)) }
            put("constraint", JsonPrimitive(constraint.toJsonString()))
            conflictingSeat?.let { put("conflictingSeat", JsonPrimitive(it)) }
            if (waived) put("waived", JsonPrimitive(true))
        }
}
