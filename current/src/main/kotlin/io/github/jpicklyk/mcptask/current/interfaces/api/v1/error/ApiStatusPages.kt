package io.github.jpicklyk.mcptask.current.interfaces.api.v1.error

import io.github.jpicklyk.mcptask.current.application.telemetry.ReqId
import io.github.jpicklyk.mcptask.current.application.telemetry.currentReqId
import io.github.jpicklyk.mcptask.current.domain.validation.ValidationException
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.envelope.respondEnvelope
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.logging.REQ_ID_HEADER
import io.github.jpicklyk.mcptask.current.interfaces.shared.envelope.EnvelopeEncoder
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

private val logger = LoggerFactory.getLogger("ApiStatusPages")

/** The path prefix the REST safety net covers; every other path keeps Ktor's default handling. */
private const val API_PREFIX = "/api/v1"

/** The 4.0 operation surface: its uncaught exceptions are answered with the envelope `internal` error. */
private const val OPS_PREFIX = "/api/v1/ops"

/** The `internal` error message: fixed, so an uncaught exception's text never reaches the client. */
internal const val INTERNAL_ERROR_MESSAGE = "Internal server error"

/**
 * Installs the REST safety net: an exception that escapes a `/api/v1` route is answered with a 3.x-shaped
 * [io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ErrorDto] instead of an empty or framework-shaped
 * 500, and never with the exception text.
 *
 * - Ktor's own status exceptions keep their status: [BadRequestException] / [ContentTransformationException]
 *   -> 400 `bad_request`, [NotFoundException] -> 404 `not_found`, [UnsupportedMediaTypeException] -> 415
 *   `unsupported_media_type`, the request-body-limit exception -> 413 `payload_too_large`.
 * - A domain [ValidationException] -> 400 `validation_error` (its message is the domain's own user-facing text).
 * - Any other [Throwable] -> 500 `internal` with a fixed message, logged at ERROR with the cause.
 * - [CancellationException] is never mapped (it is rethrown), and a path outside `/api/v1` is rethrown untouched
 *   so `/mcp` and the rest keep Ktor's default handling.
 *
 * Called from the REST wiring only when the API is enabled, so an API-disabled deployment is unaffected.
 */
internal fun Application.installApiStatusPages() {
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            if (cause is CancellationException || !call.isApiPath()) throw cause
            if (call.isOpsPath()) {
                // The 4.0 envelope surface: any escaping exception is the fixed `internal` envelope.
                logger.error("Unhandled exception on {} {}", call.request.local.method.value, call.request.path(), cause)
                call.respondEnvelope(EnvelopeEncoder.internal(call.envelopeReqId()))
                return@exception
            }
            // The most specific first: the status exceptions below may extend [ContentTransformationException].
            when {
                cause is PayloadTooLargeException -> call.respondError(LegacyRestCode.PAYLOAD_TOO_LARGE, "Payload too large")
                cause is UnsupportedMediaTypeException ->
                    call.respondError(LegacyRestCode.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type")
                cause is NotFoundException -> call.respondError(LegacyRestCode.NOT_FOUND, "Resource not found")
                cause is ContentTransformationException || cause is BadRequestException ->
                    call.respondError(LegacyRestCode.BAD_REQUEST, "Bad request")
                cause is ValidationException ->
                    call.respondError(LegacyRestCode.VALIDATION_ERROR, cause.message ?: "Validation failed")
                else -> {
                    logger.error("Unhandled exception on {} {}", call.request.local.method.value, call.request.path(), cause)
                    call.respondError(LegacyRestCode.INTERNAL, INTERNAL_ERROR_MESSAGE)
                }
            }
        }
    }
}

private fun ApplicationCall.isApiPath(): Boolean = request.path().let { it == API_PREFIX || it.startsWith("$API_PREFIX/") }

private fun ApplicationCall.isOpsPath(): Boolean = request.path().let { it == OPS_PREFIX || it.startsWith("$OPS_PREFIX/") }

/**
 * The reqId of this call: the one the correlation plugin installed (its telemetry wraps this handler), else the
 * `X-Req-Id` it already set on the response, else a fresh one when no correlation ran.
 */
private suspend fun ApplicationCall.envelopeReqId(): String = currentReqId() ?: response.headers[REQ_ID_HEADER] ?: ReqId.generate()
