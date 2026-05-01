package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.domain.model.DidDocument

interface DidResolver {
    /** Method name this resolver handles, e.g. "web", "key". */
    val method: String

    /** Resolve the DID and return its document. */
    suspend fun resolve(did: String): DidDocument
}

open class DidResolutionException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Thrown when a resolved DID document violates a security invariant — for example,
 * when the returned document's `id` does not match the requested DID (substitution attack)
 * or the document is structurally invalid in a way that indicates tampering.
 *
 * This is a subtype of [DidResolutionException] so existing `catch (e: DidResolutionException)`
 * sites continue to match. Consumers that need to distinguish policy violations from transient
 * network/parse errors can `catch (e: DidSecurityViolationException)` first.
 */
class DidSecurityViolationException(
    message: String,
    cause: Throwable? = null
) : DidResolutionException(message, cause)
