package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.tools.toJsonString
import io.github.jpicklyk.mcptask.current.domain.model.DispatchProfile
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.SeatDefinition
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import kotlinx.serialization.json.*

/**
 * Result of schema lookup + JSON serialization for tool responses.
 *
 * @property schemaMatch True when the item's tags matched a configured note schema.
 * @property expectedNotes JSON array of schema entries, empty when no schema matched.
 */
data class SchemaResponseFields(
    val schemaMatch: Boolean,
    val expectedNotes: JsonArray
)

/**
 * Build expectedNotes JSON array from schema entries.
 *
 * Token-efficiency contract: the default serialization is reference-based — each entry
 * carries only {key, role, required, exists} (+ filled when [filledNoteKeys] is provided).
 * Static schema text (description, guidance, skill) is intentionally NOT included here;
 * agents fetch it on demand via `query_items` operation "schema" (see [buildFullSchemaEntriesJson]),
 * or receive full guidance in the two designated places (manage_notes itemContext and
 * gate-failure error payloads).
 *
 * Supports three shapes:
 * - Shape 1 (creation): exists=false for all, no filled field
 * - Shape 2 (transition): exists checked against existingNoteKeys
 * - Shape 3 (context): exists + filled checked against notes
 *
 * @param schema Schema entries, or null if no schema matches
 * @param existingNoteKeys Keys of notes that exist (empty = all false)
 * @param filledNoteKeys Keys of notes with non-blank body, or null to omit "filled" field
 * @param filterRole If non-null, only include entries matching this role
 * @param seatAware When true, every entry gains a `seat` field (the entry's declared owning seat
 *   name, or JSON null when unowned) — set by callers only when the resolved schema is seat-aware
 *   ([WorkItemSchema.isSeatAware]), so a seat-less schema's response stays byte-identical (A1).
 */
fun buildExpectedNotesJson(
    schema: List<NoteSchemaEntry>?,
    existingNoteKeys: Set<String> = emptySet(),
    filledNoteKeys: Set<String>? = null,
    filterRole: Role? = null,
    seatAware: Boolean = false
): JsonArray {
    if (schema == null) return JsonArray(emptyList())
    val entries = if (filterRole != null) schema.filter { it.role == filterRole } else schema
    return JsonArray(
        entries.map { entry ->
            buildJsonObject {
                put("key", JsonPrimitive(entry.key))
                put("role", JsonPrimitive(entry.role.toJsonString()))
                put("required", JsonPrimitive(entry.required))
                put("exists", JsonPrimitive(entry.key in existingNoteKeys))
                if (filledNoteKeys != null) {
                    put("filled", JsonPrimitive(entry.key in filledNoteKeys))
                }
                if (seatAware) {
                    put("seat", entry.seat?.let { JsonPrimitive(it) } ?: JsonNull)
                }
            }
        }
    )
}

/**
 * Build the FULL schema-entry JSON array: {key, role, required, description, guidance?, skill?, maxLength?}.
 *
 * This is the reference target for the keys-only default above. Used by the `query_items`
 * operation "schema" (get_schema), which is the one place agents fetch complete schema text.
 *
 * `maxLength` is included only when set on the entry — it is NOT part of the keys-only
 * default shape produced by [buildExpectedNotesJson].
 *
 * @param seatAware When true, every entry gains a `seat` field (declared owning seat name, or JSON
 *   null when unowned) — callers pass this only when the schema is seat-aware
 *   ([WorkItemSchema.isSeatAware]), preserving byte-identity for seat-less schemas (A1). Independent
 *   of [seatAware], an entry with a non-empty [NoteSchemaEntry.independentOf] always gains that field.
 */
fun buildFullSchemaEntriesJson(
    entries: List<NoteSchemaEntry>,
    seatAware: Boolean = false
): JsonArray =
    JsonArray(
        entries.map { entry ->
            buildJsonObject {
                put("key", JsonPrimitive(entry.key))
                put("role", JsonPrimitive(entry.role.toJsonString()))
                put("required", JsonPrimitive(entry.required))
                put("description", JsonPrimitive(entry.description))
                entry.guidance?.let { put("guidance", JsonPrimitive(it)) }
                entry.skill?.let { put("skill", JsonPrimitive(it)) }
                entry.maxLength?.let { put("maxLength", JsonPrimitive(it)) }
                if (seatAware) {
                    put("seat", entry.seat?.let { JsonPrimitive(it) } ?: JsonNull)
                }
                if (entry.independentOf.isNotEmpty()) {
                    put("independentOf", JsonArray(entry.independentOf.map { JsonPrimitive(it) }))
                }
            }
        }
    )

/**
 * Build both schemaMatch and expectedNotes for tool responses.
 * Convenience wrapper for creation responses (exists=false, no filled).
 *
 * @param schema Schema entries, or null if no schema matches
 */
fun buildSchemaResponseFields(schema: List<NoteSchemaEntry>?): SchemaResponseFields =
    SchemaResponseFields(
        schemaMatch = schema != null,
        expectedNotes = buildExpectedNotesJson(schema)
    )

/**
 * Overload of [buildExpectedNotesJson] that accepts a [WorkItemSchema] instead of a raw list.
 * Delegates to the primary overload using [WorkItemSchema.notes].
 *
 * @param schema The [WorkItemSchema] whose notes to serialize
 * @param existingNoteKeys Keys of notes that exist (empty = all false)
 * @param filledNoteKeys Keys of notes with non-blank body, or null to omit "filled" field
 * @param filterRole If non-null, only include entries matching this role
 * @param seatAware See the primary overload's `seatAware` parameter.
 */
fun buildExpectedNotesJson(
    schema: WorkItemSchema,
    existingNoteKeys: Set<String> = emptySet(),
    filledNoteKeys: Set<String>? = null,
    filterRole: Role? = null,
    seatAware: Boolean = false
): JsonArray = buildExpectedNotesJson(schema.notes, existingNoteKeys, filledNoteKeys, filterRole, seatAware)

/**
 * Overload of [buildSchemaResponseFields] that accepts a [WorkItemSchema] instead of a raw list.
 * Delegates to the primary overload using [WorkItemSchema.notes].
 *
 * @param schema The [WorkItemSchema] to serialize, or null for schema-free mode
 */
fun buildSchemaResponseFields(schema: WorkItemSchema?): SchemaResponseFields = buildSchemaResponseFields(schema?.notes)

/**
 * Builds the `dispatch` JSON object for a single resolved [DispatchProfile]: `{agent?, model?,
 * effort?}`, each field present only when non-null. Used by `advance_item`'s per-transition
 * result and `get_context`'s item mode, both of which resolve a single profile for one role via
 * [io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext.resolveDispatchProfile].
 */
fun buildDispatchProfileJson(profile: DispatchProfile): JsonObject =
    buildJsonObject {
        profile.agent?.let { put("agent", JsonPrimitive(it)) }
        profile.model?.let { put("model", JsonPrimitive(it)) }
        profile.effort?.let { put("effort", JsonPrimitive(it)) }
    }

/**
 * Builds the per-phase `dispatch` JSON object for `query_items`'s `schema` operation:
 * `{"queue"|"work"|"review": {agent?, model?, effort?}}`, one entry per role present in
 * [dispatchByRole] (lowercase role name keys, via [Role.toJsonString]). Returns null — never an
 * empty object — when [dispatchByRole] is empty; callers omit the `dispatch` key entirely in that
 * case (P9: absent, never null/empty, when there is nothing to report).
 */
fun buildDispatchByRoleJson(dispatchByRole: Map<Role, DispatchProfile>): JsonObject? {
    if (dispatchByRole.isEmpty()) return null
    return buildJsonObject {
        dispatchByRole.forEach { (role, profile) ->
            put(role.toJsonString(), buildDispatchProfileJson(profile))
        }
    }
}

/**
 * Builds the `resources` JSON array for `query_items`'s `schema` operation:
 * `[{key, mode, ttlSeconds?}]`. Returns null — never an empty array — when [resources] is empty;
 * callers omit the `resources` key entirely in that case, same omit-when-empty contract as
 * [buildDispatchByRoleJson].
 */
fun buildResourcesJson(resources: List<ResourceRequirement>): JsonArray? {
    if (resources.isEmpty()) return null
    return JsonArray(
        resources.map { requirement ->
            buildJsonObject {
                put("key", JsonPrimitive(requirement.key))
                put("mode", JsonPrimitive(requirement.mode.name.lowercase()))
                requirement.ttlSeconds?.let { put("ttlSeconds", JsonPrimitive(it)) }
            }
        }
    )
}

/**
 * Builds the `seats` JSON array for `query_items`'s `schema` operation and `get_context`'s item
 * mode: `[{name, phase, enters?, after?, readsExclude?}]`, in [seats]' order (merged order for a
 * trait-merged schema). `enters` is included only when `true`; `after`/`readsExclude` only when
 * non-empty. Returns null — never an empty array — when [seats] is empty; callers omit the `seats`
 * key entirely in that case (same omit-when-empty contract as [buildDispatchByRoleJson]).
 */
fun buildSeatsJson(seats: List<SeatDefinition>): JsonArray? {
    if (seats.isEmpty()) return null
    return JsonArray(
        seats.map { seat ->
            buildJsonObject {
                put("name", JsonPrimitive(seat.name))
                put("phase", JsonPrimitive(seat.phase.toJsonString()))
                if (seat.enters) put("enters", JsonPrimitive(true))
                if (seat.after.isNotEmpty()) put("after", JsonArray(seat.after.map { JsonPrimitive(it) }))
                if (seat.readsExclude.isNotEmpty()) {
                    put("readsExclude", JsonArray(seat.readsExclude.map { JsonPrimitive(it) }))
                }
            }
        }
    )
}

/**
 * Builds the FLAT per-seat `dispatchBySeat` JSON for a SINGLE phase's profile map used by
 * `get_context`'s item mode (current phase only): `{<seat>: {agent?, model?, effort?}}`. A profile
 * with every field resolving to null (an explicit clear via [io.github.jpicklyk.mcptask.current.domain.model.SeatDispatchOverride])
 * still emits `{}` for that seat, never omits it. Returns null — never an empty object — when
 * [profiles] is empty; callers omit the `dispatchBySeat` key entirely in that case.
 */
fun buildDispatchBySeatFlatJson(profiles: Map<String, DispatchProfile>): JsonObject? {
    if (profiles.isEmpty()) return null
    return buildJsonObject {
        profiles.forEach { (seat, profile) -> put(seat, buildDispatchProfileJson(profile)) }
    }
}

/**
 * Builds the FULL per-phase, per-seat `dispatchBySeat` JSON for `query_items`'s `schema` operation:
 * `{"queue"|"work"|"review": {<seat>: {agent?, model?, effort?}}}`, phases in [Role] declaration
 * order ([Role.entries]: queue, work, review, ...), each phase included only when it has at least
 * one seat profile — see [buildDispatchBySeatFlatJson] for the per-phase shape. Returns null — never
 * an empty object — when [dispatchBySeat] is empty.
 */
fun buildDispatchBySeatJson(dispatchBySeat: Map<Role, Map<String, DispatchProfile>>): JsonObject? {
    if (dispatchBySeat.isEmpty()) return null
    var wroteAny = false
    val result =
        buildJsonObject {
            for (role in Role.entries) {
                val profiles = dispatchBySeat[role] ?: continue
                val flat = buildDispatchBySeatFlatJson(profiles) ?: continue
                put(role.toJsonString(), flat)
                wroteAny = true
            }
        }
    return if (wroteAny) result else null
}

/**
 * Builds the `missingBySeat` JSON object shared by `get_context`'s `gateStatus`, `advance_item`'s
 * per-transition gate-failure payload, and their REST counterparts: `{<seat>: [keys], ...,
 * "unowned": [keys]}`. Returns null only when [missingBySeat] itself is null (a seat-less schema, or
 * a check that doesn't apply — see [computeMissingBySeat]); an empty map still serializes as `{}`,
 * never omitted, when the schema is seat-aware but nothing is missing.
 */
fun buildMissingBySeatJson(missingBySeat: Map<String, List<String>>?): JsonObject? {
    if (missingBySeat == null) return null
    return buildJsonObject {
        missingBySeat.forEach { (seat, keys) -> put(seat, JsonArray(keys.map { JsonPrimitive(it) })) }
    }
}
