package io.github.jpicklyk.mcptask.current.application.port

import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.FieldViolation
import io.github.jpicklyk.mcptask.current.domain.error.Outcome

/**
 * Reads the text a `bodyFromFile` request field names (AR-98): the one way a tool turns a caller-supplied,
 * server-side path into a body.
 *
 * [read] takes the path exactly as the caller wrote it (relative to the source's trusted root) and returns the raw
 * text or a [DomainError]:
 * - `invalid_request` (field `/bodyFromFile`) for a path that is absolute, escapes the root (by `..` or a symlink),
 *   does not exist, is not a regular file, is not valid for the platform, or holds bytes that are not strict UTF-8;
 * - `payload_too_large` (`max` = [MAX_FILE_BYTES]) for a file over the cap.
 *
 * Error text names only the caller's own path: never the root, a resolved path or an I/O exception message. The text
 * is returned as stored: CRLF normalization and each consumer's own size policy stay with the consumer.
 */
interface BodySource {
    fun read(path: String): Outcome<String>

    companion object {
        /** The cap on a file read through any [BodySource]: equal to the note and plan body caps. */
        const val MAX_FILE_BYTES = 65536

        /** The wire field every [BodySource] error points at. */
        const val FIELD = "/bodyFromFile"

        /** Rejects every path with `invalid_request`: the fail-closed default of a context nobody wired a root into. */
        val DISABLED: BodySource =
            object : BodySource {
                override fun read(path: String): Outcome<String> =
                    Outcome.Err(invalid("bodyFromFile is not available on this server", path))
            }

        /** The `invalid_request` of a rejected [path]; [reason] must not name the root or any resolved path. */
        fun invalid(
            reason: String,
            path: String
        ): DomainError =
            DomainError(
                code = ErrorCode.INVALID_REQUEST,
                message = "The request has 1 invalid field(s): $FIELD.",
                detail = ErrorDetail.InvalidRequest(listOf(FieldViolation(FIELD, reason, path.take(MAX_ECHO)))),
            )

        /** The `payload_too_large` of a file of [actual] bytes. */
        fun tooLarge(actual: Long): DomainError =
            DomainError(
                code = ErrorCode.PAYLOAD_TOO_LARGE,
                message = "The file is $actual bytes, over the $MAX_FILE_BYTES byte cap.",
                detail = ErrorDetail.PayloadTooLarge(MAX_FILE_BYTES.toLong(), actual),
                fixArgs = mapOf("max" to MAX_FILE_BYTES.toString())
            )

        private const val MAX_ECHO = 200
    }
}
