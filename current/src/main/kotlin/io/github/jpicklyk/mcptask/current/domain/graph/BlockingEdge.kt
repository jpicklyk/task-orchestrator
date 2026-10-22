package io.github.jpicklyk.mcptask.current.domain.graph

import io.github.jpicklyk.mcptask.current.domain.model.Role
import java.util.UUID

/**
 * A normalized blocking dependency: [blocker] must reach [unblockAt] (in the QUEUE < WORK < REVIEW
 * < TERMINAL progression) before [blocked] may advance. IS_BLOCKED_BY is only an input alias; every
 * blocking relation is represented in this one orientation.
 */
data class BlockingEdge(
    val blocker: UUID,
    val blocked: UUID,
    val unblockAt: Role = Role.TERMINAL
) {
    init {
        require(blocker != blocked) { "blocker and blocked must differ" }
        require(unblockAt != Role.BLOCKED) { "unblockAt must be a progression role, not BLOCKED" }
    }
}

/** A non-blocking RELATES_TO link. Never participates in gating, cycles or ordering. */
data class RelatesEdge(
    val from: UUID,
    val to: UUID
)
