package io.github.jpicklyk.mcptask.current.domain.model

import io.github.jpicklyk.mcptask.current.domain.validation.ValidationException
import java.time.Instant
import java.util.UUID

/**
 * A Note is an accountability artifact attached to a WorkItem.
 * Notes replace v2 Sections — simpler, key-based, role-scoped.
 *
 * UNIQUE constraint: (itemId, key) — one note per key per item.
 *
 * [version] is a concurrency token and cache key, not a history: the note store owns it (1 on create, +1 on every
 * successful upsert of an existing note), so the value a caller builds a note with is ignored by the store.
 */
data class Note(
    val id: UUID = UUID.randomUUID(),
    val itemId: UUID,
    val key: String,
    val role: String,
    val body: String = "",
    val createdAt: Instant = Instant.now(),
    val modifiedAt: Instant = Instant.now(),
    val actorClaim: ActorClaim? = null,
    val verification: VerificationResult? = null,
    val version: Long = 1L
) {
    init {
        validate()
    }

    fun validate() {
        if (key.isBlank()) throw ValidationException("Note key must not be blank")
        if (key.length > 200) throw ValidationException("Note key must not exceed 200 characters")
        if (version < 1) throw ValidationException("Note version must be at least 1")
        val validRoles = setOf("queue", "work", "review")
        if (role.lowercase() !in validRoles) {
            throw ValidationException("Note role must be one of: queue, work, review. Got: '$role'")
        }
    }
}
