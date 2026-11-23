package io.github.jpicklyk.mcptask.current.model.schema

import io.github.jpicklyk.mcptask.current.model.item.LifecycleMode
import io.github.jpicklyk.mcptask.current.model.item.Role
import io.github.jpicklyk.mcptask.current.model.note.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.model.note.OwningSeat

/**
 * A schema describing the expected notes and lifecycle behavior for a type of work item.
 *
 * Schemas are declared in `.taskorchestrator/config.yaml` and associated with work item types.
 * They control gate enforcement during role transitions by specifying which notes must be filled
 * at each workflow phase.
 *
 * @property type The work item type this schema applies to (e.g., "feature-task", "bug-fix")
 * @property lifecycleMode Controls automatic vs. manual lifecycle transitions for matching items
 * @property notes The ordered list of note schema entries required across all phases
 * @property seats This schema's own `seats:` list (schema-level only — trait seats are merged in
 *   at resolve time by
 *   [io.github.jpicklyk.mcptask.current.application.config.LayeredConfig.mergeTraits]; a raw
 *   [WorkItemSchema] straight from parsing carries only its own seats). Empty means this schema is
 *   not seat-aware (see [isSeatAware]) — every seat-related response field stays absent for such a
 *   schema (byte-identity with pre-A1 responses).
 */
data class WorkItemSchema(
    val type: String,
    val lifecycleMode: LifecycleMode = LifecycleMode.AUTO,
    val notes: List<NoteSchemaEntry> = emptyList(),
    val defaultTraits: List<String> = emptyList(),
    val seats: List<SeatDefinition> = emptyList()
) {
    /**
     * Returns true if this schema has any note entry assigned to the REVIEW phase.
     * Used to determine whether a `start` trigger from WORK should advance to REVIEW or
     * jump directly to TERMINAL.
     */
    fun hasReviewPhase(): Boolean = notes.any { it.role == Role.REVIEW }

    /**
     * Returns the list of required note entries for the given [role].
     * Non-required entries are excluded.
     *
     * @param role The workflow phase to filter by
     * @return Required [NoteSchemaEntry] items for that phase
     */
    fun requiredNotesForRole(role: Role): List<NoteSchemaEntry> = notes.filter { it.role == role && it.required }

    /** True when this (already trait-merged, if applicable) schema declares any seats at all. */
    fun isSeatAware(): Boolean = seats.isNotEmpty()

    /** The seat owning the note keyed [key] in this schema (see [OwningSeat]), or null (unowned, or no such note). */
    fun owningSeatOf(key: String): String? = OwningSeat.of(notes.firstOrNull { it.key == key }, seats)

    /** Keys of the notes owned by [seat] in this schema (see [OwningSeat.keysOf]). */
    fun noteKeysOwnedBy(seat: String): Set<String> = OwningSeat.keysOf(seat, notes, seats)

    /** [seats] filtered to the given [role], in declared/merged order. */
    fun seatsForRole(role: Role): List<SeatDefinition> = seats.filter { it.phase == role }
}
