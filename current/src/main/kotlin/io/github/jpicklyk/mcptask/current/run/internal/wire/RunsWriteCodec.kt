package io.github.jpicklyk.mcptask.current.run.internal.wire

import io.github.jpicklyk.mcptask.current.platform.idempotency.IdempotencyCodec
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The runs_write idempotency codec. A token is shown once: [encode] drops the run token (open: `orchestratorToken` and its
 * deprecated alias) or `seatToken` (assign) from the result before it is stored and marks it `tokenRedacted: true`, so the idempotency store never
 * holds a credential and a keyed replay returns the result without the token. Recovery: re-assign the seat (it
 * supersedes the assignment and issues a fresh token) or, for the run token, `reissue` it (director or human).
 * The first, live response is not encoded and carries the token.
 */
internal object RunsWriteCodec : IdempotencyCodec<JsonObject> {
    /** The result keys that carry a token. */
    val TOKEN_KEYS: Set<String> =
        setOf(RunWireKeys.ORCHESTRATOR_TOKEN, RunWireKeys.DEPRECATED_ALIASES.getValue(RunWireKeys.ORCHESTRATOR_TOKEN), "seatToken")

    const val TOKEN_REDACTED = "tokenRedacted"

    override fun encode(value: JsonObject): JsonElement {
        if (TOKEN_KEYS.none { it in value }) return value
        return JsonObject(value.filterKeys { it !in TOKEN_KEYS } + (TOKEN_REDACTED to JsonPrimitive(true)))
    }

    /** A result a 4.0.x server stored carries only the deprecated keys: [RunWireKeys.withCanonical] adds the canonical ones. */
    override fun decode(json: JsonElement): JsonObject = RunWireKeys.withCanonical(json as JsonObject)
}
