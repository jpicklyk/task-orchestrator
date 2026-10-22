package io.github.jpicklyk.mcptask.current.application.config

import io.github.jpicklyk.mcptask.current.domain.model.DispatchProfile
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.ResourceMode
import io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.SeatDefinition
import io.github.jpicklyk.mcptask.current.domain.model.SeatDispatchOverride
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * The ONE place every cross-trait merge happens: schema notes and seats, resource requirements,
 * dispatch by role and dispatch by seat. Each trait's own entry comes from [layers] (which decides,
 * per trait name, whether the per-root or the server declaration applies — see [LayeredConfig]).
 *
 * ## Trait order
 *
 * Item traits first, then the schema's `default_traits`, deduplicated with the first position kept
 * ([order]). Every dimension uses this one order.
 *
 * ## Merge rules
 *
 * - **Notes**: base notes first, then trait notes in trait order, one entry per key. A key's content
 *   (role, description, guidance, skill, maxLength, seat, independentOf) comes from its FIRST
 *   declaration (the base schema before any trait); `required` is true when ANY declaration marks it.
 * - **Seats**: base seats first, then trait seats in trait order; the first declaration of a name
 *   wins (later ones are dropped with a WARN), and a second `enters: true` seat in a phase that
 *   already has one is demoted to non-entering (WARN).
 * - **Resources**: the union of keys; on a duplicate key EXCLUSIVE beats ADVISORY, and `ttlSeconds`
 *   keeps the first-seen value.
 * - **Dispatch by role**: the first trait to declare a role wins.
 * - **Dispatch by seat**: per (phase, seat) the first MODEL-SELECTING override in trait order wins (see
 *   [isModelSelecting]), so `[delegated, complex-implementation]` resolves like `[complex-implementation,
 *   delegated]`; with none, the first override in trait order wins. The winner replaces the others whole
 *   (no field merge).
 *
 * Traits are plain names here (`List<String>`), never a work item, so callers decide where the item
 * traits come from.
 */
class TraitResolver(
    private val layers: LayeredConfig,
) {
    /**
     * [base] merged with its traits ([order] of [itemTraits] and [base]'s `default_traits`). Unknown
     * traits WARN (naming [subject], e.g. the item id) and are skipped. Returns [base] unchanged when
     * no trait contributes a note or a seat.
     */
    fun mergeSchema(
        base: WorkItemSchema,
        itemTraits: List<String>,
        subject: Any? = null,
    ): WorkItemSchema {
        val traits = order(itemTraits, base.defaultTraits)
        if (traits.isEmpty()) return base

        val traitNoteEntries = mutableListOf<NoteSchemaEntry>()
        val traitSeatEntries = mutableListOf<SeatDefinition>()
        for (traitName in traits) {
            val notes = layers.traitNotes(traitName)
            if (notes == null) {
                logger.warn("Unknown trait '{}' on item '{}'; skipping", traitName, subject)
                continue
            }
            traitNoteEntries.addAll(notes)
            traitSeatEntries.addAll(layers.traitSeats(traitName))
        }

        if (traitNoteEntries.isEmpty() && traitSeatEntries.isEmpty()) return base

        return base.copy(
            notes = mergeNotes(base.notes, traitNoteEntries),
            seats = mergeSeats(base.seats, traitSeatEntries, subject),
        )
    }

    /** Resource requirements over [traits] (already in [order]); see the class kdoc. */
    fun resources(traits: List<String>): List<ResourceRequirement> {
        val merged = LinkedHashMap<String, ResourceRequirement>()
        for (traitName in traits) {
            for (requirement in layers.traitResources(traitName)) {
                val existing = merged[requirement.key]
                if (existing == null) {
                    merged[requirement.key] = requirement
                } else if (existing.mode != ResourceMode.EXCLUSIVE && requirement.mode == ResourceMode.EXCLUSIVE) {
                    merged[requirement.key] = existing.copy(mode = ResourceMode.EXCLUSIVE)
                }
            }
        }
        return merged.values.toList()
    }

    /** Dispatch by role over [traits] (already in [order]): the first trait with a profile for a role wins. */
    fun dispatch(traits: List<String>): Map<Role, DispatchProfile> {
        val result = LinkedHashMap<Role, DispatchProfile>()
        for (traitName in traits) {
            for ((role, profile) in layers.traitDispatch(traitName)) {
                if (role !in result) result[role] = profile
            }
        }
        return result
    }

    /**
     * Dispatch by seat over [traits] (already in [order]), keyed by phase then seat name, filtered to
     * [seats]. For each (phase, seat) pair the first model-selecting override (a seat-only trait's
     * override that sets a model and neither sets nor clears the agent) in trait order wins, else the
     * first override in trait order; that override is laid over the phase's [dispatch] profile via [SeatDispatchOverride.applyTo]. A seat with
     * neither an override nor a phase profile is omitted.
     */
    fun dispatchBySeat(
        traits: List<String>,
        seats: List<SeatDefinition>,
    ): Map<Role, Map<String, DispatchProfile>> {
        if (seats.isEmpty()) return emptyMap()

        val phaseDefaults = dispatch(traits)

        val overridesByRole = LinkedHashMap<Role, LinkedHashMap<String, SeatDispatchOverride>>()
        val selectingByRole = LinkedHashMap<Role, LinkedHashMap<String, SeatDispatchOverride>>()
        for (traitName in traits) {
            val bySeat = layers.traitDispatchBySeat(traitName)
            val seatOnly = bySeat.isNotEmpty() && layers.traitDispatch(traitName).isEmpty()
            for ((role, seatMap) in bySeat) {
                val roleOverrides = overridesByRole.getOrPut(role) { LinkedHashMap() }
                val roleSelecting = selectingByRole.getOrPut(role) { LinkedHashMap() }
                for ((seatName, override) in seatMap) {
                    if (seatName !in roleOverrides) roleOverrides[seatName] = override
                    if (seatOnly && isModelSelecting(override) && seatName !in roleSelecting) {
                        roleSelecting[seatName] = override
                    }
                }
            }
        }

        val result = LinkedHashMap<Role, Map<String, DispatchProfile>>()
        for (role in Role.entries) {
            val seatNamesInOrder = seats.filter { it.phase == role }.map { it.name }
            if (seatNamesInOrder.isEmpty()) continue
            val phaseDefault = phaseDefaults[role]
            val roleOverrides = overridesByRole[role] ?: emptyMap()
            val profiles = LinkedHashMap<String, DispatchProfile>()
            for (seatName in seatNamesInOrder) {
                val override = selectingByRole[role]?.get(seatName) ?: roleOverrides[seatName]
                when {
                    override != null -> profiles[seatName] = override.applyTo(phaseDefault)
                    phaseDefault != null -> profiles[seatName] = phaseDefault
                }
            }
            if (profiles.isNotEmpty()) result[role] = profiles
        }
        return result
    }

    private fun mergeNotes(
        baseNotes: List<NoteSchemaEntry>,
        traitNotes: List<NoteSchemaEntry>,
    ): List<NoteSchemaEntry> {
        val byKey = LinkedHashMap<String, NoteSchemaEntry>()
        for (note in baseNotes + traitNotes) {
            val existing = byKey[note.key]
            if (existing == null) {
                byKey[note.key] = note
            } else if (note.required && !existing.required) {
                byKey[note.key] = existing.copy(required = true)
            }
        }
        return byKey.values.toList()
    }

    private fun mergeSeats(
        baseSeats: List<SeatDefinition>,
        traitSeats: List<SeatDefinition>,
        subject: Any?,
    ): List<SeatDefinition> {
        if (traitSeats.isEmpty()) return baseSeats
        val existingNames = baseSeats.map { it.name }.toMutableSet()
        val entersPhases = baseSeats.filter { it.enters }.map { it.phase }.toMutableSet()
        val merged = baseSeats.toMutableList()
        for (seat in traitSeats) {
            if (seat.name in existingNames) {
                logger.warn(
                    "Trait seat '{}' on item '{}' duplicates an existing seat name; the first declaration wins, " +
                        "dropping this one",
                    seat.name,
                    subject
                )
                continue
            }
            var effectiveSeat = seat
            if (seat.enters) {
                if (seat.phase in entersPhases) {
                    logger.warn(
                        "Trait seat '{}' on item '{}' also enters phase '{}', which already has an entering seat; " +
                            "demoting to non-entering",
                        seat.name,
                        subject,
                        seat.phase.name.lowercase()
                    )
                    effectiveSeat = seat.copy(enters = false)
                } else {
                    entersPhases.add(seat.phase)
                }
            }
            merged.add(effectiveSeat)
            existingNames.add(seat.name)
        }
        return merged
    }

    companion object {
        /**
         * True when [override] (declared by a seat-only trait) selects a model without touching the
         * agent: `model` is set, `agent` is not set, and `"agent"` is not cleared.
         */
        fun isModelSelecting(override: SeatDispatchOverride): Boolean =
            override.model != null && override.agent == null && "agent" !in override.cleared

        /**
         * The one trait order: [itemTraits] first, then [defaultTraits], deduplicated with the first
         * position kept.
         */
        fun order(
            itemTraits: List<String>,
            defaultTraits: List<String>,
        ): List<String> = (itemTraits + defaultTraits).distinct()

        /**
         * Logger category kept as `ToolExecutionContext` (where this logic first lived) so existing
         * log filters and WARN consumers keep matching.
         */
        private val logger: Logger =
            LoggerFactory.getLogger("io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext")
    }
}
