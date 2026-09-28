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
 * The global (server-wide, fallback) side of config resolution, as seen by [LayeredConfig].
 *
 * Each method mirrors exactly one global-service call the legacy resolver made, so a
 * [ServiceBackedGlobalLookup] can delegate 1:1 (keeping strict mocks of the global services
 * working: no extra or eager calls), while a [LayerBackedGlobalLookup] answers the same questions
 * from a single parsed global [ConfigLayer].
 */
interface GlobalConfigLookup {
    /** Exact type match, else the `"default"` schema, else null. */
    fun schemaForType(type: String): WorkItemSchema?

    /** Notes of the first tag in [tags] with an exact schema match, else the `"default"` schema's notes, else null. */
    fun notesForTags(tags: List<String>): List<NoteSchemaEntry>?

    /** Note entries declared by trait [name], or null when the trait is unknown. */
    fun traitNotes(name: String): List<NoteSchemaEntry>?

    /** Resource requirements declared by trait [name] (empty when none or unknown). */
    fun traitResources(name: String): List<ResourceRequirement>

    /** Per-phase dispatch profiles declared by trait [name] (empty when none or unknown). */
    fun traitDispatch(name: String): Map<Role, DispatchProfile>

    /** The top-level `resources:` registry. */
    fun resourceRegistry(): Map<String, ResourceDefinition>

    /** The effective `note_limits.mode` ("warn" when not configured). */
    fun noteLimitsMode(): String

    /** Fingerprint of the global config, or null when none is loaded. */
    fun fingerprint(): String?

    /** Names of every globally declared trait. */
    fun traitNames(): List<String>

    /** The global status label for [trigger], or null for "no label". */
    fun statusLabel(trigger: String): String?

    /** Exact schema for [key] (a type, tag, or "default"); never folds in "default". */
    fun exactSchema(key: String): WorkItemSchema?

    /** True when [tag] names an exact schema (no "default" fold). Drives the LEGACY tag probe (D2). */
    fun hasExactTagSchema(tag: String): Boolean

    /** The global document's `schema_resolution`, or null when absent/unrecognized. */
    fun schemaResolution(): SchemaResolutionMode?

    /**
     * Seats declared by trait [name] (empty when none or unknown). Defaults to empty so
     * [ServiceBackedGlobalLookup] (which has no per-trait seat service to delegate to) does not
     * need to override this A1a addition — its strict mocks in existing tests see no new calls.
     */
    fun traitSeats(name: String): List<SeatDefinition> = emptyList()

    /**
     * Per-phase, per-seat dispatch override maps declared by trait [name] (empty when none or
     * unknown). Same empty-default rationale as [traitSeats].
     */
    fun traitDispatchBySeat(name: String): Map<Role, Map<String, SeatDispatchOverride>> = emptyMap()

    /**
     * The effective top-level `independence:` policy (A2). Defaults to
     * [IndependencePolicy.DEFAULT] so [ServiceBackedGlobalLookup] (which has no independence
     * service to delegate to) does not need to override this A2 addition — same empty-default
     * rationale as [traitSeats]/[traitDispatchBySeat].
     */
    fun independencePolicy(): IndependencePolicy = IndependencePolicy.DEFAULT
}
