package io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto

import kotlinx.serialization.Serializable

/** DTO for actor attribution on a note or role-transition. */
@Serializable
data class ActorClaimDto(
    val id: String,
    val kind: String,
    val parent: String?,
)

// ─── Phase 4: Config / schema-discovery DTOs ────────────────────────────────

/**
 * DTO for a single note schema entry (one row in a schema or trait definition).
 *
 * `role` is the lowercase phase name ("queue", "work", "review").
 * `skill` is present only when a skill-routing pointer is configured for this entry.
 * `maxLength` is present only when a maximum note-body length is configured for this entry.
 */
@Serializable
data class NoteSchemaEntryDto(
    val key: String,
    val role: String,
    val required: Boolean,
    val description: String,
    val guidance: String? = null,
    val skill: String? = null,
    val maxLength: Int? = null,
    val shared: Boolean? = null,
)

/**
 * DTO for a dispatch routing profile — who/what should pick up a phase: `{agent?, model?,
 * effort?}`. Declared per trait per phase under `traits.<name>.dispatch.<phase>:` in
 * `.taskorchestrator/config.yaml`. See
 * [io.github.jpicklyk.mcptask.current.domain.model.DispatchProfile].
 */
@Serializable
data class DispatchProfileDto(
    val agent: String? = null,
    val model: String? = null,
    val effort: String? = null,
)

/**
 * DTO for a trait's declared shared-resource requirement: `{key, mode, ttlSeconds?}`. See
 * [io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement].
 */
@Serializable
data class ResourceRequirementDto(
    val key: String,
    val mode: String,
    val ttlSeconds: Int? = null,
)

/**
 * DTO for a trait definition — a named set of note schema entries that can be
 * composed onto any work-item schema.
 *
 * `dispatch` (per-phase routing profiles, keyed by lowercase phase name) and `resources`
 * (declared shared-resource requirements) are omitted (null) when the trait declares neither —
 * never present as an empty map/list.
 */
@Serializable
data class TraitDto(
    val name: String,
    val notes: List<NoteSchemaEntryDto>,
    val dispatch: Map<String, DispatchProfileDto>? = null,
    val resources: List<ResourceRequirementDto>? = null,
)

/**
 * Per-type view of the status-transition graph.
 *
 * `transitions` is a map of `role → (trigger → targetRole)`.
 * `targetRole` values are lowercase role names ("queue", "work", "review", "blocked", "terminal")
 * or the sentinel string `"<previousRole>"` for the `blocked.resume` cell (the dashboard
 * must resolve this from the live item's `previousRole` field).
 */
@Serializable
data class StatusGraphTypeDto(
    val type: String,
    val lifecycleMode: String,
    val hasReviewPhase: Boolean,
    val transitions: Map<String, Map<String, String>>,
)

/**
 * Full static status-transition graph across all registered schema types.
 *
 * `roles` and `triggers` enumerate the axes of the graph.
 * `types` contains one entry per registered schema type.
 */
@Serializable
data class StatusGraphDto(
    val roles: List<String>,
    val triggers: List<String>,
    val types: List<StatusGraphTypeDto>,
)

/**
 * A single type's RESOLVED base schema (no trait merging), from the `config` tool's `effective` op — see
 * [EffectiveConfigDto].
 *
 * [type] is the queried type key; [matchedType] is the schema that actually answered it
 * (`"default"` when a default schema answered a miss). [configSource] is `"per-root"` or
 * `"global"` - the same literal `items_read` `select=schema` reports for `configSource`.
 * [configFingerprint] is the supplying layer's fingerprint, omitted when that layer has none.
 */
@Serializable
data class EffectiveSchemaDto(
    val type: String,
    val matchedType: String,
    val configSource: String,
    val configFingerprint: String? = null,
    val lifecycleMode: String,
    val hasReviewPhase: Boolean,
    val notes: List<NoteSchemaEntryDto>,
    val defaultTraits: List<String>,
)

/**
 * The resolved (layered) config view returned by the `config` tool's `effective` op: every registered type resolved
 * against [rootId]'s per-root config over the server config, in one result. [rootId] is omitted for the server-only view.
 *
 * [globalFingerprint]/[perRootFingerprint] are omitted when null (no server config loaded / no per-root config pushed
 * for this root, respectively).
 */
@Serializable
data class EffectiveConfigDto(
    val rootId: String? = null,
    val schemas: List<EffectiveSchemaDto>,
    val traits: List<TraitDto>,
    val types: List<String>,
    val statusGraph: StatusGraphDto,
    val defaultSchema: EffectiveSchemaDto? = null,
    val globalFingerprint: String? = null,
    val perRootFingerprint: String? = null,
)
