package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Per-call memo for actor-claim verification results, keyed by the SHA-256 hash of the presented
 * proof (JWT string).
 *
 * The same proof is legitimately verified more than once within a single MCP tool call — e.g.
 * `AdvanceItemTool` verifies once for an idempotency-cache lookup and again per transition,
 * `ManageNotesTool`/`CompleteTreeTool` verify once per note/item — and a proof is also reused
 * across separate calls (heartbeats). An opt-in, one-use [io.github.jpicklyk.mcptask.current.infrastructure.config.JtiReplayCache]
 * would otherwise reject the second in-call verification of the very same proof as a "replay".
 *
 * Installing one instance of this element in the [CoroutineContext] for the lifetime of a single
 * MCP call (see `McpToolAdapter`) lets [io.github.jpicklyk.mcptask.current.application.tools.ActorAware.parseActorClaim]
 * reuse the first verification result for a repeated proof within that call, so the underlying
 * verifier (and any replay cache it consults) is invoked at most once per distinct proof per call.
 *
 * Scope is strictly per-call: a fresh instance must be installed for every call, never shared
 * server-wide — reusing one across calls would silently disable replay protection for repeated
 * proofs across calls, which is exactly the case the replay cache exists to catch. When no element
 * is present in the coroutine context (e.g. direct in-process invocations, tests), callers fall
 * back to verifying every time — see [ActorAware.parseActorClaim][io.github.jpicklyk.mcptask.current.application.tools.ActorAware].
 */
class ActorVerificationScope : AbstractCoroutineContextElement(Key) {
    /** proof SHA-256 hex -> verification result, memoized for the lifetime of one MCP call. */
    val memo: ConcurrentHashMap<String, VerificationResult> = ConcurrentHashMap()

    companion object Key : CoroutineContext.Key<ActorVerificationScope>
}
