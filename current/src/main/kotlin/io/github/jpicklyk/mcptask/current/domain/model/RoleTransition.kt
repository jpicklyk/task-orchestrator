package io.github.jpicklyk.mcptask.current.domain.model

import java.time.Instant
import java.util.UUID

/**
 * Audit trail entry for a role change on a WorkItem.
 * Records the transition, trigger, and optional summary.
 */
data class RoleTransition(
    val id: UUID = UUID.randomUUID(),
    val itemId: UUID,
    val fromRole: String,
    val toRole: String,
    val fromStatusLabel: String? = null,
    val toStatusLabel: String? = null,
    val trigger: String,
    val summary: String? = null,
    val transitionedAt: Instant = Instant.now()
) {
    companion object {
        val VALID_TRIGGERS = setOf("start", "complete", "block", "hold", "resume", "cancel")
    }
}
