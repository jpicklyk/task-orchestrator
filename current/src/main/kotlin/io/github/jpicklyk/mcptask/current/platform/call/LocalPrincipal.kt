package io.github.jpicklyk.mcptask.current.platform.call

import io.github.jpicklyk.mcptask.current.platform.port.AccessScope

/**
 * The one local principal (V10): every call no credential establishes runs as it. Stdio, HTTP `/mcp` in local mode
 * (`MCP_ALLOW_UNAUTHENTICATED=true` with the API off, or `API_AUTH_MODE=none`) and REST in unauthenticated mode
 * (`API_AUTH_MODE=none` + `API_ALLOW_UNAUTHENTICATED=true`) all attach it, so ownership, claims and idempotency keys
 * meet across hosts. [ApiCapability.ADMIN] and an unrestricted scope, so every capability and scope check passes
 * unchanged. Kind [PrincipalKind.AGENT], proof [ProofStatus.NONE]; audited as the bare id `local` (no `api:` prefix).
 */
val LOCAL_PRINCIPAL =
    Principal(
        id = "local",
        kind = PrincipalKind.AGENT,
        proofStatus = ProofStatus.NONE,
        scope = AccessScope.unrestricted(),
        capabilities = setOf(ApiCapability.ADMIN)
    )
