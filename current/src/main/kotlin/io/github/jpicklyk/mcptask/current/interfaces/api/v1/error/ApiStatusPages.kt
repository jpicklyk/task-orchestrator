package io.github.jpicklyk.mcptask.current.interfaces.api.v1.error

import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.validation.ValidationException
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.envelope.envelopeReqId
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.envelope.respondEnvelope
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.envelope.respondEnvelopeError
import io.github.jpicklyk.mcptask.current.interfaces.shared.envelope.EnvelopeEncoder
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.invalidField
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.path
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

private val logger = LoggerFactory.getLogger("ApiStatusPages")

/** The path prefix the REST safety net covers; every other path keeps Ktor's default handling. */
private const val API_PREFIX = "/api/v1"

/** The most characters of a received value an error echoes. */
private const val MAX_RECEIVED = 64

/** The byte limit named in a Ktor body-limit message ("... limit of N bytes"), when it names one. */
private val LIMIT_IN_MESSAGE = Regex("(\\d+)")

/**
 * Installs the REST safety net: an exception that escapes a `/api/v1` route is answered with the 4.0 error envelope
 * (never an empty or framework-shaped body, never the exception text), at the status of its catalog code.
 *
 * - The request-body-limit exception -> 413 `payload_too_large`.
 * - [UnsupportedMediaTypeException] -> 400 `invalid_request` on `Content-Type`.
 * - Ktor's NotFoundException has no producer here (an unmatched route is Ktor's own 404, never thrown), so it is not
 *   special-cased: like any other escaping exception it is `internal`.
 * - [BadRequestException] / [ContentTransformationException] (malformed JSON included) -> 400 `invalid_request`.
 * - A domain [ValidationException] -> 400 `invalid_request` (its message is the domain's own user-facing text).
 * - Any other [Throwable] -> 500 `internal` with the fixed message, logged at ERROR with the cause.
 * - [CancellationException] is never mapped (it is rethrown), and a path outside `/api/v1` is rethrown untouched
 *   so `/mcp` and the rest keep Ktor's default handling.
 *
 * Called from the REST wiring only when the API is enabled, so an API-disabled deployment is unaffected.
 */
internal fun Application.installApiStatusPages() {
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            if (cause is CancellationException || !call.isApiPath()) throw cause
            // The most specific first: the status exceptions below may extend [ContentTransformationException].
            when {
                cause is PayloadTooLargeException -> call.respondEnvelopeError(payloadTooLarge(cause))
                cause is UnsupportedMediaTypeException ->
                    call.respondEnvelopeError(
                        invalidField(
                            HttpHeaders.ContentType,
                            "is not a supported media type",
                            call.request.header(HttpHeaders.ContentType)?.take(MAX_RECEIVED)
                        )
                    )

                cause is ContentTransformationException || cause is BadRequestException ->
                    call.respondEnvelopeError(invalidField("$", "must be a well-formed request"))
                cause is ValidationException -> call.respondEnvelopeError(invalidField("$", cause.message ?: "is invalid"))
                else -> {
                    logger.error("Unhandled exception on {} {}", call.request.local.method.value, call.request.path(), cause)
                    call.respondEnvelope(EnvelopeEncoder.internal(call.envelopeReqId()))
                }
            }
        }
    }
}

/** `payload_too_large` of the body-limit exception: the limit its message names; the body was at least one byte over. */
private fun payloadTooLarge(cause: PayloadTooLargeException): DomainError {
    val max = cause.message?.let { LIMIT_IN_MESSAGE.find(it)?.value?.toLongOrNull() } ?: 0L
    return DomainError(
        code = ErrorCode.PAYLOAD_TOO_LARGE,
        message = "The request body exceeds $max bytes.",
        detail = ErrorDetail.PayloadTooLarge(max, max + 1),
        fixArgs = mapOf("max" to max.toString())
    )
}

private fun ApplicationCall.isApiPath(): Boolean = request.path().let { it == API_PREFIX || it.startsWith("$API_PREFIX/") }
