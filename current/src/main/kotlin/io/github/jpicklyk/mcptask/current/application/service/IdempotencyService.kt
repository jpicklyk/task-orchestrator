package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.port.IdempotencyRecord
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.application.support.rethrowIfCancellation
import io.github.jpicklyk.mcptask.current.application.telemetry.recordCallReplayed
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.FieldViolation
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

/** Who is asking ([principalId]), under which client [key], and a digest ([fingerprint]) of what they asked. */
data class IdempotentRequest(
    val principalId: String,
    val key: String,
    val fingerprint: String
)

/** The result of an idempotent call; [replayed] is true when [outcome] was served from a stored record. */
data class IdempotentOutcome<out T>(
    val outcome: Outcome<T>,
    val replayed: Boolean
)

/** Converts a result value to and from the JSON stored in an idempotency record. */
interface IdempotencyCodec<T> {
    fun encode(value: T): JsonElement

    fun decode(json: JsonElement): T
}

/** The codec of results that already are JSON. */
object JsonElementCodec : IdempotencyCodec<JsonElement> {
    override fun encode(value: JsonElement): JsonElement = value

    override fun decode(json: JsonElement): JsonElement = json
}

/**
 * Durable, per-element idempotency over the
 * [IdempotencyStore][io.github.jpicklyk.mcptask.current.application.port.IdempotencyStore].
 *
 * A keyed element runs inside ONE write unit together with its record: a live record with the same
 * fingerprint replays the stored outcome without running the block; a live record with another
 * fingerprint is an [ErrorCode.IDEMPOTENCY_MISMATCH]; otherwise the block runs and an [Outcome.Ok] is
 * recorded in the same unit, so a record exists if and only if the effects committed. Only payload
 * validation rejections ([ErrorCode.INVALID_REQUEST], [ErrorCode.UNKNOWN_PARAMETER]) are recorded for
 * an [Outcome.Err] (in a follow-up unit, after the rollback); any other error, a throw or a
 * cancellation records nothing, so a retry with the same key runs the block again.
 *
 * The lookup runs under the writer, so concurrent callers with the same key serialize, in this process
 * and across processes. Time is `scope.now` only.
 *
 * Every failure inside the block must surface as [Outcome.Err] (or a throw): a nested unit error
 * inside a joined unit does not roll the unit back, so returning [Outcome.Ok] for a failed element
 * would commit its partial writes and record them.
 */
class IdempotencyService(
    private val unitOfWork: UnitOfWork,
    private val ttl: Duration = DEFAULT_TTL
) {
    private val logger = LoggerFactory.getLogger(IdempotencyService::class.java)

    suspend fun <T> execute(
        op: String,
        req: IdempotentRequest,
        codec: IdempotencyCodec<T>,
        block: suspend WriteScope.() -> Outcome<T>
    ): IdempotentOutcome<T> {
        var replayed = false
        val outcome =
            unitOfWork.write(op) {
                replayed = false
                val existing = stores.idempotencyStore().find(req.principalId, op, req.key)
                if (existing != null && existing.createdAt.isAfter(now.minus(ttl))) {
                    if (existing.fingerprint != req.fingerprint) return@write Outcome.Err(mismatch(req.key))
                    replayed = true
                    return@write decode(existing.resultJson, codec)
                }
                when (val result = block()) {
                    is Outcome.Ok -> {
                        stores.idempotencyStore().upsert(record(req, op, okJson(codec, result.value), now))
                        result
                    }
                    is Outcome.Err -> {
                        if (result.error.code in STORED_ERRORS) {
                            val rejection = record(req, op, errJson(result.error), now)
                            afterRollback { recordRejection(op, rejection) }
                        }
                        result
                    }
                }
            }
        // Read AFTER the unit returns: a BUSY retry re-runs the body, so only the final attempt's flag counts.
        if (replayed) recordCallReplayed()
        return IdempotentOutcome(outcome, replayed)
    }

    /**
     * For a call whose work cannot run inside one unit (it opens units of its own): a lookup unit
     * (replay or mismatch), the [block] outside any unit, and, when it returns [Outcome.Ok], a
     * follow-up unit that records it. Concurrent callers with the same key may both run the block.
     */
    suspend fun <T> executeDetached(
        op: String,
        req: IdempotentRequest,
        codec: IdempotencyCodec<T>,
        block: suspend () -> Outcome<T>
    ): IdempotentOutcome<T> {
        var found: IdempotentOutcome<T>? = null
        val lookup =
            unitOfWork.write(op) {
                found = null
                val existing = stores.idempotencyStore().find(req.principalId, op, req.key)
                if (existing != null && existing.createdAt.isAfter(now.minus(ttl))) {
                    found =
                        if (existing.fingerprint != req.fingerprint) {
                            IdempotentOutcome(Outcome.Err(mismatch(req.key)), replayed = false)
                        } else {
                            IdempotentOutcome(decode(existing.resultJson, codec), replayed = true)
                        }
                }
                Outcome.Ok(Unit)
            }
        if (lookup is Outcome.Err) return IdempotentOutcome(lookup, replayed = false)
        found?.let {
            if (it.replayed) recordCallReplayed()
            return it
        }

        val result = block()
        if (result is Outcome.Ok) {
            val recorded =
                unitOfWork.write(op) {
                    stores.idempotencyStore().insertIfAbsent(record(req, op, okJson(codec, result.value), now))
                    Outcome.Ok(Unit)
                }
            if (recorded is Outcome.Err) logger.warn("Failed to record idempotency result for {}: {}", op, recorded.error.message)
        }
        return IdempotentOutcome(result, replayed = false)
    }

    private suspend fun recordRejection(
        op: String,
        rejection: IdempotencyRecord
    ) {
        try {
            val recorded =
                unitOfWork.write(op) {
                    stores.idempotencyStore().insertIfAbsent(rejection)
                    Outcome.Ok(Unit)
                }
            if (recorded is Outcome.Err) logger.warn("Failed to record idempotency rejection for {}: {}", op, recorded.error.message)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            logger.warn("Failed to record idempotency rejection for {}: {}", op, e.message)
        }
    }

    private fun record(
        req: IdempotentRequest,
        op: String,
        resultJson: JsonElement,
        createdAt: Instant
    ): IdempotencyRecord = IdempotencyRecord(req.principalId, op, req.key, req.fingerprint, resultJson.toString(), createdAt)

    private fun <T> okJson(
        codec: IdempotencyCodec<T>,
        value: T
    ): JsonElement =
        buildJsonObject {
            put("v", FORMAT_VERSION)
            put("ok", true)
            put("value", codec.encode(value))
        }

    private fun errJson(error: DomainError): JsonElement =
        buildJsonObject {
            put("v", FORMAT_VERSION)
            put("ok", false)
            put(
                "error",
                buildJsonObject {
                    put("code", error.code.wire)
                    put("message", error.message)
                    when (val detail = error.detail) {
                        is ErrorDetail.InvalidRequest ->
                            put(
                                "detail",
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
                        is ErrorDetail.UnknownParameter -> put("detail", JsonArray(detail.parameters.map { JsonPrimitive(it) }))
                        else -> {}
                    }
                }
            )
        }

    private fun <T> decode(
        stored: String,
        codec: IdempotencyCodec<T>
    ): Outcome<T> {
        val unreadable = Outcome.Err(DomainError(ErrorCode.INTERNAL, "A stored idempotency record is unreadable"))
        val root =
            try {
                Json.parseToJsonElement(stored) as? JsonObject
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                null
            } ?: return unreadable
        if ((root["v"] as? JsonPrimitive)?.intOrNull != FORMAT_VERSION) return unreadable
        val ok = (root["ok"] as? JsonPrimitive)?.booleanOrNull ?: return unreadable
        if (ok) return Outcome.Ok(codec.decode(root["value"] ?: JsonNull))
        val error = root["error"] as? JsonObject ?: return unreadable
        val code = ErrorCode.entries.firstOrNull { it.wire == (error["code"] as? JsonPrimitive)?.contentOrNull } ?: return unreadable
        val message = (error["message"] as? JsonPrimitive)?.contentOrNull ?: return unreadable
        val detail = error["detail"]
        val domainError =
            when (code) {
                ErrorCode.INVALID_REQUEST -> {
                    val fields =
                        (detail as? JsonArray)
                            ?.mapNotNull { it as? JsonObject }
                            ?.map {
                                FieldViolation(
                                    field = (it["field"] as? JsonPrimitive)?.contentOrNull ?: "",
                                    reason = (it["reason"] as? JsonPrimitive)?.contentOrNull ?: "",
                                    received = (it["received"] as? JsonPrimitive)?.contentOrNull
                                )
                            }.orEmpty()
                    DomainError(code, message, ErrorDetail.InvalidRequest(fields.ifEmpty { listOf(FieldViolation("request", message)) }))
                }
                ErrorCode.UNKNOWN_PARAMETER -> {
                    val parameters = (detail as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
                    DomainError(code, message, ErrorDetail.UnknownParameter(parameters.ifEmpty { listOf("unknown") }))
                }
                else -> return unreadable
            }
        return Outcome.Err(domainError)
    }

    private fun mismatch(key: String): DomainError =
        DomainError(
            code = ErrorCode.IDEMPOTENCY_MISMATCH,
            message = "The idempotency key was already used with a different request",
            detail = ErrorDetail.IdempotencyMismatch(key),
            fixArgs = mapOf("idempotencyKey" to key)
        )

    companion object {
        val DEFAULT_TTL: Duration = Duration.ofHours(24)

        private const val FORMAT_VERSION = 1

        /** The only rejections that are recorded: payload validation, deterministic for the payload alone. */
        private val STORED_ERRORS = setOf(ErrorCode.INVALID_REQUEST, ErrorCode.UNKNOWN_PARAMETER)
    }
}
