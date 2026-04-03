package io.github.jpicklyk.mcptask.current.domain.model

/**
 * Represents a note schema for a work item type, as declared in `.taskorchestrator/config.yaml`.
 *
 * A WorkItemSchema groups the list of required/optional notes for a given tag (schema key),
 * and optionally lists trait names that apply by default to items of this type.
 *
 * Example config:
 * ```yaml
 * note_schemas:
 *   feature-implementation:
 *     default_traits: [needs-security-review]
 *     notes:
 *       - key: specification
 *         role: queue
 *         required: true
 *         description: "Feature specification"
 * ```
 *
 * @property name The tag name that identifies this schema (e.g., "feature-implementation")
 * @property notes The list of note schema entries for this schema
 * @property defaultTraits Optional list of trait names that apply by default to items matching this schema
 */
data class WorkItemSchema(
    val name: String,
    val notes: List<NoteSchemaEntry> = emptyList(),
    val defaultTraits: List<String> = emptyList()
)
