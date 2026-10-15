package io.github.jpicklyk.mcptask.current.interfaces.shared.tools

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.BatchElement
import io.github.jpicklyk.mcptask.current.application.api.BatchRunner
import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.RequestDecoder
import io.github.jpicklyk.mcptask.current.application.api.ScopeGuard
import io.github.jpicklyk.mcptask.current.application.api.SeatContext
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.api.configName
import io.github.jpicklyk.mcptask.current.application.api.grants
import io.github.jpicklyk.mcptask.current.application.config.withConfigSession
import io.github.jpicklyk.mcptask.current.application.port.SeatTokenMatch
import io.github.jpicklyk.mcptask.current.application.run.SeatService
import io.github.jpicklyk.mcptask.current.application.service.withEventActor
import io.github.jpicklyk.mcptask.current.application.support.rethrowIfCancellation
import io.github.jpicklyk.mcptask.current.application.support.toDomainError
import io.github.jpicklyk.mcptask.current.application.telemetry.recordCallPrincipal
import io.github.jpicklyk.mcptask.current.application.telemetry.recordCallRun
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.FieldViolation
import io.github.jpicklyk.mcptask.current.domain.error.ForbiddenScope
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigInvalidException
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.PersistenceFaults
import io.github.jpicklyk.mcptask.current.interfaces.shared.envelope.EncodedEnvelope
import io.github.jpicklyk.mcptask.current.interfaces.shared.envelope.EnvelopeEncoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.slf4j.LoggerFactory

/**
 * The result of one dispatched call: the [envelope] to send, and what the call log records about it:
 * [operation] (the select name, the single distinct op, `mixed`, `describe`, or null when the selector was
 * invalid) and [batchSize] (the `ops` array length for an ops tool, else null).
 */
data class Dispatched(
    val envelope: EncodedEnvelope,
    val operation: String?,
    val batchSize: Int?
)

/**
 * Runs one call of a 4.0 tool, transport-neutral, so MCP ([McpOperationAdapter][io.github.jpicklyk.mcptask.current.interfaces.mcp.McpOperationAdapter])
 * and REST (`POST /api/v1/ops/{tool}`, Q6) produce the same body for the same arguments.
 *
 * Order (task-scope D2):
 * 0. **Principal** (R14). The transport passes a [CallAccess]; one without a principal is answered `unauthenticated`
 *    before anything in the arguments is read (no describe, no selector, no decode). No request body names its own
 *    actor: `actor` is an ordinary unknown key. The principal is recorded on the call telemetry.
 * 0b. **Seat token** (R22). A top-level `seatToken` is lifted on both tool kinds before anything else in the arguments
 *    is read. Absent: no seat. Present, it must be a string naming an ACTIVE assignment bound to this call's principal;
 *    anything else (empty, not a string, unknown, ended, another principal's) is the one fixed `unauthenticated` answer
 *    with no detail. A valid token narrows the access (ADMIN is dropped: what ADMIN granted becomes the explicit
 *    non-admin capabilities, principal visibility, takeover and root writes follow), sets [CallContext.seat], stamps
 *    the run and seat on the call telemetry and, for an ops tool other than a `describe` call, touches the seat's
 *    `last_seen_at` (a describe is read-only and never counts as liveness). Under a token the
 *    idempotency principal is `<principal>#<assignmentId>`, so seats sharing one principal never replay each other.
 * 1. **Access** (Q6). The access's scope reaches handlers as `CallContext.scope`, and its capabilities are checked
 *    against each select/op (`Set<ApiCapability>.grants`; ADMIN grants all). A select lacking the capability answers
 *    the whole call `forbidden`; an op element lacking it fails `forbidden` alone and is never executed or stored.
 *    `describe` needs no capability.
 * 2. **`describe`** (D7). It must be a boolean. `true` answers with [ToolDescriber.describe]; only the selector
 *    (`select`, or `ops` whose elements hold only `op`) may narrow it, any other key is `invalid_request` "not allowed
 *    with describe".
 * 3. **Selector** (D3). A missing `select` is `invalid_request` `/select` "is required"; an unknown or
 *    profile-hidden one is `invalid_request` `/select` listing the visible names (a hidden name looks
 *    absent, never `forbidden`).
 * 4. **Decode** with [RequestDecoder] (a field of another select/op is `unknown_parameter`, D4).
 * 5. **Run** inside `withConfigSession` and with the principal installed as the event actor ([withEventActor]), so
 *    every `events` row a select or op records is attributed to the caller (q18 D3: one install for every handler);
 *    an ops tool runs every element through [BatchRunner] (D5), keyed elements under the principal's audited id.
 *
 * An escaping exception (D9): cancellation is rethrown; a persistence fault that translates to a code other
 * than `internal` is encoded as that error; anything else is the fixed `internal` envelope, the exception
 * going to the server log only.
 */
class ToolDispatcher(
    private val decoder: RequestDecoder,
    private val batchRunner: BatchRunner,
    private val env: ToolExecutionContext
) {
    private val logger = LoggerFactory.getLogger(ToolDispatcher::class.java)

    suspend fun dispatch(
        spec: ToolSpec,
        profile: ToolView,
        arguments: JsonObject,
        reqId: String,
        surface: String,
        access: CallAccess = CallAccess.LOCAL
    ): Dispatched {
        // What the call log learns as dispatch progresses; kept when an exception escapes later.
        var operation: String? = null
        val batchSize = if (spec is OpsToolSpec<*>) (arguments["ops"] as? JsonArray)?.size else null
        return try {
            val principal = access.principal ?: return Dispatched(EnvelopeEncoder.error(noPrincipal(), reqId), null, batchSize)
            recordCallPrincipal(
                principal.claim,
                principal.verification,
                principal.principal?.proofStatus?.wire,
                principal.principal?.kind?.wire
            )
            val seat =
                if (SEAT_TOKEN in arguments) {
                    resolveSeat(arguments[SEAT_TOKEN], principal.claim.id)
                        ?: return Dispatched(EnvelopeEncoder.error(SeatService.tokenRefused(), reqId), null, batchSize)
                } else {
                    null
                }
            val effective = if (seat != null) narrowForSeat(access) else access
            if (seat != null) recordCallRun(seat.seat.runId, seat.seat.seat)
            val ctx =
                CallContext(
                    reqId,
                    surface,
                    principal,
                    effective.scope,
                    effective.attributionVisible,
                    effective.principalsVisible,
                    profile = profile.wire,
                    sessionId = effective.sessionId,
                    rootWritesAllowed = effective.rootWritesAllowed,
                    takeoverAllowed = effective.takeoverAllowed,
                    seat = seat?.let { SeatContext(it.seat.runId, it.seat.id, it.seat.itemId, it.seat.seat, it.runLimited) }
                )

            when (val describe = describeFlag(arguments)) {
                is Outcome.Err -> return Dispatched(EnvelopeEncoder.error(describe.error, reqId), null, batchSize)
                is Outcome.Ok ->
                    if (describe.value) {
                        operation = DESCRIBE_OPERATION
                        return Dispatched(describe(spec, profile, arguments, reqId), DESCRIBE_OPERATION, null)
                    }
            }
            // Liveness (R25 Ruling A): only a token-carrying ops call touches the seat; describe (answered above) never does.
            if (seat != null && spec is OpsToolSpec<*>) env.seatService.touch(seat.seat)

            when (spec) {
                is SelectToolSpec -> {
                    val select =
                        when (val selected = selectFor(spec, profile, arguments)) {
                            is Outcome.Err -> return Dispatched(EnvelopeEncoder.error(selected.error, reqId), null, null)
                            is Outcome.Ok -> selected.value
                        }
                    operation = select.name
                    if (!effective.capabilities.grants(select.capability)) {
                        return Dispatched(EnvelopeEncoder.error(missingCapability(select.capability), reqId), select.name, null)
                    }
                    val envelope =
                        when (val bound = select.decode(decoder, arguments)) {
                            is Outcome.Err -> EnvelopeEncoder.error(bound.error, reqId)
                            is Outcome.Ok -> withEventActor(ctx.principal.claim) { withConfigSession { bound.value.run(env, ctx) } }
                        }
                    Dispatched(envelope, select.name, null)
                }
                is OpsToolSpec<*> -> runOps(spec, profile, arguments, ctx, effective) { operation = it }
            }
        } catch (e: PerRootConfigInvalidException) {
            // The whole-request twin of the per-element mapping: an unloadable config head is config_invalid, not internal.
            logger.warn("Tool '{}' (reqId {}) failed closed on an invalid config head: {}", spec.name, reqId, e.message)
            Dispatched(EnvelopeEncoder.error(e.toDomainError(), reqId), operation, batchSize)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            val translated = PersistenceFaults.translate(e)
            val envelope =
                if (translated != null && translated.code != ErrorCode.INTERNAL) {
                    logger.warn("Tool '{}' (reqId {}) failed with a persistence fault: {}", spec.name, reqId, translated.code.wire, e)
                    EnvelopeEncoder.error(translated, reqId)
                } else {
                    logger.error("Tool '{}' (reqId {}) failed with an internal error: {}", spec.name, reqId, e.message, e)
                    EnvelopeEncoder.internal(reqId)
                }
            Dispatched(envelope, operation, batchSize)
        }
    }

    // ---- seat token -------------------------------------------------------------------------------------

    /**
     * The active assignment [raw] names for [principalId], or null when the token is refused: not a string, empty,
     * unknown, ended or bound to another principal. Nothing tells the cases apart.
     */
    private suspend fun resolveSeat(
        raw: JsonElement?,
        principalId: String
    ): SeatTokenMatch? {
        val token = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        if (token.isEmpty()) return null
        return env.seatService.resolveMatch(token, principalId)
    }

    /**
     * [access] narrowed for a call under a seat token: ADMIN is dropped (what it granted stays granted as the explicit
     * non-admin capabilities), so other principals' ids are hidden, `takeover` is refused and a root write needs an
     * unrestricted scope. Scope, attribution visibility and the session are unchanged.
     */
    private fun narrowForSeat(access: CallAccess): CallAccess {
        val held = access.capabilities
        val narrowed =
            if (ApiCapability.ADMIN in held) {
                ApiCapability.entries.filter { it != ApiCapability.ADMIN }.toSet()
            } else {
                held - ApiCapability.ADMIN
            }
        return access.copy(
            capabilities = narrowed,
            principalsVisible = false,
            takeoverAllowed = false,
            rootWritesAllowed = access.scope.isUnrestricted
        )
    }

    // ---- describe ---------------------------------------------------------------------------------------

    private fun describeFlag(arguments: JsonObject): Outcome<Boolean> {
        val raw = arguments[DESCRIBE] ?: return Outcome.Ok(false)
        val flag = (raw as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        return if (flag == null) Outcome.Err(invalidField("/$DESCRIBE", "must be a boolean", received(raw))) else Outcome.Ok(flag)
    }

    private fun describe(
        spec: ToolSpec,
        profile: ToolView,
        arguments: JsonObject,
        reqId: String
    ): EncodedEnvelope {
        val visible = visibleNames(spec, profile)
        val selector = if (spec is SelectToolSpec) SELECT else OPS
        val faults = ArrayList<FieldViolation>()
        for (key in arguments.keys) {
            if (key != DESCRIBE && key != selector && key != SEAT_TOKEN) faults.add(FieldViolation(pointer(key), NOT_WITH_DESCRIBE))
        }
        val only = LinkedHashSet<String>()
        var narrowed = false
        val raw = arguments[selector]
        if (raw != null) {
            narrowed = true
            if (spec is SelectToolSpec) {
                val name = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (name == null || name !in visible) faults.add(oneOf("/$SELECT", visible, raw)) else only.add(name)
            } else {
                val array = raw as? JsonArray
                if (array == null || array.isEmpty()) {
                    faults.add(FieldViolation("/$OPS", "must be a non-empty array", received(raw)))
                } else {
                    array.forEachIndexed { i, element ->
                        val obj = element as? JsonObject
                        if (obj == null) {
                            faults.add(FieldViolation("/$OPS/$i", "must be an object", received(element)))
                            return@forEachIndexed
                        }
                        for (key in obj.keys) if (key != OP) faults.add(FieldViolation("/$OPS/$i${pointer(key)}", NOT_WITH_DESCRIBE))
                        val op = obj[OP]
                        val name = (op as? JsonPrimitive)?.takeIf { it.isString }?.content
                        when {
                            op == null -> faults.add(FieldViolation("/$OPS/$i/$OP", "is required"))
                            name == null || name !in visible -> faults.add(oneOf("/$OPS/$i/$OP", visible, op))
                            else -> only.add(name)
                        }
                    }
                }
            }
        }
        if (faults.isNotEmpty()) return EnvelopeEncoder.error(invalidFields(faults), reqId)
        val data = ToolDescriber.describe(spec, profile, if (narrowed) only else null)
        return EnvelopeEncoder.outcome(Outcome.Ok(data), reqId) { it }
    }

    // ---- select -----------------------------------------------------------------------------------------

    private fun selectFor(
        spec: SelectToolSpec,
        profile: ToolView,
        arguments: JsonObject
    ): Outcome<SelectSpec<*, *>> {
        val raw = arguments[SELECT] ?: return Outcome.Err(invalidField("/$SELECT", "is required"))
        val visible = spec.visibleSelects(profile)
        val name = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content
        val select = visible.firstOrNull { it.name == name }
        return if (select ==
            null
        ) {
            Outcome.Err(invalidFields(listOf(oneOf("/$SELECT", visible.map { it.name }, raw))))
        } else {
            Outcome.Ok(select)
        }
    }

    // ---- ops --------------------------------------------------------------------------------------------

    /** One `ops[]` element after the dispatcher's own checks: bound and ready to run, or already failed. */
    private sealed interface Pending<R> {
        class Bound<R>(
            val op: BoundOp<R>
        ) : Pending<R>

        class Failed<R>(
            val error: DomainError
        ) : Pending<R>
    }

    private suspend fun <R> runOps(
        spec: OpsToolSpec<R>,
        profile: ToolView,
        arguments: JsonObject,
        ctx: CallContext,
        access: CallAccess,
        onOperation: (String?) -> Unit
    ): Dispatched {
        val reqId = ctx.reqId
        val raw = arguments[OPS]
        val batchSize = (raw as? JsonArray)?.size

        // Whole-call checks: unknown top-level keys first (fix the shape first), then the value faults.
        val unknown = arguments.keys.filter { it !in OPS_TOP_LEVEL }.map { pointer(it) }
        if (unknown.isNotEmpty()) return Dispatched(EnvelopeEncoder.error(unknownParameters(unknown), reqId), null, batchSize)
        val faults = ArrayList<FieldViolation>()
        when {
            raw == null -> faults.add(FieldViolation("/$OPS", "is required"))
            raw !is JsonArray -> faults.add(FieldViolation("/$OPS", "must be an array", received(raw)))
            raw.isEmpty() -> faults.add(FieldViolation("/$OPS", "must not be empty"))
        }
        val atomicRaw = arguments[ATOMIC]
        val atomic = (atomicRaw as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        if (atomicRaw != null && atomic == null) faults.add(FieldViolation("/$ATOMIC", "must be a boolean", received(atomicRaw)))
        if (faults.isNotEmpty()) return Dispatched(EnvelopeEncoder.error(invalidFields(faults), reqId), null, batchSize)
        val array = raw as JsonArray

        val visible = spec.visibleOps(profile)
        val visibleNames = visible.map { it.name }
        val resolved = LinkedHashSet<String>()
        val elements =
            array.mapIndexed { i, element ->
                val at = "/$OPS/$i"
                val obj =
                    element as? JsonObject
                        ?: return@mapIndexed failed<R>(invalidField(at, "must be an object", received(element)), element)
                val opRaw = obj[OP] ?: return@mapIndexed failed<R>(invalidField("$at/$OP", "is required"), obj)
                val opName = (opRaw as? JsonPrimitive)?.takeIf { it.isString }?.content
                val op =
                    visible.firstOrNull { it.name == opName }
                        ?: return@mapIndexed failed<R>(invalidFields(listOf(oneOf("$at/$OP", visibleNames, opRaw))), obj)
                resolved.add(op.name)
                if (!access.capabilities.grants(op.capability)) {
                    return@mapIndexed failed<R>(missingCapability(op.capability), obj)
                }

                val keyRaw = obj[IDEMPOTENCY_KEY]
                val key = (keyRaw as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (keyRaw != null && key == null) {
                    return@mapIndexed failed<R>(invalidField("$at/$IDEMPOTENCY_KEY", "must be a string", received(keyRaw)), obj)
                }
                // The fingerprint input: the element as sent, minus its key.
                val fingerprint = JsonObject(obj.filterKeys { it != IDEMPOTENCY_KEY })
                when (val bound = op.decode(decoder, obj)) {
                    is Outcome.Err -> failed(prefix(bound.error, at), fingerprint)
                    is Outcome.Ok -> BatchElement<Pending<R>>(Pending.Bound(bound.value), fingerprint, key)
                }
            }
        val operation =
            when (resolved.size) {
                0 -> null
                1 -> resolved.first()
                else -> MIXED_OPERATION
            }
        onOperation(operation)

        // Under a seat token, the idempotency principal is the (principal, assignment) pair: seats of one principal never
        // replay each other's keyed elements.
        val principalId = ctx.seat?.let { "${ctx.principal.claim.id}#${it.assignmentId}" } ?: ctx.principal.claim.id
        val execution =
            withEventActor(ctx.principal.claim) {
                withConfigSession {
                    batchRunner.run(spec.name, principalId, elements, atomic ?: false, spec.codec) { index, pending ->
                        when (pending) {
                            is Pending.Failed -> Outcome.Err(pending.error)
                            is Pending.Bound -> rootHandlerFields(pending.op.run.invoke(this, env, ctx), index)
                        }
                    }
                }
            }
        return Dispatched(EnvelopeEncoder.batch(execution, reqId, spec.element), operation, batchSize)
    }

    /**
     * Re-roots the field pointers a handler reported for its element: a pointer starting with `/` is relative to the
     * element (`/ttlSeconds` becomes `/ops/<index>/ttlSeconds`); a field without a leading `/` is left as the handler
     * wrote it.
     */
    private fun <R> rootHandlerFields(
        outcome: Outcome<R>,
        index: Int
    ): Outcome<R> {
        val error = (outcome as? Outcome.Err)?.error ?: return outcome
        val detail = error.detail as? ErrorDetail.InvalidRequest ?: return outcome
        val at = "/$OPS/$index"
        return Outcome.Err(invalidFields(detail.fields.map { f -> if (f.field.startsWith("/")) f.copy(field = at + f.field) else f }))
    }

    /** The `forbidden` error of a caller lacking [required]; `required` is the kebab-case capability name. */
    private fun missingCapability(required: ApiCapability): DomainError =
        ScopeGuard.forbidden(ForbiddenScope.CAPABILITY, required.configName())

    /** A failed element: no idempotency key, so the failure is never stored or replayed. */
    private fun <R> failed(
        error: DomainError,
        json: JsonElement
    ): BatchElement<Pending<R>> = BatchElement(Pending.Failed(error), json, idempotencyKey = null)

    // ---- helpers ----------------------------------------------------------------------------------------

    /** Re-roots the field pointers of a decoder error (`$` and `/x`) under [at]. */
    private fun prefix(
        error: DomainError,
        at: String
    ): DomainError =
        when (val detail = error.detail) {
            is ErrorDetail.InvalidRequest ->
                invalidFields(detail.fields.map { it.copy(field = if (it.field == ROOT) at else at + it.field) })
            is ErrorDetail.UnknownParameter -> unknownParameters(detail.parameters.map { if (it == ROOT) at else at + it })
            else -> error
        }

    private fun oneOf(
        field: String,
        visible: List<String>,
        raw: JsonElement
    ): FieldViolation = FieldViolation(field, "must be one of: ${visible.joinToString(", ")}", received(raw))

    companion object {
        const val DESCRIBE = "describe"
        const val SELECT = "select"
        const val OPS = "ops"
        const val OP = "op"
        const val ATOMIC = "atomic"
        const val IDEMPOTENCY_KEY = "idempotencyKey"

        /** The top-level seat token field (R22), lifted on both tool kinds. */
        const val SEAT_TOKEN = "seatToken"

        /** `call_log` operation of a `describe: true` call. */
        const val DESCRIBE_OPERATION = "describe"

        /** `call_log` operation of an ops call naming more than one distinct op. */
        const val MIXED_OPERATION = "mixed"

        /** The top-level keys an ops tool accepts. */
        val OPS_TOP_LEVEL: Set<String> = setOf(DESCRIBE, OPS, ATOMIC, SEAT_TOKEN)

        /** The message of a call refused because its transport established no principal. */
        const val NO_PRINCIPAL_MESSAGE = "The call has no authenticated principal."

        private const val NOT_WITH_DESCRIBE = "not allowed with describe"
        private const val ROOT = "$"
        private const val MAX_RECEIVED = 100

        /** The `unauthenticated` error of a call made without a principal; nothing in its arguments was read. */
        private fun noPrincipal(): DomainError = DomainError(ErrorCode.UNAUTHENTICATED, NO_PRINCIPAL_MESSAGE)

        /** RFC 6901 pointer of a top-level key. */
        private fun pointer(key: String): String = "/" + key.replace("~", "~0").replace("/", "~1")

        private fun received(value: JsonElement): String =
            when (value) {
                is JsonNull -> "null"
                is JsonPrimitive -> value.content.take(MAX_RECEIVED)
                is JsonObject -> "object"
                is JsonArray -> "array"
            }
    }
}
