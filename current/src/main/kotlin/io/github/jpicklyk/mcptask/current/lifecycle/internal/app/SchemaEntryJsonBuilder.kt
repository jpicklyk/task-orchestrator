package io.github.jpicklyk.mcptask.current.lifecycle.internal.app

import io.github.jpicklyk.mcptask.current.lifecycle.internal.domain.PhaseNoteContext
import io.github.jpicklyk.mcptask.current.model.item.Role
import io.github.jpicklyk.mcptask.current.model.lease.ResourceRequirement
import io.github.jpicklyk.mcptask.current.model.note.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.model.note.OwningSeat
import io.github.jpicklyk.mcptask.current.model.schema.DispatchProfile
import io.github.jpicklyk.mcptask.current.model.schema.SeatDefinition
import io.github.jpicklyk.mcptask.current.model.schema.SkillMode
import io.github.jpicklyk.mcptask.current.model.schema.WorkItemSchema
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
 * agents fetch it on demand via `items_read` `select=schema` (see [buildFullSchemaEntriesJson]),
 * or receive full guidance in gate-failure error payloads.
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
                if (entry.shared) put("shared", JsonPrimitive(true))
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
 * This is the reference target for the keys-only default above. Used by `items_read`
 * `select=schema`, which is the one place agents fetch complete schema text.
 *
 * `maxLength` is included only when set on the entry — it is NOT part of the keys-only
 * default shape produced by [buildExpectedNotesJson].
 *
 * @param seats The schema's declared seats. The schema is seat-aware when this is non-empty, and then every entry
 *   gains a `seat` field (the seat name the entry declares, or JSON null). Every entry of every schema, seat-aware or
 *   not, gains `owningSeat`: its [OwningSeat] (a string, or JSON null when unowned), always present. Independent of
 *   the seats, an entry with a non-empty [NoteSchemaEntry.independentOf] always gains that field.
 */
fun buildFullSchemaEntriesJson(
    entries: List<NoteSchemaEntry>,
    seats: List<SeatDefinition> = emptyList()
): JsonArray {
    val seatAware = seats.isNotEmpty()
    return JsonArray(
        entries.map { entry ->
            buildJsonObject {
                put("key", JsonPrimitive(entry.key))
                put("role", JsonPrimitive(entry.role.toJsonString()))
                put("required", JsonPrimitive(entry.required))
                put("description", JsonPrimitive(entry.description))
                entry.guidance?.let { put("guidance", JsonPrimitive(it)) }
                entry.skill?.let { put("skill", JsonPrimitive(it)) }
                if (entry.skills.size >= 2) put("skills", JsonArray(entry.skills.map { JsonPrimitive(it) }))
                if (entry.skillMode == SkillMode.METHOD_ON_DIFF) put("skillMode", JsonPrimitive(entry.skillMode.wire))
                entry.maxLength?.let { put("maxLength", JsonPrimitive(it)) }
                if (seatAware) {
                    put("seat", entry.seat?.let { JsonPrimitive(it) } ?: JsonNull)
                }
                put("owningSeat", OwningSeat.of(entry, seats)?.let { JsonPrimitive(it) } ?: JsonNull)
                if (entry.independentOf.isNotEmpty()) {
                    put("independentOf", JsonArray(entry.independentOf.map { JsonPrimitive(it) }))
                }
                if (entry.shared) {
                    put("shared", JsonPrimitive(true))
                }
                entry.lane?.let { lane ->
                    put(
                        "lane",
                        buildJsonObject {
                            put("output", JsonPrimitive(lane.output.wire))
                            if (lane.after.isNotEmpty()) put("after", JsonArray(lane.after.map { JsonPrimitive(it) }))
                        }
                    )
                }
            }
        }
    )
}

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
 * effort?}`, each field present only when non-null. Used by the `advance` `transition` per-transition
 * result and `items_read` `select=context` item mode, both of which resolve a single profile for one role via
 * [io.github.jpicklyk.mcptask.current.config.EffectiveConfigResolver.resolveDispatchProfile].
 */
fun buildDispatchProfileJson(profile: DispatchProfile): JsonObject =
    buildJsonObject {
        profile.agent?.let { put("agent", JsonPrimitive(it)) }
        profile.model?.let { put("model", JsonPrimitive(it)) }
        profile.effort?.let { put("effort", JsonPrimitive(it)) }
    }

/**
 * Builds the per-phase `dispatch` JSON object for `items_read` `select=schema`:
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
 * Builds the `resources` JSON array for `items_read` `select=schema`:
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
 * Builds the `seats` JSON array for `items_read` `select=schema` and `select=context` item
 * mode: `[{name, phase, enters?, writes, after?, readsExclude?, transitions?, rules?, skills?}]`, in [seats]' order (merged order for a
 * trait-merged schema). `enters` is included only when `true`; `writes` is always present (the declared value, else true in the work phase: absent must never mean false, since an older server omits it); `after`/`readsExclude` only when
 * non-empty; `transitions`, `rules` and `skills` only when declared (an empty declared list is served as `[]`). Returns null — never an empty array — when [seats] is empty; callers omit the `seats`
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
                put("writes", JsonPrimitive(seat.effectiveWrites))
                if (seat.after.isNotEmpty()) put("after", JsonArray(seat.after.map { JsonPrimitive(it) }))
                if (seat.readsExclude.isNotEmpty()) {
                    put("readsExclude", JsonArray(seat.readsExclude.map { JsonPrimitive(it) }))
                }
                seat.transitions?.let { declared -> put("transitions", JsonArray(declared.map { JsonPrimitive(it) })) }
                seat.rules?.let { declared -> put("rules", JsonArray(declared.map { JsonPrimitive(it) })) }
                seat.skills?.let { declared -> put("skills", JsonArray(declared.map { JsonPrimitive(it) })) }
            }
        }
    )
}

/**
 * Builds the FLAT per-seat `dispatchBySeat` JSON for a SINGLE phase's profile map used by
 * `items_read` `select=context` item mode (current phase only): `{<seat>: {agent?, model?, effort?}}`. A profile
 * with every field resolving to null (an explicit clear via [io.github.jpicklyk.mcptask.current.model.run.SeatDispatchOverride])
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
 * Builds the FULL per-phase, per-seat `dispatchBySeat` JSON for `items_read` `select=schema`:
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
 * Builds the `missingBySeat` JSON object shared by `items_read` `select=context` `gateStatus` and the
 * `advance` `transition` gate-failure payload: `{<seat>: [keys], ...,
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

/** Lowercase wire name of a [Role] (`queue`, `work`, ...), as every schema-entry JSON field spells it. */
private fun Role.toJsonString(): String = name.lowercase()

/**
 * Adds the skill members of a [PhaseNoteContext] right after `skillPointer`: `skillPointers` only when the note names
 * two or more skills, `skillMode` only when it is [SkillMode.METHOD_ON_DIFF]. A single-skill note serves unchanged.
 */
fun JsonObjectBuilder.putSkillExtras(phase: PhaseNoteContext) {
    if (phase.skillPointers.size >= 2) put("skillPointers", JsonArray(phase.skillPointers.map { JsonPrimitive(it) }))
    if (phase.skillMode == SkillMode.METHOD_ON_DIFF) put("skillMode", JsonPrimitive(SkillMode.METHOD_ON_DIFF.wire))
}
