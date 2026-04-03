package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.tools.toJsonString
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.Role
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
                put("description", JsonPrimitive(entry.description))
                entry.guidance?.let { put("guidance", JsonPrimitive(it)) }
                put("exists", JsonPrimitive(entry.key in existingNoteKeys))
                if (filledNoteKeys != null) {
                    put("filled", JsonPrimitive(entry.key in filledNoteKeys))
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
fun buildSchemaResponseFields(
    schema: List<NoteSchemaEntry>?
): SchemaResponseFields =
    SchemaResponseFields(
        schemaMatch = schema != null,
        expectedNotes = buildExpectedNotesJson(schema)
    )
