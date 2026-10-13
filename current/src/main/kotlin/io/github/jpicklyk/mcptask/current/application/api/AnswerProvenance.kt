package io.github.jpicklyk.mcptask.current.application.api

import java.util.UUID

/**
 * Who answered, and through what: the provenance tuple a recorded answer carries.
 *
 * @property principalId The audited actor id (`api:<id>` for a REST principal).
 * @property kind The principal's kind.
 * @property proofStatus How the principal's credential was proven.
 * @property host The client application name, or null.
 * @property sessionId The session the call belongs to; null until sessions exist (R14).
 * @property runId The run the call was made under, or null.
 * @property seat The seat within that run, or null.
 * @property profile The wire name of the tool view that served the call (`rest`, `director`, ...), or null.
 */
data class AnswerProvenance(
    val principalId: String,
    val kind: PrincipalKind,
    val proofStatus: ProofStatus,
    val host: String?,
    val sessionId: String?,
    val runId: UUID?,
    val seat: String?,
    val profile: String?
)
