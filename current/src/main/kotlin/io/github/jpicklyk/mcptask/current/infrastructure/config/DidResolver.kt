package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.domain.model.DidDocument

interface DidResolver {
    /** Method name this resolver handles, e.g. "web", "key". */
    val method: String

    /** Resolve the DID and return its document. */
    suspend fun resolve(did: String): DidDocument
}

class DidResolutionException(message: String, cause: Throwable? = null) : Exception(message, cause)
