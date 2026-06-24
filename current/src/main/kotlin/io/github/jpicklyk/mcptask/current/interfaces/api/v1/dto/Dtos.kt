package io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * DTO for a work item returned by the REST API.
 *
 * `properties` is deserialized from the domain JSON string on read (parse failure returns empty object).
 * `tags` is expanded from the comma-separated domain string via WorkItem.tagList().
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

/**
 * DTO for actor attribution on a note or role-transition.
 *
 * `proof` is additionally redacted unless the caller has `admin` AND `?include=proof`.
 */
@Serializable
data class ActorClaimDto(
    val id: String,
    val kind: String,
    val parent: String?,
    val proof: String? = null,
)

/** DTO for a verification result attached to a note or role-transition. */
@Serializable
data class VerificationDto(
    val status: String,
    val verifier: String?,
    val reason: String?,
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
)

/** DTO for the combined dependencies view of a work item. */
@Serializable
data class DependenciesDto(
    val blocks: List<DependencyEdgeDto>,
    val blockedBy: List<DependencyEdgeDto>,
    val related: List<DependencyEdgeDto>,
)

/** DTO for a single backlink entry — another item referencing this one via a dependency edge. */
@Serializable
data class BacklinkDto(
    val fromItemId: String,
    val fromTitle: String,
    val type: String,
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
 */
@Serializable
data class PageDto<T>(
    val items: List<T>,
    val page: Int,
    val pageSize: Int,
    val totalItems: Long?,
    val hasMore: Boolean,
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
 */
@Serializable
data class NoteSchemaEntryDto(
    val key: String,
    val role: String,
    val required: Boolean,
    val description: String,
    val guidance: String? = null,
    val skill: String? = null,
)

/**
 * DTO for a full work-item schema type.
 *
 * `lifecycleMode` is the lowercase lifecycle mode ("auto", "manual", etc.).
 * `hasReviewPhase` is `true` when the schema contains at least one REVIEW-phase note.
 * `defaultTraits` lists trait names automatically applied to items of this type.
 */
@Serializable
data class SchemaDto(
    val type: String,
    val lifecycleMode: String,
    val hasReviewPhase: Boolean,
    val notes: List<NoteSchemaEntryDto>,
    val defaultTraits: List<String>,
)

/**
 * DTO for a trait definition — a named set of note schema entries that can be
 * composed onto any work-item schema.
 */
@Serializable
data class TraitDto(
    val name: String,
    val notes: List<NoteSchemaEntryDto>,
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
 * Full config snapshot DTO returned by `GET /api/v1/config`.
 *
 * Aggregates all schemas, traits, type names, and the status-transition graph.
 * `defaultSchema` is the schema registered under the key `"default"`, or `null`
 * if no default schema is configured.
 */
@Serializable
data class ConfigSnapshotDto(
    val schemas: List<SchemaDto>,
    val traits: List<TraitDto>,
    val types: List<String>,
    val statusGraph: StatusGraphDto,
    val defaultSchema: SchemaDto? = null,
)

// ─── Phase 5: Write-API request DTOs ─────────────────────────────────────────

/**
 * Request DTO for `POST /api/v1/items`.
 *
 * All optional fields match [WorkItem] semantics. `parentId` must be a valid UUID string
 * when provided; null creates a root item.
 */
@Serializable
data class ItemCreateDto(
    val title: String,
    val description: String? = null,
    val summary: String? = null,
    val parentId: String? = null,
    val type: String? = null,
    val priority: String? = null,
    val complexity: Int? = null,
    val requiresVerification: Boolean? = null,
    val tags: List<String>? = null,
    val statusLabel: String? = null,
    val properties: JsonObject? = null,
    val metadata: String? = null,
)

/**
 * Merge-patch DTO for `PATCH /api/v1/items/{id}`.
 *
 * ALL fields are nullable so that absent fields are distinguished from explicit nulls.
 * This DTO is decoded from the MERGED JsonObject, not directly from the request body
 * (the request body is parsed as a raw [JsonObject] to preserve absent-vs-null semantics).
 *
 * Tags: when present in the merged object, `tags` is a comma-separated string mirroring
 * the domain model storage format.
 */
@Serializable
data class ItemPatchDto(
    val title: String? = null,
    val description: String? = null,
    val summary: String? = null,
    val statusLabel: String? = null,
    val priority: String? = null,
    val complexity: Int? = null,
    val requiresVerification: Boolean? = null,
    val tags: String? = null,
    val type: String? = null,
    val properties: String? = null,
    val metadata: String? = null,
    val parentId: String? = null,
)

/**
 * Request DTO for `PUT /api/v1/items/{id}/notes/{key}`.
 *
 * `role` must be one of "queue", "work", "review".
 * `properties` is reserved for future note-level metadata and is silently ignored in v1.
 */
@Serializable
data class NoteWriteDto(
    val role: String,
    val body: String,
    val properties: JsonObject? = null,
)

/**
 * Request DTO for `POST /api/v1/dependencies`.
 *
 * `type` must be one of "blocks" or "relates_to" (case-insensitive).
 * `unblockAt` may be one of "queue", "work", "review", "terminal"; null defaults to "terminal"
 * for BLOCKS edges and must be absent for RELATES_TO edges.
 */
@Serializable
data class DependencyCreateDto(
    val fromItemId: String,
    val toItemId: String,
    val type: String,
    val unblockAt: String? = null,
)

/**
 * Request DTO for `POST /api/v1/items/{id}/advance`.
 *
 * `trigger` must be one of the 7 user triggers:
 * start, complete, block, hold, resume, cancel, reopen.
 */
@Serializable
data class AdvanceRequestDto(
    val trigger: String,
)

/**
 * A note schema entry missing at an advance gate, returned in the [AdvanceResponseDto.cascadeEvents]
 * gate-block details and in the `details` of a gate-rejection error.
 */
@Serializable
data class MissingNoteDto(
    val key: String,
    val description: String,
    val guidance: String? = null,
    val skill: String? = null,
)

/**
 * A cascade transition applied (or gate-blocked) as a side effect of an advance.
 *
 * Mirrors the MCP `advance_item` cascadeEvents shape. When [gateBlocked] is true the cascade was
 * suppressed because the parent had unfilled required notes; [applied] is then false and
 * [missingNotes] lists the structured gaps.
 */
@Serializable
data class CascadeEventDto(
    val itemId: String,
    val title: String,
    val previousRole: String,
    val targetRole: String,
    val applied: Boolean,
    val statusLabel: String? = null,
    val gateBlocked: Boolean = false,
    val missingNotes: List<MissingNoteDto>? = null,
)

/** A downstream item that became fully unblocked as a result of an advance. */
@Serializable
data class UnblockedItemDto(
    val itemId: String,
    val title: String,
)

/**
 * A schema note entry expected for the item's new role after an advance (additive parity with the
 * MCP `advance_item` expectedNotes field).
 */
@Serializable
data class ExpectedNoteDto(
    val key: String,
    val role: String,
    val required: Boolean,
    val description: String,
    val guidance: String? = null,
    val skill: String? = null,
    val exists: Boolean,
)

/**
 * Response DTO for `POST /api/v1/items/{id}/advance`.
 *
 * Does NOT include `claimedBy` — tiered-disclosure principle prevents exposing
 * claim-holder identity via the API surface.
 *
 * The `cascadeEvents`, `unblockedItems`, and `expectedNotes` fields were added when the REST and
 * MCP advance paths were unified behind `AdvanceService` (additive change — older clients ignore
 * the new fields). They mirror the corresponding MCP `advance_item` response data.
 */
@Serializable
data class AdvanceResponseDto(
    val itemId: String,
    val previousRole: String,
    val newRole: String,
    val trigger: String,
    val statusLabel: String? = null,
    val cascadeEvents: List<CascadeEventDto> = emptyList(),
    val unblockedItems: List<UnblockedItemDto> = emptyList(),
    val expectedNotes: List<ExpectedNoteDto> = emptyList(),
)

/**
 * Response DTO returned by idempotency-cached responses.
 *
 * When an `Idempotency-Key` header is present and the request was already processed,
 * the original [ItemDto] / [NoteDto] / [DependencyEdgeDto] is returned from cache.
 * This wrapper is NOT used — the actual DTO types are cached and returned directly.
 *
 * (This comment block documents the idempotency cache replay contract for reviewers.)
 */
@Suppress("unused")
private object IdempotencyCacheContract

/**
 * A single FTS5 search hit returned by `/api/v1/search` (items) and `/api/v1/notes/search` (notes).
 *
 * `noteKey` is populated only for note-body hits; it is null/omitted for item hits.
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
)
