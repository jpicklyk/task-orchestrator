package io.github.jpicklyk.mcptask.current.application.tools

import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import kotlinx.serialization.json.*

// ──────────────────────────────────────────────
// Enum formatting
// ──────────────────────────────────────────────

/** Canonical JSON representation of a [Role] value (lowercase enum name). */
fun Role.toJsonString(): String = this.name.lowercase()

/** Canonical JSON representation of a [Priority] value (lowercase enum name). */
fun Priority.toJsonString(): String = this.name.lowercase()

// ──────────────────────────────────────────────
// WorkItem serializers
// ──────────────────────────────────────────────

/**
 * Full JSON representation of a [WorkItem] with all fields.
 * Used for `get` operations and detailed single-item responses.
 */
fun WorkItem.toFullJson(): JsonObject =
    buildJsonObject {
        put("id", JsonPrimitive(id.toString()))
        parentId?.let { put("parentId", JsonPrimitive(it.toString())) }
        put("title", JsonPrimitive(title))
        description?.let { put("description", JsonPrimitive(it)) }
        put("summary", JsonPrimitive(summary))
        put("role", JsonPrimitive(role.toJsonString()))
        statusLabel?.let { put("statusLabel", JsonPrimitive(it)) }
        previousRole?.let { put("previousRole", JsonPrimitive(it.toJsonString())) }
        put("priority", JsonPrimitive(priority.toJsonString()))
        put("complexity", JsonPrimitive(complexity))
        put("depth", JsonPrimitive(depth))
        metadata?.let { put("metadata", JsonPrimitive(it)) }
        tags?.let { put("tags", JsonPrimitive(it)) }
        type?.let { put("type", JsonPrimitive(it)) }
        properties?.let { put("properties", JsonPrimitive(it)) }
        put("createdAt", JsonPrimitive(createdAt.toString()))
        put("modifiedAt", JsonPrimitive(modifiedAt.toString()))
        put("roleChangedAt", JsonPrimitive(roleChangedAt.toString()))
    }

/**
 * Minimal JSON representation of a [WorkItem] for list/search responses.
 * Includes only: id, parentId, title, role, priority, depth, tags.
 */
fun WorkItem.toMinimalJson(): JsonObject =
    buildJsonObject {
        put("id", JsonPrimitive(id.toString()))
        parentId?.let { put("parentId", JsonPrimitive(it.toString())) }
        put("title", JsonPrimitive(title))
        put("role", JsonPrimitive(role.toJsonString()))
        statusLabel?.let { put("statusLabel", JsonPrimitive(it)) }
        put("priority", JsonPrimitive(priority.toJsonString()))
        put("depth", JsonPrimitive(depth))
        tags?.let { put("tags", JsonPrimitive(it)) }
        type?.let { put("type", JsonPrimitive(it)) }
    }

/**
 * JSON object mapping each [Role] to its child count.
 * Used in overview responses for child-count-by-role summaries.
 */
fun roleCountToJson(counts: Map<Role, Int>): JsonObject =
    buildJsonObject {
        for (role in Role.entries) {
            put(role.toJsonString(), JsonPrimitive(counts[role] ?: 0))
        }
    }

// ──────────────────────────────────────────────
// Note serializer
// ──────────────────────────────────────────────

/**
 * JSON representation of a [Note] with optional body inclusion.
 * Used for both single-note and list responses.
 *
 * @param includeBody When false, omits the `body` field (metadata-only queries).
 */
fun Note.toJson(includeBody: Boolean = true): JsonObject =
    buildJsonObject {
        put("id", JsonPrimitive(id.toString()))
        put("itemId", JsonPrimitive(itemId.toString()))
        put("key", JsonPrimitive(key))
        put("role", JsonPrimitive(role))
        if (includeBody) put("body", JsonPrimitive(body))
        put("createdAt", JsonPrimitive(createdAt.toString()))
        put("modifiedAt", JsonPrimitive(modifiedAt.toString()))
        actorClaim?.let { put("actor", it.toJson()) }
        verification?.let { put("verification", it.toJson()) }
    }

// ──────────────────────────────────────────────
// Dependency serializer
// ──────────────────────────────────────────────

/**
 * JSON representation of a [Dependency] with core fields.
 * Tools that need additional fields (effectiveUnblockRole, item info) extend inline.
 */
fun Dependency.toJson(): JsonObject =
    buildJsonObject {
        put("id", JsonPrimitive(id.toString()))
        put("fromItemId", JsonPrimitive(fromItemId.toString()))
        put("toItemId", JsonPrimitive(toItemId.toString()))
        put("type", JsonPrimitive(type.name))
        unblockAt?.let { put("unblockAt", JsonPrimitive(it)) }
    }

// ── Actor attribution serializers ──

fun ActorClaim.toJson(): JsonObject =
    buildJsonObject {
        put("id", JsonPrimitive(id))
        put("kind", JsonPrimitive(kind.toJsonString()))
        parent?.let { put("parent", JsonPrimitive(it)) }
        proof?.let { put("proof", JsonPrimitive(it)) }
    }

fun VerificationResult.toJson(): JsonObject =
    buildJsonObject {
        put("status", JsonPrimitive(status.toJsonString()))
        verifier?.let { put("verifier", JsonPrimitive(it)) }
        reason?.let { put("reason", JsonPrimitive(it)) }
        if (metadata.isNotEmpty()) {
            put(
                "metadata",
                buildJsonObject {
                    metadata.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                }
            )
        }
    }
