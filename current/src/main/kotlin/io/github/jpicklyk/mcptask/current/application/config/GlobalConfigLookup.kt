package io.github.jpicklyk.mcptask.current.application.config

import io.github.jpicklyk.mcptask.current.domain.model.DispatchProfile
import io.github.jpicklyk.mcptask.current.domain.model.IndependencePolicy
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.ResourceDefinition
import io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.SeatDefinition
import io.github.jpicklyk.mcptask.current.domain.model.SeatDispatchOverride
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema

/**
 * The server (global, fallback) side of config resolution, as seen by [LayeredConfig]: plain
 * per-key lookups over one document. No method folds in `"default"` or merges across traits; the
 * precedence rule lives in [LayeredConfig] and the trait merge in [TraitResolver].
 * [LayerBackedGlobalLookup] answers every question from a single parsed [ConfigLayer].
 */
interface GlobalConfigLookup {
    /** Exact schema for [key] (a type or `"default"`); never folds in `"default"`. */
    fun exactSchema(key: String): WorkItemSchema?

    /** Every schema key the server config declares (types and `"default"` alike); empty when none. */
    fun schemaKeys(): Set<String>

    /** Note entries declared by trait [name], or null when the trait is unknown. */
    fun traitNotes(name: String): List<NoteSchemaEntry>?

    /** Resource requirements declared by trait [name] (empty when none or unknown). */
    fun traitResources(name: String): List<ResourceRequirement>

    /** Per-phase dispatch profiles declared by trait [name] (empty when none or unknown). */
    fun traitDispatch(name: String): Map<Role, DispatchProfile>

    /** Seats declared by trait [name] (empty when none or unknown). */
    fun traitSeats(name: String): List<SeatDefinition>

    /** Per-phase, per-seat dispatch override maps declared by trait [name] (empty when none or unknown). */
    fun traitDispatchBySeat(name: String): Map<Role, Map<String, SeatDispatchOverride>>

    /** The top-level `resources:` registry. */
    fun resourceRegistry(): Map<String, ResourceDefinition>

    /** The effective `note_limits.mode` ("warn" when not configured). */
    fun noteLimitsMode(): String

    /** The effective top-level `independence:` policy (A2); [IndependencePolicy.DEFAULT] when not configured. */
    fun independencePolicy(): IndependencePolicy

    /** Fingerprint of the server config, or null when none is loaded. */
    fun fingerprint(): String?

    /** Names of every server-declared trait. */
    fun traitNames(): List<String>

    /** The server status label for [trigger], or null for "no label". */
    fun statusLabel(trigger: String): String?
}
