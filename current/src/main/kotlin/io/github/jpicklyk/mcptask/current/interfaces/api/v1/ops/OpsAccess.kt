package io.github.jpicklyk.mcptask.current.interfaces.api.v1.ops

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.Principal
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.toCallPrincipal
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.CallAccess

/**
 * The [CallAccess] a REST call runs under: the credential principal as the actor (`api:<id>`, see
 * [toCallPrincipal]), its scope and its capabilities.
 *
 * Note attribution is visible unless [redactNoteAttribution] (`API_REDACT_NOTE_ATTRIBUTION`, default on) is set and the
 * caller lacks ADMIN: the rule the REST note routes always applied. The one definition, shared by `POST /ops/{tool}`, the
 * note resource GETs and the parity harness, so MCP and REST agree byte for byte.
 */
fun Principal.toCallAccess(redactNoteAttribution: Boolean): CallAccess =
    CallAccess(
        principal = toCallPrincipal(),
        scope = scope,
        capabilities = capabilities,
        attributionVisible = !redactNoteAttribution || ApiCapability.ADMIN in capabilities
    )
