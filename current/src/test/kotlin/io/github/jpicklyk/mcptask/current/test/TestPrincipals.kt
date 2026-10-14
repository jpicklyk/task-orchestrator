package io.github.jpicklyk.mcptask.current.test

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.Principal
import io.github.jpicklyk.mcptask.current.application.api.PrincipalKind
import io.github.jpicklyk.mcptask.current.application.api.ProofStatus
import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.ops.toCallAccess
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.CallAccess
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.Dispatched
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolDispatcher
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolView
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A test caller named [id]: a principal no credential established ([ProofStatus.NONE]), so it is audited as the bare
 * [id] (exactly as the local principal is audited as `local`). Since R14 no request body names its own actor; tests that
 * need several distinct callers (claim holders, idempotency namespaces, event attribution) pass one of these as the
 * call's access instead.
 */
fun testPrincipal(
    id: String,
    capabilities: Set<ApiCapability> = setOf(ApiCapability.ADMIN),
    scope: AccessScope = AccessScope.unrestricted(),
    kind: PrincipalKind = PrincipalKind.AGENT
): Principal = Principal(id = id, kind = kind, proofStatus = ProofStatus.NONE, scope = scope, capabilities = capabilities)

/** The [CallAccess] of [testPrincipal] [id] (note attribution visible, as for an ADMIN caller). */
fun testAccess(
    id: String,
    capabilities: Set<ApiCapability> = setOf(ApiCapability.ADMIN),
    scope: AccessScope = AccessScope.unrestricted()
): CallAccess = testPrincipal(id, capabilities, scope).toCallAccess(redactNoteAttribution = false)

/**
 * Test-harness translation for fixtures written before R14: a top-level `actor` object in [arguments] selects the
 * caller ([testAccess] of its `id`) and is removed from the arguments the tool receives. Without one, the call runs as
 * [default]. Only the test rigs use it; the server itself never reads a body `actor` (it is `unknown_parameter`).
 */
fun liftTestActor(
    arguments: JsonObject,
    default: CallAccess = CallAccess.LOCAL
): Pair<JsonObject, CallAccess> {
    val actor = arguments["actor"] as? JsonObject ?: return arguments to default
    val id = (actor["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return arguments to default
    return JsonObject(arguments - "actor") to testAccess(id)
}

/** [ToolDispatcher.dispatch] with a fixture's top-level `actor` selecting the caller ([liftTestActor]). */
suspend fun ToolDispatcher.dispatchAsTestActor(
    spec: ToolSpec,
    profile: ToolView,
    arguments: JsonObject,
    reqId: String,
    surface: String
): Dispatched {
    val (args, access) = liftTestActor(arguments)
    return dispatch(spec, profile, args, reqId, surface, access)
}
