package io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto

import kotlinx.serialization.Serializable

/**
 * Response DTO for `GET /api/v1/roots/{rootId}/rules/{key}`.
 *
 * Mirrors the `data` payload of the MCP `query_rules` tool's `get` operation (direct rootId+key
 * lookup) -- both surfaces converge on the same
 * [io.github.jpicklyk.mcptask.current.application.service.RuleService]. `rulesVersion` is the
 * backing plan document's `contentHash`; `body` is served byte-for-byte as stored.
 */
@Serializable
data class RuleResponseDto(
    val rootId: String,
    val key: String,
    val rulesVersion: String,
    val body: String,
)

/** One row of `GET /api/v1/roots/{rootId}/rules` -- metadata only, never the body. */
@Serializable
data class RuleSummaryDto(
    val key: String,
    val rulesVersion: String,
    val updatedAt: String,
)

/** Response DTO for `GET /api/v1/roots/{rootId}/rules`. */
@Serializable
data class RuleListResponseDto(
    val rootId: String,
    val rules: List<RuleSummaryDto>,
)
