package io.github.jpicklyk.mcptask.current.domain.model

import java.util.UUID

/**
 * A root's head config version cannot be loaded (NULL or undecodable `canonical_json`). Fails closed: the root
 * is never treated as having no config, and resolution never falls through to the global layer.
 *
 * Deliberately NOT a [PerRootConfigUnavailableException]: that one is a transient read fault; this is a data fault.
 */
class PerRootConfigInvalidException(
    /** The project root whose head is unloadable; null = the server-scope head. */
    val rootId: UUID?,
    val version: Int,
    detail: String
) : RuntimeException() {
    override val message: String =
        if (rootId == null) {
            "Server config (version $version) is invalid: $detail"
        } else {
            "Per-root config for root $rootId (version $version) is invalid: $detail"
        }

    companion object {
        /** MCP `errorCode` / REST `ErrorDto.error` literal for this failure mode. */
        const val CODE = "config_invalid"
    }
}
