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
 * @property role Workflow phase this note belongs to (QUEUE, WORK, or REVIEW)
 * @property required Whether this note must be filled before advancing past its phase
 * @property description Human-readable description of what the note should contain
 * @property guidance Optional detailed instructions for filling the note
 * @property maxLength Optional maximum note body length in characters. When set, every note write path
 *   (`manage_notes`, REST `PUT /items/{id}/notes/{key}`, `create_work_tree`) enforces it through the shared
 *   note command service, after body/bodyFromFile resolution and CRLF-to-LF normalization, per the
 *   configured `note_limits.mode` (warn or reject). Null means no limit is enforced.
 * @property seat Name of the [io.github.jpicklyk.mcptask.current.domain.model.SeatDefinition] that
 *   owns filling this note (an orchestration signal only, A1a). Null means the note has no declared
 *   owner; in a seat-aware schema (see [WorkItemSchema.isSeatAware]) that surfaces as "unowned" —
 *   see `io.github.jpicklyk.mcptask.current.application.service.SeatOwnership`.
 * @property independentOf Seat names this note's authorship must be independent of (an
 *   orchestration signal only; A1a parses and serves it, A2 enforces it).
 */
data class NoteSchemaEntry(
    val key: String,
    val role: Role,
    val required: Boolean = false,
    val description: String = "",
    val guidance: String? = null,
    val skill: String? = null,
    val maxLength: Int? = null,
    val seat: String? = null,
    val independentOf: List<String> = emptyList()
)
