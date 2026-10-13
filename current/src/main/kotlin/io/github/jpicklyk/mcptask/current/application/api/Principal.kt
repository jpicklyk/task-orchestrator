package io.github.jpicklyk.mcptask.current.application.api

import io.github.jpicklyk.mcptask.current.application.port.AccessScope

/**
 * Who a caller is, as established by a credential: the request-scoped identity of an authenticated REST caller.
 *
 * Built only by the credential pipeline (the token file or a verified JWT), never from a request body `actor`: in
 * particular [PrincipalKind.HUMAN] can come only from a credential source.
 *
 * @property id Stable credential id: the token-file entry `id`, or the JWT `sub`. Audited as `api:<id>`.
 * @property kind What sort of caller holds the credential.
 * @property proofStatus How the credential was proven.
 * @property scope What the caller may see.
 * @property capabilities What the caller may do; an empty set grants nothing.
 * @property host The client application name (`X-Client-Host`, else the `User-Agent` product), or null when unset.
 */
data class Principal(
    val id: String,
    val kind: PrincipalKind,
    val proofStatus: ProofStatus,
    val scope: AccessScope,
    val capabilities: Set<ApiCapability>,
    val host: String? = null
)

/** What sort of caller a [Principal] is. [wire] is the token-file `kind` / JWT `to_kind` spelling. */
enum class PrincipalKind(
    val wire: String
) {
    HUMAN("human"),
    AGENT("agent"),
    SERVICE("service");

    companion object {
        /**
         * Parses a kind (the token-file `kind` value or the JWT `to_kind` claim): the trimmed, case-insensitive
         * [wire] value; anything else (including blank) is null, an unknown kind. Callers decide what an unknown
         * kind means (the token file fails startup, a JWT is rejected); there is no default here.
         */
        fun parse(raw: String): PrincipalKind? {
            val text = raw.trim()
            return entries.firstOrNull { it.wire.equals(text, ignoreCase = true) }
        }
    }
}

/** How a [Principal]'s credential was proven. [wire] is its lowercase spelling. */
enum class ProofStatus(
    val wire: String
) {
    /** A JWT verified against a fresh key set. */
    VERIFIED("verified"),

    /** A JWT verified against a key set served from the stale cache after a failed refresh. */
    VERIFIED_STALE("verified_stale"),

    /** A server-issued static token from the token file. */
    ISSUED("issued"),

    /** No credential at all (unauthenticated mode). */
    NONE("none")
}
