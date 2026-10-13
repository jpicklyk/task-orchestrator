package io.github.jpicklyk.mcptask.current.interfaces.api.v1.ops

import io.github.jpicklyk.mcptask.current.application.api.ScopeGuard
import io.github.jpicklyk.mcptask.current.application.support.rethrowIfCancellation
import io.github.jpicklyk.mcptask.current.application.telemetry.ReqId
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.FieldViolation
import io.github.jpicklyk.mcptask.current.domain.error.ForbiddenScope
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiPrincipalKey
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.envelope.respondEnvelope
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.logging.OPS_CALL_INFO
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.logging.OpsCallInfo
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.logging.REQ_ID_HEADER
import io.github.jpicklyk.mcptask.current.interfaces.shared.envelope.EnvelopeEncoder
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolDispatcher
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolView
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.visibleNames
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * `POST /api/v1/ops/{tool}` (plan section 3.2): the canonical RPC route over the 4.0 tool registry. The body
 * is the tool's MCP arguments; the answer is the envelope, byte-equal to what MCP returns for the same
 * arguments and access, with the HTTP status derived from the error code.
 *
 * The principal comes from the bearer credential (`api:<tokenId>`, never a body `actor`, which is ignored),
 * so idempotency is namespaced per token. Capability is checked per select/op by the dispatcher with the
 * shared `grants` predicate, so this route installs no capability wrapper; scope reaches handlers through
 * `CallContext.scope`. Every fault answers an envelope: no authenticated principal is `unauthenticated`
 * (fail closed); an unknown tool, a non-JSON `Content-Type`, an `Idempotency-Key` header
 * (use `ops[].idempotencyKey`) or a body that is not a JSON object is `invalid_request`; a body over
 * [MAX_JSON_WRITE_BODY_BYTES] is `payload_too_large`. Serves [ToolView.Rest] by default: every registered select and op, REST-only members included. Note attribution is
 * hidden from a non-admin caller while [redactNoteAttribution] holds (see [toCallAccess]).
 */
fun Route.opsRoutes(
    registry: ToolRegistry,
    dispatcher: ToolDispatcher,
    profile: ToolView = ToolView.Rest,
    redactNoteAttribution: Boolean = true
) {
    post("/ops/{tool}") {
        serveOps(call, registry, dispatcher, profile, redactNoteAttribution, refuseHidden = false)
    }
}

/**
 * `POST /api/v1/ops/director/{tool}`: the director tool profile ([ToolProfile.DIRECTOR]) over REST, through the same
 * dispatcher and with the same envelopes as [opsRoutes]. A registered tool with no member in the director set is
 * `forbidden {scope: capability, required: director}`; an unregistered name is `invalid_request` listing the director
 * tools; a select or op hidden inside a visible tool is the dispatcher's `invalid_request`. This is the only path a
 * credential with the token-file `profile: director` may call (the auth plugins enforce that); any credential with
 * the capability a member needs may call it.
 */
fun Route.directorOpsRoutes(
    registry: ToolRegistry,
    dispatcher: ToolDispatcher,
    redactNoteAttribution: Boolean = true
) {
    post("/ops/director/{tool}") {
        serveOps(call, registry, dispatcher, ToolProfile.DIRECTOR, redactNoteAttribution, refuseHidden = true)
    }
}

/**
 * One ops call served under [profile]. With [refuseHidden], a registered tool [profile] does not show is `forbidden`
 * (the director path) rather than the `invalid_request` an unknown tool answers.
 */
private suspend fun serveOps(
    call: ApplicationCall,
    registry: ToolRegistry,
    dispatcher: ToolDispatcher,
    profile: ToolView,
    redactNoteAttribution: Boolean,
    refuseHidden: Boolean
) {
    run {
        val reqId = call.response.headers[REQ_ID_HEADER] ?: ReqId.generate()

        val principal = call.attributes.getOrNull(ApiPrincipalKey)
        if (principal == null) {
            call.respondEnvelope(EnvelopeEncoder.error(DomainError(ErrorCode.UNAUTHENTICATED, "No authenticated principal."), reqId))
            return
        }

        // CSRF guard: a cross-site form post cannot set a JSON Content-Type without a preflight.
        if (!hasJsonContentType(call)) {
            call.respondEnvelope(
                EnvelopeEncoder.error(
                    invalid("Content-Type", "must be application/json", call.request.header(HttpHeaders.ContentType)),
                    reqId
                )
            )
            return
        }
        if (call.request.header(IDEMPOTENCY_KEY_HEADER) != null) {
            call.respondEnvelope(
                EnvelopeEncoder.error(invalid(IDEMPOTENCY_KEY_HEADER, "is not supported here; use ops[].idempotencyKey"), reqId)
            )
            return
        }

        val text =
            when (val read = readBounded(call)) {
                is Read.TooLarge -> {
                    val error =
                        DomainError(
                            code = ErrorCode.PAYLOAD_TOO_LARGE,
                            message = "The request body exceeds $MAX_JSON_WRITE_BODY_BYTES bytes.",
                            detail = ErrorDetail.PayloadTooLarge(MAX_JSON_WRITE_BODY_BYTES.toLong(), read.actual),
                            fixArgs = mapOf("max" to MAX_JSON_WRITE_BODY_BYTES.toString())
                        )
                    call.respondEnvelope(EnvelopeEncoder.error(error, reqId))
                    return
                }
                is Read.Body -> read.text
            }
        val arguments =
            try {
                Json.parseToJsonElement(text) as? JsonObject
            } catch (e: IllegalArgumentException) {
                null
            }
        if (arguments == null) {
            call.respondEnvelope(EnvelopeEncoder.error(invalid("$", "must be a JSON object"), reqId))
            return
        }

        val toolName = call.parameters["tool"].orEmpty()
        val registered = registry[toolName]
        val spec = registered?.takeIf { visibleNames(it, profile).isNotEmpty() }
        if (spec == null) {
            if (registered != null && refuseHidden) {
                call.respondEnvelope(
                    EnvelopeEncoder.error(ScopeGuard.forbidden(ForbiddenScope.CAPABILITY, ToolProfile.DIRECTOR.wire), reqId)
                )
                return
            }
            val visible = registry.visible(profile).map { it.name }
            call.respondEnvelope(
                EnvelopeEncoder.error(invalid("tool", "must be one of: ${visible.joinToString(", ")}", toolName.take(MAX_RECEIVED)), reqId)
            )
            return
        }

        val access = principal.toCallAccess(redactNoteAttribution)
        val dispatched = dispatcher.dispatch(spec, profile, arguments, reqId, SURFACE_REST, access)
        call.attributes.put(OPS_CALL_INFO, OpsCallInfo(spec.name, dispatched))
        call.respondEnvelope(dispatched.envelope)
    }
}

/**
 * Byte cap of an `/ops/{tool}` request body (1 MiB, the cap the bounded-read safety limits elsewhere use, e.g.
 * `DidWebResolver.MAX_BODY_BYTES`): the body is read at most one byte past it and never buffered whole.
 */
const val MAX_JSON_WRITE_BODY_BYTES: Int = 1 * 1024 * 1024

/** The `surface` of every call a REST route dispatches. */
internal const val SURFACE_REST = "rest"

private const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"
private const val MAX_RECEIVED = 100

private fun invalid(
    field: String,
    reason: String,
    received: String? = null
): DomainError {
    val fields = listOf(FieldViolation(field, reason, received))
    return DomainError(
        code = ErrorCode.INVALID_REQUEST,
        message = "The request has 1 invalid field(s): $field.",
        detail = ErrorDetail.InvalidRequest(fields)
    )
}

/** True for an absent `Content-Type` or `application/json` with any parameters. */
private fun hasJsonContentType(call: ApplicationCall): Boolean {
    val raw = call.request.header(HttpHeaders.ContentType) ?: return true
    val parsed =
        try {
            ContentType.parse(raw)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            return false
        }
    return parsed.withoutParameters().match(ContentType.Application.Json)
}

private sealed interface Read {
    class Body(
        val text: String
    ) : Read

    class TooLarge(
        val actual: Long
    ) : Read
}

/** Reads the body, never buffering more than the cap plus one byte, whatever `Content-Length` declares. */
private suspend fun readBounded(call: ApplicationCall): Read {
    val max = MAX_JSON_WRITE_BODY_BYTES
    val declared = call.request.contentLength()
    if (declared != null && declared > max) return Read.TooLarge(declared)
    val bytes = call.receiveChannel().readRemaining(max.toLong() + 1).readByteArray()
    if (bytes.size > max) return Read.TooLarge(bytes.size.toLong())
    return Read.Body(bytes.decodeToString())
}
