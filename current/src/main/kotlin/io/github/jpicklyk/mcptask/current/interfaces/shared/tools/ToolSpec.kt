package io.github.jpicklyk.mcptask.current.interfaces.shared.tools

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.ListPage
import io.github.jpicklyk.mcptask.current.application.api.RequestDecoder
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.application.service.IdempotencyCodec
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.FieldViolation
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.interfaces.shared.envelope.EncodedEnvelope
import io.github.jpicklyk.mcptask.current.interfaces.shared.envelope.EnvelopeEncoder
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * A 4.0 tool declaration (plan section 3.2, Q1b). The contract is PROVISIONAL until R20 merges.
 *
 * A spec is a context-free declaration: handlers receive the one composed [ToolExecutionContext] as
 * `env` at call time, so guards and goldens render specs without a database. Each select or op lives in
 * its own file and registers into its tool (plan section 2, "Tool registry layout").
 */
sealed interface ToolSpec {
    val name: String
    val description: String
}

/** A read tool dispatched by its top-level `select`. */
class SelectToolSpec(
    override val name: String,
    override val description: String,
    val selects: List<SelectSpec<*, *>>
) : ToolSpec

/**
 * One select of a [SelectToolSpec]: its [request] class (decoded strictly; `actor`, `describe` and
 * `select` are lifted first), the [profiles] it is visible in (an empty set is REST-only), the [capability] a caller needs (READ
 * unless declared otherwise; ADMIN grants all), and the [errors] its handler may return beyond the common ones.
 */
sealed class SelectSpec<R : Any, T>(
    val name: String,
    val doc: String,
    val profiles: Set<ToolProfile>,
    val request: KSerializer<R>,
    val errors: Set<ErrorCode>,
    val capability: ApiCapability
) {
    /** A select returning one value; [data] maps it to `data` (null means no `data` member). */
    class Single<R : Any, T>(
        name: String,
        doc: String,
        profiles: Set<ToolProfile>,
        request: KSerializer<R>,
        errors: Set<ErrorCode> = emptySet(),
        capability: ApiCapability = ApiCapability.READ,
        val handler: suspend (ToolExecutionContext, CallContext, R) -> Outcome<T>,
        val data: (T) -> JsonElement?
    ) : SelectSpec<R, T>(name, doc, profiles, request, errors, capability) {
        override suspend fun respond(
            env: ToolExecutionContext,
            ctx: CallContext,
            value: R
        ): EncodedEnvelope = EnvelopeEncoder.outcome(handler(env, ctx, value), ctx.reqId, data = data)
    }

    /** A select returning one page of a list; [item] maps each element of `data.items`. */
    class Paged<R : Any, T>(
        name: String,
        doc: String,
        profiles: Set<ToolProfile>,
        request: KSerializer<R>,
        errors: Set<ErrorCode> = emptySet(),
        capability: ApiCapability = ApiCapability.READ,
        val handler: suspend (ToolExecutionContext, CallContext, R) -> Outcome<ListPage<T>>,
        val item: (T) -> JsonElement
    ) : SelectSpec<R, T>(name, doc, profiles, request, errors, capability) {
        override suspend fun respond(
            env: ToolExecutionContext,
            ctx: CallContext,
            value: R
        ): EncodedEnvelope =
            when (val outcome = handler(env, ctx, value)) {
                is Outcome.Err -> EnvelopeEncoder.error(outcome.error, ctx.reqId)
                is Outcome.Ok -> EnvelopeEncoder.page(outcome.value, ctx.reqId, item)
            }
    }

    /** Runs the handler on a decoded request and encodes its result. */
    protected abstract suspend fun respond(
        env: ToolExecutionContext,
        ctx: CallContext,
        value: R
    ): EncodedEnvelope

    /** Decodes [arguments] into [request]; the error is the decoder's `invalid_request`/`unknown_parameter`. */
    internal fun decode(
        decoder: RequestDecoder,
        arguments: JsonObject
    ): Outcome<Bound> = decoder.decode(request, arguments, SELECT_RESERVED).map { value -> Bound(value) }

    /** A decoded request of this select, ready to run. */
    inner class Bound internal constructor(
        private val value: R
    ) {
        suspend fun run(
            env: ToolExecutionContext,
            ctx: CallContext
        ): EncodedEnvelope = respond(env, ctx, value)
    }

    companion object {
        /** Top-level keys a select request never decodes: the transport lifts them first. */
        val SELECT_RESERVED: Set<String> = setOf("actor", "describe", "select")
    }
}

/**
 * A write tool: one `ops[]` array of flat objects, each naming its op in `op` (plan section 3.2: one flat
 * object per op, `oneOf` rejected). Every element runs through `BatchRunner`; [codec] stores an element's
 * result in an idempotency record and [element] maps a successful result to its `results[i]` fields.
 */
class OpsToolSpec<R>(
    override val name: String,
    override val description: String,
    val ops: List<OpSpec<*, R>>,
    val codec: IdempotencyCodec<R>,
    val element: (R) -> JsonObject
) : ToolSpec

/**
 * One op of an [OpsToolSpec]: its element [request] class (decoded strictly; `op` and `idempotencyKey`
 * are lifted first), the [profiles] it is visible in (an empty set is REST-only), whether it is [destructive], the [capability] a caller
 * needs (required, no default: a new write op must never silently be READ-only), the [errors] it may return
 * beyond the common ones, and [execute], which runs inside the element's write unit.
 */
class OpSpec<E : Any, R>(
    val name: String,
    val doc: String,
    val profiles: Set<ToolProfile>,
    val destructive: Boolean = false,
    val capability: ApiCapability,
    val request: KSerializer<E>,
    val errors: Set<ErrorCode> = emptySet(),
    val execute: suspend WriteScope.(ToolExecutionContext, CallContext, E) -> Outcome<R>
) {
    /** Decodes one `ops[]` element into [request]; field pointers are relative to the element. */
    internal fun decode(
        decoder: RequestDecoder,
        element: JsonObject
    ): Outcome<BoundOp<R>> =
        decoder.decode(request, element, OP_RESERVED).map { value ->
            BoundOp(name) { env, ctx -> execute.invoke(this, env, ctx, value) }
        }

    companion object {
        /** Element keys an op request never decodes: the dispatcher reads them itself. */
        val OP_RESERVED: Set<String> = setOf("op", "idempotencyKey")

        /** Field names an op request class must not declare. */
        val OP_FORBIDDEN_FIELDS: Set<String> = setOf("actor", "op", "idempotencyKey")
    }
}

/** A decoded `ops[]` element of op [op], ready to run inside a write unit. */
class BoundOp<R> internal constructor(
    val op: String,
    val run: suspend WriteScope.(ToolExecutionContext, CallContext) -> Outcome<R>
)

/** A tool's selects or ops visible to [view] ([ToolView.sees]), in registration order. */
internal fun SelectToolSpec.visibleSelects(view: ToolView): List<SelectSpec<*, *>> = selects.filter { view.sees(it.profiles) }

/** See [visibleSelects]. */
internal fun <R> OpsToolSpec<R>.visibleOps(view: ToolView): List<OpSpec<*, R>> = ops.filter { view.sees(it.profiles) }

/** Names of the selects or ops of [spec] visible to [profile], in registration order. */
internal fun visibleNames(
    spec: ToolSpec,
    profile: ToolView
): List<String> =
    when (spec) {
        is SelectToolSpec -> spec.visibleSelects(profile).map { it.name }
        is OpsToolSpec<*> -> spec.visibleOps(profile).map { it.name }
    }

/** Builds the `invalid_request` error of a single offending field. */
internal fun invalidField(
    field: String,
    reason: String,
    received: String? = null
): DomainError = invalidFields(listOf(FieldViolation(field, reason, received)))

/** Builds an `invalid_request` error over [fields], sorted by pointer (the decoder's message form). */
internal fun invalidFields(fields: List<FieldViolation>): DomainError {
    val sorted = fields.sortedBy { it.field }
    return DomainError(
        code = ErrorCode.INVALID_REQUEST,
        message = "The request has ${sorted.size} invalid field(s): ${sorted.joinToString(", ") { it.field }}.",
        detail = ErrorDetail.InvalidRequest(sorted)
    )
}

/** Builds an `unknown_parameter` error over [parameters], sorted (the decoder's message form). */
internal fun unknownParameters(parameters: List<String>): DomainError {
    val sorted = parameters.sorted()
    return DomainError(
        code = ErrorCode.UNKNOWN_PARAMETER,
        message = "The request has ${sorted.size} unknown parameter(s): ${sorted.joinToString(", ")}.",
        detail = ErrorDetail.UnknownParameter(sorted)
    )
}
