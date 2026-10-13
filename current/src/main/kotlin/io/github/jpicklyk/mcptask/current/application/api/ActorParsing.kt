package io.github.jpicklyk.mcptask.current.application.api

import io.github.jpicklyk.mcptask.current.application.service.ActorVerificationScope
import io.github.jpicklyk.mcptask.current.application.support.sha256Hex
import io.github.jpicklyk.mcptask.current.application.telemetry.recordCallPrincipal
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.coroutineContext

/**
 * Result of parsing an optional actor claim from a JSON object.
 */
sealed class ActorParseResult {
    /** Actor was present and parsed successfully. */
    data class Success(
        val claim: ActorClaim,
        val verification: VerificationResult
    ) : ActorParseResult()

    /** No actor object was provided (legitimate absence). */
    data object Absent : ActorParseResult()

    /** Actor object was present but invalid. */
    data class Invalid(
        val error: String
    ) : ActorParseResult()
}

/**
 * Mixin interface for tools that accept optional actor claims on write operations.
 * Provides a shared [parseActorClaim] method with default implementation.
 *
 * Callers opt into actor support by implementing this interface.
 */
interface ActorAware {
    /**
     * Parses an optional `actor` JSON object, validates required fields,
     * constructs an [ActorClaim], and verifies it via the context's [ActorVerifier].
     *
     * @param actorObj The `actor` JSON object extracted from the input, or null if absent.
     * @param context The tool execution context (provides the actor verifier).
     * @return [ActorParseResult.Success] with claim and verification,
     *         [ActorParseResult.Absent] if no actor provided, or
     *         [ActorParseResult.Invalid] with an error message.
     */
    suspend fun parseActorClaim(
        actorObj: JsonObject?,
        context: ToolExecutionContext
    ): ActorParseResult {
        if (actorObj == null) return ActorParseResult.Absent

        val actorId =
            actorObj["id"]?.jsonPrimitive?.contentOrNull
                ?: return ActorParseResult.Invalid("actor.id is required")

        val actorKindStr =
            actorObj["kind"]?.jsonPrimitive?.contentOrNull
                ?: return ActorParseResult.Invalid("actor.kind is required")

        val actorKind =
            try {
                ActorKind.fromString(actorKindStr)
            } catch (e: IllegalArgumentException) {
                return ActorParseResult.Invalid("Invalid actor.kind: $actorKindStr")
            }

        val claim =
            ActorClaim(
                id = actorId,
                kind = actorKind,
                parent = actorObj["parent"]?.jsonPrimitive?.contentOrNull,
                proof = actorObj["proof"]?.jsonPrimitive?.contentOrNull
            )

        // Forensic hash: computed here (not in the verifier) so EVERY verifier — jwks, noop, and
        // any future one — gets identical, evidence-preserving behavior for free. Only set when a
        // non-blank proof was supplied; independent of verification outcome (REJECTED proofs are
        // still worth hashing — the hash is what lets an operator later confirm "was THIS specific
        // token ever presented", regardless of whether it validated). This same hash is also part
        // of the per-call memo key in ActorVerificationScope, so a repeated (proof, claim) pair
        // within one MCP call reuses the first verification result instead of re-invoking the
        // verifier (see that class's KDoc for why: an opt-in one-use replay cache would otherwise
        // reject the second in-call use of the very same proof). The key MUST include the full
        // claim identity (id/kind/parent), not just the proof hash — verification depends on the
        // claim too (e.g. require_sub_match checks claim.id against the JWT's sub), so a proof-only
        // key would let a second, differently-id'd claim presenting the same proof reuse the first
        // claim's VERIFIED result (forged identity). A memo hit must never yield a verification
        // result for a different (id, kind, parent) than the one that was actually verified.
        val proofSha256 = claim.proof?.takeIf { it.isNotBlank() }?.let { sha256Hex(it.toByteArray(Charsets.UTF_8)) }
        val scope = coroutineContext[ActorVerificationScope]
        val memoKey =
            proofSha256?.let {
                ActorVerificationScope.key(
                    proofSha256 = it,
                    actorId = claim.id,
                    actorKind = claim.kind,
                    actorParent = claim.parent
                )
            }
        val verification =
            if (scope != null && memoKey != null) {
                scope.memo[memoKey] ?: context.actorVerifier().verify(claim).also { scope.memo[memoKey] = it }
            } else {
                context.actorVerifier().verify(claim)
            }
        val hashedVerification = proofSha256?.let { verification.copy(proofSha256 = it) } ?: verification
        recordCallPrincipal(claim, hashedVerification)
        return ActorParseResult.Success(claim, hashedVerification)
    }
}
