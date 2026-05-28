package io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth

import java.util.UUID

/**
 * The resolved, request-scoped identity of an authenticated API caller.
 *
 * Produced by [AuthenticationPlugin] after successful bearer-token or JWT verification,
 * and stored in `call.attributes` for the lifetime of one HTTP request.
 *
 * @param tokenId Stable identifier for the credential: for bearer mode this is the `id`
 *   field from the secret file; for JWKS mode this is the JWT `sub` claim.
 * @param scope Structural scope restricting which items the principal may access.
 * @param capabilities Set of granted operations. Empty set means no access at all (should
 *   not be issued in practice but is handled gracefully by the authorization layer).
 * @param authMode How this principal was authenticated.
 */
data class ApiPrincipal(
    val tokenId: String,
    val scope: ApiScope,
    val capabilities: Set<ApiCapability>,
    val authMode: ApiAuthMode,
)

/**
 * Structural scope filter attached to an authenticated principal.
 *
 * Scope enforcement walks the item's ancestor chain.  A null [rootIds] means the
 * principal has unrestricted access (all items visible).  A non-null [rootIds] means
 * the principal may only access items whose ancestor chain (including itself) contains
 * at least one of the listed root UUIDs.
 *
 * [tagsInclude] applies to the item directly (does NOT walk ancestors).  When non-empty,
 * the item must carry at least one of the listed tags in addition to satisfying the root
 * constraint.
 *
 * @param rootIds Anchoring root UUIDs, or null for unrestricted scope.
 * @param tagsInclude Tag allowlist; empty = no tag constraint.
 */
data class ApiScope(
    val rootIds: Set<UUID>?,
    val tagsInclude: Set<String>,
)

/**
 * Operations that may be granted to an API principal.
 *
 * Capabilities are **additive** — a principal with [READ] and [WRITE_NOTES] cannot create
 * items.  [ADMIN] implies all other capabilities and is reserved for future operator-only
 * endpoints.
 */
enum class ApiCapability {
    /** All GET (read) endpoints. */
    READ,

    /** PUT/DELETE on note endpoints. */
    WRITE_NOTES,

    /** POST/PATCH/DELETE on item endpoints. */
    WRITE_ITEMS,

    /** POST on advance-item endpoint. */
    ADVANCE,

    /** POST/DELETE on dependency endpoints. */
    MANAGE_DEPENDENCIES,

    /** Future: any operator-only endpoint; also implies all of the above. */
    ADMIN,
    ;

    companion object {
        /**
         * Parses a capability from its canonical string form as used in the secret file
         * and JWT claims.  Values use kebab-case to match the YAML convention:
         * `read`, `write-notes`, `write-items`, `advance`, `manage-dependencies`, `admin`.
         *
         * @throws IllegalArgumentException for unknown values.
         */
        fun fromConfigString(value: String): ApiCapability =
            when (value.lowercase().trim()) {
                "read" -> READ
                "write-notes" -> WRITE_NOTES
                "write-items" -> WRITE_ITEMS
                "advance" -> ADVANCE
                "manage-dependencies" -> MANAGE_DEPENDENCIES
                "admin" -> ADMIN
                else -> throw IllegalArgumentException(
                    "Unknown capability '$value'. Valid values: read, write-notes, write-items, " +
                        "advance, manage-dependencies, admin",
                )
            }
    }
}

/** Which authentication mechanism produced this principal. */
enum class ApiAuthMode {
    /** Static SHA-256-hashed bearer token loaded from the secret file. */
    BEARER,

    /** JWT validated against a JWKS endpoint. */
    JWKS,
}
