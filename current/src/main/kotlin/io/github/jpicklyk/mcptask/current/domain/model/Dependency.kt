package io.github.jpicklyk.mcptask.current.domain.model

import io.github.jpicklyk.mcptask.current.domain.validation.ValidationException
import java.time.Instant
import java.util.UUID

/**
 * A directed dependency between two WorkItems.
 *
 * UNIQUE constraint: (fromItemId, toItemId, type)
 * ON DELETE CASCADE on both foreign keys.
 */
data class Dependency(
    val id: UUID = UUID.randomUUID(),
    val fromItemId: UUID,
    val toItemId: UUID,
    val type: DependencyType = DependencyType.BLOCKS,
    val unblockAt: String? = null,
    val createdAt: Instant = Instant.now()
) {
    init {
        validate()
    }

    fun validate() {
        if (fromItemId == toItemId) throw ValidationException("A dependency cannot reference the same item on both sides")
        if (type == DependencyType.RELATES_TO && unblockAt != null) {
            throw ValidationException("RELATES_TO dependencies cannot have an unblockAt threshold (no blocking semantics)")
        }
        unblockAt?.let { threshold ->
            val validThresholds = setOf("queue", "work", "review", "terminal")
            if (threshold.lowercase() !in validThresholds) {
                throw ValidationException("unblockAt must be one of: queue, work, review, terminal. Got: '$threshold'")
            }
        }
    }

    /**
     * Returns the effective unblock role threshold.
     * If unblockAt is null, defaults to "terminal".
     * Returns null for RELATES_TO dependencies (no blocking semantics).
     */
    fun effectiveUnblockRole(): String? {
        if (type == DependencyType.RELATES_TO) return null
        return unblockAt ?: "terminal"
    }

    /**
     * Returns the UUID of the item that BLOCKS the other side of this dependency, or null for
     * RELATES_TO (no blocking semantics).
     *
     * - BLOCKS: `fromItemId` blocks `toItemId` -> blocker is [fromItemId].
     * - IS_BLOCKED_BY: `fromItemId` is blocked by `toItemId` -> blocker is [toItemId].
     */
    fun blockerId(): UUID? =
        when (type) {
            DependencyType.BLOCKS -> fromItemId
            DependencyType.IS_BLOCKED_BY -> toItemId
            DependencyType.RELATES_TO -> null
        }

    /**
     * Returns the UUID of the item that is BLOCKED by the other side of this dependency, or null
     * for RELATES_TO (no blocking semantics).
     *
     * - BLOCKS: `fromItemId` blocks `toItemId` -> blocked is [toItemId].
     * - IS_BLOCKED_BY: `fromItemId` is blocked by `toItemId` -> blocked is [fromItemId].
     */
    fun blockedId(): UUID? =
        when (type) {
            DependencyType.BLOCKS -> toItemId
            DependencyType.IS_BLOCKED_BY -> fromItemId
            DependencyType.RELATES_TO -> null
        }
}
