package io.github.jpicklyk.mcptask.current.interfaces.api.v1.ops

import io.github.jpicklyk.mcptask.current.application.api.CallPrincipal
import io.github.jpicklyk.mcptask.current.infrastructure.identity.ApiCapability
import io.github.jpicklyk.mcptask.current.infrastructure.identity.ApiPrincipal
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.audit.ApiAuditBridge
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.toAccessScope
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.CallAccess

/**
 * The [CallAccess] a REST call runs under: the bearer principal as the actor, its scope and its capabilities.
 *
 * Note attribution is visible unless [redactNoteAttribution] (`API_REDACT_NOTE_ATTRIBUTION`, default on) is set and the
 * caller lacks ADMIN: the rule the REST note routes always applied. The one definition, shared by `POST /ops/{tool}`, the
 * note resource GETs and the parity harness, so MCP and REST agree byte for byte.
 */
fun ApiPrincipal.toCallAccess(redactNoteAttribution: Boolean): CallAccess =
    CallAccess(
        principal = CallPrincipal(ApiAuditBridge.toActorClaim(this), ApiAuditBridge.toVerificationResult(this)),
        scope = toAccessScope(),
        capabilities = capabilities,
        attributionVisible = !redactNoteAttribution || ApiCapability.ADMIN in capabilities
    )
