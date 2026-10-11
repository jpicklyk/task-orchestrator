package io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * DTO for a work item returned by the REST API.
 *
 * `properties` is deserialized from the domain JSON string on read (parse failure returns empty object).
 * `tags` is expanded from the comma-separated domain string via WorkItem.tagList().
 * `traits` is the item's typed trait list in order; always present, possibly empty.
 * All timestamps are ISO-8601 strings.
 * `etag` is derived from `modifiedAt` via [io.github.jpicklyk.mcptask.current.interfaces.api.v1.etag.etagFor].
 */
@Serializable
data class ItemDto(
    val id: String,
    val parentId: String?,
    val title: String,
    val description: String?,
    val summary: String,
    val type: String?,
    val role: String,
    val previousRole: String?,
    val statusLabel: String?,
    val priority: String,
    val complexity: Int?,
    val requiresVerification: Boolean,
    val tags: List<String>,
    val traits: List<String>,
    val properties: JsonObject,
    val createdAt: String,
    val modifiedAt: String,
    val roleChangedAt: String,
    val etag: String,
    val depth: Int,
    val isClaimed: Boolean,
    val notes: List<NoteDto>? = null,
    val children: List<ItemDto>? = null,
    val dependencies: DependenciesDto? = null,
)

/**
 * DTO for a note attached to a work item.
 *
 * `actor` and `verification` are redacted (null) unless the caller has `admin` capability
 * and [io.github.jpicklyk.mcptask.current.interfaces.api.v1.redaction.AttributionRedactor]
 * opts them in.
 */
@Serializable
data class NoteDto(
    val key: String,
    val role: String,
    val body: String,
    val createdAt: String,
    val modifiedAt: String,
    val etag: String,
    val actor: ActorClaimDto?,
    val verification: VerificationDto?,
)

/** DTO for actor attribution on a note or role-transition. */
@Serializable
data class ActorClaimDto(
    val id: String,
    val kind: String,
    val parent: String?,
)

/** DTO for a verification result attached to a note or role-transition. */
@Serializable
data class VerificationDto(
    val status: String,
    val verifier: String?,
    val reason: String?,
    /**
     * Forensic evidence about the proof (hash + verified claims), in place of the raw proof
     * (which is never stored since migration V17). Populated only for callers with
     * [io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability.ADMIN] — its own
     * admin check, independent of `API_REDACT_NOTE_ATTRIBUTION`. Null when no proof was ever
     * supplied, or for a non-admin caller.
     */
    val proof: ProofEvidenceDto? = null,
)

/**
 * DTO for the forensic evidence retained about an actor proof since migration V17: a SHA-256
 * hash of the raw proof (always present when a proof was supplied, regardless of verification
 * outcome) plus the cryptographically verified JWT claims (present only when the proof was
 * VERIFIED). Mirrors [io.github.jpicklyk.mcptask.current.domain.model.ProofClaims].
 */
@Serializable
data class ProofEvidenceDto(
    val sha256: String,
    val iss: String? = null,
    val sub: String? = null,
    val aud: List<String>? = null,
    val jti: String? = null,
    val iat: Long? = null,
    val exp: Long? = null,
    val kid: String? = null,
    val alg: String? = null,
)

/** DTO for a single role-transition row (append-only audit surface). */
@Serializable
data class RoleTransitionDto(
    val id: String,
    val itemId: String,
    val fromRole: String?,
    val toRole: String,
    val trigger: String,
    val statusLabel: String?,
    val occurredAt: String,
    val actor: ActorClaimDto?,
    val verification: VerificationDto?,
    /**
     * Opaque credential/secret labels consumed by this transition (e.g. "vault/prod-db-password"),
     * never raw secret material. Omitted (null) when empty. Deliberately NOT subject to attribution
     * redaction — the labels themselves are not sensitive.
     */
    val consumedCredentials: List<String>? = null,
)

/** DTO for the combined dependencies view of a work item. */
@Serializable
data class DependenciesDto(
    val blocks: List<DependencyEdgeDto>,
    val blockedBy: List<DependencyEdgeDto>,
    val related: List<DependencyEdgeDto>,
)

/** DTO for a single dependency edge. */
@Serializable
data class DependencyEdgeDto(
    val id: String,
    val fromItemId: String,
    val toItemId: String,
    val type: String,
    val unblockAt: String?,
    val createdAt: String,
)

/**
 * Generic paginated response wrapper.
 *
 * `totalItems` may be null when computing the exact count is expensive — callers should
 * use `hasMore` for pagination control in that case.
 *
 * `skipped` counts rows in this page's underlying query window that were dropped because they
 * failed domain validation (see `ItemFetchResult.skipped`). Null/omitted when no rows were
 * skipped. Invariant: `items.size == min(pageSize, totalItems - offset) - skipped` (clamped to
 * the actual window), so a caller can derive whether the window itself was short without a
 * separate `truncated` field.
 */
@Serializable
data class PageDto<T>(
    val items: List<T>,
    val page: Int,
    val pageSize: Int,
    val totalItems: Long?,
    val hasMore: Boolean,
    val skipped: Int? = null,
)

/**
 * Standard error response body.
 *
 * `error` is machine-readable (e.g., `scope_forbidden`, `not_found`, `validation_error`).
 * `message` is human-readable.
 * `details` carries structured extra context (field errors, conflicting ETag, etc.).
 */
@Serializable
data class ErrorDto(
    val error: String,
    val message: String,
    val details: JsonObject? = null,
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
 * `"global"` — the same literal MCP `query_items(schema, ...)` uses for `configSource`.
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

// ─── Phase 5: Write-API request DTOs ─────────────────────────────────────────

/**
 * A single FTS5 search hit returned by `/api/v1/search` (items) and `/api/v1/notes/search` (notes).
 *
 * `noteKey` is populated only for note-body hits; it is null/omitted for item hits. `field` names the field that
 * contains a match (`title`/`summary`/`body`) and `snippet` is taken from it.
 * `score` is the RRF-fused relevance score from the repository search layer.
 *
 * This is a serializable replacement for the raw `Map<String, Any>` previously emitted by the
 * search routes — kotlinx.serialization has no serializer for `Any`, so the map form threw at
 * response-encoding time (HTTP 500). See SearchRoutesIntegrationTest.
 */
@Serializable
data class SearchHitDto(
    val itemId: String,
    val field: String,
    val snippet: String,
    val score: Double,
    val noteKey: String? = null,
    /** Title of the owning work item (the item's own title for an item hit); omitted when unreadable. */
    val title: String? = null,
)

/**
 * Response DTO for `PUT /api/v1/roots/{rootId}/plans/{slug}` and `GET /api/v1/roots/{rootId}/plans/{slug}`.
 *
 * Mirrors the `data` payload of the MCP `manage_plan_documents` tool's `stash`/`get` operations —
 * both surfaces converge on the same [io.github.jpicklyk.mcptask.current.application.service.PlanDocumentService].
 * `body` is included only on GET (omitted on PUT, which already echoed the caller's own body).
 */
@Serializable
data class PlanDocumentResponseDto(
    val id: String,
    val rootId: String,
    val slug: String,
    val contentHash: String,
    val status: String,
    val adoptedByItemId: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val body: String? = null,
)

/** One row of `GET /api/v1/roots/{rootId}/plans` — metadata only, never the document body. */
@Serializable
data class PlanDocumentSummaryDto(
    val id: String,
    val rootId: String,
    val slug: String,
    val contentHash: String,
    val status: String,
    val adoptedByItemId: String? = null,
    val createdAt: String,
    val updatedAt: String,
)

/** Response DTO for `GET /api/v1/roots/{rootId}/plans`. */
@Serializable
data class PlanDocumentListResponseDto(
    val rootId: String,
    val plans: List<PlanDocumentSummaryDto>,
)

// ─── Gate status DTOs ────────────────────────────────────────────────────────

/**
 * REST DTO mirror of [io.github.jpicklyk.mcptask.current.domain.model.IndependenceViolation]
 * (A2) -- actor-free by construction (never an actor id, proof, or claim on any surface, MCP or
 * REST; [io.github.jpicklyk.mcptask.current.interfaces.api.v1.redaction.AttributionRedactor] is
 * NOT involved here since there is nothing attribution-bearing to redact). `seat` /
 * `conflictingSeat` are omitted when null; `waived` is omitted unless true (matches
 * `explicitNulls = false`, mirroring
 * [io.github.jpicklyk.mcptask.current.application.tools.workflow.NoteSchemaJsonHelpers.buildViolationsArray]'s
 * MCP JSON shape field-for-field).
 */
@Serializable
data class IndependenceViolationDto(
    val key: String,
    val seat: String? = null,
    val constraint: String,
    val conflictingSeat: String? = null,
    val waived: Boolean? = null,
)

/**
 * Gate-status sub-object for `GET /api/v1/items/{id}/gate`.
 *
 * Field-for-field identical to `get_context` item mode's `gateStatus`: `phase` is the item's
 * CURRENT role, lowercased; `missing` is the required-note KEY strings (schema order) for that
 * phase — never `{key, description, ...}` objects. `missingBySeat` (A1c) is present only for a
 * seat-aware schema whose item is NOT terminal — see
 * [io.github.jpicklyk.mcptask.current.application.service.computeMissingBySeat].
 * `violations` (A2) is present (possibly `[]`) whenever independence mode is not OFF and the
 * resolved schema declares `independent_of` somewhere; null otherwise. `canAdvance` is "a `start`
 * advance would be allowed right now" (claim ownership excluded): the same policy evaluation the
 * advance runs ([io.github.jpicklyk.mcptask.current.application.service.TransitionPreview]), so it is
 * false when the transition table, an unmet dependency, a required note / REJECT-mode independence
 * violation, or an exclusive resource lease held elsewhere blocks it; `blockedBy` names that gate.
 */
@Serializable
data class GateStatusDto(
    val canAdvance: Boolean,
    val phase: String,
    val missing: List<String>,
    val missingBySeat: Map<String, List<String>>? = null,
    val violations: List<IndependenceViolationDto>? = null,
    /**
     * The first gate that blocks a `start` (`"table"` | `"dependency"` | `"note"` | `"lease"`); present only
     * when [canAdvance] is false and the item is not terminal.
     */
    val blockedBy: String? = null,
)

/**
 * Response DTO for `GET /api/v1/items/{id}/gate`.
 *
 * Mirrors `get_context` item mode's gate fields for a single item. `guidanceKey` / `skillPointer`
 * are the FIRST missing required note's guidance key / skill pointer for the current phase —
 * omitted (not JSON `null`) when there is none, matching McpJson's `explicitNulls=false`. No
 * dependency/blocker info and no dispatch field.
 */
@Serializable
data class ItemGateDto(
    val itemId: String,
    val title: String,
    val role: String,
    val gateStatus: GateStatusDto,
    val guidanceKey: String? = null,
    val skillPointer: String? = null,
)
