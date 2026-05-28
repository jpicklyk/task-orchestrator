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
