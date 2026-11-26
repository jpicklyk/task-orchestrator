package io.github.jpicklyk.mcptask.current.model.note

import io.github.jpicklyk.mcptask.current.model.schema.SeatDefinition

/**
 * The one rule for which seat owns a note (glossary: "Owning seat"). Every site that asks who owns a note (gate buckets,
 * note-write ownership, the independence check, config lint and the unowned-required-note warning of config validation)
 * goes through here.
 *
 * A note's owning seat is the seat its schema entry names ([NoteSchemaEntry.seat]):
 * - when [seats] is empty (the schema is not seat-aware) the named seat stands as written;
 * - otherwise when a seat of that name is declared for the note's own phase ([NoteSchemaEntry.role]), or when the name is
 *   [ORCHESTRATOR]: the orchestrator is not a seat, so its labelled notes are owned in every seat-aware schema, declared
 *   seat or not, whatever phase they sit in.
 * Any other note (no seat named, an undeclared seat, a seat declared for another phase) has no owning seat: it is
 * unowned.
 */
object OwningSeat {
    /** The reserved owner of the notes the orchestrator writes itself (glossary: "Orchestrator note"); never a seat. */
    const val ORCHESTRATOR: String = "orchestrator"

    /** The seat owning [entry] under [seats], or null (unowned, or [entry] is null). */
    fun of(
        entry: NoteSchemaEntry?,
        seats: List<SeatDefinition>
    ): String? {
        val name = entry?.seat ?: return null
        if (seats.isEmpty()) return name
        if (name == ORCHESTRATOR) return name
        return name.takeIf { seats.any { it.name == name && it.phase == entry.role } }
    }

    /** Keys of the entries in [notes] whose owning seat is [seat] under [seats]. */
    fun keysOf(
        seat: String,
        notes: List<NoteSchemaEntry>,
        seats: List<SeatDefinition>
    ): Set<String> = notes.filter { of(it, seats) == seat }.mapTo(LinkedHashSet()) { it.key }
}
