package io.github.jpicklyk.mcptask.current.application.tools

import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

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
 * Tools opt into actor support by implementing this interface rather than inheriting
 * the logic from [BaseToolDefinition] (which is common to all tools).
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
        val verification = context.actorVerifier().verify(claim)
        return ActorParseResult.Success(claim, verification)
    }
}
