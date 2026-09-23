package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.tools.toJsonString
import io.github.jpicklyk.mcptask.current.domain.model.DispatchProfile
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement
import io.github.jpicklyk.mcptask.current.domain.model.Role
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
 */
fun buildExpectedNotesJson(
    schema: List<NoteSchemaEntry>?,
    existingNoteKeys: Set<String> = emptySet(),
    filledNoteKeys: Set<String>? = null,
    filterRole: Role? = null
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
 */
fun buildFullSchemaEntriesJson(entries: List<NoteSchemaEntry>): JsonArray =
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
 */
fun buildExpectedNotesJson(
    schema: WorkItemSchema,
    existingNoteKeys: Set<String> = emptySet(),
    filledNoteKeys: Set<String>? = null,
    filterRole: Role? = null
): JsonArray = buildExpectedNotesJson(schema.notes, existingNoteKeys, filledNoteKeys, filterRole)

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
