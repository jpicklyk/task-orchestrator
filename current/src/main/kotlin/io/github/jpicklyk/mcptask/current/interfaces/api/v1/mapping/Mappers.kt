package io.github.jpicklyk.mcptask.current.interfaces.api.v1.mapping

import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.RoleTransition
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ActorClaimDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.DependenciesDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.DependencyEdgeDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ItemDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.NoteDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.RoleTransitionDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.VerificationDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.etag.etagFor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

// Lenient JSON instance for parsing stored `properties` strings — parse failures return null.
private val lenientJson =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

/**
 * Parses a JSON string into a [JsonObject], returning an empty object on any failure.
 *
 * The `properties` column in the domain model stores a raw JSON string which may be null,
 * malformed, or a non-object JSON value. All these cases produce an empty [JsonObject] rather
 * than throwing, consistent with the plan §6 "parse failure = empty object on read" contract.
 */
private fun parsePropertiesOrEmpty(json: String?): JsonObject {
    if (json.isNullOrBlank()) return JsonObject(emptyMap())
    return try {
        when (val element = lenientJson.parseToJsonElement(json)) {
            is JsonObject -> element
            else -> JsonObject(emptyMap())
        }
    } catch (_: Exception) {
        JsonObject(emptyMap())
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ItemMapper
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Maps a [WorkItem] domain entity to an [ItemDto].
 *
 * @param includeNotes Inlined notes (from `?include=notes`); null when not requested.
 * @param includeChildren Inlined children (from `?include=children` or `/tree`); null when not requested.
 * @param includeDependencies Inlined dependencies (from `?include=deps`); null when not requested.
 */
fun WorkItem.toDto(
    includeNotes: List<NoteDto>? = null,
    includeChildren: List<ItemDto>? = null,
    includeDependencies: DependenciesDto? = null,
): ItemDto =
    ItemDto(
        id = id.toString(),
        parentId = parentId?.toString(),
        title = title,
        description = description,
        summary = summary,
        type = type,
        role = role.name.lowercase(),
        previousRole = previousRole?.name?.lowercase(),
        statusLabel = statusLabel,
        priority = priority.name.lowercase(),
        complexity = complexity,
        requiresVerification = requiresVerification,
        tags = tagList() ?: emptyList(),
        properties = parsePropertiesOrEmpty(properties),
        createdAt = createdAt.toString(),
        modifiedAt = modifiedAt.toString(),
        roleChangedAt = roleChangedAt.toString(),
        etag = etagFor(modifiedAt),
        depth = depth,
        isClaimed = claimedBy != null,
        notes = includeNotes,
        children = includeChildren,
        dependencies = includeDependencies,
    )

// ─────────────────────────────────────────────────────────────────────────────
// NoteMapper
// ─────────────────────────────────────────────────────────────────────────────

/** Maps an [ActorClaim] domain object to an [ActorClaimDto]. */
fun ActorClaim.toDto(): ActorClaimDto =
    ActorClaimDto(
        id = id,
        kind = kind.toJsonString(),
        parent = parent,
        proof = proof,
    )

/** Maps a [VerificationResult] domain object to a [VerificationDto]. */
fun VerificationResult.toDto(): VerificationDto =
    VerificationDto(
        status = status.toJsonString(),
        verifier = verifier,
        reason = reason,
    )

/**
 * Maps a [Note] domain entity to a [NoteDto].
 *
 * The caller is responsible for applying [io.github.jpicklyk.mcptask.current.interfaces.api.v1.redaction.AttributionRedactor]
 * after mapping — this function always includes actor/verification when present.
 */
fun Note.toDto(): NoteDto =
    NoteDto(
        key = key,
        role = role.lowercase(),
        body = body,
        createdAt = createdAt.toString(),
        modifiedAt = modifiedAt.toString(),
        etag = etagFor(modifiedAt),
        actor = actorClaim?.toDto(),
        verification = verification?.toDto(),
    )

// ─────────────────────────────────────────────────────────────────────────────
// DependencyMapper
// ─────────────────────────────────────────────────────────────────────────────

/** Maps a [Dependency] domain entity to a [DependencyEdgeDto]. */
fun Dependency.toDto(): DependencyEdgeDto =
    DependencyEdgeDto(
        id = id.toString(),
        fromItemId = fromItemId.toString(),
        toItemId = toItemId.toString(),
        type = type.name.lowercase(),
        unblockAt = unblockAt ?: if (type == DependencyType.BLOCKS) "terminal" else null,
        createdAt = createdAt.toString(),
    )

/**
 * Builds a [DependenciesDto] from a flat list of [Dependency] objects relative to [itemId].
 *
 * Categorises each dependency:
 * - `blocks`: edges where this item is the FROM side and type is BLOCKS
 * - `blockedBy`: edges where this item is the TO side and type is BLOCKS (or IS_BLOCKED_BY)
 * - `related`: edges of type RELATES_TO (regardless of direction)
 */
fun buildDependenciesDto(
    itemId: String,
    deps: List<Dependency>,
): DependenciesDto {
    val blocks = mutableListOf<DependencyEdgeDto>()
    val blockedBy = mutableListOf<DependencyEdgeDto>()
    val related = mutableListOf<DependencyEdgeDto>()

    for (dep in deps) {
        when (dep.type) {
            DependencyType.RELATES_TO -> related.add(dep.toDto())
            DependencyType.BLOCKS -> {
                if (dep.fromItemId.toString() == itemId) {
                    blocks.add(dep.toDto())
                } else {
                    blockedBy.add(dep.toDto())
                }
            }
            DependencyType.IS_BLOCKED_BY -> {
                // IS_BLOCKED_BY is a logical alias: fromItem is blocked by toItem
                if (dep.fromItemId.toString() == itemId) {
                    blockedBy.add(dep.toDto())
                } else {
                    blocks.add(dep.toDto())
                }
            }
        }
    }

    return DependenciesDto(blocks = blocks, blockedBy = blockedBy, related = related)
}

// ─────────────────────────────────────────────────────────────────────────────
// RoleTransitionMapper
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Maps a [RoleTransition] domain entity to a [RoleTransitionDto].
 *
 * `actor` and `verification` are included; callers that expose the transition to the API
 * should call [redactActorProofIfNeeded] / [redactVerification] from
 * [io.github.jpicklyk.mcptask.current.interfaces.api.v1.redaction.AttributionRedactor]
 * as needed.
 */
fun RoleTransition.toDto(): RoleTransitionDto =
    RoleTransitionDto(
        id = id.toString(),
        itemId = itemId.toString(),
        fromRole = fromRole.takeIf { it.isNotBlank() },
        toRole = toRole,
        trigger = trigger,
        statusLabel = toStatusLabel,
        occurredAt = transitionedAt.toString(),
        actor = actorClaim?.toDto(),
        verification = verification?.toDto(),
    )
