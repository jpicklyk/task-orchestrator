package io.github.jpicklyk.mcptask.current.config.internal.app

import io.github.jpicklyk.mcptask.current.config.DecisionClassConfig
import io.github.jpicklyk.mcptask.current.config.RunsConfig
import io.github.jpicklyk.mcptask.current.config.TraitDescriptor
import io.github.jpicklyk.mcptask.current.model.item.Role
import io.github.jpicklyk.mcptask.current.model.lease.ResourceDefinition
import io.github.jpicklyk.mcptask.current.model.lease.ResourceRequirement
import io.github.jpicklyk.mcptask.current.model.note.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.model.run.SeatDispatchOverride
import io.github.jpicklyk.mcptask.current.model.schema.DispatchProfile
import io.github.jpicklyk.mcptask.current.model.schema.IndependencePolicy
import io.github.jpicklyk.mcptask.current.model.schema.SeatDefinition
import io.github.jpicklyk.mcptask.current.model.schema.WorkItemSchema

/**
 * The server (global, fallback) side of config resolution, as seen by [LayeredConfig]: plain
 * per-key lookups over one document. No method folds in `"default"` or merges across traits; the
 * precedence rule lives in [LayeredConfig] and the trait merge in [TraitResolver].
 * `LayerBackedGlobalLookup` answers every question from a single parsed `ConfigLayer`.
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

    /** The `description` and `applies_when` text declared by trait [name], or null when it declares neither. */
    fun traitDescriptor(name: String): TraitDescriptor? = null

    /** The top-level `resources:` registry. */
    fun resourceRegistry(): Map<String, ResourceDefinition>

    /** The effective `note_limits.mode` ("warn" when not configured). */
    fun noteLimitsMode(): String

    /** The effective top-level `independence:` policy (A2); [IndependencePolicy.DEFAULT] when not configured. */
    fun independencePolicy(): IndependencePolicy

    /** The server document's own `runs:` block, or null when it has none (no defaults applied here). */
    fun runsConfig(): RunsConfig? = null

    /** The server document's own `decision_classes:` block, or null when it has none (no built-ins merged here). */
    fun decisionClasses(): Map<String, DecisionClassConfig>? = null

    /** Fingerprint of the server config, or null when none is loaded. */
    fun fingerprint(): String?

    /** The stored server config version this lookup answers from, or null when it is not a stored version. */
    fun versionId(): java.util.UUID? = null

    /** The number of [versionId]'s version, or null when it is not a stored version. */
    fun version(): Int? = null

    /** Names of every server-declared trait. */
    fun traitNames(): List<String>

    /** The server status label for [trigger], or null for "no label". */
    fun statusLabel(trigger: String): String?
}
