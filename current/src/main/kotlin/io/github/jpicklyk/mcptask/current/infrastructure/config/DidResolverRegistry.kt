package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.domain.model.DidDocument

class DidResolverRegistry(
    resolvers: List<DidResolver>
) {
    private val byMethod: Map<String, DidResolver> = resolvers.associateBy { it.method }

    suspend fun resolve(did: String): DidDocument {
        val method = parseMethod(did) ?: throw DidResolutionException("not a DID: $did")
        val resolver = byMethod[method] ?: throw DidResolutionException("no resolver for did:$method")
        return resolver.resolve(did)
    }

    internal fun parseMethod(did: String): String? {
        if (!did.startsWith("did:")) return null
        val afterPrefix = did.substring(4)
        val colon = afterPrefix.indexOf(':')
        return if (colon > 0) afterPrefix.substring(0, colon) else null
    }
}
