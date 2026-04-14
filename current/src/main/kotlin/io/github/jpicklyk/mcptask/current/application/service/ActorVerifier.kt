package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus

/**
 * Verifies the authenticity of an actor claim.
 */
interface ActorVerifier {
    suspend fun verify(actor: ActorClaim): VerificationResult
}

/**
 * No-op verifier that marks all claims as unverified without performing any checks.
 * Used as the default implementation until real verification is configured.
 */
object NoOpActorVerifier : ActorVerifier {
    override suspend fun verify(actor: ActorClaim): VerificationResult =
        VerificationResult(status = VerificationStatus.UNVERIFIED, verifier = "noop")
}
