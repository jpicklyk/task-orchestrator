package io.github.jpicklyk.mcptask.current.application.tools

import io.github.jpicklyk.mcptask.current.application.service.Fingerprint
import io.github.jpicklyk.mcptask.current.application.service.IdempotencyService
import io.github.jpicklyk.mcptask.current.application.service.IdempotentOutcome
import io.github.jpicklyk.mcptask.current.application.service.IdempotentRequest
import io.github.jpicklyk.mcptask.current.application.service.JsonElementCodec
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.FieldViolation
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/** What one keyed MCP element produced; see [KeyedCall.element]. */
sealed interface ElementResult {
    /** The element succeeded and committed: [fragment] is its base result (no config decorations). */
    data class Done(
        val fragment: JsonObject
    ) : ElementResult

    /**
     * The element failed for a reason that depends on state or is transient: [failure] is returned
     * to the caller, the element rolls back and nothing is recorded, so a retry runs it again.
     */
    data class Failed(
        val failure: JsonObject
    ) : ElementResult

    /**
     * The element payload is malformed (not a JSON object): [failure] is returned and the rejection
     * is recorded, so a retry with the same key and payload replays it.
     */
    data class Invalid(
        val failure: JsonObject,
        val message: String
    ) : ElementResult
}

/** What a keyed element resolved to for the caller: its result or failure, plus whether a record served it. */
sealed interface ElementOutcome {
    data class Succeeded(
        val fragment: JsonObject,
        val replayed: Boolean
    ) : ElementOutcome

    data class Failed(
        val failure: JsonObject
    ) : ElementOutcome
}

/**
 * Per-element idempotency for one keyed MCP call: a request id plus a trusted principal. Each element
 * of the call's array (or the whole call, for atomic and single-element tools) is keyed
 * `"<requestId>:<index>"` under `mcp.<tool>[.<operation>]`, so a retry replays what already
 * succeeded and re-runs what did not.
 *
 * @param shared the call parameters minus `requestId`, the top-level `actor` and the element
 *   arrays: the part of the request every element shares
 */
class KeyedCall(
    private val service: IdempotencyService,
    private val principalId: String,
    requestId: UUID,
    private val op: String,
    private val shared: JsonElement
) {
    private val requestKey = requestId.toString()

    private fun request(
        index: Int,
        fingerprintInput: JsonElement
    ) = IdempotentRequest(principalId, "$requestKey:$index", Fingerprint.of(fingerprintInput))

    /**
     * Runs one array element under its key. [block] runs inside the element unit, so everything it
     * writes commits together with the record; it must report every failure as [ElementResult.Failed]
     * or [ElementResult.Invalid] (never a committed failure fragment) or throw.
     *
     * @param onError the failure the tool reports for an error that carries no failure of its own: a key
     *   used with another payload, or a replayed rejection (see [DomainError.code])
     */
    suspend fun element(
        index: Int,
        element: JsonElement,
        onError: (DomainError) -> JsonObject = { defaultFailure(index, it) },
        block: suspend () -> ElementResult
    ): ElementOutcome {
        val fingerprintInput =
            buildJsonObject {
                put("op", op)
                put("shared", shared)
                put("element", element)
            }
        var unrecorded: JsonObject? = null
        val result =
            service.execute(op, request(index, fingerprintInput), JsonElementCodec) {
                unrecorded = null
                when (val r = block()) {
                    is ElementResult.Done -> Outcome.Ok(r.fragment)
                    is ElementResult.Failed -> {
                        unrecorded = r.failure
                        Outcome.Err(DomainError(ErrorCode.INTERNAL, "The element failed and was not recorded"))
                    }
                    is ElementResult.Invalid -> {
                        unrecorded = r.failure
                        Outcome.Err(invalidRequest(r.message))
                    }
                }
            }
        return when (val outcome = result.outcome) {
            is Outcome.Ok -> {
                val fragment = outcome.value as JsonObject
                ElementOutcome.Succeeded(if (result.replayed) withReplayed(fragment) else fragment, result.replayed)
            }
            is Outcome.Err ->
                ElementOutcome.Failed(unrecorded ?: onError(outcome.error))
        }
    }

    /**
     * Runs a whole atomic or single-element call as element 0. [block] returns the tool response: an
     * error envelope, or a response [recordable] rejects, rolls the unit back and is returned
     * unrecorded; any other response is recorded (a replay returns it with `data.replayed = true`).
     *
     * @param params the call parameters minus `requestId` and the top-level `actor`
     * @param recordable whether a non-error response is a committed result worth replaying
     */
    suspend fun whole(
        params: JsonElement,
        recordable: (JsonElement) -> Boolean = { true },
        block: suspend () -> JsonElement
    ): JsonElement {
        val fingerprintInput =
            buildJsonObject {
                put("op", op)
                put("params", params)
            }
        var unrecorded: JsonElement? = null
        val result =
            service.execute(op, request(0, fingerprintInput), JsonElementCodec) {
                unrecorded = null
                val response = block()
                if (ResponseUtil.isErrorResponse(response) || !recordable(response)) {
                    unrecorded = response
                    Outcome.Err(DomainError(ErrorCode.INTERNAL, "The call failed and was not recorded"))
                } else {
                    Outcome.Ok(response)
                }
            }
        return toResponse(result, unrecorded)
    }

    /**
     * Like [whole], for a call that opens units of its own and so cannot join one (complete_tree): the
     * lookup and the record are separate units around [block], which runs outside any unit. Concurrent
     * callers with the same key may both run [block]; the call is convergent by construction.
     */
    suspend fun detached(
        params: JsonElement,
        recordable: (JsonElement) -> Boolean,
        block: suspend () -> JsonElement
    ): JsonElement {
        val fingerprintInput =
            buildJsonObject {
                put("op", op)
                put("params", params)
            }
        var unrecorded: JsonElement? = null
        val result =
            service.executeDetached(op, request(0, fingerprintInput), JsonElementCodec) {
                unrecorded = null
                val response = block()
                if (ResponseUtil.isErrorResponse(response) || !recordable(response)) {
                    unrecorded = response
                    Outcome.Err(DomainError(ErrorCode.INTERNAL, "The call failed and was not recorded"))
                } else {
                    Outcome.Ok(response)
                }
            }
        return toResponse(result, unrecorded)
    }

    private fun toResponse(
        result: IdempotentOutcome<JsonElement>,
        unrecorded: JsonElement?
    ): JsonElement =
        when (val outcome = result.outcome) {
            is Outcome.Ok -> if (result.replayed) withReplayedData(outcome.value) else outcome.value
            is Outcome.Err ->
                unrecorded ?: LegacyMcpErrorMapper.envelope(
                    message = outcome.error.message,
                    code =
                        if (outcome.error.code ==
                            ErrorCode.IDEMPOTENCY_MISMATCH
                        ) {
                            LegacyMcpCode.IDEMPOTENCY_MISMATCH
                        } else {
                            LegacyMcpCode.INTERNAL_ERROR
                        },
                    cause = outcome.error
                )
        }

    companion object {
        const val IDEMPOTENCY_MISMATCH_CODE = "idempotency_mismatch"

        /** The wire name of the tool [operation]: `mcp.<tool>` or `mcp.<tool>.<operation>`. */
        fun op(
            tool: String,
            operation: String? = null
        ): String = if (operation == null) "mcp.$tool" else "mcp.$tool.$operation"

        /** True when [response] is a success envelope whose `data.<field>` count is positive. */
        fun hasPositive(
            response: JsonElement,
            field: String
        ): Boolean {
            val data = (response as? JsonObject)?.get("data") as? JsonObject ?: return false
            return ((data[field] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0) > 0
        }

        /** The part of [params] every element shares: [params] without `requestId`, `actor` and [elementKeys]. */
        fun sharedOf(
            params: JsonElement,
            vararg elementKeys: String
        ): JsonObject =
            buildJsonObject {
                (params as? JsonObject)?.forEach { (k, v) ->
                    if (k != "requestId" && k != "actor" && k !in elementKeys) put(k, v)
                }
            }

        /** [params] without `requestId` and the top-level `actor`. */
        fun withoutKeyFields(params: JsonElement): JsonObject = sharedOf(params)

        /** The default `{index, error, errorCode, errorKind}` failure; the code is the catalog code of [error]. */
        fun defaultFailure(
            index: Int,
            error: DomainError
        ): JsonObject =
            buildJsonObject {
                put("index", JsonPrimitive(index))
                put("error", JsonPrimitive(error.message))
                putElementError(error)
            }

        private fun invalidRequest(message: String) =
            DomainError(ErrorCode.INVALID_REQUEST, message, ErrorDetail.InvalidRequest(listOf(FieldViolation("element", message))))

        private fun withReplayed(fragment: JsonObject): JsonObject =
            buildJsonObject {
                fragment.forEach { (k, v) -> if (k != "replayed") put(k, v) }
                put("replayed", JsonPrimitive(true))
            }

        private fun withReplayedData(response: JsonElement): JsonElement {
            val obj = response as? JsonObject ?: return response
            val data = obj["data"] as? JsonObject ?: return response
            return buildJsonObject {
                obj.forEach { (k, v) ->
                    if (k == "data") {
                        put(
                            "data",
                            buildJsonObject {
                                data.forEach { (dk, dv) -> if (dk != "replayed") put(dk, dv) }
                                put("replayed", JsonPrimitive(true))
                            }
                        )
                    } else {
                        put(k, v)
                    }
                }
            }
        }
    }
}

/**
 * Runs one array element: under its key when [keyed] is present, otherwise directly with no unit of
 * its own (the unkeyed path stays exactly as it was before keyed calls existed).
 */
suspend fun runElement(
    keyed: KeyedCall?,
    index: Int,
    element: JsonElement,
    onError: (DomainError) -> JsonObject = { KeyedCall.defaultFailure(index, it) },
    block: suspend () -> ElementResult
): ElementOutcome =
    if (keyed != null) {
        keyed.element(index, element, onError, block)
    } else {
        when (val r = block()) {
            is ElementResult.Done -> ElementOutcome.Succeeded(r.fragment, replayed = false)
            is ElementResult.Failed -> ElementOutcome.Failed(r.failure)
            is ElementResult.Invalid -> ElementOutcome.Failed(r.failure)
        }
    }
