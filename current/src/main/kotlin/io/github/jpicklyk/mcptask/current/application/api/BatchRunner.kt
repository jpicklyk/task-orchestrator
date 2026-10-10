package io.github.jpicklyk.mcptask.current.application.api

import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.application.service.Fingerprint
import io.github.jpicklyk.mcptask.current.application.service.IdempotencyCodec
import io.github.jpicklyk.mcptask.current.application.service.IdempotencyService
import io.github.jpicklyk.mcptask.current.application.service.IdempotentRequest
import io.github.jpicklyk.mcptask.current.domain.error.BatchResult
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.FieldViolation
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One request element of a batch write: the decoded [payload], its raw [json] (the idempotency
 * fingerprint input), and the optional client [idempotencyKey].
 */
data class BatchElement<E>(
    val payload: E,
    val json: JsonElement,
    val idempotencyKey: String? = null
)

/** A finished batch: the per-element [result] and the indices served from a stored idempotency record. */
data class BatchExecution<T>(
    val result: BatchResult<T>,
    val replayed: Set<Int>
)

/**
 * Runs the elements of a batch write (envelope design section 2, "Batch results").
 *
 * - Per element (the default, phase 2 decision U18): each element runs in its own write unit, a
 *   keyed one through [IdempotencyService.execute], an unkeyed one through [UnitOfWork.write]. A
 *   failing element rolls back only itself; later elements still run; positions are kept.
 * - Atomic: one outer write unit; the elements run in order inside it (their units join it), and the
 *   first failure ends the batch and rolls everything back, idempotency records included. Elements
 *   after the failure never run. The result list holds the computed results before the failing index
 *   and the failing error at and after it, so [BatchResult.failure] reports the failing index.
 *
 * Idempotency is keyed `(principal, tool, key)` and fingerprinted over `{"tool", "element"}`. A key
 * that is blank or longer than [MAX_IDEMPOTENCY_KEY_LENGTH] fails its element with `invalid_request`
 * (field `idempotencyKey`) without running it. Persistence faults are translated by the unit; any
 * other exception propagates.
 */
class BatchRunner(
    private val unitOfWork: UnitOfWork,
    private val idempotency: IdempotencyService
) {
    suspend fun <E, R> run(
        tool: String,
        principalId: String,
        elements: List<BatchElement<E>>,
        atomic: Boolean,
        codec: IdempotencyCodec<R>,
        execute: suspend WriteScope.(index: Int, element: E) -> Outcome<R>
    ): BatchExecution<R> {
        require(elements.isNotEmpty()) { "elements must not be empty" }
        return if (atomic) {
            runAtomic(tool, principalId, elements, codec, execute)
        } else {
            runPerElement(tool, principalId, elements, codec, execute)
        }
    }

    private suspend fun <E, R> runPerElement(
        tool: String,
        principalId: String,
        elements: List<BatchElement<E>>,
        codec: IdempotencyCodec<R>,
        execute: suspend WriteScope.(index: Int, element: E) -> Outcome<R>
    ): BatchExecution<R> {
        val results = ArrayList<Outcome<R>>(elements.size)
        val replayed = LinkedHashSet<Int>()
        elements.forEachIndexed { index, element ->
            val key = element.idempotencyKey
            val outcome =
                when {
                    key == null -> unitOfWork.write(tool) { execute(index, element.payload) }
                    !isValidKey(key) -> Outcome.Err(invalidKey(key))
                    else -> {
                        val keyed =
                            idempotency.execute(
                                tool,
                                request(tool, principalId, key, element),
                                codec
                            ) { execute(index, element.payload) }
                        if (keyed.replayed) replayed.add(index)
                        keyed.outcome
                    }
                }
            results.add(outcome)
        }
        return BatchExecution(BatchResult(results, atomic = false), replayed)
    }

    private suspend fun <E, R> runAtomic(
        tool: String,
        principalId: String,
        elements: List<BatchElement<E>>,
        codec: IdempotencyCodec<R>,
        execute: suspend WriteScope.(index: Int, element: E) -> Outcome<R>
    ): BatchExecution<R> {
        val done = ArrayList<Outcome<R>>(elements.size)
        val replayed = LinkedHashSet<Int>()
        var failedIndex: Int? = null
        val outer =
            unitOfWork.write(tool) {
                // A BUSY retry re-runs this block from the start: reset what the abandoned attempt collected.
                done.clear()
                replayed.clear()
                failedIndex = null
                for ((index, element) in elements.withIndex()) {
                    val key = element.idempotencyKey
                    val outcome: Outcome<R> =
                        when {
                            key == null -> execute(index, element.payload)
                            !isValidKey(key) -> Outcome.Err(invalidKey(key))
                            else -> {
                                // Joins this unit: its record commits or rolls back with the whole batch.
                                val keyed =
                                    idempotency.execute(
                                        tool,
                                        request(tool, principalId, key, element),
                                        codec
                                    ) { execute(index, element.payload) }
                                if (keyed.replayed) replayed.add(index)
                                keyed.outcome
                            }
                        }
                    if (outcome is Outcome.Err) {
                        failedIndex = index
                        return@write outcome
                    }
                    done.add(outcome)
                }
                Outcome.Ok(Unit)
            }
        if (outer is Outcome.Ok) return BatchExecution(BatchResult(done.toList(), atomic = true), replayed)

        // The unit's own error wins (a fault translated at commit may replace the element's error); with no
        // failing element recorded, the whole batch failed at the boundary and index 0 carries it.
        val error = (outer as Outcome.Err)
        val failing = failedIndex ?: 0
        val results = ArrayList<Outcome<R>>(elements.size)
        results.addAll(done.take(failing))
        repeat(elements.size - failing) { results.add(error) }
        return BatchExecution(BatchResult(results, atomic = true), replayed.filterTo(LinkedHashSet()) { it < failing })
    }

    private fun isValidKey(key: String): Boolean = key.isNotBlank() && key.length <= MAX_IDEMPOTENCY_KEY_LENGTH

    private fun invalidKey(key: String): DomainError =
        DomainError(
            code = ErrorCode.INVALID_REQUEST,
            message = "The idempotencyKey must be a non-blank string of at most $MAX_IDEMPOTENCY_KEY_LENGTH characters.",
            detail =
                ErrorDetail.InvalidRequest(
                    listOf(
                        FieldViolation(IDEMPOTENCY_KEY_FIELD, "must be non-blank and at most $MAX_IDEMPOTENCY_KEY_LENGTH characters", key)
                    )
                )
        )

    private fun request(
        tool: String,
        principalId: String,
        key: String,
        element: BatchElement<*>
    ): IdempotentRequest =
        IdempotentRequest(
            principalId = principalId,
            key = key,
            fingerprint =
                Fingerprint.of(
                    buildJsonObject {
                        put("tool", tool)
                        put("element", element.json)
                    }
                )
        )

    companion object {
        const val MAX_IDEMPOTENCY_KEY_LENGTH = 128
        const val IDEMPOTENCY_KEY_FIELD = "idempotencyKey"
    }
}
