package io.github.jpicklyk.mcptask.current.model.note

import io.github.jpicklyk.mcptask.current.model.item.Role
import io.github.jpicklyk.mcptask.current.model.schema.ReviewLane
import io.github.jpicklyk.mcptask.current.model.schema.SeatDefinition
import io.github.jpicklyk.mcptask.current.model.schema.SkillMode
import io.github.jpicklyk.mcptask.current.model.schema.WorkItemSchema

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
 *   (`notes_write` over MCP and `POST /api/v1/ops/notes_write`, `items_write` `create_tree`) enforces it through the shared
 *   note command service, after body/bodyFromFile resolution and CRLF-to-LF normalization, per the
 *   configured `note_limits.mode` (warn or reject). Null means no limit is enforced.
 * @property seat Name of the [io.github.jpicklyk.mcptask.current.model.schema.SeatDefinition] that
 *   owns filling this note (an orchestration signal only, A1a). Null means the note has no declared
 *   owner; in a seat-aware schema (see [WorkItemSchema.isSeatAware]) that surfaces as "unowned" —
 *   see [io.github.jpicklyk.mcptask.current.model.note.OwningSeat] for the rule and
 *   `io.github.jpicklyk.mcptask.current.lifecycle.internal.domain.SeatOwnership` for the gate buckets.
 * @property independentOf Seat names this note's authorship must be independent of (an
 *   orchestration signal only; A1a parses and serves it, A2 enforces it).
 * @property shared When true the key is exempt from note ownership: any caller may upsert or delete it (last writer
 *   wins). Default false; an off-schema key is never shared.
 * @property skills Every skill the note names, in invocation order. [skill] is always the first one, so a single
 *   skill (a string or a one-element list in config) serves exactly as before.
 * @property skillMode How the skills are used; see [SkillMode]. Default [SkillMode.INVOKE].
 * @property lane Declared review-lane contract (output and order); see [ReviewLane]. Null means not declared (the
 *   lane reports a verdict and is unordered). Only review-role entries carry one.
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
    val independentOf: List<String> = emptyList(),
    val shared: Boolean = false,
    val skills: List<String> = listOfNotNull(skill),
    val skillMode: SkillMode = SkillMode.INVOKE,
    val lane: ReviewLane? = null
) {
    init {
        require(skills.firstOrNull() == skill) { "skill must be the first of skills" }
    }
}

/** The entries a note gate can require, in schema order: the one selector of "which notes are required". */
fun Iterable<NoteSchemaEntry>.requiredEntries(): List<NoteSchemaEntry> = filter { it.required }
