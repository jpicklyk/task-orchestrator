package io.github.jpicklyk.mcptask.current.domain.model

/**
 * A single entry in a YAML-defined note schema.
 *
 * Note schemas are declared in `.taskorchestrator/config.yaml` under the `note_schemas` key.
 * Each schema is a list of NoteSchemaEntry objects, grouped under a tag name that acts as the schema key.
 *
 * Example YAML:
 * ```yaml
 * note_schemas:
 *   feature-task:
 *     - key: acceptance-criteria
 *       role: queue
 *       required: true
 *       description: "Acceptance criteria for this task"
 *       guidance: "List each criterion as a bullet point"
 *     - key: implementation-notes
 *       role: work
 *       required: true
 *       description: "Notes on the implementation approach"
 *     - key: test-coverage
 *       role: review
 *       required: false
 *       description: "Test coverage summary"
 * ```
 *
 * @property key Unique identifier for the note within a schema (e.g., "acceptance-criteria")
 * @property role Workflow phase this note belongs to: "queue", "work", or "review"
 * @property required Whether this note must be filled before advancing past its phase
 * @property description Human-readable description of what the note should contain
 * @property guidance Optional detailed instructions for filling the note
 */
data class NoteSchemaEntry(
    val key: String,
    val role: String,
    val required: Boolean = false,
    val description: String = "",
    val guidance: String? = null
)
