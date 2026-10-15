package io.github.jpicklyk.mcptask.current.interfaces.shared.envelope

import io.github.jpicklyk.mcptask.current.application.api.BatchExecution
import io.github.jpicklyk.mcptask.current.application.api.ListPage
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One encoded response: [body] is the wire JSON on both surfaces, [httpStatus] the REST status. */
data class EncodedEnvelope(
    val body: JsonObject,
    val httpStatus: Int
)

/**
 * Builds the 4.0 response envelope (envelope design sections 2-4), the same bytes for MCP
 * `structuredContent` and the REST body:
 *
 * - success `{"ok":true,"data"?,"meta":{"reqId","replayed"?}}`;
 * - error `{"ok":false,"error":{"code","kind","message","detail"?,"fix"?,"retryAfterMs"?,"index"?},"meta"}`;
 *   a replayed stored rejection keeps `meta.replayed` (whole call) or `replayed: true` (batch element);
 * - list data `{"items":[..],"page":{"total","truncated","capped"?,"next"?}}`;
 * - batch data `{"results":[..],"summary":{"ok","failed"}}`, with a `partial_failure` error next to it
 *   when a non-atomic batch has failures, or only the failing element's error (with `index`) when an
 *   atomic batch fails.
 *
 * Keys are emitted in that fixed order, nulls never. The status is the code's [ErrorCode.httpStatus]
 * (200 for success). There is no top-level `success`, `message` or `metadata`.
 */
object EnvelopeEncoder {
    const val INTERNAL_MESSAGE = "Internal server error"

    private const val OK_STATUS = 200
    private val RESERVED_RESULT_KEYS = setOf("ok", "error", "replayed")

    fun <T> outcome(
        outcome: Outcome<T>,
        reqId: String,
        replayed: Boolean = false,
        data: (T) -> JsonElement?
    ): EncodedEnvelope =
        when (outcome) {
            is Outcome.Err -> error(outcome.error, reqId, replayed = replayed)
            is Outcome.Ok -> success(data(outcome.value), reqId, replayed)
        }

    fun <T> page(
        page: ListPage<T>,
        reqId: String,
        item: (T) -> JsonElement
    ): EncodedEnvelope = pageWith(page, reqId, extra = null, item = item)

    /** List data whose [extra] members (never `items` or `page`) follow `page`, in their own order. */
    fun <T> pageWith(
        page: ListPage<T>,
        reqId: String,
        extra: JsonObject?,
        item: (T) -> JsonElement
    ): EncodedEnvelope {
        if (extra != null) require("items" !in extra && "page" !in extra) { "extra list data must not emit items or page" }
        val data =
            buildJsonObject {
                put("items", JsonArray(page.items.map(item)))
                put(
                    "page",
                    buildJsonObject {
                        put("total", page.total)
                        put("truncated", page.truncated)
                        if (page.capped) put("capped", true)
                        page.next?.let { put("next", it) }
                    }
                )
                extra?.let { putAll(it) }
            }
        return success(data, reqId, replayed = false)
    }

    fun <T> batch(
        execution: BatchExecution<T>,
        reqId: String,
        element: (T) -> JsonObject
    ): EncodedEnvelope {
        val result = execution.result
        val failure = result.failure()
        if (failure != null && result.atomic) return error(failure.error, reqId, failure.index)

        val data =
            buildJsonObject {
                put(
                    "results",
                    buildJsonArray {
                        result.results.forEachIndexed { index, outcome ->
                            add(resultEntry(outcome, index in execution.replayed, element))
                        }
                    }
                )
                val summary = result.summary
                put(
                    "summary",
                    buildJsonObject {
                        put("ok", summary.ok)
                        put("failed", summary.failed)
                    }
                )
            }
        if (failure == null) return success(data, reqId, replayed = false)
        val body =
            buildJsonObject {
                put("ok", false)
                put("error", errorMember(failure.error))
                put("data", data)
                put("meta", meta(reqId, replayed = false))
            }
        return EncodedEnvelope(body, failure.error.code.httpStatus)
    }

    fun error(
        error: DomainError,
        reqId: String,
        index: Int? = null,
        replayed: Boolean = false
    ): EncodedEnvelope {
        val body =
            buildJsonObject {
                put("ok", false)
                put("error", errorMember(error, index))
                put("meta", meta(reqId, replayed))
            }
        return EncodedEnvelope(body, error.code.httpStatus)
    }

    /**
     * The `error` member. `retryAfterMs` is copied to error level from the `claim_held`,
     * `resource_unavailable` (when set) and `unavailable` details, which keep it too.
     */
    fun errorMember(
        error: DomainError,
        index: Int? = null
    ): JsonObject =
        buildJsonObject {
            put("code", error.code.wire)
            put("kind", error.kind.toJsonString())
            // `internal` never carries exception text, whatever built the error: a store fault translated by a unit of
            // work arrives here with the driver's message, which the wire must not see (the server log has it).
            put("message", if (error.code == ErrorCode.INTERNAL) INTERNAL_MESSAGE else error.message)
            error.detail?.let { put("detail", detail(it)) }
            error.fix?.let { put("fix", it) }
            retryAfterMs(error.detail)?.let { put("retryAfterMs", it) }
            index?.let { put("index", it) }
        }

    /**
     * A detail object: member names are the [ErrorDetail] property names, ids and instants are
     * strings (instants ISO-8601), enums are lowercase, nested values are objects, nulls are omitted.
     */
    fun detail(detail: ErrorDetail): JsonObject =
        buildJsonObject {
            when (detail) {
                is ErrorDetail.InvalidRequest ->
                    put(
                        "fields",
                        JsonArray(
                            detail.fields.map { f ->
                                buildJsonObject {
                                    put("field", f.field)
                                    put("reason", f.reason)
                                    f.received?.let { put("received", it) }
                                }
                            }
                        )
                    )
                is ErrorDetail.UnknownParameter -> put("parameters", strings(detail.parameters))
                is ErrorDetail.NotFound -> {
                    put("kind", detail.kind.name.lowercase())
                    detail.id?.let { put("id", it) }
                }
                is ErrorDetail.AmbiguousId -> {
                    put("prefix", detail.prefix)
                    put("candidates", strings(detail.candidates.map { it.toString() }))
                }
                is ErrorDetail.VersionConflict -> {
                    put("kind", detail.kind.name.lowercase())
                    put("id", detail.id)
                    put("expected", detail.expected)
                    put("actual", detail.actual)
                }
                is ErrorDetail.Duplicate -> {
                    put("kind", detail.kind.name.lowercase())
                    detail.id?.let { put("id", it) }
                    detail.existingId?.let { put("existingId", it) }
                }
                is ErrorDetail.IdempotencyMismatch -> put("idempotencyKey", detail.idempotencyKey)
                is ErrorDetail.InvalidTransition -> {
                    put("itemId", detail.itemId.toString())
                    put("fromRole", detail.fromRole)
                    put("trigger", detail.trigger)
                    put("allowed", strings(detail.allowed))
                }
                is ErrorDetail.GateBlocked -> {
                    put("itemId", detail.itemId.toString())
                    put("role", detail.role)
                    put(
                        "missing",
                        JsonArray(
                            detail.missing.map { m ->
                                buildJsonObject {
                                    put("key", m.key)
                                    put("role", m.role)
                                    m.seat?.let { put("seat", it) }
                                }
                            }
                        )
                    )
                    if (!detail.violations.isNullOrEmpty()) {
                        put(
                            "violations",
                            JsonArray(
                                detail.violations.map { v ->
                                    buildJsonObject {
                                        put("gate", v.gate)
                                        put("reason", v.reason)
                                    }
                                }
                            )
                        )
                    }
                }
                is ErrorDetail.DependencyUnmet -> {
                    put("itemId", detail.itemId.toString())
                    put(
                        "blockers",
                        JsonArray(
                            detail.blockers.map { b ->
                                buildJsonObject {
                                    put("id", b.id.toString())
                                    put("role", b.role)
                                }
                            }
                        )
                    )
                }
                is ErrorDetail.CycleDetected -> put("path", strings(detail.path.map { it.toString() }))
                is ErrorDetail.ClaimHeld -> {
                    put("itemId", detail.itemId.toString())
                    put("expiresAt", detail.expiresAt.toString())
                    put("retryAfterMs", detail.retryAfterMs)
                }
                is ErrorDetail.NotClaimHolder -> put("itemId", detail.itemId.toString())
                is ErrorDetail.SeatForbidden -> {
                    put("itemId", detail.itemId.toString())
                    // Always present: JSON null when the caller carries no seat token.
                    put("seat", detail.seat?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("action", detail.action)
                    put("allowedSeats", strings(detail.allowedSeats))
                }
                is ErrorDetail.NoteOwnedByOther -> {
                    put("itemId", detail.itemId.toString())
                    put("key", detail.key)
                    detail.ownerSeat?.let { put("ownerSeat", it) }
                }
                is ErrorDetail.NoteTooLong -> {
                    put("key", detail.key)
                    put("max", detail.max)
                    put("actual", detail.actual)
                }
                is ErrorDetail.ResourceUnavailable -> {
                    put("itemId", detail.itemId.toString())
                    put(
                        "resources",
                        JsonArray(
                            detail.resources.map { r ->
                                buildJsonObject {
                                    put("name", r.name)
                                    put("mode", r.mode)
                                }
                            }
                        )
                    )
                    detail.retryAfterMs?.let { put("retryAfterMs", it) }
                }
                is ErrorDetail.RunClosed -> {
                    put("runId", detail.runId.toString())
                    put("terminalReason", detail.terminalReason)
                }
                is ErrorDetail.RunPaused -> {
                    put("runId", detail.runId.toString())
                    put("pauseReason", detail.pauseReason)
                    detail.pauseDecisionId?.let { put("pauseDecisionId", it.toString()) }
                }
                is ErrorDetail.InvalidRunState -> {
                    put("runId", detail.runId.toString())
                    put("status", detail.status)
                    put("allowed", strings(detail.allowed))
                }
                is ErrorDetail.SchemaViolation -> {
                    detail.type?.let { put("type", it) }
                    put("reason", detail.reason)
                    detail.availableTypes?.let { put("availableTypes", strings(it)) }
                    detail.availableTraits?.let { put("availableTraits", strings(it)) }
                }
                is ErrorDetail.SchemaPinnedConflict -> {
                    put("itemId", detail.itemId.toString())
                    put("pinnedVersion", detail.pinnedVersion)
                    put("currentVersion", detail.currentVersion)
                }
                is ErrorDetail.ConfigInvalid ->
                    put(
                        "errors",
                        JsonArray(
                            detail.errors.map { e ->
                                buildJsonObject {
                                    put("path", e.path)
                                    put("reason", e.reason)
                                }
                            }
                        )
                    )
                is ErrorDetail.PayloadTooLarge -> {
                    put("max", detail.max)
                    put("actual", detail.actual)
                }
                is ErrorDetail.Forbidden -> {
                    put("scope", detail.scope.name.lowercase())
                    put("required", detail.required)
                }
                is ErrorDetail.Unavailable -> put("retryAfterMs", detail.retryAfterMs)
            }
        }

    /**
     * The `internal` envelope (500). It takes no cause and no text, so an exception's message can never
     * reach the wire.
     */
    fun internal(reqId: String): EncodedEnvelope = error(DomainError(ErrorCode.INTERNAL, INTERNAL_MESSAGE), reqId)

    private fun success(
        data: JsonElement?,
        reqId: String,
        replayed: Boolean
    ): EncodedEnvelope {
        val body =
            buildJsonObject {
                put("ok", true)
                data?.let { put("data", it) }
                put("meta", meta(reqId, replayed))
            }
        return EncodedEnvelope(body, OK_STATUS)
    }

    private fun meta(
        reqId: String,
        replayed: Boolean
    ): JsonObject =
        buildJsonObject {
            put("reqId", reqId)
            if (replayed) put("replayed", true)
        }

    private fun <T> resultEntry(
        outcome: Outcome<T>,
        replayed: Boolean,
        element: (T) -> JsonObject
    ): JsonObject =
        when (outcome) {
            is Outcome.Err ->
                buildJsonObject {
                    put("ok", false)
                    put("error", errorMember(outcome.error))
                    if (replayed) put("replayed", true)
                }
            is Outcome.Ok -> {
                val fields = element(outcome.value)
                val clash = fields.keys intersect RESERVED_RESULT_KEYS
                require(clash.isEmpty()) { "A batch element mapper must not emit $clash" }
                buildJsonObject {
                    put("ok", true)
                    putAll(fields)
                    if (replayed) put("replayed", true)
                }
            }
        }

    private fun JsonObjectBuilder.putAll(fields: JsonObject) {
        for ((key, value) in fields) put(key, value)
    }

    private fun retryAfterMs(detail: ErrorDetail?): Long? =
        when (detail) {
            is ErrorDetail.ClaimHeld -> detail.retryAfterMs
            is ErrorDetail.ResourceUnavailable -> detail.retryAfterMs
            is ErrorDetail.Unavailable -> detail.retryAfterMs
            else -> null
        }

    private fun strings(values: List<String>): JsonArray = JsonArray(values.map { JsonPrimitive(it) })
}
