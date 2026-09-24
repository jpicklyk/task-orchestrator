package io.github.jpicklyk.mcptask.current.domain.model

import java.util.UUID

/**
 * Thrown by [io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService] when a
 * per-root config read for [rootId] fails (the underlying [io.github.jpicklyk.mcptask.current.domain.repository.ProjectConfigRepository]
 * returned a `Result.Error`) AND there is no last-known-good cached parse to serve instead.
 *
 * This is deliberately an unchecked exception rather than a new arm on a sealed result type: the
 * per-root config resolvers (`PerRootConfigService`, `ToolExecutionContext`) have roughly fifteen
 * call sites across application and interface code, and none of their public signatures change
 * because of this failure mode. Callers that need to convert this into a specific tool/HTTP outcome
 * catch it explicitly at their own boundary (see `McpToolAdapter`, `AdvanceItemTool`,
 * `CompleteTreeTool`, `ItemWriteRoutes`, `ItemRoutes`); callers that don't catch it simply propagate
 * it, which is the fail-closed behavior this exception exists to guarantee — a config read error
 * must never be silently treated as "no per-root config, fall back to the global layer".
 *
 * The error-code literal callers should surface for this condition is `"config_unavailable"`
 * (MCP `errorCode` / REST `ErrorDto.error`), with `errorKind` `"transient"` — the caller is expected
 * to apply its own backoff, per the documented `ErrorKind` contract.
 */
class PerRootConfigUnavailableException(
    val rootId: UUID,
    override val message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause) {
    companion object {
        /** MCP `errorCode` / REST `ErrorDto.error` literal for this failure mode. */
        const val CODE = "config_unavailable"
    }
}
