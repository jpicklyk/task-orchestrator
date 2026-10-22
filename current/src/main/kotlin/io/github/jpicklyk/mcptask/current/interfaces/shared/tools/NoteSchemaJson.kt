package io.github.jpicklyk.mcptask.current.interfaces.shared.tools

import io.github.jpicklyk.mcptask.current.domain.model.IndependenceViolation
import kotlinx.serialization.json.*

/**
 * JSON builders for the A2 independence-attestation `violations` array, shared by the `advance` transition op,
 * the `complete_tree` op and the `items_read` `select=context` gate view. The "filled" note predicate itself is
 * [io.github.jpicklyk.mcptask.current.application.service.GatePredicate.filledNoteKeys] (AR-92: one predicate).
 */
object NoteSchemaJson {
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
     * (advance success, cascade events, `gate_blocked` details, and `advance` `transition` /
     * `complete_tree` failure AND applied entries): the key is emitted only when the list is non-null AND non-empty. Unlike
     * [buildViolationsArray] (used solely by `gateStatus` of `items_read` `select=context`, which
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
