package io.github.jpicklyk.mcptask.current.interfaces.api.v1.ops

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.Principal
import io.github.jpicklyk.mcptask.current.interfaces.shared.auth.toCallPrincipal
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.CallAccess

/**
 * The [CallAccess] a call runs under on every transport (REST, `/mcp` over HTTP, stdio): the transport principal as
 * the actor (`api:<id>` for a credential, `local` for the local principal, see [toCallPrincipal]), its scope and its
 * capabilities.
 *
 * Note attribution is visible unless [redactNoteAttribution] (`API_REDACT_NOTE_ATTRIBUTION`, default on) is set and the
 * caller lacks ADMIN: the rule the REST note routes always applied. The one definition, shared by `POST /ops/{tool}`, the
 * note resource GETs, the MCP session access and the parity harness, so MCP and REST agree byte for byte.
 */
fun Principal.toCallAccess(redactNoteAttribution: Boolean): CallAccess =
    CallAccess(
        principal = toCallPrincipal(),
        scope = scope,
        capabilities = capabilities,
        attributionVisible = !redactNoteAttribution || ApiCapability.ADMIN in capabilities
    )
