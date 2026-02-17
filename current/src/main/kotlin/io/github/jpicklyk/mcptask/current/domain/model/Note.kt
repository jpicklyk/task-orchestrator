package io.github.jpicklyk.mcptask.current.domain.model

import io.github.jpicklyk.mcptask.current.domain.validation.ValidationException
import java.time.Instant
import java.util.UUID

/**
 * A Note is an accountability artifact attached to a WorkItem.
 * Notes replace v2 Sections — simpler, key-based, role-scoped.
 *
 * UNIQUE constraint: (itemId, key) — one note per key per item.
 */
data class Note(
    val id: UUID = UUID.randomUUID(),
    val itemId: UUID,
    val key: String,
    val role: String,
    val body: String = "",
    val createdAt: Instant = Instant.now(),
    val modifiedAt: Instant = Instant.now()
) {
    init {
        validate()
    }

    fun validate() {
        if (key.isBlank()) throw ValidationException("Note key must not be blank")
        if (key.length > 200) throw ValidationException("Note key must not exceed 200 characters")
        val validRoles = setOf("queue", "work", "review")
        if (role.lowercase() !in validRoles) {
            throw ValidationException("Note role must be one of: queue, work, review. Got: '$role'")
        }
    }
}
