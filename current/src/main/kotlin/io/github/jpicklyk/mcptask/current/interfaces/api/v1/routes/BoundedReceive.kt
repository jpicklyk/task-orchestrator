package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ErrorDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray

/**
 * Shared byte cap for the write routes that hand-decode a small JSON body and had NO size limit
 * at all before this fix (bug e941c2c7): POST /items, PATCH /items/{id}, POST
 * /items/{id}/advance, PUT note, POST /dependencies. 1 MiB matches the cap already used elsewhere
 * in this codebase for a bounded-read safety limit on a comparably-sized document
 * ([io.github.jpicklyk.mcptask.current.infrastructure.config.DidWebResolver.MAX_BODY_BYTES],
 * [io.github.jpicklyk.mcptask.current.infrastructure.config.JwksKeySetProvider.MAX_BODY_BYTES])
 * and comfortably exceeds any legitimate item/note/dependency JSON payload.
 *
 * This is a NEW cap, not a change to an existing one — the two routes that already had a
 * documented limit ([io.github.jpicklyk.mcptask.current.application.service.ProjectConfigPushService.MAX_CONFIG_YAML_BYTES],
 * [io.github.jpicklyk.mcptask.current.application.service.PlanDocumentService.MAX_BODY_BYTES])
 * keep their own numeric value unchanged, passed to [receiveBounded] directly instead of this
 * constant.
 */
const val MAX_JSON_WRITE_BODY_BYTES: Int = 1 * 1024 * 1024

/**
 * Reads the request body bounded to at most [maxBytes], fixing the defect where every REST write
 * route buffered the ENTIRE body into heap before any size check ran (bug e941c2c7: `receiveText()`
 * / `receive<T>()` buffers unconditionally; the two routes that DID check a size — project-config,
 * plan-document — only did so AFTER that full buffering had already happened).
 *
 * Two-stage enforcement, applied in order:
 *
 *  1. **Content-Length pre-check.** When the header is present and its declared value exceeds
 *     [maxBytes], responds 413 immediately — ZERO body bytes are ever touched. A negative or
 *     otherwise nonsensical declared length is never `> maxBytes`, so (like an absent header) it
 *     simply falls through to stage 2, which is the real enforcement in that case.
 *  2. **Bounded channel read.** Reads at most `maxBytes + 1` bytes from
 *     [ApplicationCall.receiveChannel] regardless of what Content-Length declared — this is what
 *     catches a chunked-encoded, absent-, or understated-Content-Length body whose real size
 *     exceeds the cap. When the number of bytes actually read is `> maxBytes`, responds 413
 *     having consumed at most `maxBytes + 1` bytes — NEVER the full stream, however large it
 *     actually is.
 *
 * A body whose size is exactly [maxBytes] is accepted (the cap is inclusive).
 *
 * Both 413 paths use the identical [ErrorDto] shape: `error = "payload_too_large"` — the SAME
 * code the two previously-capped routes already used for their post-buffer check, so this fix
 * does not introduce a second 413 shape.
 *
 * On success, returns the body decoded as UTF-8 text — the same decoding
 * [io.ktor.server.request.receiveText] performed at every call site this replaces. On either 413
 * path the response has already been sent and this returns `null`; callers use
 * `call.receiveBounded(CAP) ?: return@xxx`, the same nullable-sentinel convention already used by
 * `parseIdempotencyKey` (returns `IdempotencyKeyResult.Invalid` after responding) and by each
 * route file's `parseRootId` / `parseSlug` helpers.
 */
suspend fun ApplicationCall.receiveBounded(maxBytes: Int): String? {
    val declaredLength = request.contentLength()
    if (declaredLength != null && declaredLength > maxBytes) {
        respond(
            HttpStatusCode.PayloadTooLarge,
            ErrorDto(
                "payload_too_large",
                "Request body declares $declaredLength bytes, exceeds the $maxBytes byte limit",
            ),
        )
        return null
    }

    // Bounded regardless of Content-Length: reads at most maxBytes + 1 bytes total, so a
    // chunked or absent/understated-Content-Length body can never be buffered in full.
    val bytes = receiveChannel().readRemaining(maxBytes.toLong() + 1).readByteArray()
    if (bytes.size > maxBytes) {
        respond(
            HttpStatusCode.PayloadTooLarge,
            ErrorDto(
                "payload_too_large",
                "Request body exceeds the $maxBytes byte limit",
            ),
        )
        return null
    }

    return bytes.decodeToString()
}
