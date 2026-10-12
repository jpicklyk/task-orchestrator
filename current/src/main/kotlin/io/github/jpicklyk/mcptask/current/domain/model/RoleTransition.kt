package io.github.jpicklyk.mcptask.current.domain.model

import java.time.Instant
import java.util.UUID

/**
 * One applied role change on a WorkItem: the in-memory value an advance result is built from. It is persisted as an
 * `item.transitioned` event (the audit record), not as a row of its own.
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
    val transitionedAt: Instant = Instant.now(),
    val actorClaim: ActorClaim? = null,
    val verification: VerificationResult? = null,
    /**
     * Opaque credential/secret labels consumed by this transition (e.g. "vault/prod-db-password",
     * "github-pat-ci") — never raw secret material. Optional audit trail; empty when not supplied.
     */
    val consumedCredentials: List<String> = emptyList()
)
