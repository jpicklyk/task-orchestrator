package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Per-call memo for actor-claim verification results, keyed by the full claim identity that was
 * verified — the SHA-256 hash of the presented proof (JWT string) *plus* the claimed
 * `(id, kind, parent)` — never by the proof hash alone.
 *
 * Keying on the proof hash alone is unsound: verification outcome depends on the claim too (e.g.
 * `require_sub_match` checks `claim.id` against the JWT's `sub`), so a memo keyed only by proof
 * would let a second, differently-`id`'d claim presenting the *same* proof within one call reuse
 * the first claim's VERIFIED result — a forged-identity hole. **Invariant: a memo hit must never
 * yield a verification result for a different `(id, kind, parent)` than the one that was actually
 * verified.** [memo]'s key type stays the plain `String` it always was (declared public surface
 * unchanged) — callers build the composite key via [key], which folds the proof hash and the full
 * claim identity together with each field **length-prefixed** (`"<len>:<value>|..."`) rather than
 * delimiter-joined. A plain delimiter (even an unusual one like NUL) is not safe here because
 * `actorId`/`actorParent` are attacker-controlled: a claim whose `id` or `parent` itself contains
 * the delimiter could be crafted so two different `(id, kind, parent)` tuples serialize to the same
 * joined string, colliding in the map. Length-prefixing each field makes the encoding injective —
 * the field boundaries are unambiguous regardless of what characters (including NUL) the fields
 * contain — so no two distinct `(proofSha256, id, kind, parent)` tuples can ever produce the same
 * key.
 *
 * The same proof is legitimately verified more than once within a single MCP tool call — e.g.
 * `AdvanceItemTool` verifies once for an idempotency-cache lookup and again per transition,
 * `ManageNotesTool`/`CompleteTreeTool` verify once per note/item — and a proof is also reused
 * across separate calls (heartbeats). An opt-in, one-use [io.github.jpicklyk.mcptask.current.infrastructure.config.JtiReplayCache]
 * would otherwise reject the second in-call verification of the very same (proof, claim) as a
 * "replay".
 *
 * Installing one instance of this element in the [CoroutineContext] for the lifetime of a single
 * MCP call (see `McpToolAdapter`) lets [io.github.jpicklyk.mcptask.current.application.tools.ActorAware.parseActorClaim]
 * reuse the first verification result for a repeated (proof, claim) pair within that call, so the
 * underlying verifier (and any replay cache it consults) is invoked at most once per distinct
 * (proof, claim) pair per call.
 *
 * Scope is strictly per-call: a fresh instance must be installed for every call, never shared
 * server-wide — reusing one across calls would silently disable replay protection for repeated
 * proofs across calls, which is exactly the case the replay cache exists to catch. When no element
 * is present in the coroutine context (e.g. direct in-process invocations, tests), callers fall
 * back to verifying every time — see [ActorAware.parseActorClaim][ActorAware].
 */
class ActorVerificationScope : AbstractCoroutineContextElement(Key) {
    /** Composite memo key -> verification result, memoized for the lifetime of one MCP call. */
    val memo: ConcurrentHashMap<String, VerificationResult> = ConcurrentHashMap()

    companion object Key : CoroutineContext.Key<ActorVerificationScope> {
        /**
         * Builds the composite memo key for a (proof, claim) pair: the proof's SHA-256 hash plus
         * the full claim identity (`id`, `kind`, `parent`) that verification outcome depends on
         * (see class KDoc for why proof hash alone is unsound, and why each field is
         * length-prefixed rather than delimiter-joined — `actorId`/`actorParent` are
         * attacker-controlled and must not be able to force a collision).
         */
        fun key(
            proofSha256: String,
            actorId: String,
            actorKind: ActorKind,
            actorParent: String?
        ): String {
            val parent = actorParent ?: ""
            val kind = actorKind.name
            return "${proofSha256.length}:$proofSha256|${actorId.length}:$actorId|" +
                "${kind.length}:$kind|${parent.length}:$parent"
        }
    }
}
